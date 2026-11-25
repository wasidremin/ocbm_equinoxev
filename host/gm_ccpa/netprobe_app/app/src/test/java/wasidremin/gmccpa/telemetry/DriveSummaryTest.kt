package wasidremin.gmccpa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [DriveSummary] on made-up drives. Coordinates are an invented grid near 0°, 0°. */
class DriveSummaryTest {
    private val day = TripStats.DAY_MS
    private val home = 0.00 to 0.00
    private val work = 0.20 to 0.00

    private fun drive(t1: Long, km: Double, whPerKm: Double, minutes: Int = 20, open: Boolean = false): Trip =
        Trip(Trip.Kind.DRIVE, t1 - minutes * 60_000L, t1, 0, 60_000.0, 60_000.0 - km * whPerKm,
            lat0 = home.first, lon0 = home.second, lat1 = work.first, lon1 = work.second,
            distM = km * 1000, disWh = km * whPerKm + 500.0, regWh = 500.0, tempC = 5.0, movingS = minutes * 60.0,
            open = open)

    @Test fun itShowsOnlyOnAMoveIntoParkFromAKnownGear() {
        assertTrue(DriveSummary.parkedAfterDriving(wasParked = false, parked = true))
        // The app started with the car already in Park: nothing was just driven under it.
        assertFalse(DriveSummary.parkedAfterDriving(wasParked = null, parked = true))
        assertFalse(DriveSummary.parkedAfterDriving(wasParked = true, parked = true))
        assertFalse(DriveSummary.parkedAfterDriving(wasParked = true, parked = false))
    }

    @Test fun aDriveUnderAMileOrOneAlreadySummarisedIsNotShown() {
        val t = 40 * day
        assertFalse(DriveSummary.worthShowing(null, null))
        assertFalse(DriveSummary.worthShowing(drive(t, 1.5, 180.0, open = true), null))
        val d = drive(t, 2.0, 180.0, open = true)
        assertTrue(DriveSummary.worthShowing(d, null))
        // Parked at a light in Park and moved off again inside the same drive: one summary.
        assertFalse(DriveSummary.worthShowing(d, d.t0))
    }

    @Test fun theOpenDriveIsComparedWithItsRouteAndCountedOnceInTheMonth() {
        val now = 40 * day
        val runs = (1..9).map { drive(now - it * day, 20.0, 175.0 + it) }
        val open = drive(now, 20.0, 214.0, open = true)
        // The recorder may close the drive while the log is read: the stored copy must not count twice.
        val stored = runs + open.copy(open = false)
        val s = DriveSummary.of(open, stored, capWh = 86_380.0, monthStartMs = now - 5 * day)
        assertEquals(TripStats.Basis.ROUTE, s.usual!!.basis)
        assertEquals(TripStats.Standing.WORSE, s.verdict!!.standing)
        assertEquals(10, s.recent.size)
        assertEquals(214.0, s.recent.last(), 1e-6)
        // The runs one to five days back fall inside the month, and this drive once.
        assertEquals(6, s.month.drives)
        assertEquals(69, s.pct0)
        assertEquals(65, s.pct1)
    }

    @Test fun withNoHistoryThereIsNoComparisonButTheDriveStillShows() {
        val now = 40 * day
        val s = DriveSummary.of(drive(now, 12.0, 190.0, open = true), emptyList(), capWh = null, monthStartMs = now - day)
        assertNull(s.usual)
        assertNull(DriveSummary.versus(s, metric = false))
        assertNull(s.pct0)
        assertEquals(listOf(190.0), s.recent)
        assertEquals("12.0 km  ·  2.3 kWh  ·  20 min  ·  5°C", DriveSummary.facts(s, metric = true))
    }

    @Test fun thePartsAddUpToTheDriveAndSmallOnesAreLeftOut() {
        val s = DriveSummary.sample(40 * day)
        val parts = DriveSummary.parts(s)
        assertEquals(s.split.netWh, parts.sumOf { it.wh }, 1.0)
        assertEquals(listOf("Driving", "Warm-up", "Stopped"), parts.map { it.label })
        val quiet = DriveSummary.of(drive(40 * day, 12.0, 190.0, open = true), emptyList(), null, 0L)
        assertEquals(listOf("Driving"), DriveSummary.parts(quiet).map { it.label })
        assertEquals("Regen put back 0.5 kWh", DriveSummary.regen(quiet))
    }

    @Test fun theSampleReadsAsTheOwnersEveningDrive() {
        val s = DriveSummary.sample(40 * day)
        assertEquals("2.9", DriveSummary.rate(s.whPerKm, metric = false))
        assertEquals("▼ 17% below your usual 3.5", DriveSummary.versus(s, metric = false))
        assertEquals("12.4 mi  ·  4.3 kWh  ·  15 min  ·  41°F  ·  78% → 73%", DriveSummary.facts(s, metric = false))
        assertNotNull(DriveSummary.monthLine(s, "November", metric = false))
        assertTrue(DriveSummary.monthLine(s, "November", metric = false)!!.startsWith("November  "))
    }

    @Test fun theClockRangeSaysAmOrPmOnceUnlessTheDriveCrossesNoon() {
        val evening = (17 * 60 + 53) * 60_000L
        assertEquals("5:53 – 6:08 PM", DriveSummary.clockRange(evening, evening + 15 * 60_000L, 0))
        val late = (11 * 60 + 50) * 60_000L
        assertEquals("11:50 AM – 12:10 PM", DriveSummary.clockRange(late, late + 20 * 60_000L, 0))
        // The car's offset, not UTC: 22:53 UTC at −5 h is 5:53 PM.
        assertEquals("5:53 – 6:08 PM", DriveSummary.clockRange(evening + 5 * 3_600_000L, evening + 5 * 3_600_000L + 15 * 60_000L, -300))
    }
}
