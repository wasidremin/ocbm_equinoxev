package wasidremin.gmccpa.video

import android.content.Context
import org.json.JSONObject
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.ocbm.AdapterSession
import wasidremin.gmccpa.ocbm.Ocbm
import wasidremin.gmccpa.video.proxy.PhoneProxy
import wasidremin.gmccpa.video.proxy.TunnelDataSource
import wasidremin.gmccpa.video.proxy.Tunnels

/**
 * Route A (T34): the phone's video inside CarPlay, played on the centre screen while the car is parked. The
 * adapter offers it when the subscribe says `enablesVideoPlayback: true`, which it does only with this setting on
 * and firmware that says `"video":true` ([offered]); the wire is `docs/carplay/01_OCBM_PROTOCOL.md` "Route A".
 *
 * This object joins the parts enigy/carlink wires in its `CarlinkManager` (a04f4c0): the play queue
 * ([VideoPlayback]), the player ([ExoVideoEngine]), the phone's proxy offer ([PhoneProxy]) and the tunnels to it
 * ([Tunnels]). It hears the session from [AdapterSession], the gear from `VehicleStateWatcher`, and the phone's
 * records from the metadata seam.
 */
object RouteA {
    private val log = ProbeLog.sub("video")
    private const val PREFS = "route_a"
    private const val KEY_ENABLED = "enabled"

    /** Settings ▸ CarPlay ▸ Video from the iPhone while parked. On by default (owner, 2026-11-21: asked for it). */
    @Volatile var enabled: Boolean = true
        private set

    /** This session's subscribe offered video: the setting is on and the adapter's firmware carries route A. */
    @Volatile var offered: Boolean = false

    /** Whether the car is known to be parked: its gear reads PARK. Unknown is not parked. */
    @Volatile var parked: Boolean = false
        private set

    @Volatile private var sessionUp = false

    /** The phone's offer to carry the video over its own network, for this session. Its secrets are never logged. */
    @Volatile private var phoneProxy: PhoneProxy? = null

    /** The tunnels to the phone's proxy, through the adapter. */
    val tunnels = Tunnels(send = { op, id, data -> AdapterSession.client?.sendTunnel(op, id, data) == true })

    @Volatile var engine: ExoVideoEngine? = null
        private set

    @Volatile var playback: VideoPlayback? = null
        private set

    fun load(ctx: Context) {
        val app = ctx.applicationContext
        enabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        synchronized(this) {
            if (engine != null) return
            val e = ExoVideoEngine(app, mayPlay = { parked }, network = { network() })
            engine = e
            playback = VideoPlayback(
                send = { stream, json -> AdapterSession.client?.sendDataStream(stream, json) == true },
                engine = e,
                parked = { parked },
                announce = { allowed, status -> announce(allowed, status) },
                pausePhone = { AdapterSession.sendMediaButton(Ocbm.MEDIA_BTN_PAUSE) },
                viaPhone = { phoneProxy != null },
            )
        }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabled = on
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun statusText(): String = if (enabled) {
        "On. Video you send from the iPhone (Safari, for example) plays here while the car is in Park, over the " +
            "iPhone's own network. Needs the latest adapter firmware; takes effect at the next connect."
    } else {
        "Off. The iPhone is not offered the car's screen for video."
    }

    /** The phone's network for the player, while it has offered one; null otherwise. */
    private fun network(): TunnelDataSource.Factory? = phoneProxy?.let { TunnelDataSource.Factory(tunnels, it) }

    /** The gear: parked when it reads PARK. The phone hears each change while a session that offered video is up. */
    fun onGear(nowParked: Boolean) {
        if (parked == nowParked) return
        parked = nowParked
        if (sessionUp && offered) playback?.onParked(nowParked)
    }

    /** A keyed CarPlay session exists: the phone hears whether video may play now. */
    fun onSessionUp() {
        sessionUp = true
        if (offered) playback?.onParked(parked)
    }

    /** The session ended: the queue, the proxy offer and every tunnel go with it. */
    fun onSessionDown(why: String) {
        if (!sessionUp && phoneProxy == null && tunnels.openCount == 0) return
        sessionUp = false
        playback?.reset()
        phoneProxy = null
        tunnels.closeAll(why)
    }

    /** The phone's `setProxyParameters` params (the command plist's `params`). */
    fun onProxyParameters(params: Map<*, *>?) {
        val offer = PhoneProxy.from(params)
        phoneProxy = offer
        log.i(if (offer != null) "the phone offers its network for video (its proxy, port ${offer.port})" else "the phone's proxy offer isn't usable")
    }

    /** One `{"kind":"dataStream"}` record from the adapter. */
    fun onDataStream(record: JSONObject) {
        val p = playback ?: return
        if (!offered) {
            log.w("a DataStream record in a session that didn't offer video — ignored")
            return
        }
        p.onRecord(record)
    }

    /** iOS asked for the car's video screen (`requestUI` with `videoplayback:`). */
    fun showRequested(): Boolean = playback?.showRequested() == true

    /** One `CH_TUNNEL` message: `op` over `[id][data]`. The OCBM read thread: hands off only. */
    fun onTunnel(op: Int, payload: ByteArray) {
        // A tunnel's opening and end are logged as they arrive, by id: whether the adapter's answer came, and when.
        if (op.toByte() != Ocbm.TUNNEL_DATA) {
            val what = if (op.toByte() == Ocbm.TUNNEL_OPENED) "opened" else "closed by the adapter"
            log.i("tunnel ${payload.firstOrNull()?.toInt()?.and(0xFF)}: $what (${tunnels.openCount} open here)")
        }
        tunnels.onMessage(op, payload)
    }

    /**
     * Bench (`--es run video_net_test`): fetch [url] over the phone's network exactly as the player would (tunnel,
     * the proxy's TLS-PSK, CONNECT, the origin's TLS), and say how it went in numbers. Blocks: not the main thread.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun netTest(url: String): String {
        val net = network() ?: return "no phone network offered in this session"
        val t0 = System.nanoTime()
        val src = net.createDataSource()
        return try {
            val declared = src.open(androidx.media3.datasource.DataSpec(android.net.Uri.parse(url)))
            var total = 0L
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = src.read(buf, 0, buf.size)
                if (n < 0) break
                total += n
            }
            "fetched $total B (declared $declared) in ${(System.nanoTime() - t0) / 1_000_000} ms over the phone's network"
        } catch (e: java.io.IOException) {
            "failed after ${(System.nanoTime() - t0) / 1_000_000} ms: ${e.javaClass.simpleName}" +
                (e.cause?.let { " (${it.javaClass.simpleName})" } ?: "")
        } finally {
            runCatching { src.close() }
        }
    }

    private fun announce(allowed: Boolean, status: VideoPlayback.Status) {
        val sent = AdapterSession.client?.sendVideoAllowed(allowed, status.wire) == true
        log.i("the phone is told video ${if (allowed) "may play" else "may not play"} ($status): sent=$sent")
    }

    /** The `requestUI` URL with which iOS asks for the video screen. */
    const val VIDEO_PLAYBACK_URL = "videoplayback:"

    /** How the adapter starts a DataStream record (`receiver::playback::record`). */
    private val DATA_STREAM_PREFIX = "{\"kind\":\"dataStream\"".toByteArray(Charsets.UTF_8)

    /** Whether a `META_JSON` record is a DataStream one, before decoding it. Pure. */
    fun isDataStreamRecord(json: ByteArray): Boolean =
        json.size >= DATA_STREAM_PREFIX.size && DATA_STREAM_PREFIX.indices.all { json[it] == DATA_STREAM_PREFIX[it] }
}
