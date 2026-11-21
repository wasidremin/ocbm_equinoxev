package wasidremin.gmccpa.ocbm

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import org.json.JSONObject
import wasidremin.gmccpa.ProbeLog
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The adapter link over the car's hotspot (T15, 2026-11-21), so the app never asks Android for the USB
 * device and the driver never taps Allow. USB stays the fallback, and the road the key travels once.
 *
 * On with Settings ▸ Advanced ▸ Join the car's hotspot (with the Vehicle hotspot's name and password):
 *  - connected over USB to firmware whose `MGMT_INFO` carries `"wifi_link"`, the app gives the adapter
 *    this head unit's link key once (`MGMT_WIFI_LINK`, [OcbwLink.provisionBody]). The adapter uses it
 *    from its next start; the USB session carries on;
 *  - once the adapter has said it holds that key ([wifiFirst]), every start looks for it on the
 *    hotspot first ([connect]): the address that answered last, then mDNS (`_ocbm._tcp`, TXT `id=`),
 *    then every address on the hotspot's /24 on port 7020. The adapter's keeper (`radio_hal.sh
 *    sta_keep`) keeps it on the hotspot from boot. Not reached: the usual USB claim, with its dialog.
 *
 * The sweep is there because GM's firewall drops connections from hotspot clients into the head unit
 * (2026-09, versionCodes 21-32), which may include mDNS answers; connections from the app out to a
 * client worked then. Logs carry how and how long, never an address.
 *
 * Discovery follows mammut3's enigy/carlink PR #2 (`WifiBoxFinder.kt`, `WifiLinkStore.kt`, aca4733,
 * Unlicense; NOTICE.md); the sweep and the key-only provisioning are this app's.
 */
object WifiLink {
    private val log by lazy { ProbeLog.sub("wifi") }
    private const val PREFS = "wifi_link"
    private const val P_KEY = "key"
    private const val P_ADAPTER_ID = "adapter_id"
    private const val P_LAST = "last_peer"

    /** How long a start keeps looking once the car's hotspot is up. */
    const val FIND_MS = 15_000L
    /** The longest a start waits for the car's hotspot to come up before it tries USB. */
    const val HOTSPOT_WAIT_MS = 30_000L
    private const val NSD_MS = 3_000L
    private const val SWEEP_CONNECT_MS = 400
    private const val SWEEP_THREADS = 32

    // ---- decisions (pure) ----------------------------------------------------------------------

    /**
     * Pure: what `MGMT_INFO` says of the Wi-Fi link: null when the firmware has none, else the id of
     * the key the adapter holds ("" for none).
     */
    fun adapterId(mgmtInfo: String?): String? = runCatching {
        JSONObject(mgmtInfo ?: return null).optJSONObject("wifi_link")?.optString("id", "")
    }.getOrNull()

    /** Pure: give the adapter our key now: the join is on, the firmware takes a key, and it holds another or none. */
    fun shouldProvision(joinOn: Boolean, adapterId: String?, ourId: String?): Boolean =
        joinOn && adapterId != null && adapterId != ourId

    /** Pure: this start looks on Wi-Fi before USB: the join is on and the adapter last said it holds our key. */
    fun wifiFirst(joinOn: Boolean, ourId: String?, lastAdapterId: String?): Boolean =
        joinOn && ourId != null && ourId == lastAdapterId

    /**
     * Pure: keep looking? [elapsedMs] since the start, [hotspotUpForMs] since the car's hotspot was seen
     * up (null: not yet). Up to [FIND_MS] once it is up, and never past [HOTSPOT_WAIT_MS] without it.
     */
    fun keepLooking(elapsedMs: Long, hotspotUpForMs: Long?): Boolean =
        if (hotspotUpForMs == null) elapsedMs < HOTSPOT_WAIT_MS else hotspotUpForMs < FIND_MS

    /** Pure: the other addresses of [own]'s /24, nearest first (DHCP pools start low). */
    fun sweepTargets(own: String): List<String> {
        val parts = own.split('.')
        if (parts.size != 4) return emptyList()
        val me = parts[3].toIntOrNull() ?: return emptyList()
        val net = parts.take(3).joinToString(".")
        return (1..254).filter { it != me }.sortedBy { kotlin.math.abs(it - me) }.map { "$net.$it" }
    }

    /** Pure: `host:port` as stored, or null. */
    fun parsePeer(s: String?): Pair<String, Int>? {
        val host = s?.substringBeforeLast(':', "")?.takeIf { it.isNotEmpty() } ?: return null
        val port = s.substringAfterLast(':').toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return host to port
    }

    // ---- state --------------------------------------------------------------------------------

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(ctx: Context): ByteArray? =
        prefs(ctx).getString(P_KEY, null)?.let { hexBytes(it) }?.takeIf { it.size == OcbwLink.KEY_LEN }

    private fun keyOrNew(ctx: Context): ByteArray = key(ctx) ?: OcbwLink.random32().also {
        prefs(ctx).edit().putString(P_KEY, it.toHex()).apply()
        log.i("wifi: made this head unit's link key")
    }

    private fun ourId(ctx: Context): String? = key(ctx)?.let { OcbwLink.boxId(it) }

    private fun joinOn(ctx: Context): Boolean = StaUplink.joins(StaUplink.wanted(ctx))

    /** Whether this start looks on Wi-Fi before USB (and so asks for no USB permission first). */
    fun active(ctx: Context): Boolean =
        wifiFirst(joinOn(ctx), ourId(ctx), prefs(ctx).getString(P_ADAPTER_ID, null))

    // ---- after a connect ------------------------------------------------------------------------

    /**
     * On every connect, with the start's `MGMT_INFO`: note the key the adapter holds, and give it ours
     * when [shouldProvision]. Blocking; on the link's command thread.
     */
    fun afterConnect(ctx: Context, c: OcbmClient, mgmtInfo: String?) {
        val held = adapterId(mgmtInfo) ?: return
        var now = held
        if (shouldProvision(joinOn(ctx), held, ourId(ctx))) {
            val key = keyOrNew(ctx)
            val status = c.mgmtAction(Ocbm.MGMT_WIFI_LINK, OcbwLink.provisionBody(key), 5_000)
            if (status == 0) now = OcbwLink.boxId(key)
            log.i("wifi: gave the adapter this head unit's link key: ${if (status == 0) "taken, used from its next start" else "status $status"}")
        }
        prefs(ctx).edit().putString(P_ADAPTER_ID, now).apply()
    }

    // ---- finding the adapter --------------------------------------------------------------------

    /**
     * Look for the adapter on the hotspot and link to it, while [keepLooking]; null when it was not
     * reached (the caller claims USB), or once [cancelled]. Blocking.
     */
    fun connect(ctx: Context, cancelled: () -> Boolean): WifiBulkTransport? {
        val key = key(ctx) ?: return null
        val id = OcbwLink.boxId(key)
        val t0 = SystemClock.elapsedRealtime()
        var hotspotSince: Long? = null
        var round = 0
        while (!cancelled()) {
            val now = SystemClock.elapsedRealtime()
            val own = hotspotAddresses()
            if (own.isNotEmpty() && hotspotSince == null) hotspotSince = now
            if (!keepLooking(now - t0, hotspotSince?.let { now - it })) break
            round++
            val found = parsePeer(prefs(ctx).getString(P_LAST, null))?.let { (h, p) -> link(h, p, key, "the last address") }
                ?: nsd(ctx, id)?.let { (h, p) -> link(h, p, key, "mDNS") }
                ?: own.firstNotNullOfOrNull { sweep(it, key, cancelled) }
            if (found != null) {
                prefs(ctx).edit().putString(P_LAST, found.peer).apply()
                log.i("wifi: linked to the adapter in ${SystemClock.elapsedRealtime() - t0} ms, round $round")
                return found
            }
            if (!cancelled()) Thread.sleep(1_000)
        }
        log.w("wifi: the adapter was not reached in ${SystemClock.elapsedRealtime() - t0} ms ($round rounds; car hotspot ${if (hotspotSince != null) "up" else "not up"}): trying USB")
        return null
    }

    /** The head unit's own IPv4 addresses on the car's hotspot interfaces ([StaUplink.isHotspotName]). */
    private fun hotspotAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && StaUplink.isHotspotName(it.name) }
            .flatMap { n -> n.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress } }
    }.getOrDefault(emptyList())

    private fun link(host: String, port: Int, key: ByteArray, how: String): WifiBulkTransport? = try {
        WifiBulkTransport.connect(host, port, key, log).also { log.i("wifi: reached the adapter by $how") }
    } catch (e: java.io.IOException) {
        log.i("wifi: not reached by $how (${e.javaClass.simpleName})")
        null
    }

    /** Every other address of [own]'s /24 on [OcbwLink.PORT], [SWEEP_THREADS] at a time; the first to prove the key. */
    private fun sweep(own: String, key: ByteArray, cancelled: () -> Boolean): WifiBulkTransport? {
        val t0 = SystemClock.elapsedRealtime()
        val won = AtomicReference<WifiBulkTransport?>(null)
        val pool = Executors.newFixedThreadPool(SWEEP_THREADS)
        try {
            for (host in sweepTargets(own)) pool.execute {
                if (won.get() != null || cancelled()) return@execute
                val t = runCatching { WifiBulkTransport.connect(host, OcbwLink.PORT, key, log, SWEEP_CONNECT_MS) }.getOrNull()
                    ?: return@execute
                if (!won.compareAndSet(null, t)) t.stop()
            }
            pool.shutdown()
            pool.awaitTermination(30, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
        val t = won.get()
        log.i("wifi: hotspot sweep ${if (t != null) "found the adapter" else "found nothing"} in ${SystemClock.elapsedRealtime() - t0} ms")
        return t
    }

    /** mDNS: the first `_ocbm._tcp` advert whose TXT `id` is [id], within [NSD_MS]. */
    @Suppress("DEPRECATION") // resolveService: the API 32 way; its replacement is API 34
    private fun nsd(ctx: Context, id: String): Pair<String, Int>? {
        val nsd = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return null
        val found = LinkedBlockingQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { found.add(info) }
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        if (runCatching { nsd.discoverServices(OcbwLink.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.isFailure) return null
        try {
            val end = SystemClock.elapsedRealtime() + NSD_MS
            val tried = HashSet<String>()
            while (true) {
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) return null
                val info = found.poll(left, TimeUnit.MILLISECONDS) ?: return null
                if (!tried.add(info.serviceName)) continue
                val done = CountDownLatch(1)
                var out: NsdServiceInfo? = null
                runCatching {
                    nsd.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) { done.countDown() }
                        override fun onServiceResolved(si: NsdServiceInfo) { out = si; done.countDown() }
                    })
                }
                done.await(left, TimeUnit.MILLISECONDS)
                val r = out ?: continue
                if (r.attributes["id"]?.let { String(it, Charsets.US_ASCII) } != id) continue
                return (r.host?.hostAddress ?: continue) to r.port
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }
}
