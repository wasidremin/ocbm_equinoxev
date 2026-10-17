package wasidremin.gmccpa.av

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
