package wasidremin.gmccpa.ocbm

import android.content.Context
import android.view.Surface
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.av.AacPlayer
import wasidremin.gmccpa.av.BPlist
import wasidremin.gmccpa.av.CarPlayActivity
import wasidremin.gmccpa.av.CarPlayMediaBrowserService
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
    /** A Surface that arrived before [lanes]. [startAudio] attaches it once the lanes exist. */
    private var pendingSurface: Surface? = null
    /** Last `modes resources` line, so a repeated modesChanged does not log the same ownership. */
    private var lastResources: String? = null

    fun bind(c: OcbmClient, ctx: Context) {
        quietStop()
        val app = ctx.applicationContext
        // CONNECTING, before any phone traffic. The original shows buffering and starts its
        // media service here, seconds before the first stream asks for focus. Doing both at
        // lane-arm time let focus land while the car still had FM as the source.
        wasidremin.gmccpa.AudioRoute.load(app)
        CarPlayMediaBrowserService.announcePreparing()
        CarPlayMediaBrowserService.ensureStarted(app)
        val e = epoch.incrementAndGet()
        client = c
        c.adapterMode = true
        c.onMetadata = { marker, payload -> if (epoch.get() == e) onMeta(marker, payload) }
        c.onUplinkGate = { on, rate, ch -> if (epoch.get() == e) mic?.onGate(on, rate, ch) }
        c.onLanesArmed = { armed -> if (epoch.get() == e) startAudio(app, armed) }
        c.onLanesRetired = { retired -> if (epoch.get() == e) stopAudio(retired) }
        c.onSessionKeyed = keyed@{
            if (epoch.get() != e) return@keyed
            sessionUp = true
            // Before the activity's onKeyed. publish() drops every update until this runs,
            // and the activity launch is a main-thread post that can lose the first title.
            CarPlayMediaBrowserService.onSessionUp()
            log.i("session keyed — media card session up")
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
        if (!surface.isValid) {
            log.i("video surface ignored — not valid")
            return
        }
        val l = lanes
        if (l == null) {
            pendingSurface = surface
            log.i("video surface pending — lanes not armed")
            return
        }
        if (renderer != null) return
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
        pendingSurface = null
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
        val old = client
        old?.onMetadata = null
        old?.onUplinkGate = null
        old?.onLanesArmed = null
        old?.onLanesRetired = null
        old?.onSessionKeyed = null
        quietStop()
        client = null
        lastResources = null
        CarPlayMediaBrowserService.onSessionDown()
        CarPlayActivity.nowPlaying.clear()
        log.i("adapter released — media card cleared")
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
        var resume: Surface? = null
        synchronized(this) {
            player?.stop("replaced by a new A/V generation")
            player = null
            router?.stop("replaced by a new A/V generation")
            router = null
            lanes = armed
            resume = pendingSurface?.takeIf { it.isValid }
            var p: AacPlayer? = null
            var voice: VoiceRouter? = null
            try {
                // Idempotent with [bind]. Focus still waits for [AacPlayer.onStreamStart].
                CarPlayMediaBrowserService.ensureStarted(ctx)
                CarPlayMediaBrowserService.announcePreparing()
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                val created = AacPlayer(am)
                p = created
                created.start()
                if (!wasidremin.gmccpa.AudioRoute.bluetooth) created.prime()
                val routed = VoiceRouter(
                    ctx,
                    onDuck = { ducked -> created.setVoiceDucked(ducked) },
                    onAssistant = { speaking -> created.setAssistantSpeaking(speaking) },
                )
                voice = routed
                routed.start()
            } catch (t: Throwable) {
                log.e("audio start failed (${t.javaClass.simpleName}: ${t.message}) — pipes still drain")
            }
            player = p
            router = voice
            val audio = p
            val nav = voice
            if (audio != null) armed.runConsumer("cp-audio", armed.mediaPipe) { audio.consume(it) }
            else armed.runConsumer("cp-audio", armed.mediaPipe) { drainSeam(it) }
            if (nav != null) armed.runConsumer("cp-voice", armed.voicePipe) { nav.consume(it) }
            else armed.runConsumer("cp-voice", armed.voicePipe) { drainSeam(it) }
            log.i("audio consumers armed")
        }
        val surface = synchronized(this) {
            pendingSurface?.takeIf { it.isValid && it === resume && lanes != null }
        }
        if (surface != null) {
            log.i("video surface was waiting — attaching now that lanes are armed")
            attachVideo(surface)
        }
    }

    /** Keeps an undrained seam from blocking the USB read thread when a player failed to start. */
    private fun drainSeam(ins: java.io.InputStream) {
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = try { ins.read(buf) } catch (_: Exception) { break }
            if (n <= 0) break
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
        if (notify) {
            lastResources = null
            CarPlayMediaBrowserService.onSessionDown()
            CarPlayActivity.nowPlaying.clear()
            log.i("lanes retired — media card cleared")
            main.post { if (epoch.get() == e) onRetired?.invoke() }
        }
    }

    private fun onMeta(marker: Int, payload: ByteArray) {
        if (marker == MetadataSeam.META_CMD) {
            val root = BPlist.parse(payload) ?: return
            when (BPlist.str(root, "type")) {
                "modesChanged" -> onModes(root, payload.size)
                "requestViewArea" -> CarPlayActivity.onAdapterViewArea(root)
                "requestUI" -> CarPlayActivity.openSettingsFromIcon()
            }
        } else {
            CarPlayActivity.nowPlaying.dispatch(marker, payload)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun onModes(root: Any?, size: Int) {
        val params = (root as? Map<String, Any?>)?.get("params") as? Map<String, Any?> ?: return
        logResources(params["resources"])
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

    /**
     * `modesChanged` `resources[]` says who owns MainScreen (resourceID 1) and MainAudio
     * (resourceID 2). Logged only when the line changes. The voice path still reads `appStates`.
     */
    private fun logResources(raw: Any?) {
        val list = raw as? List<*>
        val text = when {
            list == null -> "(absent)"
            list.isEmpty() -> "(empty)"
            else -> list.joinToString("; ") { item ->
                val d = item as? Map<*, *> ?: return@joinToString "$item"
                val entities = d.entries
                    .filter { (k, _) -> k.toString().contains("entity", ignoreCase = true) }
                    .joinToString(" ") { (k, v) -> "$k=$v" }
                "resourceID=${d["resourceID"]} transferType=${d["transferType"]} transferPriority=${d["transferPriority"]}${if (entities.isEmpty()) "" else " $entities"}"
            }
        }
        if (text == lastResources) return
        lastResources = text
        log.i("modes resources: $text")
    }
}
