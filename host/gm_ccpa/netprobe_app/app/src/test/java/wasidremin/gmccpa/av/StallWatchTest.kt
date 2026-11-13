package wasidremin.gmccpa.av

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A quiet screen is not a stuck decoder (2026-11-13: two rebuilds on the morning drive, 55 in a month). */
class StallWatchTest {
    private val limit = HevcRenderer.STALL_RESET_MS

    /** Feed access units at [times]; [renders] are the frames that came out. Returns the worst stuck time seen at a feed. */
    private fun worst(times: List<Long>, renders: List<Long>): Long {
        var run = 0L; var lastQueued = 0L; var worst = 0L
        for (t in times) {
            val lastRender = renders.filter { it <= t }.maxOrNull() ?: 0L
            worst = maxOf(worst, StallWatch.stuckMs(t, run, lastQueued, lastRender))   // checked before the feed
            run = StallWatch.runBegan(t, run, lastQueued, lastRender)
            lastQueued = t
        }
        return worst
    }

    /** The morning's case: the last access unit before a pause never came out, then 10.9 s of nothing. */
    @Test fun theFirstFrameAfterAPauseIsNotAStall() {
        val times = listOf(1_000L, 1_016L, 1_033L, 11_929L, 11_945L, 11_962L)
        val renders = listOf(1_010L, 1_025L, /* 1_033 held by the decoder */ 11_940L, 11_955L)
        assertEquals(0L, worst(times, renders))
    }

    /** Frames going in every 16 ms and nothing coming out is a stall once it has lasted the limit. */
    @Test fun unrenderedFramesInARowAreAStall() {
        val times = (0..250).map { 1_000L + it * 16L }
        assertTrue(worst(times, listOf(990L)) >= limit)
        // The same input with frames coming out is never stuck for longer than one frame.
        assertTrue(worst(times, times.map { it + 8 }) < 50)
    }

    /** A slow screen (a frame every two seconds) that stops rendering is still caught. */
    @Test fun aSlowStreamThatStopsRenderingIsCaught() {
        val times = (0..5).map { 1_000L + it * 2_000L }
        assertTrue(worst(times, listOf(900L)) >= limit)
    }

    /** A pause in the input ends the run: the clock starts again at the frame after it. */
    @Test fun aPauseRestartsTheClock() {
        // Two unrendered frames, a 5 s pause, then frames for 2 s with nothing out: under the limit so far.
        val times = listOf(1_000L, 1_016L) + (0..125).map { 6_016L + it * 16L }
        val w = worst(times, emptyList())
        assertTrue("worst $w", w in 1_900L until limit)
    }

    @Test fun nothingQueuedIsNeverStuck() {
        assertEquals(0L, StallWatch.stuckMs(50_000, 0, 0, 0))
        assertEquals(0L, StallWatch.stuckMs(50_000, 0, 0, 49_000))
    }
}
