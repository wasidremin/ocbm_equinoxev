package wasidremin.gmccpa.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.telemetry.DriveOutlook
import wasidremin.gmccpa.telemetry.Trip
import wasidremin.gmccpa.telemetry.TripStats

class DriveCardTest {
    private val base = DriveOutlook.Baseline(1.0, 230.0, 20.0, DriveOutlook.Basis.SIMILAR, 8, 0.8, 1.4)
    private fun drive(km: Double, wh: Double, minutes: Int = 24) = Trip(Trip.Kind.DRIVE, 0, minutes * 60_000L, 0, 60_000.0, 60_000.0 - wh,
        distM = km * 1000, movingS = minutes * 60.0, open = true)
    private fun arrival(pct: Double, wh: Double) = DriveOutlook.Arrival(pct, wh, 170.0, 25.0)

    @Test fun aRouteShowsTheBatteryOnArrival() {
        val o = DriveOutlook.Outlook(170.0, null, base, null, arrival(61.6, 9_400.0))
        val f = DriveCard.face(o, null, 60_350.0, 85_000.0, 49_890.0, metric = false)!!
        assertEquals(DriveCard.Kind.ARRIVAL, f.kind)
        assertEquals("62%", f.ring)
        assertEquals("Arrive with 62%", f.title)
        assertEquals("9.4 kWh · 31 mi left", f.detail)
        assertEquals("on arrival", f.chip)
        assertEquals(DriveCard.Bar.Battery(0.71f, 0.62f), f.bar)
        assertFalse(f.warn)
    }

    @Test fun underTenPercentWarnsAndBelowZeroAsksForACharge() {
        val low = DriveCard.face(DriveOutlook.Outlook(null, null, base, null, arrival(7.2, 24_600.0)), null, 30_700.0, 85_000.0, 135_000.0, false)!!
        assertEquals(DriveCard.Tone.WARN, low.tone)
        assertTrue(low.warn)
        assertEquals("Arrive with 7%", low.title)
        val short = DriveCard.face(DriveOutlook.Outlook(null, null, base, null, arrival(-3.6, 18_400.0)), null, 15_300.0, 85_000.0, 100_000.0, false)!!
        assertEquals("Charge on the way", short.title)
        assertEquals("3.1 kWh short · 62 mi left", short.detail)
        assertEquals("0%", short.ring)
        assertTrue(short.warn)
    }

    @Test fun withoutARouteTheDriveIsSetAgainstTheUsual() {
        val d = drive(20.0, 3_660.0)
        val better = DriveOutlook.Outlook(183.0, TripStats.Verdict(TripStats.Standing.BETTER, 0.08), base, 0.93, null)
        val f = DriveCard.face(better, d, 50_000.0, 85_000.0, null, metric = false)!!
        assertEquals(DriveCard.Kind.PACE, f.kind)
        assertEquals("+8%", f.ring)
        assertEquals("3.4 mi/kWh", f.title)
        assertEquals("8% better than similar", f.detail)
        assertEquals(DriveCard.Tone.GOOD, f.tone)
        // 0.93 on a scale from 1.4 (worst) to 0.8 (best), normal at 1.0.
        val bar = f.bar as DriveCard.Bar.Range
        assertEquals(0.783f, bar.at, 0.01f)
        assertEquals(0.667f, bar.normal, 0.01f)
        val worse = DriveOutlook.Outlook(183.0, TripStats.Verdict(TripStats.Standing.WORSE, 0.12), base, 1.12, null)
        assertEquals("−12%", DriveCard.face(worse, d, 50_000.0, 85_000.0, null, false)!!.ring)
        val usual = DriveOutlook.Outlook(183.0, TripStats.Verdict(TripStats.Standing.TYPICAL, 0.01), base, 1.0, null)
        assertEquals("about your similar", DriveCard.face(usual, d, 50_000.0, 85_000.0, null, false)!!.detail)
    }

    @Test fun untilThereIsAVerdictItIsATripComputer() {
        val o = DriveOutlook.Outlook(183.0, null, base, null, null)
        val f = DriveCard.face(o, drive(19.96, 3_640.0), 50_000.0, 85_000.0, null, metric = false)!!
        assertEquals(DriveCard.Kind.TRIP, f.kind)
        assertEquals("12 mi · 24 min", f.title)
        assertEquals("3.6 kWh · 3.4 mi/kWh", f.detail)
        assertEquals("3.4", f.ring)
        val metric = DriveCard.face(o, drive(19.96, 3_640.0), 50_000.0, 85_000.0, null, metric = true)!!
        assertEquals("20 km · 24 min", metric.title)
        assertEquals("183", metric.ring)
        assertEquals("Wh/km", metric.chip)
    }

    @Test fun nothingToSayMeansNoCard() {
        assertNull(DriveCard.face(DriveOutlook.Outlook(null, null, base, null, null), null, 50_000.0, 85_000.0, null, false))
    }

    @Test fun itOpensForNewsAndFoldsAfterEightSeconds() {
        assertEquals(DriveCard.Mode.HIDDEN, DriveCard.mode(false, false, 0))
        assertEquals(DriveCard.Mode.OPEN, DriveCard.mode(true, false, 7_999))
        assertEquals(DriveCard.Mode.CHIP, DriveCard.mode(true, false, 8_000))
        assertEquals(DriveCard.Mode.OPEN, DriveCard.mode(true, true, 600_000))
        val at62 = DriveCard.sampleFace(null)
        assertTrue(DriveCard.changed(null, at62))
        assertFalse(DriveCard.changed(at62, at62.copy(ring = "61%", value = 61, title = "Arrive with 61%")))
        assertTrue(DriveCard.changed(at62, at62.copy(value = 59)))
        assertTrue(DriveCard.changed(at62, DriveCard.sampleFace("better")))
        assertTrue(DriveCard.changed(DriveCard.sampleFace("better"), DriveCard.sampleFace("worse")))
    }

    @Test fun longDrivesReadInHoursAndShortDistancesKeepADecimal() {
        assertEquals("1 h 05", DriveCard.duration(65 * 60.0))
        assertEquals("4.2 mi", DriveCard.distance(4.2 * TripStats.M_PER_MILE, false))
        assertEquals("31 mi", DriveCard.distance(49_890.0, false))
    }

    @Test fun theDriveCardSitsUnderNowPlayingAndMovesAsideForADockedTurnCard() {
        val b = ClusterLayout.rect(2000, 810, ClusterLayout.Slot.DRIVE, ClusterLayout.Slot.DRIVE.default)
        val media = ClusterLayout.rect(2000, 810, ClusterLayout.Slot.MEDIA, ClusterLayout.Slot.MEDIA.default)
        assertTrue(b.top >= media.top + media.height)
        // The glass shows to about 95% across (ClusterLayout.DOCK_DEFAULT's note).
        assertTrue(b.left + b.width <= 2000 * 0.95f)
    }
}
