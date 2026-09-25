package io.github.vladrey.treadmillhub

import io.github.vladrey.treadmillhub.session.Export
import io.github.vladrey.treadmillhub.session.Sample
import io.github.vladrey.treadmillhub.session.SavedSession
import io.github.vladrey.treadmillhub.session.SessionStats
import io.github.vladrey.treadmillhub.session.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ExportTest {
    // 60 с на 6 км/ч = 100 м по скорости; счётчик дорожки насчитал 95 м
    private val session = SavedSession(
        id = 1_790_361_769_000,
        weightKg = 90.0,
        stats = SessionStats(movingS = 60.0, distanceM = 95.0, distanceCalcM = 100.0, kcalCalc = 6.0, kcalTreadmill = 5.2),
        samples = (0..60).map { Sample(t = it * 1000L, speedKmh = 6.0, inclinePct = 2.0, kcalTreadmill = it * 0.1, heartRate = if (it == 30) 120 else null) },
    )

    @Test fun trackDistanceEndsAtTreadmillTotal() {
        val d = Export.cumulativeDistance(session)
        assertEquals(0.0, d.first(), 1e-9)
        assertEquals(95.0, d.last(), 1e-9)
    }

    @Test fun tcxHasLapTotalsTrackpointsAndSpeed() {
        val x = Export.tcx(session)
        assertTrue(x.contains("<Activity Sport=\"Running\">"))
        assertTrue(x.contains("<DistanceMeters>95.0</DistanceMeters>"))
        assertTrue(x.contains("<TotalTimeSeconds>60.0</TotalTimeSeconds>"))
        assertEquals(61, Regex("<Trackpoint>").findAll(x).count())
        assertTrue(x.contains("<ns3:Speed>1.667</ns3:Speed>"))
        assertTrue(x.contains("<HeartRateBpm><Value>120</Value></HeartRateBpm>"))
        assertTrue(x.contains("<Time>2026-09-25T18:42:49Z</Time>"))
    }

    @Test fun csvExports() {
        val lines = Export.samplesCsv(session).trim().lines()
        assertEquals("time_s,speed_kmh,incline_pct,distance_m,kcal_calc,kcal_treadmill,heart_rate", lines[0])
        assertEquals(62, lines.size)
        val summary = Export.summaryCsv(listOf(SessionSummary(session.id, 60.0, 95.0, 6.0, 5.2, null, "x")), ZoneId.of("Europe/Kyiv"))
        assertEquals("2026-09-25 21:42,1.0,0.095,5.70,6.0,5.2", summary.trim().lines()[1])
    }
}
