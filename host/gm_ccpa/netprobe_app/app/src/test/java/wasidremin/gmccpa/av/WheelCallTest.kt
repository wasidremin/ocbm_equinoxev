package wasidremin.gmccpa.av

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import wasidremin.gmccpa.ocbm.Ocbm

class WheelCallTest {
    private val ringing = Ocbm.TEL_ANSWER
    private val inCall = Ocbm.TEL_END

    @Test fun theCallKeyAnswersOnlyWhileACallRings() {
        assertEquals(Ocbm.TEL_ANSWER, WheelCall.actionFor(KeyEvent.KEYCODE_CALL, ringing))
        assertNull(WheelCall.actionFor(KeyEvent.KEYCODE_CALL, inCall))
        assertNull(WheelCall.actionFor(KeyEvent.KEYCODE_CALL, null))
    }

    @Test fun headsetHookAnswersARingingCallAndIsPlayPauseOtherwise() {
        assertEquals(Ocbm.TEL_ANSWER, WheelCall.actionFor(KeyEvent.KEYCODE_HEADSETHOOK, ringing))
        assertNull(WheelCall.actionFor(KeyEvent.KEYCODE_HEADSETHOOK, null))
    }

    @Test fun endCallDeclinesOrHangsUp() {
        assertEquals(Ocbm.TEL_END, WheelCall.actionFor(KeyEvent.KEYCODE_ENDCALL, ringing))
        assertEquals(Ocbm.TEL_END, WheelCall.actionFor(KeyEvent.KEYCODE_ENDCALL, inCall))
        assertNull(WheelCall.actionFor(KeyEvent.KEYCODE_ENDCALL, null))
    }

    @Test fun mediaKeysAreNeverTheCall() {
        for (k in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_VOLUME_UP)) {
            assertNull(WheelCall.actionFor(k, ringing))
        }
    }
}
