package wasidremin.gmccpa

import android.content.Context

/**
 * Where CarPlay music is heard.
 *
 * The earlier Carlink app called this Audio Source. Adapter (the default) plays the CarPlay
 * stream through this app and asks the head unit to select us as the media source. Bluetooth
 * leaves the car stereo on the phone and keeps this app's media session inactive, which is
 * what that app did when `audioTransferMode` was true. There is no dongle command to send:
 * the CPC200 `UseBoxTransAudio` opcode does not exist on this adapter.
 *
 * Stored as a boolean, false = Adapter, matching that app's `audio_source_bluetooth` key so
 * the meaning does not drift if the control is reordered.
 */
object AudioRoute {
    private const val PREFS = "audio_route"
    private const val KEY_BLUETOOTH = "audio_source_bluetooth"

    /** True when the car stereo should play the phone over Bluetooth. */
    @Volatile var bluetooth: Boolean = false
        private set

    fun load(ctx: Context) {
        bluetooth = prefs(ctx).getBoolean(KEY_BLUETOOTH, false)
    }

    fun set(ctx: Context, bluetooth: Boolean) {
        this.bluetooth = bluetooth
        prefs(ctx).edit().putBoolean(KEY_BLUETOOTH, bluetooth).apply()
        wasidremin.gmccpa.av.CarPlayMediaBrowserService.applyRoute()
        wasidremin.gmccpa.av.AacPlayer.applyAudioRoute()
    }

    fun label(): String = if (bluetooth) "Bluetooth" else "Adapter"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
