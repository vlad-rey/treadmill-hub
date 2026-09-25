package io.github.vladrey.treadmillhub

import io.github.vladrey.treadmillhub.gamification.Achievements
import io.github.vladrey.treadmillhub.gamification.Game
import io.github.vladrey.treadmillhub.gamification.MetricsCalc
import io.github.vladrey.treadmillhub.gamification.Period
import io.github.vladrey.treadmillhub.gamification.Periods
import io.github.vladrey.treadmillhub.gamification.RewardDef
import io.github.vladrey.treadmillhub.gamification.Telegram
import io.github.vladrey.treadmillhub.session.HistoryStore
import io.github.vladrey.treadmillhub.session.ProfilePatch
import io.github.vladrey.treadmillhub.session.ProfileStore
import io.github.vladrey.treadmillhub.session.Sample
import io.github.vladrey.treadmillhub.session.SavedSession
import io.github.vladrey.treadmillhub.session.SessionStats
import io.github.vladrey.treadmillhub.session.WeightStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime

class GameTest {
    private val dir = Files.createTempDirectory("game").toFile()
    private val profiles = ProfileStore(java.io.File(dir, "profiles.json"))
    private val history = HistoryStore(java.io.File(dir, "sessions"))
    private val weights = WeightStore(java.io.File(dir, "weights.json"))
    private var changes = 0
    private val game = Game(dir, history, weights, profiles, Telegram({ null }, { null }), CoroutineScope(Dispatchers.Unconfined)) { changes++ }
    private val diana = profiles.create(ProfilePatch(name = "Диана", weightKg = 55.0))

    private fun session(atMs: Long, km: Double, speed: Double = 5.0, incline: Double = 0.0): SavedSession {
        val secs = (km * 1000 / (speed / 3.6)).toInt()
        val samples = (0..secs).map { Sample(it * 1000L, speed, incline) }
        return SavedSession(atMs, 55.0, SessionStats(movingS = secs.toDouble(), distanceM = km * 1000, kcalCalc = km * 50), samples, profileId = diana.id)
            .let { it.copy(metrics = MetricsCalc.of(it)) }
    }

    @Test fun weeklyRewardFiresOnceWhenThresholdCrossedLive() {
        game.store.setRewards(diana.id, listOf(RewardDef("sushi", diana.id, "Суши-сет", "🍣", Period.WEEK, 6.0)))
        history.save(session(System.currentTimeMillis() - 3_600_000, 4.0))

        game.liveCheck(diana.id, 42L, 1_500.0)                  // 4 + 1,5 = 5,5 км — ещё нет
        assertTrue(game.store.rewardsEarned(diana.id).isEmpty())

        game.liveCheck(diana.id, 42L, 2_100.0)                  // 6,1 км — награда
        game.liveCheck(diana.id, 42L, 3_000.0)                  // повторно не выдаётся
        assertEquals(1, game.store.rewardsEarned(diana.id).size)
        val c = game.store.pending().single()
        assertEquals("reward", c.kind)
        assertEquals(diana.id, c.profileId)

        game.store.ack(c.id)
        assertTrue(game.store.pending().isEmpty())
    }

    @Test fun periodKeysAreIsoWeeksAndMonths() {
        val zone = ZoneId.of("Europe/Kyiv")
        val fri = ZonedDateTime.of(2026, 9, 25, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        val nextMon = ZonedDateTime.of(2026, 9, 28, 0, 30, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("2026-W39", Periods.key(Period.WEEK, fri, zone))
        assertEquals("2026-W40", Periods.key(Period.WEEK, nextMon, zone))
        assertEquals("2026-09", Periods.key(Period.MONTH, fri, zone))
    }

    @Test fun achievementsAreAwardedFromMetrics() {
        history.save(session(System.currentTimeMillis() - 7_200_000, 5.2, speed = 6.0, incline = 10.0))
        game.evaluate(diana.id)
        val ids = game.store.achievements(diana.id).map { it.achievementId }.toSet()
        assertTrue("first_step" in ids)
        assertTrue("km_5" in ids)
        assertTrue("climb_100" in ids)          // 5,2 км × 10 % = 520 м
        assertFalse("km_10" in ids)
        game.evaluate(diana.id)                 // повторно не дублируются
        assertEquals(ids.size, game.store.achievements(diana.id).size)
        assertTrue(Achievements.all.size >= 25)
    }

    @Test fun changedMindIsSecretEventAchievement() {
        game.event(diana.id, "changed_mind")
        assertTrue("changed_mind" in game.store.achievements(diana.id).map { it.achievementId })
    }
}
