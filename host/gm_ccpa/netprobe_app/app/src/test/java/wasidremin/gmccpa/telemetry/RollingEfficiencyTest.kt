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

    @Test fun moreIsAlwaysBetterAndAGainIsTheCap() {
        assertEquals(TripStats.milesPerKwh(186.4), RollingEfficiency.perKwh(186.4, metric = false), 1e-9)
        assertEquals(5.0, RollingEfficiency.perKwh(200.0, metric = true), 1e-9)
        assertEquals(RollingEfficiency.CAP, RollingEfficiency.perKwh(-10.0, false), 0.0)
        assertEquals(RollingEfficiency.CAP, RollingEfficiency.perKwh(1.0, true), 0.0)
    }

    /** Marks every 100 m of [km] at a rate that may change along the way, from 60 kWh. */
    private fun drive(km: Double, whPerKm: (Double) -> Double): List<RollingEfficiency.Mark> {
        val out = ArrayList<RollingEfficiency.Mark>()
        var wh = 60_000.0
        var d = 0.0
        while (d <= km * 1000.0 + 1e-6) {
            out += m(d, wh)
            wh -= whPerKm(d / 1000.0) * 0.1
            d += 100.0
        }
        return out
    }

    @Test fun theLineIsTheRateAlongTheDriveSmoothed() {
        val steady = RollingEfficiency.line(drive(8.0) { 200.0 }, 1000.0, 10.0)
        assertTrue(steady.size in 70..80)
        assertTrue(steady.all { kotlin.math.abs(it.whPerKm - 200.0) < 1e-6 })
        // Oldest first, a point every tenth of a kilometre, ending just short of now.
        assertTrue(steady.zipWithNext().all { (a, b) -> kotlin.math.abs(b.x - a.x - 0.1) < 1e-9 })
        assertEquals(-0.15, steady.last().x, 1e-9)
        // A step from 150 to 300 Wh/km becomes a ramp: no point jumps, and both levels are reached.
        val step = RollingEfficiency.line(drive(8.0) { if (it < 4.0) 150.0 else 300.0 }, 1000.0, 10.0)
        assertEquals(150.0, step.first().whPerKm, 1.0)
        assertEquals(300.0, step.last().whPerKm, 1.0)
        assertTrue(step.zipWithNext().all { (a, b) -> b.whPerKm - a.whPerKm in -1e-6..40.0 })
        // Only the span asked for, and nothing from a drive too short to have a rate.
        assertTrue(RollingEfficiency.line(drive(8.0) { 200.0 }, 1000.0, 3.0).first().x >= -3.0)
        assertTrue(RollingEfficiency.line(drive(0.2) { 200.0 }, 1000.0, 10.0).isEmpty())
    }

    @Test fun aNewDriveStartsWithTheEndOfTheOneBefore() {
        val before = RollingEfficiency.appended(emptyList(), drive(12.0) { 250.0 })
        // Just started: the whole span is the drive before, ending at now.
        val start = RollingEfficiency.trace(drive(0.0) { 200.0 }, before, 1000.0)
        assertTrue(start.now.isEmpty())
        assertEquals(0.0, start.startX!!, 1e-9)
        assertTrue(start.past.first().x >= -10.0 && start.past.last().x <= 0.0)
        assertTrue(start.past.all { kotlin.math.abs(it.whPerKm - 250.0) < 1e-6 })
        // Three kilometres in: this drive on the right, the one before filling the other seven.
        val three = RollingEfficiency.trace(drive(3.0) { 150.0 }, before, 1000.0)
        assertEquals(-3.0, three.startX!!, 1e-9)
        assertTrue(three.now.all { it.x > -3.0 && kotlin.math.abs(it.whPerKm - 150.0) < 1e-6 })
        assertTrue(three.past.all { it.x < -3.0 && it.x >= -10.0 && kotlin.math.abs(it.whPerKm - 250.0) < 1e-6 })
        // Past the span, or with no drive before: this drive alone and no mark for its start.
        assertNull(RollingEfficiency.trace(drive(11.0) { 150.0 }, before, 1000.0).startX)
        val alone = RollingEfficiency.trace(drive(3.0) { 150.0 }, emptyList(), 1000.0)
        assertTrue(alone.past.isEmpty())
        assertNull(alone.startX)
    }

    @Test fun finishedDrivesJoinEndToEndAndTheJoinSurvivesACheckpoint() {
        val a = drive(5.0) { 200.0 }
        val b = drive(4.0) { 300.0 }.map { m(it.distM, it.wh - 20_000.0) }   // after some other use of the battery
        val joined = RollingEfficiency.appended(RollingEfficiency.appended(emptyList(), a), b)
        assertEquals(9000.0, joined.last().distM - joined.first().distM, 1e-6)
        // The energy runs on from where the first drive left it: 5 km at 200 and 4 km at 300.
        assertEquals(5 * 200.0 + 4 * 300.0, joined.first().wh - joined.last().wh, 1e-6)
        assertTrue(joined.zipWithNext().all { (x, y) -> y.distM > x.distM })
        // No more is kept than the line can show.
        val long = RollingEfficiency.appended(joined, drive(16.0) { 180.0 })
        assertTrue(long.last().distM - long.first().distM <= RollingEfficiency.KEEP_M)
        // A drive with no marks to speak of leaves the tail alone.
        assertEquals(joined, RollingEfficiency.appended(joined, listOf(m(0.0, 1.0))))
        val back = RollingEfficiency.unpack(RollingEfficiency.pack(joined))
        assertEquals(joined.size, back.size)
        assertTrue(joined.zip(back).all { (x, y) -> kotlin.math.abs(x.distM - y.distM) <= 0.5 && kotlin.math.abs(x.wh - y.wh) <= 0.5 })
        assertTrue(RollingEfficiency.unpack(null).isEmpty())
        assertTrue(RollingEfficiency.unpack("rubbish;1,2;x,y").size == 1)
    }
}
