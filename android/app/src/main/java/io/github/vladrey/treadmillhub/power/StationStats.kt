package io.github.vladrey.treadmillhub.power

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Station totals for a day (or sum over a period). */
@Serializable
data class StationTotals(
    /** How many times AC charging started. */
    val chargeSessions: Int = 0,
    /** Battery percent gained and given up. 100% charge = 1 equivalent full cycle. */
    val chargedPct: Double = 0.0,
    val dischargedPct: Double = 0.0,
    /** Energy from AC charging and energy delivered to the output, Wh. */
    val chargedWh: Double = 0.0,
    val outputWh: Double = 0.0,
    /** Delivered to the output while there was no grid power (i.e. from the battery), Wh. */
    val offgridOutputWh: Double = 0.0,
    val outages: Int = 0,
    val outageS: Double = 0.0,
) {
    val fullCycles get() = chargedPct / 100

    operator fun plus(o: StationTotals) = StationTotals(
        chargeSessions + o.chargeSessions, chargedPct + o.chargedPct, dischargedPct + o.dischargedPct,
        chargedWh + o.chargedWh, outputWh + o.outputWh, offgridOutputWh + o.offgridOutputWh, outages + o.outages, outageS + o.outageS,
    )
}

@Serializable
data class PeriodStats(
    val today: StationTotals, val week: StationTotals, val month: StationTotals,
    val quarter: StationTotals, val year: StationTotals, val all: StationTotals,
    val sinceDate: String?,
)

/** Storage: station → date (YYYY-MM-DD) → day totals. Saved no more often than once a minute. */
class StationStatsStore(private val file: File, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val days: MutableMap<String, MutableMap<String, StationTotals>> =
        runCatching { json.decodeFromString<Map<String, Map<String, StationTotals>>>(file.readText()) }.getOrDefault(emptyMap())
            .mapValues { it.value.toMutableMap() }.toMutableMap()
    private var dirtySinceMs: Long? = null

    fun date(ms: Long): String = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toString()

    @Synchronized
    fun add(stationId: String, ms: Long, delta: StationTotals) {
        val m = days.getOrPut(stationId) { mutableMapOf() }
        val key = date(ms)
        m[key] = (m[key] ?: StationTotals()) + delta
        if (dirtySinceMs == null) dirtySinceMs = ms
        if (ms - dirtySinceMs!! >= 60_000) flush()
    }

    @Synchronized
    fun flush() {
        dirtySinceMs = null
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString<Map<String, Map<String, StationTotals>>>(days))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** Totals for dates [from]..[to] inclusive. */
    @Synchronized
    fun range(stationId: String, from: LocalDate, to: LocalDate): StationTotals =
        days[stationId].orEmpty().filterKeys { LocalDate.parse(it) in from..to }.values.fold(StationTotals()) { a, b -> a + b }

    @Synchronized
    fun periods(stationId: String, nowMs: Long = System.currentTimeMillis()): PeriodStats {
        val m = days[stationId].orEmpty()
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        fun sum(from: LocalDate?) = m.filterKeys { from == null || LocalDate.parse(it) >= from }.values.fold(StationTotals()) { a, b -> a + b }
        return PeriodStats(
            today = sum(today),
            week = sum(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))),
            month = sum(today.withDayOfMonth(1)),
            quarter = sum(today.withMonth((today.monthValue - 1) / 3 * 3 + 1).withDayOfMonth(1)),
            year = sum(today.withDayOfYear(1)),
            all = sum(null),
            sinceDate = m.keys.minOrNull(),
        )
    }
}

/**
 * Turns a stream of station states into total increments. Percent — from charge change
 * (increase — "charged", decrease — "discharged"), energy — by integrating power over time,
 * charging start — when charging power stays above 20 W for two polls in a row.
 */
class StationStatsTracker(private val stationId: String, private val store: StationStatsStore) {
    private var lastTs = 0L
    private var lastSoc: Double? = null
    private var chargingPolls = 0
    private var charging = false

    /** [gridOn] — confirmed (debounced) power state from GridWatch; [outageStarted] — power just went out. */
    fun onState(s: StationState, gridOn: Boolean?, outageStarted: Boolean) {
        if (!s.connected || s.updatedAtMs == lastTs) return
        val now = s.updatedAtMs
        val dt = if (lastTs == 0L) 0.0 else ((now - lastTs) / 1000.0).coerceIn(0.0, 60.0)
        lastTs = now

        var charged = 0.0; var discharged = 0.0
        val soc = s.socPct
        if (soc != null) {
            lastSoc?.let { prev -> if (soc > prev) charged = soc - prev else discharged = prev - soc }
            lastSoc = soc
        }

        chargingPolls = if (s.acChargeW > 20) chargingPolls + 1 else 0
        val sessionStarted = !charging && chargingPolls >= 2
        charging = when { chargingPolls >= 2 -> true; s.acChargeW <= 20 -> false; else -> charging }

        store.add(stationId, now, StationTotals(
            chargeSessions = if (sessionStarted) 1 else 0,
            chargedPct = charged,
            dischargedPct = discharged,
            chargedWh = s.acChargeW * dt / 3600,
            outputWh = s.outputW * dt / 3600,
            offgridOutputWh = if (gridOn == false) s.outputW * dt / 3600 else 0.0,
            outages = if (outageStarted) 1 else 0,
            outageS = if (gridOn == false) dt else 0.0,
        ))
    }
}
