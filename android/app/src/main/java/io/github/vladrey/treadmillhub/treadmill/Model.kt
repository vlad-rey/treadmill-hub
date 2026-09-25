package io.github.vladrey.treadmillhub.treadmill

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

@Serializable
enum class Connection { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

@Serializable
enum class Phase { IDLE, COUNTDOWN, RUNNING, PAUSED, STOPPING, FINISHED }

@Serializable
data class TreadmillState(
    val connection: Connection = Connection.DISCONNECTED,
    val phase: Phase = Phase.IDLE,
    val countdown: Int? = null,
    val speedKmh: Double = 0.0,
    /** Target incline: the treadmill reports the target, not the deck's actual position. */
    val inclinePct: Double = 0.0,
    val elapsedS: Int = 0,
    /** FTMS Total Distance. Always 0 on the T12B so far — distance is computed by the hub. */
    val distanceM: Int? = null,
    /** Calories from the treadmill (FitShow, 0.1 kcal steps; otherwise FTMS, 1 kcal steps). */
    val kcal: Double? = null,
    val heartRate: Int? = null,
    /** Undecoded FitShow status fields — for cross-checking against the treadmill console. */
    val vendorRaw: List<Int>? = null,
    val updatedAtMs: Long = 0,
)

sealed interface Command {
    data object Start : Command
    data object Stop : Command
    data object Pause : Command
    data class Speed(val kmh: Double) : Command
    data class Incline(val pct: Double) : Command
}

@Serializable
data class CommandResult(val ok: Boolean, val message: String)

/** Raw BLE packet for debugging (the /ws/debug/ble stream). */
@Serializable
data class BleFrame(val ts: Long, val dir: String, val uuid: String, val hex: String)

/** BLE link state with the treadmill — for the "Hub" tab. */
@Serializable
data class LinkInfo(
    val connectedSinceMs: Long? = null,
    val lastDisconnectMs: Long? = null,
    val connects: Int = 0,
    val address: String? = null,
)

interface TreadmillBackend {
    val name: String
    val link: LinkInfo get() = LinkInfo()
    val state: StateFlow<TreadmillState>
    val frames: SharedFlow<BleFrame>
    fun start(scope: kotlinx.coroutines.CoroutineScope)
    fun close()
    suspend fun command(cmd: Command): CommandResult
}

object Limits {
    const val MIN_SPEED_KMH = 1.0
    const val MAX_SPEED_KMH = 16.0
    const val MIN_INCLINE_PCT = 0.0
    const val MAX_INCLINE_PCT = 15.0

    /** Validate a command before sending. [maxSpeedKmh] — the limit from the hub settings. */
    fun check(cmd: Command, maxSpeedKmh: Double): String? = when (cmd) {
        is Command.Speed -> when {
            cmd.kmh < MIN_SPEED_KMH -> "скорость ниже минимальной $MIN_SPEED_KMH км/ч"
            cmd.kmh > minOf(maxSpeedKmh, MAX_SPEED_KMH) -> "скорость выше лимита ${minOf(maxSpeedKmh, MAX_SPEED_KMH)} км/ч"
            else -> null
        }
        is Command.Incline -> if (cmd.pct < MIN_INCLINE_PCT || cmd.pct > MAX_INCLINE_PCT)
            "наклон вне диапазона $MIN_INCLINE_PCT–$MAX_INCLINE_PCT %" else null
        else -> null
    }
}
