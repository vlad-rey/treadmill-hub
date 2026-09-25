package io.github.vladrey.treadmillhub.power

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.suspend
import java.io.ByteArrayOutputStream
import java.util.UUID

private const val TAG = "PowerStation"

/** State of a Fossibot F2400 station (Sydpower / BrightEMS). */
@Serializable
data class StationState(
    val connected: Boolean = false,
    /** Whether there's grid voltage on the input (power). null — not yet known. */
    val gridOn: Boolean? = null,
    val gridVoltage: Double? = null,
    val gridHz: Double? = null,
    /** AC charging power, W (reg 3). */
    val acChargeW: Int = 0,
    /** Total input, W (reg 6) — with grid power, includes pass-through load power. */
    val inputW: Int = 0,
    val outputW: Int = 0,
    val socPct: Double? = null,
    val updatedAtMs: Long = 0,
    /** Settings (holding registers) keyed as in [StationSettings]. */
    val settings: Map<String, Int> = emptyMap(),
    val settingsAtMs: Long = 0,
)

/**
 * Settings allowed to be changed from the UI. Registers verified on two F2400 units (2026-09-25).
 * Dangerous registers (68 — auto shutdown: writing 0 bricks the station) are not included here.
 */
object StationSettings {
    class Def(val key: String, val reg: Int, val allowed: (Int) -> Boolean)

    val all = listOf(
        Def("usbOutput", 24) { it == 0 || it == 1 },
        Def("dcOutput", 25) { it == 0 || it == 1 },
        Def("acOutput", 26) { it == 0 || it == 1 },
        Def("chargeSpeed", 13) { it in 1..5 },
        Def("keySound", 56) { it == 0 || it == 1 },
        Def("usbStandby", 59) { it in setOf(0, 180, 300, 600, 1800) },
        Def("acStandby", 60) { it in setOf(0, 480, 960, 1440) },
        Def("dcStandby", 61) { it in setOf(0, 480, 960, 1440) },
        Def("dischargeLimit", 66) { it in 0..500 step 10 },
        Def("chargeLimit", 67) { it in 500..1000 step 10 },
    )
    /** Read-only. */
    const val AUTO_OFF_REG = 68
    const val MAX_CHARGE_W_REG = 14

    fun byKey(key: String) = all.firstOrNull { it.key == key }
}

/** Protocol: 11 · function · … · CRC-16 Modbus (high byte first). Registers starting at byte 6, big-endian. */
object StationCodec {
    val SERVICE: UUID = UUID.fromString("0000a002-0000-1000-8000-00805f9b34fb")
    val WRITE: UUID = UUID.fromString("0000c304-0000-1000-8000-00805f9b34fb")
    val NOTIFY: UUID = UUID.fromString("0000c305-0000-1000-8000-00805f9b34fb")
    const val REGS = 80
    const val READ_INPUT = 0x04
    const val READ_HOLDING = 0x03
    const val WRITE_ONE = 0x06

    fun crc16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }

    private fun frame(vararg body: Int): ByteArray {
        val b = ByteArray(body.size) { body[it].toByte() }
        val c = crc16(b)
        return b + byteArrayOf((c shr 8).toByte(), c.toByte())
    }

    fun read(function: Int) = frame(0x11, function, 0x00, 0x00, 0x00, REGS)
    fun writeOne(reg: Int, value: Int) = frame(0x11, WRITE_ONE, reg shr 8, reg and 0xFF, value shr 8 and 0xFF, value and 0xFF)

    /** Expected response length for a given function code. */
    fun expectedLength(function: Int) = if (function == WRITE_ONE) 8 else 6 + REGS * 2 + 2

    fun registers(frame: ByteArray): IntArray? {
        if (frame.size < 6 + REGS * 2 || frame[0] != 0x11.toByte()) return null
        return IntArray(REGS) { r -> ((frame[6 + 2 * r].toInt() and 0xFF) shl 8) or (frame[7 + 2 * r].toInt() and 0xFF) }
    }

    fun parseStatus(regs: IntArray, now: Long, prev: StationState): StationState {
        val v = regs[21] / 10.0   // grid voltage; 0 or a placeholder code when there's no grid power
        val hz = regs[22] / 100.0
        val grid = v in 150.0..280.0 && hz in 45.0..65.0
        return prev.copy(
            connected = true, gridOn = grid, gridVoltage = v.takeIf { grid }, gridHz = hz.takeIf { grid },
            acChargeW = regs[3], inputW = regs[6], outputW = regs[20], socPct = regs[56] / 10.0, updatedAtMs = now,
        )
    }

    fun parseSettings(regs: IntArray, now: Long, prev: StationState): StationState {
        val m = StationSettings.all.associate { it.key to regs[it.reg] } +
            mapOf("autoOffMin" to regs[StationSettings.AUTO_OFF_REG], "maxChargeW" to regs[StationSettings.MAX_CHARGE_W_REG])
        return prev.copy(settings = m, settingsAtMs = now)
    }
}

/** A single station: BLE connection, status polling (10 s) and settings polling (60 s), writing settings. */
@SuppressLint("MissingPermission")
class PowerStation(private val context: Context, val address: String) {
    private val _state = MutableStateFlow(StationState())
    val state = _state.asStateFlow()
    private val manager = Manager()
    private var job: Job? = null
    private val io = Mutex()
    private val buf = ByteArrayOutputStream()
    @Volatile private var waiting: Pair<Int, CompletableDeferred<ByteArray>>? = null

    fun start(scope: CoroutineScope) { job = scope.launch { loop() } }
    fun close() { job?.cancel(); manager.close() }

    private suspend fun loop() {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        while (currentCoroutineContext().isActive) {
            if (!adapter.isEnabled) { delay(10_000); continue }
            try {
                manager.connect(adapter.getRemoteDevice(address)).retry(2, 500).useAutoConnect(false).timeout(30_000).suspend()
                Log.i(TAG, "connected to station $address")
                var lastSettings = 0L
                while (manager.isConnected) {
                    request(StationCodec.READ_INPUT)?.let { regs ->
                        _state.value = StationCodec.parseStatus(regs, System.currentTimeMillis(), _state.value)
                    }
                    if (System.currentTimeMillis() - lastSettings > 60_000) {
                        request(StationCodec.READ_HOLDING)?.let { regs ->
                            _state.value = StationCodec.parseSettings(regs, System.currentTimeMillis(), _state.value)
                            lastSettings = System.currentTimeMillis()
                        }
                    }
                    if (System.currentTimeMillis() - _state.value.updatedAtMs > 40_000) _state.value = _state.value.copy(connected = false)
                    delay(10_000)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "station $address: ${e.message}")
            }
            _state.value = _state.value.copy(connected = false)
            delay(5_000)
        }
    }

    /** Read request → registers, or null with no response within 5 s. Requests are strictly serialized. */
    private suspend fun request(function: Int): IntArray? = io.withLock {
        send(StationCodec.read(function), function)?.let(StationCodec::registers)
    }

    private suspend fun send(bytes: ByteArray, function: Int): ByteArray? {
        val d = CompletableDeferred<ByteArray>()
        synchronized(buf) { buf.reset() }
        waiting = function to d
        return try {
            manager.write(bytes)
            withTimeoutOrNull(5_000) { d.await() }
        } finally {
            waiting = null
        }
    }

    /** Write a whitelisted setting, with range validation and a verification read. */
    suspend fun writeSetting(key: String, value: Int): Result<StationState> {
        val def = StationSettings.byKey(key) ?: return Result.failure(IllegalArgumentException("настройка $key не поддерживается"))
        if (!def.allowed(value)) return Result.failure(IllegalArgumentException("недопустимое значение $value для $key"))
        if (!manager.isConnected) return Result.failure(IllegalStateException("станция не на связи"))
        return io.withLock {
            send(StationCodec.writeOne(def.reg, value), StationCodec.WRITE_ONE)
            delay(500)
            val regs = send(StationCodec.read(StationCodec.READ_HOLDING), StationCodec.READ_HOLDING)?.let(StationCodec::registers)
                ?: return@withLock Result.failure(IllegalStateException("нет ответа после записи"))
            _state.value = StationCodec.parseSettings(regs, System.currentTimeMillis(), _state.value)
            if (regs[def.reg] == value) Result.success(_state.value)
            else Result.failure(IllegalStateException("станция не приняла значение (сейчас ${regs[def.reg]})"))
        }
    }

    private fun onData(data: ByteArray) {
        val (function, deferred) = waiting ?: return
        synchronized(buf) {
            if (buf.size() == 0 && (data.isEmpty() || data[0] != 0x11.toByte())) return
            buf.write(data)
            val f = buf.toByteArray()
            if (f.size >= 2 && (f[1].toInt() and 0xFF) != function) { buf.reset(); return }
            if (f.size >= StationCodec.expectedLength(function)) { buf.reset(); deferred.complete(f) }
        }
    }

    private inner class Manager : BleManager(context) {
        private var write: BluetoothGattCharacteristic? = null
        private var notify: BluetoothGattCharacteristic? = null

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val s = gatt.getService(StationCodec.SERVICE) ?: return false
            write = s.getCharacteristic(StationCodec.WRITE)
            notify = s.getCharacteristic(StationCodec.NOTIFY)
            return write != null && notify != null
        }

        override fun initialize() {
            requestMtu(247).enqueue()   // otherwise the 168-byte response arrives in 20-byte chunks
            setNotificationCallback(notify).with { _, d -> d.value?.let(::onData) }
            enableNotifications(notify).enqueue()
        }

        override fun onServicesInvalidated() { write = null; notify = null }

        suspend fun write(bytes: ByteArray) {
            writeCharacteristic(write, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE).suspend()
        }

        override fun log(priority: Int, message: String) {
            if (priority >= Log.WARN) Log.println(priority, TAG, message)
        }
    }
}
