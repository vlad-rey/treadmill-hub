package io.github.vladrey.treadmillhub

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import io.github.vladrey.treadmillhub.gamification.Celebration
import io.github.vladrey.treadmillhub.gamification.Game
import io.github.vladrey.treadmillhub.gamification.MetricsCalc
import io.github.vladrey.treadmillhub.gamification.Telegram
import io.github.vladrey.treadmillhub.bot.Bot
import io.github.vladrey.treadmillhub.net.DeviceWatch
import io.github.vladrey.treadmillhub.net.NetState
import io.github.vladrey.treadmillhub.net.NetWatch
import io.github.vladrey.treadmillhub.power.PowerHub
import io.github.vladrey.treadmillhub.router.RouterSpeed
import io.github.vladrey.treadmillhub.router.RouterWatch
import io.github.vladrey.treadmillhub.net.SpeedResult
import io.github.vladrey.treadmillhub.program.BuiltinPrograms
import io.github.vladrey.treadmillhub.program.RunState
import io.github.vladrey.treadmillhub.program.ProgramRunner
import io.github.vladrey.treadmillhub.program.ProgramStatus
import io.github.vladrey.treadmillhub.program.ProgramStore
import io.github.vladrey.treadmillhub.program.Segment
import io.github.vladrey.treadmillhub.program.capSpeed
import io.github.vladrey.treadmillhub.session.HistoryStore
import io.github.vladrey.treadmillhub.session.ProfileStore
import io.github.vladrey.treadmillhub.session.StatsCalculator
import io.github.vladrey.treadmillhub.session.WeightStore
import io.github.vladrey.treadmillhub.session.Sample
import io.github.vladrey.treadmillhub.session.SavedSession
import io.github.vladrey.treadmillhub.session.SessionStats
import io.github.vladrey.treadmillhub.session.SessionTracker
import io.github.vladrey.treadmillhub.treadmill.Command
import io.github.vladrey.treadmillhub.treadmill.CommandResult
import io.github.vladrey.treadmillhub.treadmill.FtmsBleBackend
import io.github.vladrey.treadmillhub.treadmill.LinkInfo
import io.github.vladrey.treadmillhub.treadmill.Limits
import io.github.vladrey.treadmillhub.treadmill.Phase
import io.github.vladrey.treadmillhub.treadmill.SimulatorBackend
import io.github.vladrey.treadmillhub.treadmill.TreadmillBackend
import io.github.vladrey.treadmillhub.treadmill.TreadmillState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    /** Зарядка подключена (при ограничителе заряда может быть подключена, но не заряжать). */
    val plugged: Boolean? = null,
    val memAvailMb: Long? = null,
    val memTotalMb: Long? = null,
    val storageFreeMb: Long? = null,
    val wifiRssi: Int? = null,
    val link: LinkInfo = LinkInfo(),
    val sessions: Int = 0,
    val lastBackupMs: Long? = null,
    val net: NetState = NetState(),
)

@Serializable
data class Snapshot(
    val treadmill: TreadmillState,
    val session: SessionStats,
    val hub: HubInfo,
    /** Чья сейчас тренировка (тот, кто нажал СТАРТ); null — запуск с пульта. */
    val ownerProfileId: String? = null,
    val ownerName: String? = null,
    val program: ProgramStatus? = null,
    /** Окна наград/ачивок, ещё не подтверждённые на телефоне владельца (клиент фильтрует по своему профилю). */
    val celebrations: List<Celebration> = emptyList(),
)

@Serializable
data class ControlRequest(
    val action: String,
    val value: Double? = null,
    val profileId: String? = null,
    val programId: String? = null,
    val level: Int? = null,
    val minutes: Int? = null,
)

/** Связывает дорожку, учёт тренировки и API. Живёт внутри HubService. */
class Hub(private val context: Context, val config: HubConfig) {
    val backend: TreadmillBackend =
        if (config.backend == "sim") SimulatorBackend { Limits.MAX_SPEED_KMH } else FtmsBleBackend(context, config)

    val profiles = ProfileStore(File(context.filesDir, "profiles.json"))
    val weights = WeightStore(File(context.filesDir, "weights.json"))
    val builtin = BuiltinPrograms(context.assets.open("programs-t12b.json").bufferedReader().use { it.readText() })
    val programs = ProgramStore(File(context.filesDir, "programs.json"))
    @Volatile private var runner: ProgramRunner? = null
    val history = HistoryStore(File(context.filesDir, "sessions"))
    lateinit var game: Game
        private set
    val telegram = Telegram({ config.telegramToken }, { config.telegramChatId }, File(context.filesDir, "telegram-outbox.json"))
    val power = PowerHub(context, context.filesDir, telegram)
    val net = NetWatch(context, context.filesDir, telegram)
    val router = RouterWatch(config, host = { net.gateway() })
    val devices = DeviceWatch(context, context.filesDir, telegram, router, stations = { power.configs().associate { it.address.lowercase() to it.name } })
    val speed = RouterSpeed(router, context.filesDir, onResult = ::onSpeedResult)
    private lateinit var scope: CoroutineScope
    val bot = Bot(this, context.filesDir)
    private val programsDone = mutableListOf<String>()
    private var lastDoneRunner: ProgramRunner? = null
    private var lastLiveCheckMs = 0L

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
        game = Game(context.filesDir, history, weights, profiles, telegram, scope) {
            _snapshot.value = _snapshot.value.copy(celebrations = game.store.pending())
        }
        _snapshot.value = _snapshot.value.copy(celebrations = game.store.pending())
        // Ачивки за уже пройденные тренировки (например, после обновления списка ачивок)
        scope.launch { profiles.all().forEach { p -> runCatching { game.evaluate(p.id) } } }
        backend.start(scope)
        power.start(scope)
        net.start(scope)
        this.scope = scope
        router.start(scope)
        speed.start(scope, busy = { tracker.current.active })
        devices.start(scope)
        telegram.start(scope)
        bot.start(scope)
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
        // Программа: раз в секунду сверяемся с дорожкой и отправляем команды нового отрезка
        scope.launch {
            while (isActive) {
                delay(1_000)
                val r = runner ?: continue
                for (cmd in r.tick(backend.state.value.phase, 1.0)) {
                    val res = backend.command(cmd)
                    if (!res.ok) android.util.Log.w("Hub", "программа: $cmd — ${res.message}")
                }
                // Программа пройдена до конца — для ачивок «По плану», «Отличник», «Сам себе тренер»
                if (r.state == RunState.DONE && r !== lastDoneRunner && tracker.current.active) {
                    lastDoneRunner = r
                    programsDone += r.id
                }
                _snapshot.value = _snapshot.value.copy(program = r.status())
            }
        }
    }

    fun close() {
        if (tracker.current.active) save(tracker.current)
        power.close()
        backend.close()
    }

    /** Посекундная запись тренировки; сохранение раз в минуту и по окончании. */
    private fun record(s: TreadmillState, session: SessionStats, wasActive: Boolean, now: Long) {
        if (session.active) {
            if (!wasActive) {
                samples.clear(); lastSampleMs = 0; lastSaveMs = now
                programsDone.clear()
                owner = nextOwner
                nextOwner = null
            }
            if (now - lastSampleMs >= 900) {
                samples += Sample(now - (session.startedAtMs ?: now), s.speedKmh, s.inclinePct, s.kcal, s.heartRate, s.vendorRaw, s.distanceM)
                lastSampleMs = now
            }
            if (now - lastSaveMs >= 60_000) { save(session); lastSaveMs = now }
            // Реальные награды — в момент достижения, прямо во время тренировки
            if (now - lastLiveCheckMs >= 5_000 && backend.name != "sim") {
                lastLiveCheckMs = now
                runCatching { game.liveCheck(owner, session.startedAtMs, session.distanceM) }
            }
        } else if (wasActive) {
            save(session)
            if (backend.name != "sim") runCatching { game.evaluate(owner) }
            // Тренировка закрыта — убираем завершённую программу с экрана
            if (runner?.finished == true) {
                runner = null
                _snapshot.value = _snapshot.value.copy(program = null)
            }
        }
    }

    private fun save(session: SessionStats) {
        if (backend.name == "sim") return // симулятор — только для проверок, в историю не пишем
        val id = session.startedAtMs ?: return
        runCatching {
            val base = SavedSession(id, weightOf(owner), session, samples.toList(), profileId = owner, programsDone = programsDone.toList())
            history.save(base.copy(metrics = MetricsCalc.of(base, base.programsDone)))
        }
            .onFailure { android.util.Log.w("Hub", "не удалось сохранить тренировку: ${it.message}") }
    }

    fun stats(profileId: String?) = StatsCalculator.compute(profileId, history.list(), StatsCalculator.now())

    suspend fun control(req: ControlRequest): CommandResult {
        val s = backend.state.value
        val maxSpeed = minOf(profiles.get(req.profileId)?.maxSpeedKmh ?: config.maxSpeedKmh, Limits.MAX_SPEED_KMH)
        val cmd = when (req.action) {
            "start" -> {
                if (!tracker.current.active) nextOwner = req.profileId
                if (runner?.finished == true) { runner = null; _snapshot.value = _snapshot.value.copy(program = null) }
                Command.Start
            }
            "program" -> return startProgram(req, maxSpeed)
            "programEnd" -> {
                // Программа прекращается, лента продолжает ехать в ручном режиме
                runner?.cancel()
                runner?.let { _snapshot.value = _snapshot.value.copy(program = it.status()) }
                return CommandResult(true, "программа завершена, ручной режим")
            }
            "stop" -> {
                if (s.phase == Phase.COUNTDOWN) runCatching { game.event(req.profileId ?: nextOwner ?: owner, "changed_mind") }
                runner?.cancel()
                Command.Stop
            }
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

    /** Отрезки программы (встроенной — по уровню и длительности) со скоростью в пределах лимита. */
    fun programSegments(id: String, level: Int?, minutes: Int?, maxSpeed: Double): List<Segment>? =
        (builtin.segments(id, level ?: 1, minutes ?: builtin.defaultMinutes) ?: programs.get(id)?.segments())?.capSpeed(maxSpeed)

    private suspend fun startProgram(req: ControlRequest, maxSpeed: Double): CommandResult {
        val id = req.programId ?: return bad("нужен programId")
        val phase = backend.state.value.phase
        if (phase != Phase.IDLE && phase != Phase.FINISHED) return bad("программу можно запустить, когда лента стоит")
        val segments = programSegments(id, req.level, req.minutes, maxSpeed) ?: return bad("программа не найдена")
        val name = builtin.get(id)?.let { "${it.id} ${it.name} · ур. ${req.level ?: 1}" } ?: programs.get(id)?.name ?: id
        if (!tracker.current.active) nextOwner = req.profileId
        val r = ProgramRunner(id, name, segments)
        runner = r
        _snapshot.value = _snapshot.value.copy(program = r.status())
        val res = backend.command(Command.Start)
        if (!res.ok) { runner = null; _snapshot.value = _snapshot.value.copy(program = null) }
        return res
    }

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
            plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)?.takeIf { it >= 0 }?.let { it != 0 },
            memAvailMb = mem.availMem / 1_048_576,
            memTotalMb = mem.totalMem / 1_048_576,
            storageFreeMb = context.filesDir.usableSpace / 1_048_576,
            wifiRssi = runCatching {
                @Suppress("DEPRECATION")
                context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java).connectionInfo.rssi
            }.getOrNull()?.takeIf { it > -127 },
            link = backend.link,
            sessions = history.list().size,
            lastBackupMs = config.lastBackupMs,
            net = net.state,
        )
    }

    private val mem get() = android.app.ActivityManager.MemoryInfo().also {
        context.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(it)
    }

    /** Замер скорости по кнопке или команде бота; [done] — после окончания. */
    fun runSpeedTest(done: (SpeedResult) -> Unit = {}): Boolean {
        if (speed.running || !router.status.connected) return false
        scope.launch(Dispatchers.IO) { done(speed.run()) }
        return true
    }

    /** Скорость заметно ниже обычной (меньше половины медианы 10 прошлых замеров) — сообщение владельцу. */
    private fun onSpeedResult(r: SpeedResult) {
        val down = r.downMbps ?: return
        val prev = speed.all().dropLast(1).mapNotNull { it.downMbps }.takeLast(10)
        if (prev.size < 3) return
        val median = prev.sorted()[prev.size / 2]
        if (down < median / 2) telegram.send(scope, "🐢 Скорость интернета упала: ${down.roundToInt()} ↓ / ${r.upMbps?.roundToInt() ?: "?"} ↑ Мбит/с, " +
            "обычно около ${median.roundToInt()} ↓. Замер роутера в ${java.text.SimpleDateFormat("HH:mm").format(java.util.Date(r.atMs))}.")
    }

    fun refreshCelebrations() { _snapshot.value = _snapshot.value.copy(celebrations = game.store.pending()) }

    /** Отметка от скрипта бэкапа на PC (redmi6-homeserver/tools/backup-hub-data.ps1). */
    fun markBackup() {
        config.lastBackupMs = System.currentTimeMillis()
        _snapshot.value = _snapshot.value.copy(hub = hubInfo())
    }
}

object BuildConfigCompat {
    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
}
