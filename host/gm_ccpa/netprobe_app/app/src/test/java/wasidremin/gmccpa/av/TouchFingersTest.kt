package wasidremin.gmccpa.av

import org.junit.Assert.assertEquals
import org.junit.Test
import wasidremin.gmccpa.av.TouchFingers.Companion.ACTION_CANCEL
import wasidremin.gmccpa.av.TouchFingers.Companion.ACTION_DOWN
import wasidremin.gmccpa.av.TouchFingers.Companion.ACTION_MOVE
import wasidremin.gmccpa.av.TouchFingers.Companion.ACTION_POINTER_DOWN
import wasidremin.gmccpa.av.TouchFingers.Companion.ACTION_POINTER_UP
import wasidremin.gmccpa.av.TouchFingers.Companion.ACTION_UP
import wasidremin.gmccpa.av.TouchFingers.Companion.CANCEL
import wasidremin.gmccpa.av.TouchFingers.Companion.DOWN
import wasidremin.gmccpa.av.TouchFingers.Companion.MOVE
import wasidremin.gmccpa.av.TouchFingers.Companion.UP
import wasidremin.gmccpa.av.TouchFingers.Pointer
import wasidremin.gmccpa.av.TouchFingers.Touch

/** [TouchFingers] on gestures as Android reports them: pointer ids, view pixels. */
class TouchFingersTest {
    private fun p(id: Int, x: Float, y: Float = 100f) = Pointer(id, x, y)

    @Test fun aTapIsOneFingerDownAndUp() {
        val t = TouchFingers()
        assertEquals(listOf(Touch(0, DOWN, 10f, 100f)), t.on(ACTION_DOWN, 7, listOf(p(7, 10f))))
        assertEquals(listOf(Touch(0, UP, 10f, 100f)), t.on(ACTION_UP, 7, listOf(p(7, 10f))))
        assertEquals(0, t.down)
    }

    @Test fun aPinchSendsBothFingersEachUnderItsOwnNumber() {
        val t = TouchFingers()
        t.on(ACTION_DOWN, 0, listOf(p(0, 400f)))
        assertEquals(listOf(Touch(1, DOWN, 600f, 100f)), t.on(ACTION_POINTER_DOWN, 1, listOf(p(0, 400f), p(1, 600f))))
        // Fingers spread: one MOVE each, in the same event.
        assertEquals(listOf(Touch(0, MOVE, 350f, 100f), Touch(1, MOVE, 650f, 100f)),
            t.on(ACTION_MOVE, 0, listOf(p(0, 350f), p(1, 650f))))
        assertEquals(listOf(Touch(1, UP, 650f, 100f)), t.on(ACTION_POINTER_UP, 1, listOf(p(0, 350f), p(1, 650f))))
        assertEquals(listOf(Touch(0, UP, 350f, 100f)), t.on(ACTION_UP, 0, listOf(p(0, 350f))))
    }

    @Test fun theFirstFingerLiftingFirstEndsOnlyItsOwnContact() {
        val t = TouchFingers()
        t.on(ACTION_DOWN, 3, listOf(p(3, 100f)))
        t.on(ACTION_POINTER_DOWN, 4, listOf(p(3, 100f), p(4, 300f)))
        assertEquals(listOf(Touch(0, UP, 100f, 100f)), t.on(ACTION_POINTER_UP, 3, listOf(p(3, 100f), p(4, 300f))))
        // The survivor keeps its number: its moves are finger 1's, never teleported onto finger 0.
        assertEquals(listOf(Touch(1, MOVE, 320f, 100f)), t.on(ACTION_MOVE, 4, listOf(p(4, 320f))))
        // A new finger takes the free number.
        assertEquals(listOf(Touch(0, DOWN, 50f, 100f)), t.on(ACTION_POINTER_DOWN, 5, listOf(p(4, 320f), p(5, 50f))))
        assertEquals(2, t.down)
    }

    @Test fun aThirdFingerIsNotSentAtAll() {
        val t = TouchFingers()
        t.on(ACTION_DOWN, 0, listOf(p(0, 100f)))
        t.on(ACTION_POINTER_DOWN, 1, listOf(p(0, 100f), p(1, 200f)))
        val three = listOf(p(0, 100f), p(1, 200f), p(2, 300f))
        assertEquals(emptyList<Touch>(), t.on(ACTION_POINTER_DOWN, 2, three))
        assertEquals(listOf(Touch(0, MOVE, 100f, 100f), Touch(1, MOVE, 200f, 100f)), t.on(ACTION_MOVE, 0, three))
        assertEquals(emptyList<Touch>(), t.on(ACTION_POINTER_UP, 2, three))
    }

    @Test fun aCancelLiftsEveryHeldFingerAsACancel() {
        val t = TouchFingers()
        t.on(ACTION_DOWN, 0, listOf(p(0, 100f)))
        t.on(ACTION_POINTER_DOWN, 1, listOf(p(0, 100f), p(1, 200f)))
        assertEquals(listOf(Touch(0, CANCEL, 100f, 100f), Touch(1, CANCEL, 200f, 100f)),
            t.on(ACTION_CANCEL, 0, listOf(p(0, 100f), p(1, 200f))))
        assertEquals(0, t.down)
    }

    @Test fun aNewGestureStartsAfterAMissedUp() {
        val t = TouchFingers()
        t.on(ACTION_DOWN, 0, listOf(p(0, 100f)))
        // No UP arrived (the window lost focus); a fresh DOWN starts clean at finger 0.
        assertEquals(listOf(Touch(0, DOWN, 500f, 100f)), t.on(ACTION_DOWN, 9, listOf(p(9, 500f))))
        assertEquals(1, t.down)
    }

    @Test fun theActionNumbersAreAndroids() {
        assertEquals(android.view.MotionEvent.ACTION_DOWN, ACTION_DOWN)
        assertEquals(android.view.MotionEvent.ACTION_UP, ACTION_UP)
        assertEquals(android.view.MotionEvent.ACTION_MOVE, ACTION_MOVE)
        assertEquals(android.view.MotionEvent.ACTION_CANCEL, ACTION_CANCEL)
        assertEquals(android.view.MotionEvent.ACTION_POINTER_DOWN, ACTION_POINTER_DOWN)
        assertEquals(android.view.MotionEvent.ACTION_POINTER_UP, ACTION_POINTER_UP)
        assertEquals(wasidremin.gmccpa.ocbm.Ocbm.TOUCH_DOWN.toInt(), DOWN)
        assertEquals(wasidremin.gmccpa.ocbm.Ocbm.TOUCH_UP.toInt(), UP)
    }
}
