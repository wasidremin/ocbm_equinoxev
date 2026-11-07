@file:Suppress("MatchingDeclarationName") // the lock plus the helpers both tests share

package wasidremin.gmccpa.calltest

// Ported from enigy/carlink `audio/` (Unlicense).

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps the parked audio tests ([EchoTest], [VoiceTest]) from running over each other. */
internal object AudioTestLock {
    private val busy = AtomicBoolean(false)

    fun tryLock(): Boolean = busy.compareAndSet(false, true)

    fun unlock() = busy.set(false)
}

/** Attributes of the call track: USAGE_VOICE_COMMUNICATION speech. */
internal fun voiceCallAttributes(): AudioAttributes =
    AudioAttributes
        .Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

/**
 * Attach AEC, NS and AGC to [sessionId] like the call mic does, adding each created effect to
 * [into] (the caller releases them). Returns one "name=state:implementation" entry per effect.
 */
@Suppress("TooGenericExceptionCaught") // a vendor effect that throws is reported, never fatal
internal fun attachVoiceEffects(
    sessionId: Int,
    into: MutableList<AudioEffect>,
): String {
    val parts = mutableListOf<String>()

    fun add(
        name: String,
        available: Boolean,
        create: () -> AudioEffect?,
    ) {
        if (!available) {
            parts += "$name=unavailable"
            return
        }
        try {
            val e = create()
            if (e == null) {
                parts += "$name=null"
            } else {
                e.enabled = true
                into += e
                parts += "$name=${if (e.enabled) "on" else "OFF"}:${AudioRouteInfo.effect(e.descriptor)}"
            }
        } catch (ex: Exception) {
            parts += "$name=failed(${ex.message})"
        }
    }
    add("aec", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(sessionId) }
    add("ns", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(sessionId) }
    add("agc", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(sessionId) }
    return parts.joinToString(" ")
}

/**
 * One way a call could record the driver, as the parked tests ([EchoTest], [VoiceTest]) try it.
 * [appEffects] attaches Android's AEC/NS/AGC to the record session; [commMode] puts the platform
 * in MODE_IN_COMMUNICATION first; [device] pins the record to an input (GM's Voice_Uplink bus).
 */
internal data class CallPath(
    val label: String,
    val source: Int,
    val appEffects: Boolean,
    val commMode: Boolean,
    val device: AudioDeviceInfo? = null,
) {
    val isRawMic: Boolean get() = source == MediaRecorder.AudioSource.MIC
}

/**
 * The call paths worth comparing on this head unit, the one calls use now marked " (calls now)":
 *  - "Voice communication": VOICE_COMMUNICATION, no app effects — what [wasidremin.gmccpa.av.MicUplink]
 *    records for a call while this app does not hold the assistant role.
 *  - "Voice recognition": VOICE_RECOGNITION, no app effects — what it records while the role is held
 *    (Android silences the assistant's VOICE_COMMUNICATION, `host/gm_ccpa/docs/13_AUDIO_ROUTING.md` §4).
 *    Whether the car still cancels echo on this source is the open question these tests answer.
 *  - "Voice recognition + Android echo canceller": the same with AEC/NS/AGC attached — the fix to
 *    reach for if the plain one echoes.
 *  - "Car call path": GM's Voice_Uplink bus, when the car lists it (enigy's truck: no echo and the
 *    voice ~27 dB louder than the built-in mic, 2026-10-20).
 *  - "Raw mic": AudioSource.MIC, no effects; the baseline nothing cancels.
 * Ported from enigy/carlink `audio/AudioTestSupport.kt` (Unlicense), with this app's paths.
 */
internal fun callPathsToCompare(am: AudioManager, assistantHeld: Boolean): List<CallPath> {
    val vc = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    val vr = MediaRecorder.AudioSource.VOICE_RECOGNITION
    val bus = AudioRouteInfo.uplinkBus(am)
    val now = " (calls now)"
    return listOfNotNull(
        CallPath("Voice communication" + if (!assistantHeld) now else "", vc, appEffects = false, commMode = false),
        CallPath("Voice recognition" + if (assistantHeld) now else "", vr, appEffects = false, commMode = false),
        CallPath("Voice recognition + Android echo canceller", vr, appEffects = true, commMode = false),
        bus?.let { CallPath("Car call path", vc, appEffects = false, commMode = false, device = it) },
        CallPath("Raw mic", MediaRecorder.AudioSource.MIC, appEffects = false, commMode = false),
    )
}
