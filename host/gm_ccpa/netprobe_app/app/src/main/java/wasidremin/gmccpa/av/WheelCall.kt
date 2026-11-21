package wasidremin.gmccpa.av

import android.view.KeyEvent
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.ocbm.AdapterSession
import wasidremin.gmccpa.ocbm.Ocbm

/**
 * The steering wheel's phone controls, for the CarPlay call on the iPhone.
 *
 * The Equinox EV's wheel has a phone toggle (up answers a ringing call; down declines, ends, or with
 * no call mutes the car) and a voice button whose press answers a ringing call (2026 owner manual,
 * "Steering Wheel Controls"). If GM hands the toggle's up to Android as `KEYCODE_CALL`,
 * `CarInputService.handleCallKey` keeps it from every app: it answers a ringing call Android's
 * Telecom knows about, else hands the key to a projection app holding the system-only
 * `CAR_PROJECTION`, else opens the car's dialer. The iPhone is not on the car's Bluetooth, so Telecom
 * knows of no call. Registering the CarPlay call with Telecom as a self-managed call does not get
 * past that: `TelecomServiceImpl.acceptRingingCallInternal` drops an accept aimed at a self-managed
 * call (android14-release), and Telecom would also take ring focus and set `MODE_RINGTONE`.
 *
 * On the car the toggle reaches no app at all: tried during two calls, it put no key on a window or
 * the media session and did not open the car's phone screen (owner and log, 2026-11-21, 262).
 *
 * What is left, and what this does:
 *  - The wheel's voice button reaches this app as the digital assistant (T14), and is the call's one
 *    button: a press answers a ringing call, as the manual says it does for a Bluetooth phone's call,
 *    and ends a live one (owner, 2026-11-21). During a call iOS ignores a Siri press: five on the car
 *    on 2026-11-21 opened no Siri audio and changed no mode, so ending the call costs nothing.
 *  - A phone key that does reach a window or the media session answers or ends the call. GM routes
 *    some wheel keys itself: the favourite switch arrives as `KEYCODE_F7` at the window from
 *    `com.gm.rhmi`, then a skip-next on the media session (car, 2026-11-08 to 11-14), so the toggle
 *    may also come by a route AOSP does not have.
 *
 * Every key that reaches the app is logged, so a drive shows which of these GM does.
 */
object WheelCall {
    private val log = ProbeLog.sub("call")

    /** The key whose down edge sent a call action, so its up edge is consumed too. */
    @Volatile private var actedKey = KeyEvent.KEYCODE_UNKNOWN

    /**
     * The telephony action [keyCode] asks for in call state [phoneAction], or null to leave the key
     * alone. Pure.
     */
    fun actionFor(keyCode: Int, phoneAction: Byte?): Byte? = when (keyCode) {
        // The Equinox EV's toggle up answers a ringing call and does nothing else (owner manual, 2026).
        KeyEvent.KEYCODE_CALL -> if (phoneAction == Ocbm.TEL_ANSWER) Ocbm.TEL_ANSWER else null
        // A headset's one button (issue #4). Before 261 a press during a call went on to the media
        // session's play/pause and reached the iPhone as a media key.
        KeyEvent.KEYCODE_HEADSETHOOK -> oneButtonAction(phoneAction)
        // Declines a ringing call and hangs up a live one, as the pill's End does.
        KeyEvent.KEYCODE_ENDCALL -> if (phoneAction != null) Ocbm.TEL_END else null
        else -> null
    }

    /**
     * What a call's one button does in call state [phoneAction]: answers a ringing call (a waiting one
     * too, ahead of the live call) and ends a live or dialling one; null with no call. Android's Telecom
     * gives a headset's button the same short press (`CallsManager.onMediaButton`). Pure.
     */
    fun oneButtonAction(phoneAction: Byte?): Byte? = phoneAction

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
     * The voice button's press, while there is a call: answers or ends it ([oneButtonAction]). True when
     * the press went to the call; false, with no call, leaves it to Siri.
     */
    fun voiceButtonActsOnCall(source: String): Boolean {
        val phone = CarPlayActivity.nowPlaying.phoneAction()
        val action = oneButtonAction(phone) ?: return false
        val sent = AdapterSession.sendTelephony(action)
        log.i("voice button ($source) while ${callWord(phone)} -> ${actionWord(action)} sent=$sent")
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
