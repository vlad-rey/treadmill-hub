package io.github.vladrey.treadmillhub.gamification

import io.github.vladrey.treadmillhub.session.SessionSummary
import io.github.vladrey.treadmillhub.session.WeightEntry
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import kotlin.math.abs

@Serializable
enum class Grade { BRONZE, SILVER, GOLD, PLATINUM, LEGEND }

/** Data for checking a single profile's achievements. */
class AchievementContext(
    val sessions: List<SessionSummary>,
    val weights: List<WeightEntry>,
    val events: Set<String>,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    val metrics get() = sessions.mapNotNull { it.metrics }
    val totalKm get() = sessions.sumOf { it.distanceM } / 1000
    val totalKcal get() = sessions.sumOf { it.kcalCalc }
    fun day(s: SessionSummary): LocalDate = Instant.ofEpochMilli(s.id).atZone(zone).toLocalDate()

    /** Longest streak of consecutive days with a workout. */
    val longestStreak: Int
        get() {
            val days = sessions.filter { it.distanceM >= 100 }.map(::day).toSortedSet()
            var best = 0; var cur = 0; var prev: LocalDate? = null
            for (d in days) { cur = if (prev != null && prev.plusDays(1) == d) cur + 1 else 1; best = maxOf(best, cur); prev = d }
            return best
        }
}

/**
 * Achievement. [progress] — from 0 to 1 for the "how much is left" bar (null — pass/fail achievement).
 * [secret] — shown as "???" until it is earned.
 */
class Achievement(
    val id: String,
    val icon: String,
    val title: String,
    val description: String,
    val grade: Grade,
    val secret: Boolean = false,
    val progress: (AchievementContext) -> Double,
)

@Serializable
data class AchievementDto(
    val id: String, val icon: String, val title: String, val description: String,
    val grade: Grade, val secret: Boolean, val progress: Double, val earnedAtMs: Long?,
)

object Achievements {
    private fun frac(value: Double, target: Double) = (value / target).coerceIn(0.0, 1.0)
    private fun any(ctx: AchievementContext, p: (SessionMetrics) -> Boolean) = if (ctx.metrics.any(p)) 1.0 else 0.0
    private fun bestSession(ctx: AchievementContext, f: (SessionSummary) -> Double) = ctx.sessions.maxOfOrNull(f) ?: 0.0
    private fun bestMetric(ctx: AchievementContext, f: (SessionMetrics) -> Double) = ctx.metrics.maxOfOrNull(f) ?: 0.0

    val all: List<Achievement> = listOf(
        Achievement("first_step", "👟", "Первый шаг", "Первая тренировка", Grade.BRONZE) { if (it.sessions.isNotEmpty()) 1.0 else 0.0 },
        Achievement("km_1", "🚶", "Километр", "1 км за одну тренировку", Grade.BRONZE) { frac(bestSession(it) { s -> s.distanceM }, 1000.0) },
        Achievement("km_5", "🏃", "Пятёрочка", "5 км за одну тренировку", Grade.SILVER) { frac(bestSession(it) { s -> s.distanceM }, 5000.0) },
        Achievement("km_10", "🔥", "Десятка", "10 км за одну тренировку", Grade.GOLD) { frac(bestSession(it) { s -> s.distanceM }, 10000.0) },
        Achievement("hour", "🕐", "Час силы", "60 минут в движении за одну тренировку", Grade.SILVER) { frac(bestSession(it) { s -> s.movingS }, 3600.0) },
        Achievement("total_42", "🏅", "Марафонец", "42,2 км за всё время", Grade.GOLD) { frac(it.totalKm, 42.195) },
        Achievement("total_100", "💯", "Сотня", "100 км за всё время", Grade.PLATINUM) { frac(it.totalKm, 100.0) },
        Achievement("total_500", "🌍", "Пешком до моря", "500 км за всё время", Grade.LEGEND) { frac(it.totalKm, 500.0) },
        Achievement("climb_100", "⛰️", "Холмик", "Набрать 100 м высоты за тренировку", Grade.BRONZE) { frac(bestMetric(it) { m -> m.climbM }, 100.0) },
        Achievement("climb_everest", "🏔️", "Эверест", "8 848 м высоты за всё время", Grade.LEGEND) { frac(it.metrics.sumOf { m -> m.climbM }, 8848.0) },
        Achievement("wall", "🧗", "Отвесная стена", "5 минут подряд на наклоне 15 %", Grade.SILVER) { frac(bestMetric(it) { m -> m.maxRunAt15PctS }, 300.0) },
        Achievement("speed_12", "⚡", "Спринтер", "Разогнаться до 12 км/ч", Grade.SILVER) { frac(bestMetric(it) { m -> m.maxSpeedKmh }, 12.0) },
        Achievement("speed_16", "🚀", "Ракета", "Выжать из дорожки все 16 км/ч", Grade.GOLD) { frac(bestMetric(it) { m -> m.maxSpeedKmh }, 16.0) },
        Achievement("gearbox", "🎛️", "Коробка передач", "Пройти на всех скоростях от 1 до 10 км/ч в одной тренировке", Grade.GOLD) {
            (it.metrics.maxOfOrNull { m -> (1..10).count { v -> v in m.speedsUsed } } ?: 0) / 10.0
        },
        Achievement("kcal_500", "🍕", "Минус пицца", "500 ккал за одну тренировку", Grade.GOLD) { frac(bestSession(it) { s -> s.kcalCalc }, 500.0) },
        Achievement("kcal_10000", "🔥", "Печка", "10 000 ккал за всё время", Grade.PLATINUM) { frac(it.totalKcal, 10000.0) },
        Achievement("streak_3", "📅", "Три дня подряд", "Тренировки три дня подряд", Grade.SILVER) { frac(it.longestStreak.toDouble(), 3.0) },
        Achievement("streak_7", "🗓️", "Без пропусков", "Тренировки семь дней подряд", Grade.GOLD) { frac(it.longestStreak.toDouble(), 7.0) },
        Achievement("streak_30", "👑", "Железная воля", "Тренировки 30 дней подряд", Grade.LEGEND) { frac(it.longestStreak.toDouble(), 30.0) },
        Achievement("weekend", "☀️", "Воин выходного дня", "Тренировки и в субботу, и в воскресенье одних выходных", Grade.SILVER) { ctx ->
            val days = ctx.sessions.map(ctx::day).toSet()
            if (days.any { d -> d.dayOfWeek.value == 6 && d.plusDays(1) in days }) 1.0 else 0.0
        },
        Achievement("early", "🌅", "Ранняя пташка", "Начать тренировку до 7 утра", Grade.BRONZE) { any(it) { m -> m.startHour < 7 } },
        Achievement("owl", "🦉", "Сова", "Тренироваться после 23:00", Grade.BRONZE) { any(it) { m -> m.endHour >= 23 || m.startHour >= 23 } },
        Achievement("program_done", "📋", "По плану", "Пройти программу до конца", Grade.BRONZE) { any(it) { m -> m.programsDone.isNotEmpty() } },
        Achievement("programs_all", "🎓", "Отличник", "Пройти до конца все 8 встроенных программ", Grade.PLATINUM) { ctx ->
            ctx.metrics.flatMap { m -> m.programsDone }.filter { id -> id.matches(Regex("P[1-8]")) }.toSet().size / 8.0
        },
        Achievement("own_program", "🛠️", "Сам себе тренер", "Пройти до конца свою программу", Grade.SILVER) { any(it) { m -> m.programsDone.any { id -> !id.matches(Regex("P[1-8]")) } } },
        Achievement("weigh_in", "⚖️", "Весы не врут", "Записывать вес 4 недели подряд", Grade.BRONZE) { ctx ->
            val weeks = ctx.weights.map { w -> Instant.ofEpochMilli(w.atMs).atZone(ctx.zone).let { d -> d.get(IsoFields.WEEK_BASED_YEAR) * 100 + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) } }.toSortedSet()
            var best = 0; var cur = 0; var prev = -1
            for (w in weeks) { cur = if (prev >= 0 && (w == prev + 1 || (w % 100 == 1 && prev % 100 >= 52))) cur + 1 else 1; best = maxOf(best, cur); prev = w }
            frac(best.toDouble(), 4.0)
        },
        // --- Secret and fun ---
        Achievement("turtle", "🐢", "Черепаха", "10 минут подряд на 1 км/ч", Grade.SILVER, secret = true) { frac(bestMetric(it) { m -> m.maxRunAtWalkingMinS }, 600.0) },
        Achievement("cinderella", "🕛", "Золушка", "Тренировка, которая перешла через полночь", Grade.GOLD, secret = true) { any(it) { m -> m.crossedMidnight } },
        Achievement("coffee", "☕", "Кофе-брейк", "Пауза дольше 5 минут — и продолжить", Grade.BRONZE, secret = true) { any(it) { m -> m.maxResumedPauseS >= 300 } },
        Achievement("perfectionist", "🎯", "Перфекционист", "Закончить ровно на целом километре (±10 м)", Grade.GOLD, secret = true) { ctx ->
            if (ctx.sessions.any { s -> s.distanceM >= 990 && abs(s.distanceM - Math.round(s.distanceM / 1000.0) * 1000.0) <= 10 }) 1.0 else 0.0
        },
        Achievement("changed_mind", "🙃", "Передумал", "Нажать СТОП во время отсчёта 3-2-1", Grade.BRONZE, secret = true) { if ("changed_mind" in it.events) 1.0 else 0.0 },
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}
