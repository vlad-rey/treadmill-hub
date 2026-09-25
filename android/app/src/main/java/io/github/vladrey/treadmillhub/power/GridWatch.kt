package io.github.vladrey.treadmillhub.power

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Grid power on/off based on station data, debounced: a state change is confirmed once the new
 * state holds for [confirmMs]. Returns the Telegram message text, or null.
 * While there's no power — warnings when the station's battery drops to 20% and 10%.
 */
class GridWatch(
    private val capacityWh: Double = 2048.0,     // Fossibot F2400
    private val confirmMs: Long = 20_000,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    var gridOn: Boolean? = null
        private set
    var changedAtMs: Long? = null
        private set
    private var candidate: Boolean? = null
    private var candidateSinceMs = 0L
    private val lowWarned = mutableSetOf<Int>()

    fun update(s: StationState): String? {
        val g = s.gridOn ?: return null
        if (!s.connected) return null
        val now = s.updatedAtMs

        if (gridOn == null) { gridOn = g; changedAtMs = now; return null } // first known state — no message

        if (g != gridOn) {
            if (candidate != g) { candidate = g; candidateSinceMs = now }
            if (now - candidateSinceMs >= confirmMs) {
                val offFor = changedAtMs?.let { now - it }
                gridOn = g; changedAtMs = candidateSinceMs; candidate = null
                lowWarned.clear()
                return if (g) onText(s, offFor) else offText(s)
            }
        } else {
            candidate = null
        }

        if (gridOn == false) {
            val soc = s.socPct ?: return null
            for (level in listOf(20, 10)) {
                if (soc <= level && level !in lowWarned) {
                    lowWarned += level
                    return "🪫 Батарея станции ${soc.roundToInt()} % — света всё ещё нет (${duration(now - (changedAtMs ?: now))}). " +
                        "Нагрузка ${s.outputW} Вт, хватит примерно на ${hoursLeft(s)}."
                }
            }
        }
        return null
    }

    private fun offText(s: StationState) =
        "⚡ Свет выключили в ${clock(candidateSinceMs)}. Станция перестала заряжаться и работает от батареи: " +
            "${s.socPct?.roundToInt() ?: "?"} %, нагрузка ${s.outputW} Вт — хватит примерно на ${hoursLeft(s)}."

    private fun onText(s: StationState, offForMs: Long?) =
        "💡 Свет включили в ${clock(candidateSinceMs)}" + (offForMs?.let { ", не было ${duration(it)}" } ?: "") + ". " +
            (if (s.acChargeW > 0) "Станция заряжается: ${s.acChargeW} Вт, " else "Станция снова от сети, ") +
            "батарея ${s.socPct?.roundToInt() ?: "?"} %."

    private fun hoursLeft(s: StationState): String {
        val soc = s.socPct ?: return "?"
        if (s.outputW <= 5) return "много часов"
        val h = capacityWh * soc / 100 * 0.9 / s.outputW   // ~90% inverter efficiency
        return if (h >= 1) String.format(Locale.ROOT, "%.1f ч", h).replace('.', ',') else "${(h * 60).roundToInt()} мин"
    }

    private fun clock(ms: Long) = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(ms))

    private fun duration(ms: Long): String {
        val m = (ms / 60_000).toInt()
        return if (m >= 60) "${m / 60} ч ${m % 60} мин" else "$m мин"
    }
}
