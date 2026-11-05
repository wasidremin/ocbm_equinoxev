package wasidremin.gmccpa.av

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [MapsWatch] switches the cluster only after two reads agree, so a glance at another app does nothing. */
class MapsAgreementTest {
    @Test fun twoReadsInARowSwitchEitherWay() {
        val a = MapsAgreement(2)
        assertFalse(a.offer(true))
        assertTrue(a.offer(true))
        assertTrue(a.state)
        assertFalse(a.offer(false))
        assertTrue(a.offer(false))
        assertFalse(a.state)
    }

    @Test fun aSingleOddReadChangesNothing() {
        val a = MapsAgreement(2)
        a.offer(true); a.offer(true)
        assertFalse(a.offer(false))
        assertFalse(a.offer(true))
        assertFalse(a.offer(false))
        assertTrue(a.state)
    }

    @Test fun aLongerRunCanBeAskedFor() {
        val a = MapsAgreement(2)
        a.force(true)
        repeat(7) { assertFalse(a.offer(false, 8)) }
        assertTrue(a.offer(false, 8))
        assertFalse(a.state)
    }

    @Test fun losingTheCardWithNothingTouchedTakesTheLongRun() {
        // 2026-11-05: Maps held from 12:19:26, card lost at 12:21:27 with no touch since 12:19:24.
        val touched = 1_000L
        val cardSeen = 120_000L
        assertEquals(MapsWatch.UNPROMPTED_AGREE, readsToAgree(true, false, touched, cardSeen))
        // A touch after the card was last seen, or just before it, is a possible app switch.
        assertEquals(MapsWatch.AGREE, readsToAgree(true, false, cardSeen + 500, cardSeen))
        assertEquals(MapsWatch.AGREE, readsToAgree(true, false, cardSeen - 2_000, cardSeen))
        // Finding the card, or losing it while Maps is not in front, keeps the short run.
        assertEquals(MapsWatch.AGREE, readsToAgree(false, true, touched, cardSeen))
        assertEquals(MapsWatch.AGREE, readsToAgree(false, false, touched, cardSeen))
        assertEquals(MapsWatch.AGREE, readsToAgree(true, true, touched, cardSeen))
    }

    @Test fun aKnownAnswerSwitchesAtOnceAndCountsAsAgreed() {
        val a = MapsAgreement(2)
        assertTrue(a.force(true))
        assertFalse(a.force(true))
        assertFalse(a.offer(true))
        assertTrue(a.state)
        assertTrue(a.force(false))
        assertFalse(a.state)
    }
}
