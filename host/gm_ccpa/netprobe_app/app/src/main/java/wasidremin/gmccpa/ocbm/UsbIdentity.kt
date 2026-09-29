package wasidremin.gmccpa.ocbm

import android.content.Context

/**
 * Serial remembered from the last successful OCBM claim, and the one-time "tick Always" hint.
 * The serial is unreadable until USB permission is held, so it is stored here rather than at attach.
 */
object UsbIdentity {
    const val PREFS = "usb_identity"
    const val KEY_SERIAL = "serial"
    const val EXTRA_ALWAYS_HINT = "wasidremin.gmccpa.USB_ALWAYS_HINT"
    private const val KEY_HINT_SHOWN = "always_hint_shown"

    fun shouldShowAlwaysHint(ctx: Context): Boolean =
        !ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HINT_SHOWN, false)

    fun markAlwaysHintShown(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_HINT_SHOWN, true).apply()
    }
}
