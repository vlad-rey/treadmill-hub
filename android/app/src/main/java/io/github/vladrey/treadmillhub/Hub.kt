package io.github.vladrey.treadmillhub

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import io.github.vladrey.treadmillhub.session.HistoryStore
import io.github.vladrey.treadmillhub.session.ProfileStore
import io.github.vladrey.treadmillhub.session.StatsCalculator
import io.github.vladrey.treadmillhub.session.Sample
import io.github.vladrey.treadmillhub.session.SavedSession
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
import java.io.File
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
data class Snapshot(
    val treadmill: TreadmillState,
    val session: SessionStats,
    val hub: HubInfo,
    /** Чья сейчас тренировка (тот, кто нажал СТАРТ); null — запуск с пульта. */
    val ownerProfileId: String? = null,
    val ownerName: String? = null,
)

@Serializable
data class ControlRequest(val action: String, val value: Double? = null, val profileId: String? = null)

/** Связывает дорожку, учёт тренировки и API. Живёт внутри HubService. */
class Hub(private val context: Context, val config: HubConfig) {
    val backend: TreadmillBackend =
        if (config.backend == "sim") SimulatorBackend { Limits.MAX_SPEED_KMH } else FtmsBleBackend(context, config)

    val profiles = ProfileStore(File(context.filesDir, "profiles.json"))
    val history = HistoryStore(File(context.filesDir, "sessions"))

    /** Кто нажал СТАРТ — станет владельцем следующей тренировки. */
    @Volatile private var nextOwner: String? = null
    @Volatile private var owner: String? = null
    private fun weightOf(profileId: String?) = profiles.get(profileId)?.weightKg ?: config.weightKg

    private val tracker = SessionTracker { weightOf(owner) }
    private val samples = ArrayList<Sample>()
    private var lastSampleMs = 0L
    private var lastSaveMs = 0L
    private val _snapshot = MutableStateFlow(Snapshot(backend.state.value, tracker.current, hubInfo()))
    val snapshot = _snapshot.asStateFlow()

    fun start(scope: CoroutineScope) {
        backend.start(scope)
        scope.launch {
            backend.state.collect { s ->
                val now = System.currentTimeMillis()
                val wasActive = tracker.current.active
                val session = tracker.onState(s, now)
                record(s, session, wasActive, now)
                _snapshot.value = _snapshot.value.copy(
                    treadmill = s, session = session, ownerProfileId = owner, ownerName = profiles.get(owner)?.name,
                )
            }
        }
        scope.launch {
            while (isActive) {
                _snapshot.value = _snapshot.value.copy(hub = hubInfo())
                delay(10_000)
            }
        }
    }

    fun close() {
        if (tracker.current.active) save(tracker.current)
        backend.close()
    }

    /** Посекундная запись тренировки; сохранение раз в минуту и по окончании. */
    private fun record(s: TreadmillState, session: SessionStats, wasActive: Boolean, now: Long) {
        if (session.active) {
            if (!wasActive) {
                samples.clear(); lastSampleMs = 0; lastSaveMs = now
                owner = nextOwner
                nextOwner = null
            }
            if (now - lastSampleMs >= 900) {
                samples += Sample(now - (session.startedAtMs ?: now), s.speedKmh, s.inclinePct, s.kcal, s.heartRate, s.vendorRaw)
                lastSampleMs = now
            }
            if (now - lastSaveMs >= 60_000) { save(session); lastSaveMs = now }
        } else if (wasActive) {
            save(session)
        }
    }

    private fun save(session: SessionStats) {
        val id = session.startedAtMs ?: return
        runCatching { history.save(SavedSession(id, weightOf(owner), session, samples.toList(), profileId = owner)) }
            .onFailure { android.util.Log.w("Hub", "не удалось сохранить тренировку: ${it.message}") }
    }

    fun stats(profileId: String?) = StatsCalculator.compute(profileId, history.list(), StatsCalculator.now())

    suspend fun control(req: ControlRequest): CommandResult {
        val s = backend.state.value
        val maxSpeed = minOf(profiles.get(req.profileId)?.maxSpeedKmh ?: config.maxSpeedKmh, Limits.MAX_SPEED_KMH)
        val cmd = when (req.action) {
            "start" -> {
                if (!tracker.current.active) nextOwner = req.profileId
                Command.Start
            }
            "stop" -> Command.Stop
            "pause" -> Command.Pause
            "speed" -> Command.Speed(req.value ?: return bad("нужно value"))
            "incline" -> Command.Incline(req.value ?: return bad("нужно value"))
            "speedDelta" -> {
                if (s.phase != Phase.RUNNING) return bad("лента не движется")
                val v = ((s.speedKmh + (req.value ?: return bad("нужно value"))) * 10).roundToInt() / 10.0
                Command.Speed(v.coerceIn(Limits.MIN_SPEED_KMH, maxSpeed))
            }
            "inclineDelta" -> {
                val v = (s.inclinePct + (req.value ?: return bad("нужно value"))).roundToInt().toDouble()
                Command.Incline(v.coerceIn(Limits.MIN_INCLINE_PCT, Limits.MAX_INCLINE_PCT))
            }
            else -> return bad("неизвестное действие ${req.action}")
        }
        // Лимит скорости — из профиля того, кто отправил команду
        Limits.check(cmd, maxSpeed)?.let { return bad(it) }
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
