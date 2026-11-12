package wasidremin.gmccpa.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.telemetry.RollingEfficiency
import wasidremin.gmccpa.telemetry.TripStats

class DriveCardTest {
    private val mile = TripStats.M_PER_MILE
    /** A drive of [miles] at a steady [whPerMile], marked every 100 m from a full 60 kWh. */
    private fun marks(miles: Double, whPerMile: (Double) -> Double): List<RollingEfficiency.Mark> {
        val q = ArrayDeque<RollingEfficiency.Mark>()
        var wh = 60_000.0
        var d = 0.0
        while (d <= miles * mile + 1e-6) {
            RollingEfficiency.add(q, RollingEfficiency.Mark(d, wh))
            wh -= whPerMile(d / mile) * 100.0 / mile
            d += 100.0
        }
        return q.toList()
    }

    @Test fun theLastMileIsSetAgainstTheLastTen() {
        // Ten miles at 300 Wh/mi (3.33 mi/kWh), then two at 250 (4.0). A mile counts once a mark passes its end.
        val f = DriveCard.face(marks(12.1) { if (it < 10.0) 300.0 else 250.0 }, 180.0, 200.0, "Nov", metric = false)!!
        assertEquals(4.0, f.now!!, 0.05)
        assertEquals(3.45, f.ten!!, 0.08)
        assertFalse(f.tenIsSoFar)
        assertEquals(DriveCard.Tone.GOOD, f.tone)
        assertEquals(10, f.bars.size)
        assertEquals(4.0, f.bars.last(), 0.05)
        assertEquals(3.33, f.bars.first(), 0.05)
        assertEquals(TripStats.milesPerKwh(180.0), f.month!!, 0.001)
        assertTrue(DriveCard.chipText(f).startsWith("▲"))
        assertEquals("4.0 mi/kWh · last mi", DriveCard.title(f))
    }

    @Test fun beforeTheFirstMileThereIsNoFigureAndShortDrivesShowSoFar() {
        val early = DriveCard.face(marks(0.6) { 300.0 }, null, null, "Nov", false)!!
        assertNull(early.now)
        assertTrue(early.tenIsSoFar)
        assertEquals("first mi…", DriveCard.chipText(early))
        assertEquals("Measuring the first mi", DriveCard.title(early))
        assertEquals("—", DriveCard.figure(early.month))
        assertNull(DriveCard.face(emptyList(), null, null, "Nov", false))
        val three = DriveCard.face(marks(3.05) { 300.0 }, null, null, "Nov", false)!!
        assertTrue(three.tenIsSoFar)
        assertEquals("so far", DriveCard.legend(three)[0].first)
        assertEquals(3, three.bars.size)
    }

    @Test fun aFasterStretchIsWorseAndWithinFivePercentIsAbout() {
        val worse = DriveCard.face(marks(11.0) { if (it < 10.0) 300.0 else 360.0 }, null, null, "Nov", false)!!
        assertEquals(DriveCard.Tone.WARN, worse.tone)
        assertTrue(DriveCard.chipText(worse).startsWith("▼"))
        val same = DriveCard.face(marks(11.0) { if (it < 10.0) 300.0 else 310.0 }, null, null, "Nov", false)!!
        assertEquals(DriveCard.Tone.PLAIN, same.tone)
        assertEquals("≈ 10 mi avg", DriveCard.chipText(same))
    }

    @Test fun aMileThatGainedEnergyShowsAsTheCap() {
        val down = DriveCard.face(marks(11.0) { if (it < 10.0) 300.0 else -50.0 }, null, null, "Nov", false)!!
        assertEquals(RollingEfficiency.CAP, down.now!!, 0.0)
        assertEquals(">9.9", DriveCard.figure(down.now))
    }

    @Test fun metricIsKilometresPerKwh() {
        val f = DriveCard.face(marks(8.0) { 300.0 }, null, null, "Nov", metric = true)!!
        assertEquals(1000.0 / (300.0 / mile * 1000.0), f.now!!, 0.05)
        assertEquals("km/kWh", f.rate)
        assertEquals(8.0, f.scale, 0.0)
    }

    @Test fun itOpensWhenStoppedWithNoRouteOrOnAFlipAndIsFoldedOtherwise() {
        assertEquals(DriveCard.Mode.HIDDEN, DriveCard.mode(false, true, 0, Long.MAX_VALUE))
        assertEquals(DriveCard.Mode.OPEN, DriveCard.mode(true, false, 0, Long.MAX_VALUE))
        assertEquals(DriveCard.Mode.CHIP, DriveCard.mode(true, true, 2_999, Long.MAX_VALUE))
        assertEquals(DriveCard.Mode.OPEN, DriveCard.mode(true, true, 3_000, Long.MAX_VALUE))
        assertEquals(DriveCard.Mode.OPEN, DriveCard.mode(true, true, 0, 7_999))
        assertEquals(DriveCard.Mode.CHIP, DriveCard.mode(true, true, 0, 8_000))
    }

    @Test fun onlyAChangeBetweenBetterAndWorseIsAFlip() {
        val g = DriveCard.Tone.GOOD; val w = DriveCard.Tone.WARN; val p = DriveCard.Tone.PLAIN
        assertFalse(DriveCard.flipped(null, g))
        assertFalse(DriveCard.flipped(g, g))
        assertFalse(DriveCard.flipped(g, p))
        assertTrue(DriveCard.flipped(g, w))
        assertTrue(DriveCard.flipped(w, g))
    }

    @Test fun theSamplesCoverEveryFace() {
        assertEquals(DriveCard.Tone.GOOD, DriveCard.sampleFace(null).tone)
        assertEquals(DriveCard.Tone.WARN, DriveCard.sampleFace("worse").tone)
        assertNull(DriveCard.sampleFace("first").now)
        assertTrue(DriveCard.sampleArrival("low").low)
        assertTrue(DriveCard.sampleArrival("short").short)
        assertEquals(62, DriveCard.sampleArrival(null).pct)
    }

    @Test fun theDriveCardSitsUnderNowPlaying() {
        val b = ClusterLayout.rect(2000, 810, ClusterLayout.Slot.DRIVE, ClusterLayout.Slot.DRIVE.default)
        val media = ClusterLayout.rect(2000, 810, ClusterLayout.Slot.MEDIA, ClusterLayout.Slot.MEDIA.default)
        assertTrue(b.top >= media.top + media.height)
        assertTrue(b.left + b.width <= 2000 * 0.95f)
    }
}
