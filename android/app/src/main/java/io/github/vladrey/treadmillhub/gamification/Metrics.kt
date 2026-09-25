package io.github.vladrey.treadmillhub.gamification

import io.github.vladrey.treadmillhub.session.SavedSession
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** Metrics for a single workout, used for achievements — computed from per-second samples on save. */
@Serializable
data class SessionMetrics(
    val maxSpeedKmh: Double = 0.0,
    /** Elevation gain: Σ speed × time × incline. */
    val climbM: Double = 0.0,
    /** Longest continuous stretch at incline ≥ 15%, s. */
    val maxRunAt15PctS: Double = 0.0,
    /** Longest continuous stretch at speed ≤ 1.0 km/h (while moving), s. */
    val maxRunAtWalkingMinS: Double = 0.0,
    /** Longest pause in the middle of a workout (after which movement resumed), s. */
    val maxResumedPauseS: Double = 0.0,
    /** Whole-number speeds maintained for at least 10 s. */
    val speedsUsed: List<Int> = emptyList(),
    val startHour: Int = 0,
    val endHour: Int = 0,
    val crossedMidnight: Boolean = false,
    /** Programs completed to the end (ids). */
    val programsDone: List<String> = emptyList(),
)

object MetricsCalc {
    fun of(s: SavedSession, programsDone: List<String> = emptyList(), zone: ZoneId = ZoneId.systemDefault()): SessionMetrics {
        var maxV = 0.0
        var climb = 0.0
        var run15 = 0.0; var best15 = 0.0
        var runSlow = 0.0; var bestSlow = 0.0
        var pause = 0.0; var bestPause = 0.0; var movedBefore = false
        val perSpeed = HashMap<Int, Double>()
        for (i in 1 until s.samples.size) {
            val p = s.samples[i - 1]; val x = s.samples[i]
            val dt = ((x.t - p.t) / 1000.0).coerceIn(0.0, 5.0)
            val v = p.speedKmh
            if (v > 0) {
                maxV = maxOf(maxV, v)
                climb += v / 3.6 * dt * p.inclinePct / 100
                perSpeed.merge(v.roundToInt(), dt, Double::plus)
                if (pause > 0 && movedBefore) bestPause = maxOf(bestPause, pause)
                pause = 0.0
                movedBefore = true
            } else if (movedBefore) {
                pause += dt
            }
            run15 = if (v > 0 && p.inclinePct >= 15) run15 + dt else 0.0
            best15 = maxOf(best15, run15)
            runSlow = if (v > 0 && v <= 1.05) runSlow + dt else 0.0
            bestSlow = maxOf(bestSlow, runSlow)
        }
        val start = Instant.ofEpochMilli(s.id).atZone(zone)
        val end = Instant.ofEpochMilli(s.id + (s.samples.lastOrNull()?.t ?: 0)).atZone(zone)
        return SessionMetrics(
            maxSpeedKmh = maxV,
            climbM = climb,
            maxRunAt15PctS = best15,
            maxRunAtWalkingMinS = bestSlow,
            maxResumedPauseS = bestPause,
            speedsUsed = perSpeed.filter { it.value >= 10 }.keys.sorted(),
            startHour = start.hour,
            endHour = end.hour,
            crossedMidnight = end.toLocalDate().isAfter(start.toLocalDate()),
            programsDone = programsDone,
        )
    }
}
