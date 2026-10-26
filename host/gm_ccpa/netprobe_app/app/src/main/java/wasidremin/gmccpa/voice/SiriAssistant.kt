package wasidremin.gmccpa.voice

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import wasidremin.gmccpa.ProbeLog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The steering-wheel voice button → Siri, by being the car's selected digital assistant.
 *
 * ## Why this is the only app-side route
 * On AAOS the wheel's push-to-talk arrives as `KEYCODE_VOICE_ASSIST` inside `CarInputService`, which
 * no Activity or MediaSession ever sees. A SHORT press goes to the active assistant through
 * `VoiceInteractionManagerService` (`SHOW_SOURCE_PUSH_TO_TALK`); a LONG press goes first to
 * projection receivers (system-only), then to a Bluetooth-connected phone, then to that same
 * assistant (source.android.com, automotive voice "Integration flows"). The driver does not keep the
 * iPhone on the car's Bluetooth (the car then takes the calls), so the assistant role is what is left.
 *
 * Ported from this repo's `host/CarlinkAndroid/.../voice/` (emulator-proven there:
 * `cmd car_service inject-key 231` → session `onShow(flags=0x23)` → Siri sent).
 *
 * ## Inert until the driver picks it
 * Declaring these services only makes the app a CANDIDATE in Settings ▸ Apps ▸ Default apps ▸
 * Digital assistant. Nothing here is bound until it is selected, and the Equinox has no adb to select
 * it any other way — so whether GM's Settings offers the choice at all is exactly what [probe] and the
 * Settings row find out. Selecting it REPLACES Google Assistant ("Hey Google", car voice commands),
 * and makes [SiriRecognitionService] the default speech recognizer, which refuses every request.
 */
class SiriAssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        ProbeLog.sub("voice").i("voice: selected as the car's assistant — the wheel button now sends Siri")
    }

    override fun onShutdown() {
        ProbeLog.sub("voice").i("voice: assistant role released")
        super.onShutdown()
    }
}

/** Hosts one [SiriAssistantSession] per button press. */
class SiriAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = SiriAssistantSession(this)
}

/**
 * One press. No window: the CarPlay screen IS Siri's UI. `onShow` is the button edge; the session is
 * released at once so the platform does not think an assistant is up.
 */
class SiriAssistantSession(ctx: Context) : VoiceInteractionSession(ctx) {
    override fun onCreate() {
        super.onCreate()
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        SiriTrigger.tap(context, "wheel button (assistant session, flags=0x${Integer.toHexString(showFlags)})")
        hide()
        finish()
    }
}

/**
 * Required companion: the platform rejects an assistant without a `recognitionService`, and
 * selecting the assistant makes this the default recognizer. Recognition happens on the iPhone, so
 * every request is refused at once with `ERROR_CLIENT` rather than left hanging.
 */
class SiriRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
        ProbeLog.sub("voice").w("voice: a SpeechRecognizer request was refused — this app is the assistant and recognition happens on the iPhone")
        runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onCancel(listener: Callback) {
        runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    }

    override fun onStopListening(listener: Callback) {
        runCatching { listener.error(SpeechRecognizer.ERROR_CLIENT) }
    }
}

/** Siri press + release toward the phone, from anywhere in the process. */
object SiriTrigger {
    private val log = ProbeLog.sub("voice")
    /** Off the main thread: OCBM commands share the event-channel lock with touch. */
    private val exec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "siri-trigger").apply { isDaemon = true }
    }
    /** Same hold as the sidebar button (CarPlayActivity.sendSiri). */
    private const val HOLD_MS = 120L

    fun tap(ctx: Context, source: String): Boolean {
        val adapter = wasidremin.gmccpa.ocbm.AdapterWifi.enabled(ctx)
        val up = wasidremin.gmccpa.ocbm.AdapterSession.sessionUp
        if (!adapter || !up) {
            log.w("voice: $source — no live CarPlay session (adapterWifi=$adapter sessionUp=$up), nothing sent")
            return false
        }
        exec.execute {
            val down = wasidremin.gmccpa.ocbm.AdapterSession.sendCommand(wasidremin.gmccpa.ocbm.Ocbm.CMD_SIRI_DOWN)
            log.i("voice: $source -> Siri press sent=$down")
            if (down) exec.schedule({
                wasidremin.gmccpa.ocbm.AdapterSession.sendCommand(wasidremin.gmccpa.ocbm.Ocbm.CMD_SIRI_UP)
            }, HOLD_MS, TimeUnit.MILLISECONDS)
        }
        return true
    }
}

/** Can the driver make this app the car's assistant, and is it? */
object AssistantRole {
    private val log = ProbeLog.sub("voice")
    private val PICKERS = listOf(Settings.ACTION_VOICE_INPUT_SETTINGS, "android.settings.MANAGE_DEFAULT_APPS_SETTINGS")

    data class State(
        /** RoleManager knows an assistant role (API 29+). */
        val roleAvailable: Boolean,
        /** This app holds it. */
        val held: Boolean,
        /** The Settings screen that offers the choice, if the car has one. */
        val picker: Intent?,
        /** Flattened component of the current assistant, or null when unreadable / none. */
        val current: String?,
    )

    fun state(ctx: Context): State {
        val rm = if (Build.VERSION.SDK_INT >= 29) ctx.getSystemService(RoleManager::class.java) else null
        val available = rm?.let { runCatching { it.isRoleAvailable(RoleManager.ROLE_ASSISTANT) }.getOrNull() } ?: false
        val held = rm?.let { runCatching { it.isRoleHeld(RoleManager.ROLE_ASSISTANT) }.getOrNull() } ?: false
        val picker = PICKERS.map { Intent(it) }.firstOrNull { intent ->
            runCatching { intent.resolveActivity(ctx.packageManager) != null }.getOrDefault(false)
        }
        // Readable on some builds, refused on others; either answer is useful in the log.
        val current = runCatching {
            Settings.Secure.getString(ctx.contentResolver, "voice_interaction_service")
                ?.takeIf { it.isNotEmpty() }
                ?: Settings.Secure.getString(ctx.contentResolver, "assistant")?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        return State(available, held, picker, current)
    }

    /** One line per process start: the answer to "does GM offer the choice". */
    fun probe(ctx: Context) {
        val s = state(ctx)
        val pickerName = s.picker?.let { i ->
            runCatching { i.resolveActivity(ctx.packageManager)?.flattenToShortString() }.getOrNull()
        }
        log.i("voice: assistant role available=${s.roleAvailable} heldByUs=${s.held} " +
            "current=${s.current?.let { ComponentName.unflattenFromString(it)?.packageName ?: it } ?: "?"} " +
            "picker=${pickerName ?: "none"}")
    }

    /** Settings subtitle. */
    fun statusText(ctx: Context): String {
        val s = state(ctx)
        return when {
            s.held -> "On. The wheel's voice button starts Siri. Google Assistant is off while this is selected."
            s.picker == null -> "This car has no assistant choice in Settings, so the wheel button stays with Google Assistant."
            else -> "Pick GM CCPA as the digital assistant to make the wheel's voice button start Siri. This turns off Google Assistant."
        }
    }

    /** Open the car's assistant picker. False when there is none. */
    fun openPicker(ctx: Context): Boolean {
        val i = state(ctx).picker ?: return false
        return runCatching {
            ctx.startActivity(Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            log.i("voice: opened the assistant picker (${i.action})")
        }.onFailure { log.w("voice: could not open the assistant picker: ${it.message}") }.isSuccess
    }
}
