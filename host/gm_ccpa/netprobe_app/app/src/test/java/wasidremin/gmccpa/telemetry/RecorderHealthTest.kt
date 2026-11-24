package wasidremin.gmccpa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.telemetry.RecorderHealth.Verdict

class RecorderHealthTest {
    private val t = 1_000_000L

    @Test fun aRecorderWhoseThreadMissedThreeBeatsIsStuckEvenWhileItSaysItIsCapturing() {
        // 2026-11-23: capturing on paper, silent for two days.
        assertEquals(Verdict.STUCK, RecorderHealth.verdict(t, auto = true, connected = true, capturing = true,
            lastBeatMs = t - RecorderHealth.STUCK_MS, offSinceMs = 0))
        assertEquals(Verdict.OK, RecorderHealth.verdict(t, auto = true, connected = true, capturing = true,
            lastBeatMs = t - RecorderHealth.STUCK_MS + 1, offSinceMs = 0))
    }

    @Test fun captureOffAMinuteWithTheCarConnectedIsRestartedButNotBeforeOrWithoutTheCar() {
        val off = t - RecorderHealth.OFF_MS
        assertEquals(Verdict.OFF, RecorderHealth.verdict(t, true, true, false, t, off))
        assertEquals(Verdict.IDLE, RecorderHealth.verdict(t, true, true, false, t, off + 1))
        assertEquals("no car service", Verdict.IDLE, RecorderHealth.verdict(t, true, false, false, t, off))
        assertEquals("a manual capture that finished", Verdict.IDLE, RecorderHealth.verdict(t, false, true, false, t, off))
        assertEquals("never started", Verdict.IDLE, RecorderHealth.verdict(t, true, true, false, t, 0))
    }

    @Test fun aStuckRecorderIsRebuiltAtMostEveryTenMinutes() {
        assertTrue(RecorderHealth.mayRebuild(t, 0))
        assertFalse(RecorderHealth.mayRebuild(t, t - RecorderHealth.REBUILD_EVERY_MS + 1))
        assertTrue(RecorderHealth.mayRebuild(t, t - RecorderHealth.REBUILD_EVERY_MS))
    }

    @Test fun theBoardWordsStayCoarseSoTheyDoNotLogEveryMinute() {
        assertEquals("capturing, the car heard in the last 5 min, drive open",
            RecorderHealth.boardText(Verdict.OK, true, 1_000, driveOpen = true))
        assertEquals(RecorderHealth.boardText(Verdict.OK, true, 2_000, true), RecorderHealth.boardText(Verdict.OK, true, 250_000, true))
        assertEquals("capturing, the car quiet 5 min, no drive open",
            RecorderHealth.boardText(Verdict.OK, true, RecorderHealth.QUIET_MS, driveOpen = false))
        assertEquals("capturing, nothing from the car yet, no drive open", RecorderHealth.boardText(Verdict.OK, true, null, false))
        assertEquals("thread stuck — the Drive card has no data", RecorderHealth.boardText(Verdict.STUCK, true, 1_000, true))
    }

    @Test fun whereTheThreadIsGivesClassAndMethodOnly() {
        val stack = arrayOf(
            StackTraceElement("java.io.FileOutputStream", "write", "FileOutputStream.java", 12),
            StackTraceElement("wasidremin.gmccpa.telemetry.VehicleProbe", "save", "VehicleProbe.kt", 340),
        )
        assertEquals(listOf("FileOutputStream.write:12", "VehicleProbe.save:340"), RecorderHealth.frames(stack))
        assertEquals(1, RecorderHealth.frames(stack, max = 1).size)
    }
}
