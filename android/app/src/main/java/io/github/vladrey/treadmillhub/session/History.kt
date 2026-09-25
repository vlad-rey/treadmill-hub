package io.github.vladrey.treadmillhub.session

import io.github.vladrey.treadmillhub.gamification.MetricsCalc
import io.github.vladrey.treadmillhub.gamification.SessionMetrics
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Per-second workout sample. t — ms since start. */
@Serializable
data class Sample(
    val t: Long,
    val speedKmh: Double,
    val inclinePct: Double,
    val kcalTreadmill: Double? = null,
    val heartRate: Int? = null,
    val vendorRaw: List<Int>? = null,
    /** Treadmill distance counter reading, m (for cross-checking against the speed-based calculation). */
    val distanceTreadmillM: Int? = null,
)

/** What the treadmill console showed at the end — entered manually, for calibration. */
@Serializable
data class ConsoleReading(val distanceKm: Double? = null, val kcal: Double? = null, val timeS: Int? = null, val note: String? = null)

@Serializable
data class SavedSession(
    val id: Long,
    val weightKg: Double,
    val stats: SessionStats,
    val samples: List<Sample>,
    val console: ConsoleReading? = null,
    /** Who trained; null — started from the treadmill console, can be reassigned. */
    val profileId: String? = null,
    /** Programs completed in full during this workout. */
    val programsDone: List<String> = emptyList(),
    val metrics: SessionMetrics? = null,
)

@Serializable
data class SessionSummary(
    val id: Long,
    val movingS: Double,
    val distanceM: Double,
    val kcalCalc: Double,
    val kcalTreadmill: Double?,
    val console: ConsoleReading?,
    val profileId: String?,
    val metrics: SessionMetrics? = null,
)

private fun SavedSession.summary() =
    SessionSummary(id, stats.movingS, stats.distanceM, stats.kcalCalc, stats.kcalTreadmill, console, profileId, metrics ?: MetricsCalc.of(this, programsDone))

/**
 * Workout history: one JSON file per workout (with per-second samples), kept indefinitely —
 * needed for "all time" totals. Summaries are kept in memory so files aren't re-read.
 */
class HistoryStore(private val dir: File) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val summaries = HashMap<Long, SessionSummary>()

    init {
        dir.mkdirs()
        dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty().forEach { f ->
            runCatching { json.decodeFromString<SavedSession>(f.readText()) }.getOrNull()?.let { summaries[it.id] = it.summary() }
        }
    }

    private fun file(id: Long) = File(dir, "$id.json")

    @Synchronized
    fun save(s: SavedSession) {
        val tmp = File(dir, "${s.id}.json.tmp")
        tmp.writeText(json.encodeToString(s))
        tmp.renameTo(file(s.id))
        summaries[s.id] = s.summary()
    }

    @Synchronized
    fun load(id: Long): SavedSession? = file(id).takeIf { it.exists() }?.let { json.decodeFromString<SavedSession>(it.readText()) }

    @Synchronized
    fun list(): List<SessionSummary> = summaries.values.sortedByDescending { it.id }

    @Synchronized
    fun delete(id: Long): Boolean {
        summaries.remove(id)
        return file(id).delete()
    }

    @Synchronized
    fun setProfile(id: Long, profileId: String?): Boolean {
        val s = load(id) ?: return false
        save(s.copy(profileId = profileId))
        return true
    }

    @Synchronized
    fun setConsole(id: Long, reading: ConsoleReading): Boolean {
        val s = load(id) ?: return false
        save(s.copy(console = reading))
        return true
    }
}
