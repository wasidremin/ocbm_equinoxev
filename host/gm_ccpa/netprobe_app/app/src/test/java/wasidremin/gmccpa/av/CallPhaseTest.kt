package wasidremin.gmccpa.av

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overlay pill's state machine: which [CallPhase] a `metadata.rs` TEL_ state table maps to,
 * and the pill's clamp/restore geometry. All pure — no Android, no threads.
 */
class CallPhaseTest {
    @Test fun emptyTableHidesThePill() {
        assertEquals(CallPhase.NONE, callPhaseOf(emptyMap()))
    }

    @Test fun incomingUnconnectedCallRings() {
        assertEquals(CallPhase.RINGING, callPhaseOf(mapOf("a" to 2)))
    }

    @Test fun connectedCallIsActive() {
        assertEquals(CallPhase.ACTIVE, callPhaseOf(mapOf("a" to 1)))
    }

    @Test fun ringingBeatsActiveWhenBothPresent() {
        assertEquals(CallPhase.RINGING, callPhaseOf(mapOf("a" to 1, "b" to 2)))
    }

    @Test fun endedCallReturnsToNone() {
        val calls = mutableMapOf("a" to 2)
        assertEquals(CallPhase.RINGING, callPhaseOf(calls))
        calls.remove("a")
        assertEquals(CallPhase.NONE, callPhaseOf(calls))
    }

    @Test fun pillStaysInsideItsParent() {
        val (x, y) = clampPill(-50f, 2000f, 600, 120, 2400, 960)
        assertEquals(0f, x, 0.001f)
        assertEquals(840f, y, 0.001f)
    }

    @Test fun pillInsideBoundsIsUntouched() {
        val (x, y) = clampPill(100f, 100f, 600, 120, 2400, 960)
        assertEquals(100f, x, 0.001f)
        assertEquals(100f, y, 0.001f)
    }

    @Test fun firstRunParksTopCenter() {
        val (x, y) = fractionsToMargins(-1f, -1f, 600, 120, 2400, 960)
        assertEquals(900, x)
        assertTrue(y in 100..130)
    }

    @Test fun persistedFractionsRestore() {
        val (x, y) = fractionsToMargins(0.5f, 0.5f, 600, 120, 2400, 960)
        assertEquals(1200, x)
        assertEquals(480, y)
    }

    @Test fun callActionsAreDrivingSized() {
        // AAOS driving minimum is ~76dp; the pill offers 112dp per action with gaps.
        assertTrue(CallPillView.TOUCH_DP >= 96)
        assertTrue(CallPillView.GAP_DP >= 12)
    }

    @Test fun staleFractionsClampIntoSmallerParents() {
        // Saved on the 2400px panel, restored on a 1200px window: must not park off-screen.
        val (x, y) = fractionsToMargins(0.9f, 0.9f, 600, 120, 1200, 960)
        assertTrue(x <= 1200 - 600)
        assertTrue(y <= 960 - 120)
    }
}
