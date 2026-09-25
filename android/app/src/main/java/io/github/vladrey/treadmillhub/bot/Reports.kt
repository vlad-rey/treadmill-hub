package io.github.vladrey.treadmillhub.bot

import io.github.vladrey.treadmillhub.gamification.Period
import io.github.vladrey.treadmillhub.gamification.RewardStatus
import io.github.vladrey.treadmillhub.net.NetKind
import io.github.vladrey.treadmillhub.net.NetOutage
import io.github.vladrey.treadmillhub.net.SpeedResult
import io.github.vladrey.treadmillhub.power.Outage
import io.github.vladrey.treadmillhub.power.StationTotals
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

data class StationWeek(val name: String, val totals: StationTotals)

data class ProfileWeek(
    val name: String,
    val km: Double,
    val movingS: Double,
    val sessions: Int,
    val kcal: Double,
    val prevKm: Double,
    /** "🥇 Name" of new achievements for the week. */
    val achievements: List<String> = emptyList(),
    /** Weekly rewards: (icon+title, earned?, km to goal). */
    val rewards: List<Triple<String, Boolean, Double>> = emptyList(),
)

data class StationNow(val name: String, val connected: Boolean, val gridOn: Boolean?, val socPct: Double?, val outputW: Int, val chargeW: Int)

/** Texts for Telegram: weekly reports, reward reminders, /status. No Android dependency — covered by tests. */
class Reports(private val zone: ZoneId = ZoneId.systemDefault()) {
    private val ru = Locale("ru")

    fun weekTitle(from: LocalDate, to: LocalDate): String {
        val month = { d: LocalDate -> DateTimeFormatter.ofPattern("d MMMM", ru).format(d) }
        return if (from.month == to.month) "${from.dayOfMonth}–${month(to)}" else "${month(from)} – ${month(to)}"
    }

    /** Report to the owner: power, stations, internet, speed, workouts of all profiles. */
    fun homeWeek(
        from: LocalDate, to: LocalDate,
        stations: List<StationWeek>, outages: List<Outage>, net: List<NetOutage>, speed: List<SpeedResult>, profiles: List<ProfileWeek>,
    ): String {
        val sb = StringBuilder("🏠 Итоги недели ${weekTitle(from, to)}\n")

        val spans = mergeOutages(outages)
        sb.append("\n")
        if (spans.isEmpty()) sb.append("💡 Свет не отключали.\n")
        else {
            val total = spans.sumOf { it.second - it.first }
            val longest = spans.maxBy { it.second - it.first }
            sb.append("⚡ Свет отключали ${times(spans.size)}, всего ${dur(total)} (дольше всего — ${dur(longest.second - longest.first)}, ${dayTime(longest.first)}).\n")
        }

        if (stations.isNotEmpty()) {
            sb.append("\n🔋 Станции:\n")
            for (s in stations) {
                val t = s.totals
                sb.append("• ${s.name}: циклы ${num(t.chargedPct / 100, 2)} · заряжено ${num(t.chargedPct, 0)} % (${kwh(t.chargedWh)} из сети)")
                if (t.offgridOutputWh >= 1) sb.append(" · из батареи ${kwh(t.offgridOutputWh)}")
                sb.append(" · всего отдано ${kwh(t.outputWh)}\n")
            }
        }

        sb.append("\n")
        val closed = net.filter { it.endMs != null }
        if (closed.isEmpty()) sb.append("🌐 Интернет: сбоев не было.\n")
        else {
            val router = closed.count { it.kind == NetKind.ROUTER }
            sb.append("🌐 Интернет: ${closed.size} ${plural(closed.size, "сбой", "сбоя", "сбоев")}, всего ${dur(closed.sumOf { it.endMs!! - it.startMs })}")
            sb.append(if (router > 0) " (роутер недоступен — $router)\n" else " (провайдер, роутер работал)\n")
        }
        val ok = speed.filter { it.error == null && it.downMbps != null }
        if (ok.isNotEmpty()) {
            val down = ok.map { it.downMbps!! }
            val up = ok.mapNotNull { it.upMbps }
            sb.append("🚀 Скорость: в среднем ${num(down.average(), 0)} ↓ / ${if (up.isEmpty()) "—" else num(up.average(), 0)} ↑ Мбит/с, минимум ${num(down.min(), 0)} ↓ (${ok.size} ${plural(ok.size, "замер", "замера", "замеров")})\n")
        }

        if (profiles.isNotEmpty()) {
            sb.append("\n🏃 Дорожка:\n")
            for (p in profiles) {
                sb.append("• ${p.name}: ")
                sb.append(if (p.sessions == 0) "тренировок не было" else "${num(p.km, 1)} км за ${p.sessions} ${plural(p.sessions, "тренировку", "тренировки", "тренировок")}${diff(p)}")
                p.rewards.forEach { (title, earned, left) -> sb.append(" · $title ${if (earned) "✅" else "— не хватило ${num(left, 1)} км"}") }
                sb.append("\n")
            }
        }
        return sb.toString().trimEnd()
    }

    /** Personal treadmill summary for the week. */
    fun treadmillWeek(from: LocalDate, to: LocalDate, p: ProfileWeek): String {
        val sb = StringBuilder("🏃 ${p.name}, итоги недели ${weekTitle(from, to)}\n\n")
        if (p.sessions == 0) {
            sb.append("Тренировок не было. Новая неделя — новый шанс 🙂")
            if (p.prevKm > 0) sb.append("\nНеделей раньше было ${num(p.prevKm, 1)} км.")
        } else {
            sb.append("${num(p.km, 1)} км · ${p.sessions} ${plural(p.sessions, "тренировка", "тренировки", "тренировок")} · ${hm(p.movingS)} · ${p.kcal.roundToInt()} ккал\n")
            val d = p.km - p.prevKm
            sb.append(when {
                p.prevKm == 0.0 -> "Неделей раньше тренировок не было — отличный старт 💪"
                abs(d) < 0.05 -> "Столько же, сколько неделей раньше."
                d > 0 -> "На ${num(d, 1)} км больше, чем неделей раньше 💪"
                else -> "На ${num(-d, 1)} км меньше, чем неделей раньше."
            })
        }
        if (p.achievements.isNotEmpty()) sb.append("\n\n🏆 Новые ачивки: ${p.achievements.joinToString(", ")}")
        if (p.rewards.isNotEmpty()) {
            sb.append("\n\n🎁 Награды недели:")
            p.rewards.forEach { (title, earned, left) -> sb.append("\n$title — ${if (earned) "заработана ✅" else "не хватило ${num(left, 1)} км"}") }
        }
        return sb.toString()
    }

    /** Reminder about unearned rewards: weekly — on Thu and Sat, monthly — 7, 3, and 1 day before month end. */
    fun rewardReminder(name: String, rewards: List<RewardStatus>, today: LocalDate, force: Boolean = false): String? {
        val weekLeft = ChronoUnit.DAYS.between(today, today.with(java.time.temporal.TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))).toInt()
        val monthLeft = today.lengthOfMonth() - today.dayOfMonth
        val lines = rewards.filter { !it.earned && it.def.km > it.currentKm }.mapNotNull { r ->
            val left = if (r.def.period == Period.WEEK) weekLeft else monthLeft
            val due = force || if (r.def.period == Period.WEEK) today.dayOfWeek in setOf(DayOfWeek.THURSDAY, DayOfWeek.SATURDAY) else monthLeft in setOf(7, 3, 1)
            if (!due) return@mapNotNull null
            val period = if (r.def.period == Period.WEEK) "недели" else "месяца"
            val days = if (left == 0) "это последний день $period" else "до конца $period ${left} ${plural(left, "день", "дня", "дней")}"
            "${r.def.icon} «${r.def.title}»: осталось ${num(r.def.km - r.currentKm, 1)} км (пройдено ${num(r.currentKm, 1)} из ${num(r.def.km, 0)}), $days"
        }
        if (lines.isEmpty()) return null
        return "⏳ $name, до наград совсем немного:\n\n${lines.joinToString("\n")}\n\nВперёд! 🏃"
    }

    fun status(
        nowMs: Long, stations: List<StationNow>, openOutageStartMs: Long?,
        routerOk: Boolean?, internetOk: Boolean?, routerMs: Int?,
        treadmill: String, hubBatteryPct: Int?, hubUptimeS: Long,
    ): String {
        val sb = StringBuilder("🏠 Сейчас ${clock(nowMs)}\n\n")
        val off = stations.any { it.gridOn == false }
        sb.append(when {
            stations.isEmpty() -> "Станций нет — о свете не знаю.\n"
            off -> "⚡ Света нет" + (openOutageStartMs?.let { " с ${clock(it)} (${dur(nowMs - it)})" } ?: "") + ".\n"
            else -> "💡 Свет есть.\n"
        })
        for (s in stations) {
            sb.append("🔋 ${s.name}: ")
            if (!s.connected) { sb.append("нет связи\n"); continue }
            sb.append(s.socPct?.let { "${it.roundToInt()} %" } ?: "?")
            sb.append(when {
                s.gridOn == false -> ", от батареи ${s.outputW} Вт" + runtime(s)
                s.chargeW > 0 -> ", заряжается ${s.chargeW} Вт"
                else -> ", выход ${s.outputW} Вт"
            })
            sb.append("\n")
        }
        sb.append(when {
            routerOk == null -> "🌐 Сеть: ещё не проверял\n"
            routerOk == false -> "🌐 Роутер не отвечает\n"
            internetOk == false -> "🌐 Интернета нет (роутер работает)\n"
            else -> "🌐 Интернет есть, роутер ${routerMs ?: "?"} мс\n"
        })
        sb.append("🏃 Дорожка: $treadmill\n")
        sb.append("🖥️ Хаб: батарея ${hubBatteryPct ?: "?"} %, работает ${dur(hubUptimeS * 1000)}")
        return sb.toString()
    }

    // --- formatting ---
    private fun runtime(s: StationNow): String {
        val soc = s.socPct ?: return ""
        if (s.outputW <= 5) return ""
        val h = 2048 * soc / 100 * 0.9 / s.outputW
        return if (h >= 1) " — хватит примерно на ${num(h, 1)} ч" else " — хватит примерно на ${(h * 60).roundToInt()} мин"
    }

    /** Outages of different stations that started within 3 min of each other — treated as one outage: (start, end). */
    fun mergeOutages(list: List<Outage>): List<Pair<Long, Long>> {
        val res = ArrayList<Pair<Long, Long>>()
        for (o in list.filter { it.endMs != null }.sortedBy { it.startMs }) {
            val last = res.lastOrNull()
            if (last != null && o.startMs - last.first <= 180_000) res[res.size - 1] = last.first to maxOf(last.second, o.endMs!!)
            else res += o.startMs to o.endMs!!
        }
        return res
    }

    private fun diff(p: ProfileWeek): String {
        val d = p.km - p.prevKm
        return if (p.prevKm == 0.0 || abs(d) < 0.05) "" else " (${if (d > 0) "+" else "−"}${num(abs(d), 1)} км к прошлой неделе)"
    }

    fun num(v: Double, d: Int): String = String.format(Locale.ROOT, "%.${d}f", v).replace('.', ',')
    private fun kwh(wh: Double) = if (wh >= 1000) "${num(wh / 1000, 2)} кВт·ч" else "${wh.roundToInt()} Вт·ч"
    private fun hm(s: Double): String { val m = (s / 60).roundToInt(); return if (m >= 60) "${m / 60} ч ${m % 60} мин" else "$m мин" }
    private fun clock(ms: Long) = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(ms))
    private fun dayTime(ms: Long): String {
        val z = Instant.ofEpochMilli(ms).atZone(zone)
        return z.dayOfWeek.getDisplayName(TextStyle.SHORT, ru) + " " + DateTimeFormatter.ofPattern("HH:mm").format(z)
    }
    fun dur(ms: Long): String {
        val m = (ms / 60_000).toInt()
        return when {
            m < 1 -> "меньше минуты"
            m < 60 -> "$m мин"
            m < 1440 -> "${m / 60} ч ${m % 60} мин"
            else -> "${m / 1440} д ${m % 1440 / 60} ч"
        }
    }
    private fun times(n: Int) = "$n ${plural(n, "раз", "раза", "раз")}"
    fun plural(n: Int, one: String, few: String, many: String): String {
        val a = abs(n) % 100; val b = a % 10
        return when { a in 11..14 -> many; b == 1 -> one; b in 2..4 -> few; else -> many }
    }
}
