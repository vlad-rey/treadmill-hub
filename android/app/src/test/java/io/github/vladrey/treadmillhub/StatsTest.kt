package io.github.vladrey.treadmillhub

import io.github.vladrey.treadmillhub.session.SessionSummary
import io.github.vladrey.treadmillhub.session.StatsCalculator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class StatsTest {
    private val zone = ZoneId.of("Europe/Kyiv")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun s(id: Long, km: Double, profile: String?) = SessionSummary(id, 600.0, km * 1000, 50.0, 40.0, null, profile)

    @Test fun periodsAreCalendarBasedAndPerProfile() {
        val now = ZonedDateTime.of(2026, 9, 25, 20, 0, 0, 0, zone) // Friday
        val list = listOf(
            s(at(2026, 9, 25), 2.0, "vlad"),      // today
            s(at(2026, 9, 21, 0), 3.0, "vlad"),   // Monday of this week, midnight
            s(at(2026, 9, 20, 23), 4.0, "vlad"),  // Sunday of last week, this month
            s(at(2026, 8, 31), 5.0, "vlad"),   // last month
            s(at(2026, 9, 25), 7.0, "anna"),   // a different profile
            s(at(2026, 9, 25), 1.0, null),     // started from the console, no owner
        )
        val st = StatsCalculator.compute("vlad", list, now)
        assertEquals(2000.0, st.today.distanceM, 1e-9)
        assertEquals(5000.0, st.week.distanceM, 1e-9)
        assertEquals(9000.0, st.month.distanceM, 1e-9)
        assertEquals(14000.0, st.all.distanceM, 1e-9)
        assertEquals(4, st.all.sessions)
        assertEquals(1000.0, StatsCalculator.compute(null, list, now).today.distanceM, 1e-9)
    }
}
