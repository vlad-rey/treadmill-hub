package io.github.vladrey.treadmillhub.power

import android.content.Context
import io.github.vladrey.treadmillhub.gamification.Telegram
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class StationConfig(val id: String = "", val name: String, val address: String)

@Serializable
data class StationView(val id: String, val name: String, val address: String, val state: StationState, val stats: PeriodStats)

/**
 * Станции Fossibot: список (хранится на хабе), опрос, свет есть/нет → Telegram.
 * Переходы на разных станциях в пределах [mergeMs] уходят одним сообщением.
 */
class PowerHub(private val context: Context, dir: File, private val telegram: Telegram, private val mergeMs: Long = 30_000) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val file = File(dir, "stations.json")
    @Volatile private var configs: List<StationConfig> =
        runCatching { json.decodeFromString<List<StationConfig>>(file.readText()) }.getOrDefault(emptyList())
    private val stations = HashMap<String, PowerStation>()
    private val watches = HashMap<String, GridWatch>()
    private val trackers = HashMap<String, StationStatsTracker>()
    private val statsStore = StationStatsStore(File(dir, "stations-stats.json"))
    private val pending = ArrayList<Pair<Long, String>>() // (время, текст)
    private lateinit var scope: CoroutineScope

    fun start(scope: CoroutineScope) {
        this.scope = scope
        configs.forEach(::launch)
        scope.launch {
            while (isActive) {
                delay(5_000)
                check()
            }
        }
    }

    fun close() {
        synchronized(stations) { stations.values.forEach { it.close() } }
        statsStore.flush()
    }

    private fun launch(c: StationConfig) = synchronized(stations) {
        val s = PowerStation(context, c.address)
        stations[c.id] = s
        watches[c.id] = GridWatch()
        trackers[c.id] = StationStatsTracker(c.id, statsStore)
        s.start(scope)
    }

    fun list(): List<StationView> = configs.map { c ->
        StationView(c.id, c.name, c.address, stations[c.id]?.state?.value ?: StationState(), statsStore.periods(c.id))
    }

    /** Замена списка станций: новые подключаются, удалённые отключаются. */
    fun setConfigs(input: List<StationConfig>): List<StationView> {
        val next = input.map { c ->
            require(c.name.isNotBlank() && c.name.length <= 30) { "имя станции 1–30 символов" }
            require(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").matches(c.address.uppercase())) { "адрес вида AA:BB:CC:DD:EE:FF" }
            c.copy(id = c.id.ifBlank { UUID.randomUUID().toString().take(8) }, address = c.address.uppercase())
        }
        synchronized(stations) {
            val keep = next.associateBy { it.id }
            stations.keys.filter { id -> keep[id]?.address != configs.firstOrNull { it.id == id }?.address || id !in keep }
                .forEach { id -> stations.remove(id)?.close(); watches.remove(id); trackers.remove(id) }
            configs = next
            next.filter { it.id !in stations }.forEach(::launch)
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(configs))
        tmp.renameTo(file)
        return list()
    }

    suspend fun writeSetting(id: String, key: String, value: Int): Result<StationState> =
        stations[id]?.writeSetting(key, value) ?: Result.failure(IllegalArgumentException("станция не найдена"))

    private fun check() {
        val now = System.currentTimeMillis()
        val multi = configs.size > 1
        for (c in configs) {
            val st = stations[c.id]?.state?.value ?: continue
            val watch = watches[c.id] ?: continue
            val wasOn = watch.gridOn
            val text = watch.update(st)
            // Отключение засчитывается, когда GridWatch подтвердил его (без дребезга)
            trackers[c.id]?.onState(st, watch.gridOn, outageStarted = wasOn == true && watch.gridOn == false)
            if (text == null) continue
            synchronized(pending) { pending += now to (if (multi) "«${c.name}»: " else "") + text }
        }
        synchronized(pending) {
            if (pending.isNotEmpty() && now - pending.first().first >= mergeMs) {
                telegram.send(scope, pending.joinToString("\n\n") { it.second })
                pending.clear()
            }
        }
    }
}
