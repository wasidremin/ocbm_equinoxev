package wasidremin.gmccpa.av

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
