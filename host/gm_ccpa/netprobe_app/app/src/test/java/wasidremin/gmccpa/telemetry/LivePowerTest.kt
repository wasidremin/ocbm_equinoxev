package wasidremin.gmccpa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The Drive card's power figure ([LivePower]). The month-long replay it was tuned on is in its comment. */
class LivePowerTest {
    /** The battery as the car reports it: whole 10 Wh steps of what [usedWh] has taken from 60 kWh. */
    private fun reported(usedWh: Double) = 60_000.0 - Math.floor(usedWh / 10.0) * 10.0

    /** Drive [seconds] at the speed [v] gives for each, using [watts] of real power, one reading a second. */
    private fun run(p: LivePower, seconds: Int, startMs: Long = 1_000L, v: (Int) -> Double, watts: (Int) -> Double): Double {
        var used = 0.0
        for (s in 0 until seconds) {
            used += watts(s) / 3600.0
            p.onBattery(reported(used))
            p.onSpeed(startMs + s * 1000L, v(s), 18.0)
        }
        return used
    }

    @Test fun theModelIsWhatHoldingASpeedCostsPlusWhatChangingItTakes() {
        // 25 m/s on a mild day: the fitted 95 + 0.138 v² Wh/km, as watts.
        assertEquals((95.0 + 0.138 * 625.0) * 25.0 * 3.6, LivePower.model(25.0, 0.0, 18.0), 1e-6)
        // Gaining 1 m/s² at 20 m/s adds mass x a x v, less what the drivetrain loses.
        val steady = LivePower.model(20.0, 0.0, 18.0)
        assertEquals(steady + 2400.0 * 20.0 / LivePower.DRIVE_EFF, LivePower.model(20.0, 1.0, 18.0), 1e-6)
        // Slowing gives most of it back, and can take the figure below zero.
        assertEquals(steady - 2400.0 * 20.0 * DriveOutlook.REGEN, LivePower.model(20.0, -1.0, 18.0), 1e-6)
        assertTrue(LivePower.model(20.0, -1.0, 18.0) < 0)
        assertEquals(0.0, LivePower.model(0.0, 0.0, 18.0), 0.0)
    }

    @Test fun theBatteryCorrectsWhatTheModelCannotSee() {
        // A climb: the car really uses 30 kW at a steady 25 m/s, where the model expects about 16.
        val p = LivePower()
        run(p, 90, v = { 25.0 }) { 30_000.0 }
        val model = LivePower.model(25.0, 0.0, 18.0)
        assertTrue(model < 17_000.0)
        assertEquals(30_000.0, p.watts!!, 2_500.0)
    }

    @Test fun itAnswersThePedalWithinASecondOrTwo() {
        val p = LivePower()
        val cruise = LivePower.model(20.0, 0.0, 18.0)
        run(p, 60, v = { 20.0 }) { cruise }
        val before = p.watts!!
        assertEquals(cruise, before, 2_500.0)
        // Lift off: 1.5 m/s² of slowing. Two seconds later the figure is far below, well before the battery could say so.
        var v = 20.0
        for (s in 0 until 2) {
            v -= 1.5
            p.onBattery(reported(60 * cruise / 3600.0))
            p.onSpeed(61_000L + s * 1000L, v, 18.0)
        }
        assertTrue(p.watts!! < before - 25_000.0)
    }

    @Test fun thereIsNoFigureUntilItHasSettledAndAGapStartsItAgain() {
        val p = LivePower()
        run(p, 10, v = { 20.0 }) { 12_000.0 }
        assertNull(p.watts)
        run(p, 10, startMs = 11_000L, v = { 20.0 }) { 12_000.0 }
        assertNotNull(p.watts)
        // Thirty seconds with no reading: the next one starts over.
        p.onSpeed(60_000L, 20.0, 18.0)
        assertNull(p.watts)
        p.reset()
        assertNull(p.watts)
    }

    @Test fun aRatePerDistanceNeedsDistance() {
        assertEquals(200.0, LivePower.whPerKm(18_000.0, 25.0)!!, 1e-9)
        assertNull(LivePower.whPerKm(18_000.0, 1.0))
        assertTrue(abs(LivePower.whPerKm(-9_000.0, 25.0)!! + 100.0) < 1e-9)
    }
}
