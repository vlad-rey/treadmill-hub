package io.github.vladrey.treadmillhub.gamification

import io.github.vladrey.treadmillhub.session.HistoryStore
import io.github.vladrey.treadmillhub.session.ProfileStore
import io.github.vladrey.treadmillhub.session.WeightStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Locale
import java.util.UUID

@Serializable
data class RewardStatus(
    val def: RewardDef,
    val periodKey: String,
    val currentKm: Double,
    val earned: Boolean,
    val delivered: Boolean,
    val earnedTotal: Int,
)

@Serializable
data class GameState(
    val profileId: String,
    val achievements: List<AchievementDto>,
    val rewards: List<RewardStatus>,
    val recentRewards: List<RewardEarned>,
)

/** Ачивки и реальные награды. Проверки дешёвые: по сводкам тренировок с готовыми метриками. */
class Game(
    dir: File,
    private val history: HistoryStore,
    private val weights: WeightStore,
    private val profiles: ProfileStore,
    private val telegram: Telegram,
    private val scope: CoroutineScope,
    private val onChange: () -> Unit,
) {
    val store = GameStore(dir)

    private fun ctx(profileId: String) = AchievementContext(
        sessions = history.list().filter { it.profileId == profileId },
        weights = weights.of(profileId),
        events = store.events(profileId),
    )

    /** Новые ачивки профиля → окна на его телефоне. Вызывается после тренировки, записи веса, события. */
    fun evaluate(profileId: String?) {
        profileId ?: return
        val have = store.achievements(profileId).map { it.achievementId }.toSet()
        val c = ctx(profileId)
        val fresh = Achievements.all.filter { it.id !in have && it.progress(c) >= 1.0 }
        if (fresh.isEmpty()) return
        val now = System.currentTimeMillis()
        store.addAchievements(profileId, fresh.map { it.id }, fresh.map {
            Celebration(UUID.randomUUID().toString().take(8), profileId, "achievement", it.title, it.icon, it.description,
                if (it.grade == Grade.LEGEND) "fireworks" else "chime", it.grade, now)
        })
        onChange()
    }

    fun event(profileId: String?, event: String) {
        profileId ?: return
        if (store.addEvent(profileId, event)) evaluate(profileId)
    }

    /** Дистанция профиля за текущий период: сохранённые тренировки + идущая сейчас. */
    private fun periodKm(profileId: String, period: Period, now: Long, liveSessionId: Long?, liveDistanceM: Double): Double {
        val start = Periods.start(period, now)
        val saved = history.list().filter { it.profileId == profileId && it.id >= start && it.id != liveSessionId }.sumOf { it.distanceM }
        return (saved + liveDistanceM) / 1000
    }

    /** Проверка реальных наград во время тренировки — окно всплывает в момент достижения. */
    fun liveCheck(profileId: String?, liveSessionId: Long?, liveDistanceM: Double) {
        profileId ?: return
        val defs = store.rewards(profileId)
        if (defs.isEmpty()) return
        val now = System.currentTimeMillis()
        for (d in defs) {
            val key = Periods.key(d.period, now)
            if (store.hasReward(d.id, key)) continue
            val km = periodKm(profileId, d.period, now, liveSessionId, liveDistanceM)
            if (km < d.km) continue
            val periodText = if (d.period == Period.WEEK) "за неделю" else "за месяц"
            val name = profiles.get(profileId)?.name ?: "?"
            store.addReward(
                RewardEarned(d.id, profileId, key, now),
                Celebration(UUID.randomUUID().toString().take(8), profileId, "reward", d.title, d.icon,
                    "Ты прошла ${fmt(d.km)} км $periodText! Награда: ${d.title}", d.effect, null, now),
            )
            telegram.send(scope, "${d.icon} $name заработала награду «${d.title}»: ${fmt(km)} км $periodText (цель ${fmt(d.km)} км).")
            onChange()
        }
    }

    fun state(profileId: String): GameState {
        val c = ctx(profileId)
        val earned = store.achievements(profileId).associate { it.achievementId to it.atMs }
        val now = System.currentTimeMillis()
        val earnedRewards = store.rewardsEarned(profileId)
        return GameState(
            profileId = profileId,
            achievements = Achievements.all.map {
                AchievementDto(it.id, it.icon, it.title, it.description, it.grade, it.secret, it.progress(c).coerceIn(0.0, 1.0), earned[it.id])
            },
            rewards = store.rewards(profileId).map { d ->
                val key = Periods.key(d.period, now)
                val e = earnedRewards.firstOrNull { it.rewardId == d.id && it.periodKey == key }
                RewardStatus(d, key, periodKm(profileId, d.period, now, null, 0.0), e != null, e?.delivered ?: false, earnedRewards.count { it.rewardId == d.id })
            },
            recentRewards = earnedRewards.sortedByDescending { it.atMs }.take(20),
        )
    }

    private fun fmt(x: Double) = String.format(Locale.ROOT, "%.1f", x).replace('.', ',')
}
