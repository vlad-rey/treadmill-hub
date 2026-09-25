package io.github.vladrey.treadmillhub.session

import io.github.vladrey.treadmillhub.treadmill.Connection
import io.github.vladrey.treadmillhub.treadmill.Phase
import io.github.vladrey.treadmillhub.treadmill.TreadmillState
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Energy expenditure via ACSM metabolic equations — see docs/decisions/0004-calories.md. */
object Calories {
    const val REST_VO2 = 3.5

    /** VO₂, mL/kg/min. Walking up to [walkMaxKmh], running from [runMinKmh], interpolated in between. */
    fun vo2(speedKmh: Double, inclinePct: Double, walkMaxKmh: Double = 6.5, runMinKmh: Double = 8.0): Double {
        if (speedKmh <= 0) return REST_VO2
        val s = speedKmh * 1000 / 60 // m/min
        val g = inclinePct / 100
        val walk = REST_VO2 + 0.1 * s + 1.8 * s * g
        val run = REST_VO2 + 0.2 * s + 0.9 * s * g
        return when {
            speedKmh <= walkMaxKmh -> walk
            speedKmh >= runMinKmh -> run
            else -> walk + (run - walk) * (speedKmh - walkMaxKmh) / (runMinKmh - walkMaxKmh)
        }
    }

    /** Total expenditure, kcal/min (≈ 5 kcal per liter of O₂). */
    fun kcalPerMinute(speedKmh: Double, inclinePct: Double, weightKg: Double) =
        vo2(speedKmh, inclinePct) * weightKg / 1000 * 5

    /** Active expenditure (excluding rest), kcal/min. */
    fun activeKcalPerMinute(speedKmh: Double, inclinePct: Double, weightKg: Double) =
        (vo2(speedKmh, inclinePct) - REST_VO2) * weightKg / 1000 * 5
}

@Serializable
data class Bucket(val speedKmh: Double, val inclinePct: Double, val seconds: Double, val meters: Double)

@Serializable
data class SessionStats(
    val active: Boolean = false,
    val startedAtMs: Long? = null,
    val movingS: Double = 0.0,
    /** Distance: from the treadmill counter if available (matches the console), otherwise from speed. */
    val distanceM: Double = 0.0,
    /** Distance computed by the hub from speed — for comparison (overshoots by ~5% during accelerations). */
    val distanceCalcM: Double = 0.0,
    val kcalCalc: Double = 0.0,
    val kcalActiveCalc: Double = 0.0,
    val kcalTreadmill: Double? = null,
    /** Time and distance grouped by "speed 0.1 km/h × incline 1%" pairs. */
    val buckets: List<Bucket> = emptyList(),
)

/**
 * Tracks a workout from telemetry samples (~1 Hz). Time and calories use the previous sample,
 * so speed/incline changes are accounted for to within a second. Distance is accumulated from the
 * treadmill counter (it resets after STOP — this is handled); if there's no counter, from speed.
 */
class SessionTracker(private val weightKg: () -> Double) {
    private var stats = SessionStats()
    private var lastMs = 0L
    private var lastSpeed = 0.0
    private var lastIncline = 0.0
    private var idleSinceMs: Long? = null
    private var lastTmDist: Int? = null
    private var tmSeen = false
    private var tmDistM = 0.0
    private val buckets = LinkedHashMap<Pair<Int, Int>, DoubleArray>() // (speed×10, incline) → [sec, m]

    val current: SessionStats get() = stats

    fun onState(s: TreadmillState, nowMs: Long): SessionStats {
        val moving = s.connection == Connection.CONNECTED && s.speedKmh > 0

        if (!stats.active && moving) {
            buckets.clear()
            stats = SessionStats(active = true, startedAtMs = nowMs)
            lastMs = nowMs
            lastTmDist = s.distanceM
            tmSeen = false
            tmDistM = 0.0
            lastTmKcal = s.kcal
            tmKcal = 0.0
        }

        if (stats.active) {
            val key = (lastSpeed * 10).roundToInt() to lastIncline.roundToInt()
            val dt = ((nowMs - lastMs) / 1000.0).coerceIn(0.0, 5.0) // a connection gap doesn't inflate the time
            if (dt > 0 && lastSpeed > 0) {
                val meters = lastSpeed / 3.6 * dt
                val w = weightKg()
                val acc = buckets.getOrPut(key) { DoubleArray(2) }
                acc[0] += dt
                if (!tmSeen) acc[1] += meters
                stats = stats.copy(
                    movingS = stats.movingS + dt,
                    distanceCalcM = stats.distanceCalcM + meters,
                    kcalCalc = stats.kcalCalc + Calories.kcalPerMinute(lastSpeed, lastIncline, w) * dt / 60,
                    kcalActiveCalc = stats.kcalActiveCalc + Calories.activeKcalPerMinute(lastSpeed, lastIncline, w) * dt / 60,
                )
            }

            // Treadmill counter: FTMS on the T12B always returns 0 — we treat the counter as real once it's > 0
            val d = s.distanceM
            if (d != null && (d > 0 || tmSeen)) {
                val prev = lastTmDist
                val delta = when {
                    prev == null -> 0
                    d >= prev -> d - prev
                    d < 20 -> d // treadmill was stopped and started again — counting from zero
                    else -> 0
                }
                if (!tmSeen && delta >= 0) {
                    // Switching from calculated to counter-based: replace everything calculated so far with the counter
                    buckets.values.forEach { it[1] = 0.0 }
                    tmSeen = true
                }
                if (delta > 0) {
                    tmDistM += delta
                    buckets.getOrPut(key) { DoubleArray(2) }[1] += delta.toDouble()
                }
                lastTmDist = d
            }

            stats = stats.copy(
                distanceM = if (tmSeen) tmDistM else stats.distanceCalcM,
                kcalTreadmill = trackTreadmillKcal(s.kcal),
                buckets = buckets.map { (k, v) -> Bucket(k.first / 10.0, k.second.toDouble(), v[0], v[1]) },
            )

            // Workout ended: belt is stopped and the treadmill isn't paused/counting down for over a minute
            val stopped = !moving && s.phase != Phase.PAUSED && s.phase != Phase.COUNTDOWN
            idleSinceMs = if (stopped) idleSinceMs ?: nowMs else null
            if (idleSinceMs != null && nowMs - idleSinceMs!! > 60_000) {
                stats = stats.copy(active = false)
                idleSinceMs = null
            }
        }

        lastMs = nowMs
        lastSpeed = s.speedKmh
        lastIncline = s.inclinePct
        return stats
    }

    private var lastTmKcal: Double? = null
    private var tmKcal = 0.0

    /** Treadmill calories accumulate over the run and reset after STOP — we sum the increments. */
    private fun trackTreadmillKcal(k: Double?): Double? {
        if (k == null) return stats.kcalTreadmill
        val prev = lastTmKcal
        tmKcal += when {
            prev == null -> k
            k >= prev -> k - prev
            k < 1.0 -> k   // reset after STOP and a new run
            else -> 0.0    // source switch (FitShow 57.2 → FTMS 57) — not a reset
        }
        lastTmKcal = k
        return tmKcal
    }
}