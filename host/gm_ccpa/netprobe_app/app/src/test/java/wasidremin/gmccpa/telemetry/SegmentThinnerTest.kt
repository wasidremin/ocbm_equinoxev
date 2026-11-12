package wasidremin.gmccpa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.telemetry.SegmentThinner.Decision

class SegmentThinnerTest {
    private val min = 60_000L

    @Test fun `a driven minute is always sent`() {
        val t = SegmentThinner()
        for (i in 0..30) assertEquals(Decision.MOVING, t.decide(i * min, moved = true, batteryWh = 60_000.0 - i * 100))
    }

    @Test fun `the first segment of a session is sent even when parked`() {
        assertEquals(Decision.HEARTBEAT, SegmentThinner().decide(0L, moved = false, batteryWh = null))
    }

    @Test fun `the minutes after a stop are still sent, then it falls to a heartbeat`() {
        val t = SegmentThinner()
        t.decide(0L, true, 50_000.0)
        assertEquals(Decision.SETTLING, t.decide(1 * min, false, 50_000.0))
        assertEquals(Decision.SETTLING, t.decide(4 * min, false, 50_000.0))
        assertEquals(Decision.PARKED_SKIP, t.decide(5 * min, false, 50_000.0))
        // Last kept at minute 4; the heartbeat is due fifteen minutes after that.
        for (m in 6..18) assertFalse("minute $m", t.decide(m * min, false, 50_000.0).keep)
        assertEquals(Decision.HEARTBEAT, t.decide(19 * min, false, 50_000.0))
        assertEquals(Decision.PARKED_SKIP, t.decide(20 * min, false, 50_000.0))
    }

    @Test fun `a red light inside a drive does not open a gap`() {
        val t = SegmentThinner()
        t.decide(0L, true, 50_000.0)
        assertTrue(t.decide(1 * min, false, 49_990.0).keep)
        assertTrue(t.decide(2 * min, false, 49_980.0).keep)
        assertEquals(Decision.MOVING, t.decide(3 * min, true, 49_900.0))
    }

    @Test fun `charging is sampled every five minutes`() {
        val t = SegmentThinner()
        var wh = 30_000.0
        val kept = ArrayList<Int>()
        for (m in 0..30) {
            if (t.decide(m * min, false, wh).keep) kept += m
            wh += 180.0   // about 11 kW
        }
        assertEquals(listOf(0, 5, 10, 15, 20, 25, 30), kept)
    }

    @Test fun `a slow drain is not mistaken for charging until it adds up`() {
        val t = SegmentThinner()
        var wh = 30_000.0
        val kept = ArrayList<Int>()
        for (m in 0..40) {
            if (t.decide(m * min, false, wh).keep) kept += m
            wh -= 10.0    // 0.6 kW of accessories: 300 Wh takes half an hour
        }
        assertEquals(listOf(0, 15, 30), kept)
    }

    @Test fun `a car that never reports its battery still gets heartbeats`() {
        val t = SegmentThinner()
        val kept = (0..45).filter { t.decide(it * min, false, null).keep }
        assertEquals(listOf(0, 15, 30, 45), kept)
    }
}
