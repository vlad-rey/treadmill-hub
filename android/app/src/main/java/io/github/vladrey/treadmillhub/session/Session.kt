package io.github.vladrey.treadmillhub.session

import io.github.vladrey.treadmillhub.treadmill.Connection
import io.github.vladrey.treadmillhub.treadmill.Phase
import io.github.vladrey.treadmillhub.treadmill.TreadmillState
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Расход энергии по метаболическим уравнениям ACSM — см. docs/decisions/0004-calories.md. */
object Calories {
    const val REST_VO2 = 3.5

    /** VO₂, мл/кг/мин. Ходьба до [walkMaxKmh], бег от [runMinKmh], между — интерполяция. */
    fun vo2(speedKmh: Double, inclinePct: Double, walkMaxKmh: Double = 6.5, runMinKmh: Double = 8.0): Double {
        if (speedKmh <= 0) return REST_VO2
        val s = speedKmh * 1000 / 60 // м/мин
        val g = inclinePct / 100
        val walk = REST_VO2 + 0.1 * s + 1.8 * s * g
        val run = REST_VO2 + 0.2 * s + 0.9 * s * g
        return when {
            speedKmh <= walkMaxKmh -> walk
            speedKmh >= runMinKmh -> run
            else -> walk + (run - walk) * (speedKmh - walkMaxKmh) / (runMinKmh - walkMaxKmh)
        }
    }

    /** Полный расход, ккал/мин (≈ 5 ккал на литр O₂). */
    fun kcalPerMinute(speedKmh: Double, inclinePct: Double, weightKg: Double) =
        vo2(speedKmh, inclinePct) * weightKg / 1000 * 5

    /** Активный расход (без покоя), ккал/мин. */
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
    /** Дистанция, посчитанная хабом по скорости (FTMS на T12B отдаёт 0). */
    val distanceM: Double = 0.0,
    val kcalCalc: Double = 0.0,
    val kcalActiveCalc: Double = 0.0,
    val kcalTreadmill: Double? = null,
    /** Время и дистанция по парам «скорость 0,1 км/ч × наклон 1 %». */
    val buckets: List<Bucket> = emptyList(),
)

/**
 * Считает тренировку по сэмплам телеметрии (~1 Гц). Интегрирует по предыдущему сэмплу,
 * поэтому смена скорости/наклона учитывается с точностью до секунды.
 */
class SessionTracker(private val weightKg: () -> Double) {
    private var stats = SessionStats()
    private var lastMs = 0L
    private var lastSpeed = 0.0
    private var lastIncline = 0.0
    private var idleSinceMs: Long? = null
    private val buckets = LinkedHashMap<Pair<Int, Int>, DoubleArray>() // (скорость×10, наклон) → [сек, м]

    val current: SessionStats get() = stats

    fun onState(s: TreadmillState, nowMs: Long): SessionStats {
        val moving = s.connection == Connection.CONNECTED && s.speedKmh > 0

        if (!stats.active && moving) {
            buckets.clear()
            stats = SessionStats(active = true, startedAtMs = nowMs)
            lastMs = nowMs
        }

        if (stats.active) {
            val dt = ((nowMs - lastMs) / 1000.0).coerceIn(0.0, 5.0) // разрыв связи не накручивает время
            if (dt > 0 && lastSpeed > 0) {
                val meters = lastSpeed / 3.6 * dt
                val w = weightKg()
                val key = (lastSpeed * 10).roundToInt() to lastIncline.roundToInt()
                val acc = buckets.getOrPut(key) { DoubleArray(2) }
                acc[0] += dt
                acc[1] += meters
                stats = stats.copy(
                    movingS = stats.movingS + dt,
                    distanceM = stats.distanceM + meters,
                    kcalCalc = stats.kcalCalc + Calories.kcalPerMinute(lastSpeed, lastIncline, w) * dt / 60,
                    kcalActiveCalc = stats.kcalActiveCalc + Calories.activeKcalPerMinute(lastSpeed, lastIncline, w) * dt / 60,
                )
            }
            stats = stats.copy(
                kcalTreadmill = s.kcal ?: stats.kcalTreadmill,
                buckets = buckets.map { (k, v) -> Bucket(k.first / 10.0, k.second.toDouble(), v[0], v[1]) },
            )

            // Тренировка закончена: лента стоит и дорожка не на паузе/отсчёте дольше минуты
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
}
