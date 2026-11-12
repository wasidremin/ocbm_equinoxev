package wasidremin.gmccpa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RollingEfficiencyTest {
    private fun m(d: Double, wh: Double) = RollingEfficiency.Mark(d, wh)

    @Test fun marksAreAHundredMetresApartAndAboutTenMilesAreKept() {
        val q = ArrayDeque<RollingEfficiency.Mark>()
        var d = 0.0
        while (d < 30_000.0) { RollingEfficiency.add(q, m(d, 60_000.0 - d * 0.2)); d += 10.0 }
        assertTrue(q.zipWithNext().all { (a, b) -> b.distM - a.distM >= RollingEfficiency.STEP_M - 1e-6 })
        assertTrue(q.last().distM - q.first().distM <= RollingEfficiency.KEEP_M)
        assertTrue(q.last().distM - q.first().distM >= RollingEfficiency.KEEP_M - RollingEfficiency.STEP_M)
    }

    @Test fun aSpanIsTheEnergyOverTheDistance() {
        val marks = (0..50).map { m(it * 100.0, 60_000.0 - it * 20.0) }   // 200 Wh/km
        val s = RollingEfficiency.span(marks, 1000.0)!!
        assertEquals(200.0, s.whPerKm, 1e-9)
        assertEquals(1000.0, s.distM, 1e-9)
        // Longer than the drive: the drive so far.
        assertEquals(5000.0, RollingEfficiency.span(marks, 20_000.0)!!.distM, 1e-9)
        assertNull(RollingEfficiency.span(listOf(m(0.0, 1.0)), 1000.0))
    }

    @Test fun eachWholeUnitFromTheDriveStartGetsOneFigure() {
        // 20 Wh per 100 m for the first kilometre, 30 after it.
        val marks = (0..35).map { m(it * 100.0, 60_000.0 - minOf(it, 10) * 20.0 - maxOf(0, it - 10) * 30.0) }
        val per = RollingEfficiency.perUnit(marks, 1000.0, 10)
        assertEquals(3, per.size)
        assertEquals(200.0, per[0], 1e-9)
        assertEquals(300.0, per[1], 1e-9)
        assertEquals(listOf(300.0, 300.0), RollingEfficiency.perUnit(marks, 1000.0, 2))
    }

    @Test fun moreIsAlwaysBetterAndAGainIsTheCap() {
        assertEquals(TripStats.milesPerKwh(186.4), RollingEfficiency.perKwh(186.4, metric = false), 1e-9)
        assertEquals(5.0, RollingEfficiency.perKwh(200.0, metric = true), 1e-9)
        assertEquals(RollingEfficiency.CAP, RollingEfficiency.perKwh(-10.0, false), 0.0)
        assertEquals(RollingEfficiency.CAP, RollingEfficiency.perKwh(1.0, true), 0.0)
    }
}
