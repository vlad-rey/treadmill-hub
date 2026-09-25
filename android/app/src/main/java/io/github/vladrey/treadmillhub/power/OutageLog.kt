package io.github.vladrey.treadmillhub.power

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Одно отключение света, как его видела станция. [endMs] = null — света нет до сих пор. */
@Serializable
data class Outage(
    val stationId: String,
    val startMs: Long,
    val endMs: Long? = null,
    val socStart: Double? = null,
    val socEnd: Double? = null,
    val minSoc: Double? = null,
    /** Отдано из батареи на выходы за время без света, Вт·ч. */
    val batteryWh: Double = 0.0,
    val maxOutputW: Int = 0,
    /** Начало или конец неточные: хаб был выключен или перезапускался. */
    val approximate: Boolean = false,
)

@Serializable
data class OutageView(val stationName: String, val outage: Outage)

/** Журнал отключений света по станциям (outages.json, последние [max] записей). */
class OutageLog(private val file: File, private val max: Int = 1000) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val items: MutableList<Outage> =
        runCatching { json.decodeFromString<List<Outage>>(file.readText()) }.getOrDefault(emptyList()).toMutableList()
    private val lastTs = HashMap<String, Long>()
    private var lastSaveMs = 0L

    @Synchronized fun open(id: String) = items.lastOrNull { it.stationId == id && it.endMs == null }

    @Synchronized fun all(): List<Outage> = items.sortedByDescending { it.startMs }

    @Synchronized
    fun start(id: String, ms: Long, soc: Double?, approximate: Boolean = false) {
        if (open(id) != null) return
        items += Outage(id, ms, socStart = soc, socEnd = soc, minSoc = soc, approximate = approximate)
        while (items.size > max) items.removeAt(0)
        save()
    }

    /** Состояние станции, пока нет света: минимальный заряд, энергия из батареи, пиковая нагрузка. */
    @Synchronized
    fun update(id: String, s: StationState) {
        val i = items.indexOfLast { it.stationId == id && it.endMs == null }
        if (i < 0 || !s.connected || lastTs[id] == s.updatedAtMs) return
        val dt = lastTs[id]?.let { ((s.updatedAtMs - it) / 1000.0).coerceIn(0.0, 60.0) } ?: 0.0
        lastTs[id] = s.updatedAtMs
        val o = items[i]
        items[i] = o.copy(
            socEnd = s.socPct ?: o.socEnd,
            minSoc = listOfNotNull(o.minSoc, s.socPct).minOrNull(),
            batteryWh = o.batteryWh + s.outputW * dt / 3600,
            maxOutputW = maxOf(o.maxOutputW, s.outputW),
        )
        if (s.updatedAtMs - lastSaveMs >= 60_000) save()
    }

    @Synchronized
    fun end(id: String, ms: Long, soc: Double?, approximate: Boolean = false) {
        val i = items.indexOfLast { it.stationId == id && it.endMs == null }
        if (i < 0) return
        val o = items[i]
        items[i] = o.copy(endMs = maxOf(ms, o.startMs), socEnd = soc ?: o.socEnd, approximate = o.approximate || approximate)
        lastTs.remove(id)
        save()
    }

    @Synchronized
    fun save() {
        lastSaveMs = System.currentTimeMillis()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString<List<Outage>>(items))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
