package io.github.vladrey.treadmillhub

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import io.github.vladrey.treadmillhub.session.SessionStats
import io.github.vladrey.treadmillhub.session.SessionTracker
import io.github.vladrey.treadmillhub.treadmill.Command
import io.github.vladrey.treadmillhub.treadmill.CommandResult
import io.github.vladrey.treadmillhub.treadmill.FtmsBleBackend
import io.github.vladrey.treadmillhub.treadmill.Limits
import io.github.vladrey.treadmillhub.treadmill.Phase
import io.github.vladrey.treadmillhub.treadmill.SimulatorBackend
import io.github.vladrey.treadmillhub.treadmill.TreadmillBackend
import io.github.vladrey.treadmillhub.treadmill.TreadmillState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class HubInfo(
    val backend: String,
    val version: String,
    val uptimeS: Long,
    val batteryPct: Int?,
    val batteryTempC: Double?,
    val charging: Boolean?,
    val weightKg: Double,
    val maxSpeedKmh: Double,
)

@Serializable
data class Snapshot(val treadmill: TreadmillState, val session: SessionStats, val hub: HubInfo)

@Serializable
data class ControlRequest(val action: String, val value: Double? = null)

/** Связывает дорожку, учёт тренировки и API. Живёт внутри HubService. */
class Hub(private val context: Context, val config: HubConfig) {
    val backend: TreadmillBackend =
        if (config.backend == "sim") SimulatorBackend { config.maxSpeedKmh } else FtmsBleBackend(context, config)

    private val tracker = SessionTracker { config.weightKg }
    private val _snapshot = MutableStateFlow(Snapshot(backend.state.value, tracker.current, hubInfo()))
    val snapshot = _snapshot.asStateFlow()

    fun start(scope: CoroutineScope) {
        backend.start(scope)
        scope.launch {
            backend.state.collect { s ->
                val session = tracker.onState(s, System.currentTimeMillis())
                _snapshot.value = _snapshot.value.copy(treadmill = s, session = session)
            }
        }
        scope.launch {
            while (isActive) {
                _snapshot.value = _snapshot.value.copy(hub = hubInfo())
                delay(10_000)
            }
        }
    }

    fun close() = backend.close()

    suspend fun control(req: ControlRequest): CommandResult {
        val s = backend.state.value
        val cmd = when (req.action) {
            "start" -> Command.Start
            "stop" -> Command.Stop
            "pause" -> Command.Pause
            "speed" -> Command.Speed(req.value ?: return bad("нужно value"))
            "incline" -> Command.Incline(req.value ?: return bad("нужно value"))
            "speedDelta" -> {
                if (s.phase != Phase.RUNNING) return bad("лента не движется")
                val v = ((s.speedKmh + (req.value ?: return bad("нужно value"))) * 10).roundToInt() / 10.0
                Command.Speed(v.coerceIn(Limits.MIN_SPEED_KMH, minOf(config.maxSpeedKmh, Limits.MAX_SPEED_KMH)))
            }
            "inclineDelta" -> {
                val v = (s.inclinePct + (req.value ?: return bad("нужно value"))).roundToInt().toDouble()
                Command.Incline(v.coerceIn(Limits.MIN_INCLINE_PCT, Limits.MAX_INCLINE_PCT))
            }
            else -> return bad("неизвестное действие ${req.action}")
        }
        return backend.command(cmd)
    }

    private fun bad(msg: String) = CommandResult(false, msg)

    private fun hubInfo(): HubInfo {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val temp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return HubInfo(
            backend = backend.name,
            version = BuildConfigCompat.versionName(context),
            uptimeS = SystemClock.elapsedRealtime() / 1000,
            batteryPct = if (level >= 0) level * 100 / scale else null,
            batteryTempC = temp?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 },
            charging = status?.let { it == BatteryManager.BATTERY_STATUS_CHARGING },
            weightKg = config.weightKg,
            maxSpeedKmh = config.maxSpeedKmh,
        )
    }
}

object BuildConfigCompat {
    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
}
