package wasidremin.gmccpa.av

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-02 17:20:58: `phone=0` at .514, the telephony stream torn down at .591, media set up at
 * .712 and muted until the call sink's 3 s idle release at 17:21:01.587. Times below are ms from
 * the `phone=0` edge.
 */
class CallEndTest {
    private val edge = 1_000_000L
    private val lastAu = edge + 77

    @Test fun releasesOnceTheStreamHasDrained() {
        assertEquals(600L, VoiceRouter.CALL_END_DRAIN_MS)
        assertFalse(callStreamEnded(lastAu + 300, true, false, edge, lastAu))
        assertTrue(callStreamEnded(lastAu + 700, true, false, edge, lastAu))
    }

    @Test fun neverDuringACall() {
        // Silence inside a call is delivered as AUs; a gap with phone=1 is the 3 s AU-clock window's job.
        assertFalse(callStreamEnded(lastAu + 5_000, true, true, edge, lastAu))
    }

    @Test fun aStreamLeftUpAfterTheCallKeepsTheOldWindow() {
        val stillDelivering = edge + 2_000
        assertFalse(callStreamEnded(stillDelivering + 30, true, false, edge, stillDelivering))
    }

    @Test fun noEdgeNoEarlyRelease() {
        // No call has ended this process, or the phone never sends modesChanged.
        assertFalse(callStreamEnded(lastAu + 5_000, true, false, 0L, lastAu))
        assertFalse(callStreamEnded(lastAu + 5_000, false, false, edge, lastAu))
    }
}
