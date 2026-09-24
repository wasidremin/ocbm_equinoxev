package wasidremin.gmccpa.ocbm

import android.content.Context
import android.view.Surface
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.av.AacPlayer
import wasidremin.gmccpa.av.BPlist
import wasidremin.gmccpa.av.CarPlayActivity
import wasidremin.gmccpa.av.HevcRenderer
import wasidremin.gmccpa.av.MetadataSeam
import wasidremin.gmccpa.av.MicUplink
import wasidremin.gmccpa.av.VoiceRouter
import wasidremin.gmccpa.ocbm.seam.SeamPipe

/**
 * USB renderer for the adapter-Wi-Fi role.
 *
 * Audio starts when the lanes arm, before any Surface exists: an undrained audio pipe blocks the
 * USB read thread. Video waits for a Surface. Touch, keyframes, and the microphone go back out on
 * `CH_INPUT` / `CH_MIC`. The players are the same ones the Silverado path uses, so Call, Siri, and
 * Navigation stay on their GM volume groups.
 */
object AdapterSession {
    private val log = ProbeLog.sub("adapter")

    @Volatile var client: OcbmClient? = null
        private set

    /** True from the first video key until the lanes are retired. */
    @Volatile var sessionUp: Boolean = false
        private set

    /** Fired on the main thread once a keyed session exists. [MainActivity] brings the screen up. */
    var onKeyed: (() -> Unit)? = null

    /** Fired on the main thread when the lanes go away. */
    var onRetired: (() -> Unit)? = null

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val epoch = java.util.concurrent.atomic.AtomicInteger()

    private var lanes: OcbmAvLanes? = null
    private var player: AacPlayer? = null
    private var router: VoiceRouter? = null
    private var mic: MicUplink? = null

    private var videoPipe: SeamPipe? = null
    private var renderer: HevcRenderer? = null

    fun bind(c: OcbmClient, ctx: Context) {
        quietStop()
        val e = epoch.incrementAndGet()
        client = c
        c.adapterMode = true
        c.onMetadata = { marker, payload -> onMeta(marker, payload) }
        c.onUplinkGate = { on, rate, ch -> mic?.onGate(on, rate, ch) }
        c.onLanesArmed = { armed -> if (epoch.get() == e) startAudio(ctx.applicationContext, armed) }
        c.onLanesRetired = { retired -> if (epoch.get() == e) stopAudio(retired) }
        c.onSessionKeyed = keyed@{
            if (epoch.get() != e) return@keyed
            sessionUp = true
            main.post { if (epoch.get() == e) onKeyed?.invoke() }
        }
        val uplink = MicUplink()
        uplink.directSink = { pcm, len -> c.sendMicPcm(pcm, len) }
        uplink.start()
        mic = uplink
    }

    fun sendTouch(phase: Byte, nx: Float, ny: Float): Boolean =
        client?.sendTouch(phase, nx, ny) == true

    fun sendMediaButton(index: Byte): Boolean =
        client?.sendMediaButton(index) == true

    fun sendCommand(cmd: Byte): Boolean =
        client?.sendCommand(cmd) == true

    fun sendNav(nav: Byte): Boolean =
        client?.sendNav(nav) == true

    fun requestKeyframe(): Boolean = client?.requestKeyframe() == true

    @Synchronized
    fun attachVideo(surface: Surface) {
        val l = lanes ?: return
        if (renderer != null) return
        if (!surface.isValid) return
        val pipe = SeamPipe(8 * 1024 * 1024, 64)
        val r = HevcRenderer(wasidremin.gmccpa.VideoFrame.width, wasidremin.gmccpa.VideoFrame.height, surface) {
            requestKeyframe()
        }
        r.start()
        l.runConsumer("cp-video", pipe) { r.consume(it) }
        l.videoSeam.attach(pipe)
        videoPipe = pipe
        renderer = r
        requestKeyframe()
        log.i("video epoch open ${wasidremin.gmccpa.VideoFrame.width}x${wasidremin.gmccpa.VideoFrame.height}")
    }

    @Synchronized
    fun detachVideo(why: String) {
        val pipe = videoPipe
        val r = renderer
        videoPipe = null
        renderer = null
        lanes?.videoSeam?.attach(null)
        pipe?.close()
        r?.stop(why)
        log.i("video epoch closed — $why")
    }

    /** Drop renderers and the mic. Does not notify [onRetired] — the caller owns the screen. */
    fun release() {
        quietStop()
        client = null
    }

    private fun quietStop() {
        detachVideo("adapter session released")
        val m = mic
        mic = null
        m?.stop("adapter session released")
        synchronized(this) {
            player?.stop("adapter session released")
            player = null
            router?.stop("adapter session released")
            router = null
            lanes = null
            sessionUp = false
        }
    }

    private fun startAudio(ctx: Context, armed: OcbmAvLanes) {
        synchronized(this) {
            player?.stop("replaced by a new A/V generation")
            player = null
            router?.stop("replaced by a new A/V generation")
            router = null
            lanes = armed
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            val p = AacPlayer(am).also { it.start(); it.prime() }
            val voice = VoiceRouter(
                ctx,
                onDuck = { ducked -> p.setVoiceDucked(ducked) },
                onAssistant = { speaking -> p.setAssistantSpeaking(speaking) },
            ).also { it.start() }
            player = p
            router = voice
            armed.runConsumer("cp-audio", armed.mediaPipe) { p.consume(it) }
            armed.runConsumer("cp-voice", armed.voicePipe) { voice.consume(it) }
            log.i("audio consumers armed")
        }
    }

    private fun stopAudio(retired: OcbmAvLanes?) {
        val e = epoch.get()
        var notify = false
        synchronized(this) {
            if (retired != null && lanes !== retired) return
            player?.stop("A/V lanes retired")
            player = null
            router?.stop("A/V lanes retired")
            router = null
            lanes = null
            if (sessionUp) {
                sessionUp = false
                notify = true
            }
        }
        if (notify) main.post { if (epoch.get() == e) onRetired?.invoke() }
    }

    private fun onMeta(marker: Int, payload: ByteArray) {
        if (marker == MetadataSeam.META_CMD) {
            val root = BPlist.parse(payload) ?: return
            when (BPlist.str(root, "type")) {
                "modesChanged" -> onModes(root, payload.size)
                "requestViewArea" -> CarPlayActivity.onAdapterViewArea(root)
            }
        } else {
            CarPlayActivity.nowPlaying.dispatch(marker, payload)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun onModes(root: Any?, size: Int) {
        val params = (root as? Map<String, Any?>)?.get("params") as? Map<String, Any?> ?: return
        val states = params["appStates"] as? List<Any?> ?: return
        var speechMode = -1L
        var speechEntity = 0L
        var phoneEntity = 0L
        var turnsEntity = 0L
        for (s in states) {
            val d = s as? Map<String, Any?> ?: continue
            val id = d["appStateID"] as? Long ?: continue
            val ent = d["entity"] as? Long ?: 0L
            when (id) {
                1L -> { speechEntity = ent; speechMode = d["speechMode"] as? Long ?: -1L }
                2L -> phoneEntity = ent
                3L -> turnsEntity = ent
            }
        }
        router?.onModes(speechMode, speechEntity, phoneEntity, turnsEntity, size)
    }
}
