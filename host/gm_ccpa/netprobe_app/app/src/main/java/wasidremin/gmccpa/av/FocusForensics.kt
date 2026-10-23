package wasidremin.gmccpa.av

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import wasidremin.gmccpa.ProbeLog
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Instrumentation only — answers "who took media focus" (doc 14 §14.10) without READ_LOGS.
 *
 * The 2026-10-23 drive lost focus permanently at a track boundary and again ~250 ms after each
 * sidebar play, each time next to a `com.android.car` pause+stop probe, and the app-only capture
 * could not name the taker. Three public or reflective views, all logged under `focus-who`:
 *
 * - every [AudioPlaybackConfiguration] change (usage, content type, player state; the system
 *   anonymises the uid for us, but a USAGE_MEDIA player starting at the LOSS is still visible);
 * - `CarMediaManager.getMediaSource(mode)` for playback and browse, plus a
 *   `MediaSourceChangedListener` registered through a [Proxy] so the instant CarMediaService
 *   switches source is a line of its own (a SecurityException is logged, not hidden);
 * - a snapshot on each focus change and each source-change probe, and again 1 s after a loss,
 *   when the new holder has had time to start.
 *
 * Nothing here changes behaviour: no focus requests, no transport commands. Binder and reflection
 * work runs on its own thread, never in the focus callback.
 */
object FocusForensics {
    private val log = ProbeLog.sub("focus-who")
    private val io = Executors.newSingleThreadScheduledExecutor { Thread(it, "focus-who").apply { isDaemon = true } }
    @Volatile private var app: Context? = null
    @Volatile private var car: Any? = null
    @Volatile private var carMedia: Any? = null
    private var lastPlayers = ""

    /** Idempotent; called when the media service comes up. */
    fun attach(ctx: Context) {
        if (app != null) return
        val c = ctx.applicationContext
        app = c
        val am = c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            am.registerAudioPlaybackCallback(object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                    // Main thread: a parse surprise must cost a log line, never the app.
                    val s = runCatching { describe(configs) }.getOrElse { "unreadable (${it.javaClass.simpleName})" }
                    if (s == lastPlayers) return
                    lastPlayers = s
                    log.i("players changed: $s")
                }
            }, Handler(Looper.getMainLooper()))
        }.onFailure { log.w("playback callback refused: ${it.javaClass.simpleName}: ${it.message}") }
        io.execute { connectCarMedia() }
    }

    /** Log the full picture now; with [followUpMs] > 0, once more after that delay. */
    fun snapshot(why: String, followUpMs: Long = 0L) {
        io.execute { dump(why) }
        if (followUpMs > 0) io.schedule({ dump("$why +${followUpMs}ms") }, followUpMs, TimeUnit.MILLISECONDS)
    }

    private fun dump(why: String) {
        val c = app ?: return
        val am = c.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val players = runCatching { describe(am?.activePlaybackConfigurations ?: emptyList()) }
            .getOrElse { "unreadable (${it.javaClass.simpleName})" }
        log.i("snapshot [$why] musicActive=${am?.isMusicActive} mode=${am?.mode} " +
            "source(playback)=${source(0)} source(browse)=${source(1)} players=$players")
    }

    /**
     * Compact form of each player. `toString()` is the only public view of player type and state
     * on an anonymised config; it is trimmed to the fields that identify a player.
     */
    private fun describe(configs: List<AudioPlaybackConfiguration>): String {
        if (configs.isEmpty()) return "none"
        return configs.joinToString(" | ") { p ->
            val a = p.audioAttributes
            val raw = p.toString()
            fun field(k: String) = Regex("$k:?\\s*([^ ]+)").find(raw)?.groupValues?.get(1)
            "usage=${a.usage} content=${a.contentType} piid=${field("piid") ?: "?"} " +
                "type=${field("type") ?: "?"} state=${field("state") ?: "?"} u/pid=${field("u/pid") ?: "?"}"
        }
    }

    private fun connectCarMedia() {
        val c = app ?: return
        runCatching {
            val carClass = Class.forName("android.car.Car")
            val k = carClass.getMethod("createCar", Context::class.java).invoke(null, c)
                ?: return log.w("car media: Car.createCar returned null — source not observable")
            car = k
            val name = runCatching { carClass.getField("CAR_MEDIA_SERVICE").get(null) as String }.getOrDefault("car_media")
            val mgr = carClass.getMethod("getCarManager", String::class.java).invoke(k, name)
                ?: return log.w("car media: no CarMediaManager — source not observable")
            carMedia = mgr
            log.i("car media: manager ${mgr.javaClass.name}; playback=${source(0)} browse=${source(1)}")
            listen(mgr, 0)
            listen(mgr, 1)
        }.onFailure { log.w("car media: connect failed ${it.javaClass.simpleName}: ${it.message}") }
    }

    /** `getMediaSource(int)` (API 30+), else the older no-argument form. */
    private fun source(mode: Int): String {
        val mgr = carMedia ?: return "n/a"
        return runCatching {
            val m = mgr.javaClass.methods.firstOrNull { it.name == "getMediaSource" && it.parameterTypes.size == 1 }
            val cn = if (m != null) m.invoke(mgr, mode)
            else mgr.javaClass.getMethod("getMediaSource").invoke(mgr)
            (cn as? ComponentName)?.flattenToShortString() ?: "null"
        }.getOrElse { e ->
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            "denied(${cause.javaClass.simpleName})"
        }
    }

    private fun listen(mgr: Any, mode: Int) {
        runCatching {
            val iface = Class.forName("android.car.media.CarMediaManager\$MediaSourceChangedListener")
            val add = mgr.javaClass.methods.firstOrNull {
                it.name == "addMediaSourceListener" && it.parameterTypes.size == 2
            } ?: return log.w("car media: no addMediaSourceListener(listener, mode)")
            val proxy = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { self, method, args ->
                when (method.name) {
                    "onMediaSourceChanged" -> {
                        val cn = args?.firstOrNull() as? ComponentName
                        log.i("car media source CHANGED (mode=$mode) -> ${cn?.flattenToShortString() ?: "null"}")
                        snapshot("source changed mode=$mode")
                        null
                    }
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.firstOrNull()
                    "toString" -> "FocusForensics.listener($mode)"
                    else -> null
                }
            }
            add.invoke(mgr, proxy, mode)
            log.i("car media: source listener registered (mode=$mode)")
        }.onFailure { e ->
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            log.w("car media: listener (mode=$mode) refused ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }
}
