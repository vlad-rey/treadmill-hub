package io.github.vladrey.treadmillhub.gamification

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters

@Serializable
enum class Period { WEEK, MONTH }

/** Реальная награда: за [km] км за неделю/месяц. Выдаётся заново каждый период. */
@Serializable
data class RewardDef(
    val id: String,
    val profileId: String,
    val title: String,
    val icon: String,
    val period: Period,
    val km: Double,
    /** "sound" — окно и звук; "fireworks" — ещё и фейерверк. */
    val effect: String = "sound",
)

@Serializable
data class RewardEarned(
    val rewardId: String,
    val profileId: String,
    /** «2026-W39» или «2026-09». */
    val periodKey: String,
    val atMs: Long,
    val delivered: Boolean = false,
)

@Serializable
data class AchievementEarned(val profileId: String, val achievementId: String, val atMs: Long)

/** Всплывающее окно на телефоне профиля: реальная награда или ачивка. Живёт до подтверждения. */
@Serializable
data class Celebration(
    val id: String,
    val profileId: String,
    val kind: String,          // "reward" | "achievement"
    val title: String,
    val icon: String,
    val text: String,
    val effect: String,        // "sound" | "fireworks" | "chime"
    val grade: Grade? = null,
    val atMs: Long,
)

object Periods {
    fun key(p: Period, ms: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = Instant.ofEpochMilli(ms).atZone(zone)
        return when (p) {
            Period.WEEK -> "%d-W%02d".format(d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
            Period.MONTH -> "%d-%02d".format(d.year, d.monthValue)
        }
    }

    fun start(p: Period, ms: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val date = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val first = when (p) {
            Period.WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            Period.MONTH -> date.withDayOfMonth(1)
        }
        return first.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

/** Хранилище: определения наград, выданные награды и ачивки, разовые события («Передумал»). */
class GameStore(private val dir: File) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Serializable
    private data class State(
        val rewards: List<RewardDef> = emptyList(),
        val rewardsEarned: List<RewardEarned> = emptyList(),
        val achievements: List<AchievementEarned> = emptyList(),
        val events: Map<String, List<String>> = emptyMap(),   // profileId → события
        val pending: List<Celebration> = emptyList(),
    )

    private val file = File(dir, "game.json")
    private var state: State = runCatching { json.decodeFromString<State>(file.readText()) }.getOrDefault(State())

    @Synchronized fun rewards(profileId: String) = state.rewards.filter { it.profileId == profileId }
    @Synchronized fun rewardsEarned(profileId: String) = state.rewardsEarned.filter { it.profileId == profileId }
    @Synchronized fun achievements(profileId: String) = state.achievements.filter { it.profileId == profileId }
    @Synchronized fun events(profileId: String) = state.events[profileId].orEmpty().toSet()
    @Synchronized fun pending(): List<Celebration> = state.pending

    @Synchronized
    fun setRewards(profileId: String, defs: List<RewardDef>) {
        state = state.copy(rewards = state.rewards.filterNot { it.profileId == profileId } + defs); persist()
    }

    @Synchronized
    fun hasReward(rewardId: String, periodKey: String) = state.rewardsEarned.any { it.rewardId == rewardId && it.periodKey == periodKey }

    @Synchronized
    fun addReward(e: RewardEarned, c: Celebration) {
        state = state.copy(rewardsEarned = state.rewardsEarned + e, pending = state.pending + c); persist()
    }

    @Synchronized
    fun setDelivered(rewardId: String, periodKey: String, delivered: Boolean): Boolean {
        if (!hasReward(rewardId, periodKey)) return false
        state = state.copy(rewardsEarned = state.rewardsEarned.map { if (it.rewardId == rewardId && it.periodKey == periodKey) it.copy(delivered = delivered) else it })
        persist(); return true
    }

    @Synchronized
    fun addAchievements(profileId: String, ids: List<String>, celebrations: List<Celebration>) {
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        state = state.copy(achievements = state.achievements + ids.map { AchievementEarned(profileId, it, now) }, pending = state.pending + celebrations)
        persist()
    }

    @Synchronized
    fun addEvent(profileId: String, event: String): Boolean {
        val cur = state.events[profileId].orEmpty()
        if (event in cur) return false
        state = state.copy(events = state.events + (profileId to cur + event)); persist(); return true
    }

    @Synchronized
    fun ack(celebrationId: String) {
        state = state.copy(pending = state.pending.filterNot { it.id == celebrationId }); persist()
    }

    private fun persist() {
        val tmp = File(dir, "game.json.tmp")
        tmp.writeText(json.encodeToString(state))
        tmp.renameTo(file)
    }
}
