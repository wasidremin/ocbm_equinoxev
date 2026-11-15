package wasidremin.gmccpa.ocbm

import org.json.JSONObject
import wasidremin.gmccpa.ProbeLog
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/**
 * The adapter link over a USB network function instead of a USB claim (docs/ops/08_FUTURE_TASKS.md
 * T15, route A, 2026-11-15). A car's own system takes a USB network device as it takes a USB drive,
 * so an app that reaches the adapter over it would need no USB permission and no Allow tap.
 *
 * This is the probe, not the transport. Settings ▸ Advanced ▸ USB network trial writes
 * `usb_net=eth-once` to the adapter's `/script/ocbm.conf` and restarts it: for that one start the
 * adapter adds the network function beside OCBM and says so in `MGMT_INFO` (`"usb_net"`), and its
 * next start is the usual one whatever happens. On every connect where the adapter says so,
 * [probe] logs the interfaces Android shows this app and whether the adapter's test port
 * (`ocbm_proto::USB_NET_PORT`, which answers `OCBM-NET 1`) can be reached on them. One drive with
 * the trial answers whether the car brings the link up for an ordinary app.
 *
 * Logs carry interface names, flags and counts only: no addresses.
 */
object UsbNet {
    private val log by lazy { ProbeLog.sub("usb") }
    const val BANNER = "OCBM-NET 1"
    private const val CONF = "/script/ocbm.conf"

    /** What `MGMT_INFO` says about the link this start. */
    data class Info(val style: String, val v4: String, val ll: String, val port: Int)

    /** Pure: the `"usb_net"` object of an `MGMT_INFO` document, or null when the adapter presents none. */
    fun parseInfo(json: String): Info? = runCatching {
        val o = JSONObject(json).optJSONObject("usb_net") ?: return null
        Info(o.getString("style"), o.getString("v4"), o.optString("ll"), o.getInt("port")).takeIf { it.port in 1..65535 }
    }.getOrNull()

    /** Pure: `/script/ocbm.conf` with `usb_net=eth-once`; every other line copied as read. */
    fun armTrial(existing: String?): String {
        val lines = existing?.split('\n')?.filter { it.isNotEmpty() && !it.trim().startsWith("usb_net=") && it.trim().substringBefore('=').trim() != "usb_net" }
            ?: emptyList()
        return (lines + "usb_net=eth-once").joinToString("\n") + "\n"
    }

    /** Pure: the interfaces worth trying for a link-local address: up, not loopback, with an IPv6 link-local. */
    data class Iface(val name: String, val up: Boolean, val loopback: Boolean, val linkLocal: Boolean, val v4Subnet: String?)

    fun candidates(ifs: List<Iface>, info: Info): List<Iface> {
        val v4net = info.v4.substringBeforeLast('.', "")
        return ifs.filter { it.up && !it.loopback && (it.linkLocal || it.v4Subnet == v4net) }
            .sortedBy { if (it.v4Subnet == v4net) 0 else if (it.name.matches(Regex("(usb|eth|rndis|ncm)\\d+"))) 1 else 2 }
    }

    private fun interfaces(): List<Iface> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { n ->
            val addrs = n.interfaceAddresses.orEmpty()
            Iface(
                n.name, n.isUp, n.isLoopback,
                addrs.any { (it.address as? Inet6Address)?.isLinkLocalAddress == true },
                addrs.mapNotNull { a -> a.address.hostAddress?.takeIf { a.address is java.net.Inet4Address }?.substringBeforeLast('.') }.firstOrNull(),
            )
        }
    }.getOrDefault(emptyList())

    /** Blocking, a few seconds at most: try the adapter's test port, return how it went. */
    private fun reach(host: String, port: Int): String = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 1_500)
            s.soTimeout = 1_500
            val got = s.getInputStream().bufferedReader().readLine()
            if (got == BANNER) "answered" else "wrong answer"
        }
    }.getOrElse { it.javaClass.simpleName }

    /**
     * On a connect: if [mgmtInfo] says the adapter presents the link, log what Android shows and
     * whether the adapter answers over it. Off the caller's thread.
     */
    fun probe(mgmtInfo: String) {
        val info = parseInfo(mgmtInfo) ?: return
        Thread({
            val ifs = interfaces()
            log.i("usb-net: adapter presents the network function (${info.style} naming); interfaces up: " +
                ifs.filter { it.up && !it.loopback }.joinToString(" ") { it.name + (if (it.linkLocal) "+ll" else "") + (if (it.v4Subnet != null) "+v4" else "") }.ifEmpty { "none" })
            val tried = ArrayList<String>()
            for (i in candidates(ifs, info)) {
                if (i.v4Subnet == info.v4.substringBeforeLast('.', "")) tried += "${i.name}/v4 ${reach(info.v4, info.port)}"
                if (info.ll.isNotEmpty() && i.linkLocal) tried += "${i.name}/ll ${reach("${info.ll}%${i.name}", info.port)}"
            }
            // Without an interface for it, ask by address anyway: a route may exist that the list does not show.
            if (tried.isEmpty()) tried += "v4 ${reach(info.v4, info.port)}"
            val ok = tried.any { it.endsWith("answered") }
            log.i("usb-net: ${if (ok) "REACHED the adapter with no USB claim" else "adapter not reached"}: ${tried.joinToString("; ")}")
        }, "usb-net-probe").apply { isDaemon = true }.start()
    }

    /** Settings: arm one trial start and restart the adapter. Blocking; returns a line for Settings. */
    fun startTrial(c: OcbmClient): String {
        val read = c.fileRead(CONF)
        val existing = read.bytes?.toString(Charsets.UTF_8)
        val body = armTrial(existing).toByteArray(Charsets.UTF_8)
        if (!c.filePush(CONF, body, 420)) return "Could not write the adapter's settings."
        if (c.fileRead(CONF).bytes?.contentEquals(body) != true) return "The adapter's settings did not read back."
        log.i("usb-net: trial armed for the next adapter start; restarting it")
        c.mgmtAction(Ocbm.MGMT_REBOOT)
        return "Armed. The adapter restarts now and presents the USB network for this start only. Tap Allow as usual; the log says whether the app reached it over the network."
    }
}
