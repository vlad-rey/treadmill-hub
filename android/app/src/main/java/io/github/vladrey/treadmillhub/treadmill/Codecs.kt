package io.github.vladrey.treadmillhub.treadmill

import java.util.UUID
import kotlin.math.roundToInt

private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
private fun s16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()
private fun u24(b: ByteArray, i: Int) = u16(b, i) or (u8(b, i + 2) shl 16)

fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it) }

private fun uuid16(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

/** Fitness Machine Service (Bluetooth SIG). Verified on the T12B — see protocol/PROTOCOL.md. */
object Ftms {
    val SERVICE = uuid16("1826")
    val TREADMILL_DATA = uuid16("2acd")
    val TRAINING_STATUS = uuid16("2ad3")
    val MACHINE_STATUS = uuid16("2ada")
    val CONTROL_POINT = uuid16("2ad9")

    const val OP_REQUEST_CONTROL = 0x00
    const val OP_SET_SPEED = 0x02
    const val OP_SET_INCLINE = 0x03
    const val OP_START = 0x07
    const val OP_STOP_PAUSE = 0x08
    const val OP_RESPONSE = 0x80

    const val RESULT_SUCCESS = 0x01
    const val RESULT_CONTROL_NOT_PERMITTED = 0x05

    data class TreadmillData(
        val speedKmh: Double? = null,
        val distanceM: Int? = null,
        val inclinePct: Double? = null,
        val kcal: Int? = null,
        val heartRate: Int? = null,
        val elapsedS: Int? = null,
    )

    /** Treadmill Data (2ACD). A packet with the More Data flag (bit 0) set carries no speed. */
    fun parseTreadmillData(b: ByteArray): TreadmillData? = try {
        val flags = u16(b, 0)
        fun has(bit: Int) = flags and (1 shl bit) != 0
        var i = 2
        var speed: Double? = null
        if (!has(0)) { speed = u16(b, i) / 100.0; i += 2 }
        if (has(1)) i += 2
        var distance: Int? = null
        if (has(2)) { distance = u24(b, i); i += 3 }
        var incline: Double? = null
        if (has(3)) { incline = s16(b, i) / 10.0; i += 4 }
        if (has(4)) i += 4
        if (has(5)) i += 1
        if (has(6)) i += 1
        var kcal: Int? = null
        if (has(7)) { kcal = u16(b, i).takeIf { it != 0xFFFF }; i += 5 }
        var hr: Int? = null
        if (has(8)) { hr = u8(b, i).takeIf { it != 0 }; i += 1 }
        if (has(9)) i += 1
        var elapsed: Int? = null
        if (has(10)) { elapsed = u16(b, i); i += 2 }
        if (i > b.size) null else TreadmillData(speed, distance, incline, kcal, hr, elapsed)
    } catch (_: IndexOutOfBoundsException) {
        null
    }

    fun requestControl() = byteArrayOf(OP_REQUEST_CONTROL.toByte())
    fun start() = byteArrayOf(OP_START.toByte())
    fun stop() = byteArrayOf(OP_STOP_PAUSE.toByte(), 0x01)
    fun pause() = byteArrayOf(OP_STOP_PAUSE.toByte(), 0x02)
    fun setSpeed(kmh: Double): ByteArray {
        val v = (kmh * 100).roundToInt()
        return byteArrayOf(OP_SET_SPEED.toByte(), v.toByte(), (v shr 8).toByte())
    }
    fun setIncline(pct: Double): ByteArray {
        val v = (pct * 10).roundToInt()
        return byteArrayOf(OP_SET_INCLINE.toByte(), v.toByte(), (v shr 8).toByte())
    }

    data class CpResponse(val opcode: Int, val result: Int)

    fun parseCpResponse(b: ByteArray): CpResponse? =
        if (b.size >= 3 && u8(b, 0) == OP_RESPONSE) CpResponse(u8(b, 1), u8(b, 2)) else null

    fun resultText(result: Int) = when (result) {
        0x01 -> "OK"
        0x02 -> "команда не поддерживается"
        0x03 -> "неверный параметр"
        0x04 -> "дорожка не смогла выполнить"
        0x05 -> "управление не разрешено"
        else -> "код 0x%02x".format(result)
    }
}

/** Proprietary FitShow protocol: frame 02 · command · data · XOR · 03. */
object FitShow {
    val SERVICE = uuid16("fff0")
    val NOTIFY = uuid16("fff1")

    /** Frame payload (command + data), or null if the frame is malformed. */
    fun unwrap(b: ByteArray): ByteArray? {
        if (b.size < 4 || u8(b, 0) != 0x02 || u8(b, b.size - 1) != 0x03) return null
        val payload = b.copyOfRange(1, b.size - 2)
        val xor = payload.fold(0) { acc, x -> acc xor (x.toInt() and 0xFF) }
        return if (xor == u8(b, b.size - 2)) payload else null
    }

    data class Status(
        val state: Int,
        val countdown: Int? = null,
        val speedKmh: Double? = null,
        val inclinePct: Double? = null,
        val elapsedS: Int? = null,
        /** Calories in tenths of a kcal — checked against the console (15.7 → "15"). */
        val kcal: Double? = null,
        /** Treadmill distance, m (10 m steps) — checked against the console (750 m → "0.7"). */
        val distanceM: Int? = null,
        /** Fields whose meaning is still unclear (offsets 10, 12). */
        val unknown: List<Int>? = null,
    )

    const val STATE_IDLE = 0x00
    const val STATE_COUNTDOWN = 0x02
    const val STATE_RUNNING = 0x03
    const val STATE_STOPPING = 0x04
    const val STATE_PAUSED = 0x0a

    /** Status frame 0x51: 00 idle, 02 countdown, 03 running, 04 braking/after stop, 0a paused. */
    fun parseStatus(b: ByteArray): Status? {
        val p = unwrap(b) ?: return null
        if (p.isEmpty() || u8(p, 0) != 0x51) return null
        val state = if (p.size > 1) u8(p, 1) else 0
        return when {
            state == STATE_COUNTDOWN && p.size >= 3 -> Status(state, countdown = u8(p, 2))
            state in listOf(STATE_RUNNING, STATE_STOPPING, STATE_PAUSED) && p.size >= 12 -> Status(
                state,
                speedKmh = u8(p, 2) / 10.0,
                inclinePct = u8(p, 3).toDouble(),
                elapsedS = u16(p, 4),
                distanceM = u16(p, 6),
                kcal = u16(p, 8) / 10.0,
                unknown = listOf(10, 12).filter { it + 1 < p.size }.map { u16(p, it) },
            )
            else -> Status(state)
        }
    }
}
