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

    private fun face(marks: List<RollingEfficiency.Mark>, month: Double? = null, life: Double? = null, metric: Boolean = false,
                     tail: List<RollingEfficiency.Mark> = emptyList()) = DriveCard.face(marks, tail, month, life, "Nov", metric)

    @Test fun theLastMileIsSetAgainstTheUsual() {
        // Ten miles at 300 Wh/mi (3.33 mi/kWh), then two at 250 (4.0). A mile counts once a mark passes its end.
        val usual = 300.0 / mile * 1000.0   // Wh/km: this month has been 3.33 mi/kWh
        val f = face(marks(12.1) { if (it < 10.0) 300.0 else 250.0 }, month = usual, life = usual * 1.2)!!
        assertEquals(4.0, f.last!!, 0.05)
        assertEquals(3.45, f.recent!!, 0.08)
        assertFalse(f.recentIsSoFar)
        assertEquals(3.33, f.usual!!, 0.01)
        assertEquals(DriveCard.Tone.GOOD, f.tone)
        assertEquals("▲ 20% vs usual", DriveCard.vsText(f))
        assertEquals("LAST MI", DriveCard.figureLabel(f, live = false))
        assertEquals("NOW", DriveCard.figureLabel(f, live = true))
        // The line: ten miles of it, oldest first, ending near 4.0 and starting near 3.33.
        assertTrue(f.past.isEmpty())
        assertNull(f.startX)
        assertTrue(f.line.first().x >= -10f && f.line.last().x > -0.2f)
        assertEquals(4.0f, f.line.last().y, 0.05f)
        assertEquals(3.33f, f.line.first().y, 0.05f)
        val (lo, hi) = DriveCard.range(f)
        assertTrue(lo < 3.33f && hi > 4.0f)
    }

    @Test fun beforeTheFirstMileTheDriveSoFarStandsIn() {
        val early = face(marks(0.6) { 300.0 }, life = 300.0 / mile * 1000.0)!!
        assertNull(early.last)
        assertTrue(early.recentIsSoFar)
        assertEquals("SO FAR", DriveCard.figureLabel(early, live = false))
        assertEquals("≈ usual", DriveCard.vsText(early))
        // Lifetime stands in for a month with no drive yet; with neither there is nothing to compare.
        assertEquals(early.lifetime, early.usual)
        assertEquals("", DriveCard.vsText(face(marks(0.6) { 300.0 })!!))
        assertEquals("—", DriveCard.figure(early.month))
        assertNull(face(emptyList()))
        // The first moment of a drive: one mark, no figure, and the drive before fills the line.
        val before = RollingEfficiency.appended(emptyList(), marks(12.0) { 280.0 })
        val start = face(marks(0.0) { 300.0 }, tail = before)!!
        assertEquals("THIS DRIVE", DriveCard.figureLabel(start, live = false))
        assertTrue(start.line.isEmpty())
        assertEquals(0f, start.startX!!, 1e-6f)
        assertTrue(start.past.size > 90)
        assertEquals(1000.0 / 280.0, start.past.last().y.toDouble(), 0.01)
    }

    @Test fun aFasterStretchIsWorseAndWithinFivePercentIsAbout() {
        val usual = 300.0 / mile * 1000.0
        val worse = face(marks(11.0) { if (it < 10.0) 300.0 else 360.0 }, month = usual)!!
        assertEquals(DriveCard.Tone.WARN, worse.tone)
        assertTrue(DriveCard.vsText(worse), Regex("▼ 1[5-7]% vs usual").matches(DriveCard.vsText(worse)))
        val same = face(marks(11.0) { if (it < 10.0) 300.0 else 310.0 }, month = usual)!!
        assertEquals(DriveCard.Tone.PLAIN, same.tone)
        assertEquals("≈ usual", DriveCard.vsText(same))
    }

    @Test fun aMileThatGainedEnergyShowsAsTheCap() {
        val down = face(marks(11.0) { if (it < 10.0) 300.0 else -50.0 })!!
        assertEquals(RollingEfficiency.CAP, down.last!!, 0.0)
        assertEquals(">9.9", DriveCard.figure(down.last))
        assertTrue(down.line.all { it.y <= RollingEfficiency.CAP.toFloat() })
    }

    @Test fun metricIsKilometresPerKwh() {
        val f = face(marks(8.0) { 300.0 }, metric = true)!!
        assertEquals(1000.0 / (300.0 / mile * 1000.0), f.last!!, 0.05)
        assertEquals("km/kWh", f.rate)
        assertEquals("LAST KM", DriveCard.figureLabel(f, live = false))
        val (lo, hi) = DriveCard.range(f)
        assertTrue(hi - lo >= 1.6f - 1e-4f)
    }

    @Test fun theFigureForThisMomentIsDistanceOverEnergy() {
        // 18 kW at 25 m/s is 200 Wh/km: 5 km/kWh, 3.1 mi/kWh.
        assertEquals(5.0, DriveCard.nowFigure(18_000.0, 25.0, metric = true)!!, 1e-9)
        assertEquals(3.107, DriveCard.nowFigure(18_000.0, 25.0, metric = false)!!, 0.001)
        // Regenerating, a kWh takes the car any distance: the cap. Standing, or with no power figure, nothing.
        assertEquals(RollingEfficiency.CAP, DriveCard.nowFigure(-20_000.0, 25.0, false)!!, 0.0)
        assertNull(DriveCard.nowFigure(1_000.0, 0.5, false))
        assertNull(DriveCard.nowFigure(null, 25.0, false))
    }

    @Test fun thePowerLineGivesOrdinaryDrivingMostOfItsLength() {
        assertEquals(0f, DriveCard.powerFraction(0.0), 0f)
        assertEquals(1f, DriveCard.powerFraction(400_000.0), 0f)
        assertEquals(-1f, DriveCard.powerFraction(-400_000.0), 0f)
        assertTrue(DriveCard.powerFraction(18_000.0) in 0.3f..0.4f)
        assertTrue(DriveCard.powerFraction(-20_000.0) in -0.55f..-0.45f)
        assertEquals("18 kW", DriveCard.kwText(18_400.0))
        assertEquals("−21 kW", DriveCard.kwText(-20_600.0))
        assertEquals("0 kW", DriveCard.kwText(-300.0))
    }

    @Test fun theArrivalFigureAlwaysHasOneHome() {
        val t = ClusterContent.Turn.values()
        // Free, with this app's turn card: the turn card has it. Otherwise this card does.
        assertFalse(DriveCard.arrivalHere(docked = false, turn = ClusterContent.Turn.OURS))
        assertTrue(DriveCard.arrivalHere(docked = true, turn = ClusterContent.Turn.OURS))
        assertTrue(t.filter { it != ClusterContent.Turn.OURS }.all { DriveCard.arrivalHere(false, it) })
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
        assertNull(DriveCard.sampleFace("first").last)
        assertTrue(DriveCard.sampleFace("first").line.isEmpty())
        assertTrue(DriveCard.sampleFace(null).let { it.past.last().x <= it.startX!! && it.line.first().x >= it.startX!! })
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
