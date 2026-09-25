package io.github.vladrey.treadmillhub.session

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Экспорт тренировок: TCX (Strava, Garmin Connect — без координат считается тренировкой на дорожке)
 * и CSV. Накопленная дистанция по сэмплам считается по скорости и масштабируется к итоговой
 * дистанции тренировки (счётчику дорожки), чтобы конец трека совпал с пультом.
 */
object Export {
    private fun iso(ms: Long) = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(ms).truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
    private fun f(x: Double, digits: Int) = String.format(Locale.ROOT, "%.${digits}f", x)

    /** Накопленная дистанция на момент каждого сэмпла, м. */
    fun cumulativeDistance(s: SavedSession): List<Double> {
        val raw = ArrayList<Double>(s.samples.size)
        var acc = 0.0
        s.samples.forEachIndexed { i, x ->
            if (i > 0) {
                val prev = s.samples[i - 1]
                acc += prev.speedKmh / 3.6 * ((x.t - prev.t) / 1000.0).coerceIn(0.0, 5.0)
            }
            raw += acc
        }
        val total = s.stats.distanceM
        val k = if (acc > 0 && total > 0) total / acc else 1.0
        return raw.map { it * k }
    }

    fun tcx(s: SavedSession): String {
        val dist = cumulativeDistance(s)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2" xmlns:ns3="http://www.garmin.com/xmlschemas/ActivityExtension/v2">
  <Activities>
    <Activity Sport="Running">
      <Id>${iso(s.id)}</Id>
      <Lap StartTime="${iso(s.id)}">
        <TotalTimeSeconds>${f(s.stats.movingS, 1)}</TotalTimeSeconds>
        <DistanceMeters>${f(s.stats.distanceM, 1)}</DistanceMeters>
        <Calories>${s.stats.kcalCalc.toInt()}</Calories>
        <Intensity>Active</Intensity>
        <TriggerMethod>Manual</TriggerMethod>
        <Track>
""")
        s.samples.forEachIndexed { i, x ->
            sb.append("          <Trackpoint><Time>").append(iso(s.id + x.t)).append("</Time>")
                .append("<DistanceMeters>").append(f(dist[i], 1)).append("</DistanceMeters>")
            x.heartRate?.let { sb.append("<HeartRateBpm><Value>").append(it).append("</Value></HeartRateBpm>") }
            sb.append("<Extensions><ns3:TPX><ns3:Speed>").append(f(x.speedKmh / 3.6, 3)).append("</ns3:Speed></ns3:TPX></Extensions>")
                .append("</Trackpoint>\n")
        }
        sb.append("""        </Track>
      </Lap>
      <Notes>Беговая дорожка FitLogic T12B · treadmill-hub. Калории — расчёт ACSM с учётом наклона (дорожка: ${s.stats.kcalTreadmill?.let { f(it, 0) } ?: "—"}).</Notes>
    </Activity>
  </Activities>
</TrainingCenterDatabase>
""")
        return sb.toString()
    }

    /** Посекундная запись одной тренировки. */
    fun samplesCsv(s: SavedSession): String {
        val dist = cumulativeDistance(s)
        val w = s.weightKg
        var kcal = 0.0
        val sb = StringBuilder("time_s,speed_kmh,incline_pct,distance_m,kcal_calc,kcal_treadmill,heart_rate\n")
        s.samples.forEachIndexed { i, x ->
            if (i > 0) {
                val prev = s.samples[i - 1]
                val dt = ((x.t - prev.t) / 1000.0).coerceIn(0.0, 5.0)
                if (prev.speedKmh > 0) kcal += Calories.kcalPerMinute(prev.speedKmh, prev.inclinePct, w) * dt / 60
            }
            sb.append(f(x.t / 1000.0, 1)).append(',').append(f(x.speedKmh, 1)).append(',').append(f(x.inclinePct, 0)).append(',')
                .append(f(dist[i], 1)).append(',').append(f(kcal, 2)).append(',')
                .append(x.kcalTreadmill?.let { f(it, 1) } ?: "").append(',').append(x.heartRate ?: "").append('\n')
        }
        return sb.toString()
    }

    /** Все тренировки — одна строка на тренировку. Время — местное. */
    fun summaryCsv(sessions: List<SessionSummary>, zone: ZoneId = ZoneId.systemDefault()): String {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        val sb = StringBuilder("date,duration_min,distance_km,avg_speed_kmh,kcal_calc,kcal_treadmill\n")
        for (s in sessions.sortedBy { it.id }) {
            val avg = if (s.movingS > 0) s.distanceM / s.movingS * 3.6 else 0.0
            sb.append(fmt.format(Instant.ofEpochMilli(s.id))).append(',')
                .append(f(s.movingS / 60, 1)).append(',').append(f(s.distanceM / 1000, 3)).append(',')
                .append(f(avg, 2)).append(',').append(f(s.kcalCalc, 1)).append(',')
                .append(s.kcalTreadmill?.let { f(it, 1) } ?: "").append('\n')
        }
        return sb.toString()
    }

    fun fileStamp(id: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm").withZone(zone).format(Instant.ofEpochMilli(id))
}
