package wasidremin.gmccpa

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The connect-failure recovery rule: while FAILED (the phone-didn't-connect state) the status
 * strip offers Restart, not Done. Pure decision logic — no views.
 */
class UiStripTest {
    @Test fun failedOffersRestart() {
        assertEquals(StripAction.RESTART, stripActionFor(false, LinkState.FAILED))
    }

    @Test fun liveSessionWinsOverFailed() {
        assertEquals(StripAction.BACK, stripActionFor(true, LinkState.FAILED))
        assertEquals(StripAction.BACK, stripActionFor(true, LinkState.LIVE))
    }

    @Test fun idleStatesOfferDone() {
        for (s in listOf(LinkState.IDLE, LinkState.STOPPED, LinkState.SEARCHING,
            LinkState.WAITING, LinkState.PHONE_DETECTED, LinkState.PAIRING,
            LinkState.STARTING, LinkState.RESTARTING, LinkState.CLAIMING, LinkState.LIVE)) {
            assertEquals("state=$s", StripAction.DONE, stripActionFor(false, s))
        }
    }
}
