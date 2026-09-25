package io.github.vladrey.treadmillhub.treadmill

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import io.github.vladrey.treadmillhub.HubConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.suspend
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

private const val TAG = "TreadmillBle"

/** Treadmill over FTMS (control) + FitShow FFF1 (phase, countdown, precise calories). */
@SuppressLint("MissingPermission") // permissions are granted via root at install time (tools/deploy-hub.ps1)
class FtmsBleBackend(private val context: Context, private val config: HubConfig) : TreadmillBackend {
    override val name = "ftms"

    private val _state = MutableStateFlow(TreadmillState())
    override val state = _state.asStateFlow()
    private val _frames = MutableSharedFlow<BleFrame>(extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val frames = _frames.asSharedFlow()

    private val manager = Manager()
    private var job: Job? = null
    private val cpMutex = Mutex()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Ftms.CpResponse>>()
    @Volatile private var hasControl = false

    // Calories arrive interleaved from two sources; without separating them the value flickers (0 ↔ no data)
    @Volatile private var kcalFtms: Double? = null
    @Volatile private var kcalFitShow: Double? = null
    private val kcal get() = kcalFitShow ?: kcalFtms
    @Volatile private var distFitShow: Int? = null

    override fun start(scope: CoroutineScope) {
        job = scope.launch { connectLoop() }
    }

    override fun close() {
        job?.cancel()
        manager.close()
    }

    @Volatile override var link = LinkInfo()
        private set

    /**
     * Connection loop with no gaps: each attempt waits for the treadmill to appear for up to 30 s,
     * with 2 s between attempts. The treadmill is often powered off — once plugged back in, the hub
     * connects within a few seconds.
     */
    private suspend fun connectLoop() {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val retryDelayMs = 2_000L
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            try {
                if (!adapter.isEnabled) {
                    Log.w(TAG, "Bluetooth is off")
                    delay(5_000)
                    continue
                }
                val address = config.deviceAddress ?: scan(adapter)?.also { config.deviceAddress = it }
                if (address == null) {
                    _state.update { it.copy(connection = Connection.DISCONNECTED) }
                    delay(10_000)
                    continue
                }
                _state.update { it.copy(connection = Connection.CONNECTING) }
                hasControl = false
                manager.connect(adapter.getRemoteDevice(address))
                    .retry(3, 300)
                    .useAutoConnect(false)
                    .timeout(30_000)
                    .suspend()
                Log.i(TAG, "connected to $address")
                link = link.copy(connectedSinceMs = System.currentTimeMillis(), connects = link.connects + 1, address = address)
                _state.update { it.copy(connection = Connection.CONNECTED) }
                while (manager.isConnected) delay(500)
                Log.w(TAG, "connection lost")
                link = link.copy(connectedSinceMs = null, lastDisconnectMs = System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "connection error: ${e.message}")
            }
            _state.update { it.copy(connection = Connection.DISCONNECTED, speedKmh = 0.0) }
            pending.values.forEach { it.cancel() }
            delay(retryDelayMs)
        }
    }

    /** Scan for the treadmill by the FTMS service (needs location permission and location enabled on Android 9). */
    private suspend fun scan(adapter: BluetoothAdapter): String? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        _state.update { it.copy(connection = Connection.SCANNING) }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Ftms.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        scanner.stopScan(this)
                        Log.i(TAG, "found treadmill ${result.device.address} (${result.scanRecord?.deviceName})")
                        if (cont.isActive) cont.resume(result.device.address)
                    }
                    override fun onScanFailed(errorCode: Int) {
                        Log.w(TAG, "scan failed: $errorCode")
                        if (cont.isActive) cont.resume(null)
                    }
                }
                scanner.startScan(listOf(filter), settings, cb)
                cont.invokeOnCancellation { runCatching { scanner.stopScan(cb) } }
            }
        }
    }

    override suspend fun command(cmd: Command): CommandResult {
        if (!manager.isConnected || manager.cp == null) return CommandResult(false, "дорожка не подключена")
        // Hard treadmill limit; the profile limit is checked by Hub
        Limits.check(cmd, Limits.MAX_SPEED_KMH)?.let { return CommandResult(false, it) }
        val bytes = when (cmd) {
            Command.Start -> Ftms.start()
            Command.Stop -> Ftms.stop()
            Command.Pause -> Ftms.pause()
            is Command.Speed -> Ftms.setSpeed(cmd.kmh)
            is Command.Incline -> Ftms.setIncline(cmd.pct)
        }
        // Stop does not wait in the queue behind other commands
        if (cmd == Command.Stop) return sendWithControl(bytes)
        return cpMutex.withLock { sendWithControl(bytes) }
    }

    private suspend fun sendWithControl(bytes: ByteArray): CommandResult {
        if (!hasControl) {
            val r = sendCp(Ftms.requestControl())
            if (!r.ok) return r
            hasControl = true
        }
        val r = sendCp(bytes)
        if (!r.ok && r.message == Ftms.resultText(Ftms.RESULT_CONTROL_NOT_PERMITTED)) {
            hasControl = false
            if (sendCp(Ftms.requestControl()).ok) {
                hasControl = true
                return sendCp(bytes)
            }
        }
        return r
    }

    private suspend fun sendCp(bytes: ByteArray): CommandResult {
        val opcode = bytes[0].toInt() and 0xFF
        val deferred = CompletableDeferred<Ftms.CpResponse>()
        pending[opcode]?.cancel()
        pending[opcode] = deferred
        emit("tx", Ftms.CONTROL_POINT, bytes)
        return try {
            manager.writeCp(bytes)
            val resp = withTimeoutOrNull(5_000) { deferred.await() }
                ?: return CommandResult(false, "нет ответа от дорожки")
            CommandResult(resp.result == Ftms.RESULT_SUCCESS, Ftms.resultText(resp.result))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CommandResult(false, "ошибка записи: ${e.message}")
        } finally {
            pending.remove(opcode, deferred)
        }
    }

    private fun emit(dir: String, uuid: UUID, bytes: ByteArray) {
        _frames.tryEmit(BleFrame(System.currentTimeMillis(), dir, uuid.toString().substring(4, 8), bytes.toHex()))
    }

    private fun onTreadmillData(b: ByteArray) {
        emit("rx", Ftms.TREADMILL_DATA, b)
        val d = Ftms.parseTreadmillData(b) ?: return
        d.kcal?.let { kcalFtms = it.toDouble() }
        _state.update { s ->
            s.copy(
                speedKmh = d.speedKmh ?: s.speedKmh,
                inclinePct = d.inclinePct ?: s.inclinePct,
                // FTMS Total Distance is always 0 on the T12B — the treadmill's counter comes via FitShow
                distanceM = distFitShow ?: d.distanceM ?: s.distanceM,
                elapsedS = d.elapsedS ?: s.elapsedS,
                heartRate = if (d.speedKmh != null) d.heartRate else s.heartRate,
                kcal = kcal,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    private fun onFitShow(b: ByteArray) {
        emit("rx", FitShow.NOTIFY, b)
        val st = FitShow.parseStatus(b) ?: return
        // Precise calories and distance are only available while running/paused/after stop; reset while idle
        if (st.kcal != null) kcalFitShow = st.kcal else if (st.state == FitShow.STATE_IDLE) kcalFitShow = null
        if (st.distanceM != null) distFitShow = st.distanceM else if (st.state == FitShow.STATE_IDLE) distFitShow = null
        _state.update { s ->
            val phase = when (st.state) {
                FitShow.STATE_IDLE -> Phase.IDLE
                FitShow.STATE_COUNTDOWN -> Phase.COUNTDOWN
                FitShow.STATE_RUNNING -> Phase.RUNNING
                FitShow.STATE_STOPPING -> if ((st.speedKmh ?: s.speedKmh) > 0) Phase.STOPPING else Phase.FINISHED
                FitShow.STATE_PAUSED -> Phase.PAUSED
                else -> s.phase
            }
            s.copy(
                phase = phase,
                countdown = st.countdown,
                kcal = kcal,
                distanceM = distFitShow ?: s.distanceM,
                vendorRaw = st.unknown ?: if (phase == Phase.IDLE) null else s.vendorRaw,
            )
        }
    }

    private fun onControlPoint(b: ByteArray) {
        emit("rx", Ftms.CONTROL_POINT, b)
        val r = Ftms.parseCpResponse(b) ?: return
        pending[r.opcode]?.complete(r)
    }

    private inner class Manager : BleManager(context) {
        var cp: BluetoothGattCharacteristic? = null
        private var data: BluetoothGattCharacteristic? = null
        private var training: BluetoothGattCharacteristic? = null
        private var machine: BluetoothGattCharacteristic? = null
        private var fitShow: BluetoothGattCharacteristic? = null

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val ftms = gatt.getService(Ftms.SERVICE) ?: return false
            cp = ftms.getCharacteristic(Ftms.CONTROL_POINT)
            data = ftms.getCharacteristic(Ftms.TREADMILL_DATA)
            training = ftms.getCharacteristic(Ftms.TRAINING_STATUS)
            machine = ftms.getCharacteristic(Ftms.MACHINE_STATUS)
            fitShow = gatt.getService(FitShow.SERVICE)?.getCharacteristic(FitShow.NOTIFY)
            return cp != null && data != null
        }

        override fun initialize() {
            setNotificationCallback(data).with { _, d -> d.value?.let(::onTreadmillData) }
            enableNotifications(data).enqueue()
            fitShow?.let { ch ->
                setNotificationCallback(ch).with { _, d -> d.value?.let(::onFitShow) }
                enableNotifications(ch).enqueue()
            }
            for ((ch, uuid) in listOf(training to Ftms.TRAINING_STATUS, machine to Ftms.MACHINE_STATUS)) {
                ch ?: continue
                setNotificationCallback(ch).with { _, d -> d.value?.let { emit("rx", uuid, it) } }
                enableNotifications(ch).enqueue()
            }
            setIndicationCallback(cp).with { _, d -> d.value?.let(::onControlPoint) }
            enableIndications(cp).enqueue()
        }

        override fun onServicesInvalidated() {
            cp = null; data = null; training = null; machine = null; fitShow = null
        }

        suspend fun writeCp(bytes: ByteArray) {
            writeCharacteristic(cp, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).suspend()
        }

        override fun log(priority: Int, message: String) {
            // The library logs every packet at INFO level — that's ~3 lines per second; keep only problems
            if (priority >= Log.WARN) Log.println(priority, TAG, message)
        }
    }
}
