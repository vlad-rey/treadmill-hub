package io.github.vladrey.treadmillhub

import io.github.vladrey.treadmillhub.session.Calories
import io.github.vladrey.treadmillhub.session.SessionTracker
import io.github.vladrey.treadmillhub.treadmill.Connection
import io.github.vladrey.treadmillhub.treadmill.Phase
import io.github.vladrey.treadmillhub.treadmill.TreadmillState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTest {
    @Test fun acsmWalkingFlat() {
        // 5 км/ч = 83,3 м/мин: VO₂ = 3,5 + 8,33 = 11,83; 70 кг → 4,14 ккал/мин
        assertEquals(4.14, Calories.kcalPerMinute(5.0, 0.0, 70.0), 0.01)
    }

    @Test fun inclineRaisesWalkingCostSharply() {
        // +1,8·S·G = +15 мл/кг/мин при 10 % → 9,39 ккал/мин
        assertEquals(9.39, Calories.kcalPerMinute(5.0, 10.0, 70.0), 0.01)
    }

    @Test fun runningUsesRunningEquation() {
        // 10 км/ч = 166,7 м/мин: VO₂ = 3,5 + 33,33 = 36,83; 70 кг → 12,9 ккал/мин
        assertEquals(12.89, Calories.kcalPerMinute(10.0, 0.0, 70.0), 0.01)
    }

    @Test fun mixedGaitIsBetweenWalkAndRun() {
        val walk = Calories.vo2(6.5, 0.0)
        val run = Calories.vo2(8.0, 0.0)
        val mid = Calories.vo2(7.25, 0.0)
        assertTrue(mid > walk && mid < run)
    }

    @Test fun treadmillCounterIsUsedForDistanceAndKcalIncludingPauseAndRestart() {
        val tracker = SessionTracker { 90.0 }
        var t = 1_000_000L
        fun feed(v: Double, dist: Int?, kcal: Double?, phase: Phase = Phase.RUNNING) {
            tracker.onState(TreadmillState(connection = Connection.CONNECTED, phase = phase, speedKmh = v, distanceM = dist, kcal = kcal), t)
            t += 1_000
        }
        feed(5.0, 0, 0.0)
        repeat(10) { feed(5.0, 10 * (it + 1), 0.1 * (it + 1)) }  // 100 м, 1,0 ккал
        feed(0.0, 100, 1.0, Phase.PAUSED)                         // пауза: счётчики стоят
        feed(0.0, 100, 1.0, Phase.PAUSED)
        feed(5.0, 110, 1.1)
        feed(0.0, 110, 1.0, Phase.FINISHED)                       // FitShow 1,1 → FTMS 1: не обнуление
        feed(0.0, 0, 0.0, Phase.IDLE)                             // СТОП: дорожка обнулилась
        feed(5.0, 10, 0.1)                                        // новый заезд в той же тренировке
        val s = tracker.current
        assertEquals(120.0, s.distanceM, 1e-9)
        assertEquals(1.2, s.kcalTreadmill!!, 1e-9)
        assertEquals(120.0, s.buckets.sumOf { it.meters }, 1e-9)
        assertTrue(s.distanceCalcM > 0)
    }

    @Test fun ftmsZeroDistanceFallsBackToSpeed() {
        val tracker = SessionTracker { 70.0 }
        var t = 0L
        repeat(61) { tracker.onState(TreadmillState(connection = Connection.CONNECTED, phase = Phase.RUNNING, speedKmh = 6.0, distanceM = 0), t); t += 1_000 }
        assertEquals(100.0, tracker.current.distanceM, 1e-6)
    }

    @Test fun sessionIntegratesDistanceBucketsAndEnds() {
        val tracker = SessionTracker { 70.0 }
        val running = TreadmillState(connection = Connection.CONNECTED, phase = Phase.RUNNING, speedKmh = 6.0, inclinePct = 5.0)
        var t = 1_000_000L
        for (i in 0..60) { tracker.onState(running, t); t += 1_000 }
        val s = tracker.current
        assertTrue(s.active)
        assertEquals(60.0, s.movingS, 1e-6)
        assertEquals(100.0, s.distanceM, 1e-6) // 6 км/ч × 60 с
        assertEquals(1, s.buckets.size)
        assertEquals(6.0, s.buckets[0].speedKmh, 1e-9)
        assertEquals(5.0, s.buckets[0].inclinePct, 1e-9)
        assertEquals(Calories.kcalPerMinute(6.0, 5.0, 70.0), s.kcalCalc, 1e-6)

        val stopped = running.copy(phase = Phase.FINISHED, speedKmh = 0.0)
        tracker.onState(stopped, t)
        tracker.onState(stopped, t + 61_000)
        assertFalse(tracker.current.active)
        // последняя секунда движения до сэмпла с остановкой тоже засчитана; итоги остаются видны
        assertEquals(100.0 + 6.0 / 3.6, tracker.current.distanceM, 1e-6)
    }
}
