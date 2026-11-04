package wasidremin.gmccpa.av

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.match.ScreenModel

class QuietWindowTest {
    private fun w() = QuietWindow(tapAtMs = 0, minAfterTapMs = 600, quietMs = 400, madThreshold = 0.0078f)

    @Test fun twoEqualSamplesSoonAfterTapAreNotSettled() {
        val q = w()
        assertFalse(q.offer(250, 0.0f))
        assertFalse(q.offer(310, 0.0f))
    }

    @Test fun settlesOnlyAfterMinimumAndQuietWindow() {
        val q = w()
        assertFalse(q.offer(300, 0.0f))
        assertFalse(q.offer(600, 0.0f))
        assertTrue(q.offer(700, 0.0f))
    }

    @Test fun motionResetsQuietWindow() {
        val q = w()
        assertFalse(q.offer(300, 0.0f))
        assertFalse(q.offer(650, 0.05f))
        assertFalse(q.offer(700, 0.0f))
        assertFalse(q.offer(1000, 0.0f))
        assertTrue(q.offer(1100, 0.0f))
    }

    @Test fun nanIsTreatedAsMotion() {
        val q = w()
        assertFalse(q.offer(700, Float.NaN))
        assertFalse(q.offer(800, 0.0f))
    }

    /** 2026-11-02 12:50 and 13:06: the tap worked, then a map moving under a route aborted the launch. */
    @Test fun aMovingScreenIsReadNotAborted() {
        assertTrue(readUnsettled(CarPlayFrameGrabber.FailureReason.TIMEOUT_UNSTABLE, interrupted = false))
        assertFalse(readUnsettled(CarPlayFrameGrabber.FailureReason.TIMEOUT_UNSTABLE, interrupted = true))
        assertFalse(readUnsettled(CarPlayFrameGrabber.FailureReason.SESSION_DOWN, interrupted = false))
        assertFalse(readUnsettled(CarPlayFrameGrabber.FailureReason.SURFACE_NOT_READY, interrupted = false))
    }

    private val dash = ScreenModel.Surface.DASHBOARD
    private val notDash = ScreenModel.Surface.NOT_DASHBOARD
    private fun hit(passed: Boolean) = ScreenModel.Hit("grid.nowplaying", if (passed) 0.919f else 0.433f, 0.190f, 0.637f, passed)
    private fun land(before: ScreenModel.Surface, now: ScreenModel.Surface, grid: (() -> ScreenModel.Hit)?) =
        toggleLanding(before, now, grid, 617, 240)

    /** 2026-11-03 Now Playing: Maps (square) -> Dashboard -> Home, where the icon scored 0.919 at (0.190, 0.637). */
    @Test fun aToggleHasLandedWhenTheGlyphFlips() {
        assertNull(land(notDash, notDash) { error("not searched before the flip") })
        assertEquals(Landing(dash), land(notDash, dash) { error("the Dashboard has no grid") })
        assertNull(land(dash, dash) { hit(true) })
        assertEquals(Landing(notDash, 117, 153), land(dash, notDash) { hit(true) })
    }

    @Test fun homeHasLandedOnlyOnceTheIconIsThere() {
        assertNull(land(dash, notDash) { hit(false) })
        // The square glyph can read UNKNOWN; the icon on Home is evidence enough.
        assertEquals(Landing(notDash, 117, 153), land(dash, ScreenModel.Surface.UNKNOWN) { hit(true) })
        assertNull(land(dash, ScreenModel.Surface.UNKNOWN, null))
        assertEquals(Landing(notDash), land(dash, notDash, null))
    }

    @Test fun theIconMustHoldStillAcrossTwoSamples() {
        assertTrue(sameLanding(Landing(notDash, 117, 153), Landing(notDash, 118, 152)))
        assertFalse(sameLanding(Landing(notDash, 117, 153), Landing(notDash, 119, 153)))
        assertFalse(sameLanding(Landing(dash), Landing(notDash)))
    }
}
