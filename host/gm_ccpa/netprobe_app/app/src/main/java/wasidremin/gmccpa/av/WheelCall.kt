package wasidremin.gmccpa.av

import android.view.KeyEvent
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.ocbm.AdapterSession
import wasidremin.gmccpa.ocbm.Ocbm

/**
 * The steering wheel's phone controls, for the CarPlay call on the iPhone.
 *
 * The Equinox's answer/end rocker reaches stock Android 14 as `KEYCODE_CALL`, and
 * `CarInputService.handleCallKey` keeps that key from every app: it answers a ringing call Android's
 * Telecom knows about, else hands the key to a projection app holding the system-only
 * `CAR_PROJECTION`, else opens the car's dialer. The iPhone is not on the car's Bluetooth, so Telecom
 * knows of no call. Registering the CarPlay call with Telecom as a self-managed call does not get
 * past that: `TelecomServiceImpl.acceptRingingCallInternal` drops an accept aimed at a self-managed
 * call (android14-release), and Telecom would also take ring focus and set `MODE_RINGTONE`.
 *
 * What is left, and what this does:
 *  - The wheel's voice button reaches this app as the digital assistant (T14). While a call rings it
 *    answers instead of starting Siri, the way GM's push-to-talk answers a Bluetooth phone's call.
 *  - A phone key that does reach a window or the media session answers or ends the call. GM sends
 *    media keys through its own `com.gm.rhmi`, so a GM build may route these keys differently too.
 *
 * Every key that reaches the app is logged, so a drive shows which of these GM does.
 */
object WheelCall {
    private val log = ProbeLog.sub("call")

    /** The key whose down edge sent a call action, so its up edge is consumed too. */
    @Volatile private var actedKey = KeyEvent.KEYCODE_UNKNOWN

    /** The telephony action [keyCode] asks for in call state [phoneAction], or null to leave the key alone. */
    fun actionFor(keyCode: Int, phoneAction: Byte?): Byte? = when (keyCode) {
        KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_HEADSETHOOK ->
            if (phoneAction == Ocbm.TEL_ANSWER) Ocbm.TEL_ANSWER else null
        // Declines a ringing call and hangs up a live one, as the pill's End does.
        KeyEvent.KEYCODE_ENDCALL -> if (phoneAction != null) Ocbm.TEL_END else null
        else -> null
    }

    /**
     * A key reached [where]. Logs its down edge and, for a phone key that means something in the
     * current call state, sends the call action. True when the key was used for the call; the caller
     * then consumes it.
     */
    fun onKey(ev: KeyEvent, where: String): Boolean {
        if (ev.action == KeyEvent.ACTION_UP) {
            if (ev.keyCode != actedKey) return false
            actedKey = KeyEvent.KEYCODE_UNKNOWN
            return true
        }
        if (ev.action != KeyEvent.ACTION_DOWN || ev.repeatCount != 0) return ev.keyCode == actedKey
        val phone = CarPlayActivity.nowPlaying.phoneAction()
        val action = actionFor(ev.keyCode, phone)
        val name = KeyEvent.keyCodeToString(ev.keyCode)
        if (action == null) {
            log.i("key $name at $where with ${callWord(phone)} — not a call action (device ${ev.deviceId}, source 0x${Integer.toHexString(ev.source)})")
            return false
        }
        actedKey = ev.keyCode
        val sent = AdapterSession.sendTelephony(action)
        log.i("key $name at $where while ${callWord(phone)} -> ${actionWord(action)} sent=$sent")
        return true
    }

    /**
     * The voice button's press while a call rings. True when it answered; false leaves the press to
     * Siri.
     */
    fun voiceButtonAnswers(source: String): Boolean {
        if (CarPlayActivity.nowPlaying.phoneAction() != Ocbm.TEL_ANSWER) return false
        val sent = AdapterSession.sendTelephony(Ocbm.TEL_ANSWER)
        log.i("voice button ($source) while a call rings -> answer sent=$sent")
        return true
    }

    /** Logs the CarPlay screen losing or regaining focus during a call: the car's dialer coming up shows here. */
    fun noteScreenFocus(hasFocus: Boolean, what: String) {
        val phone = CarPlayActivity.nowPlaying.phoneAction() ?: return
        log.i("CarPlay screen ${if (hasFocus) "regained" else "lost"} $what while ${callWord(phone)}")
    }

    private fun callWord(phone: Byte?): String = when (phone) {
        Ocbm.TEL_ANSWER -> "a call rings"
        Ocbm.TEL_END -> "a call is up"
        else -> "no call"
    }

    private fun actionWord(action: Byte): String = when (action) {
        Ocbm.TEL_ANSWER -> "answer"
        Ocbm.TEL_END -> "end"
        else -> "telephony $action"
    }
}
