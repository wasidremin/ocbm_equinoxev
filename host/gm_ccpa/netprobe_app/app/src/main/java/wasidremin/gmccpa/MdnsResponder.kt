package wasidremin.gmccpa

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A minimal mDNS responder that we control completely.
 *
 * **Why this exists.** `NsdManager` published PTR and TXT for our service but iOS never received an
 * SRV or address record, so it had a service it could read and no `host:port` to dial — the exact
 * observed failure. That is not fixable through the platform API: verified at AOSP source,
 * `NsdService.registerService()` forwards only `regId, name, type, port, record`,
 * `NsdServiceInfo.setHost()` is *ignored* on registration, netd's `MDnsSdListener` hardcodes the
 * interface index to 0, and resolution then does a `getaddrinfo` across every interface and takes the
 * first address — a coin flip on this 8-interface head unit.
 *
 * `rx-connect`, the proven implementation, sidesteps all of it by pinning both the SRV target
 * hostname and an explicit advertised address (`RX_ADDR`). This class does the same thing: it owns
 * the hostname, and it answers with the addresses of **one** interface — the vehicle hotspot bridge
 * the iPhone is actually on.
 *
 * **When the hotspot has a link-local address, only the AAAA is published.** GM's IPv4 `INPUT`
 * chain is default-DROP with no allow for any app port (`01_FINDINGS.md` §2), so a phone that dials
 * our IPv4 address is silently dropped. Every working Silverado session arrived on the link-local
 * address (`INBOUND CONTROL CONNECTION from fe80::…%br0`). Publishing both records left iOS an
 * address it can resolve and cannot complete: a dropped SYN is not an error it fails over from, and
 * on the Equinox the phone queried `A AAAA` on every browse and never opened `:7011`. An A query is
 * answered with the AAAA plus a cache-flush A at TTL 0, which retracts the A a previous build left
 * cached (TTL 120 s). An AAAA query is answered with the AAAA plus an NSEC whose only type bit is
 * AAAA, the same shape as the old "no AAAA" denial that iOS honored, inverted. The A record and the
 * "no AAAA" NSEC return only when the interface has no link-local address at all.
 *
 * Deliberately small. It answers PTR / SRV / TXT / A / AAAA / NSEC (and ANY) for exactly one service
 * and announces unsolicited on start.
 *
 * **RFC 6762 §8.1 probing is deliberately skipped**, even though we set the cache-flush bit on the
 * records we own. Probing exists to detect a name already claimed by another responder; here the
 * instance name and the hostname are ours alone by construction — chosen to collide with neither GM's
 * `CarPlay` nor the other adapter's `carlink`, on a closed AP whose only other members are the phone
 * and the box. There is no third responder that could hold them, so a probe phase would only add
 * 750 ms of dead time to every start on the one path that is already latency-critical.
 */
class MdnsResponder(
    private val instance: String,
    private val serviceType: String,   // "_airplay._tcp"
    private val port: Int,
    private val txt: Map<String, String>,
    private val log: ProbeLog.Logger
) {
    private val running = AtomicBoolean(false)
    private var sock: MulticastSocket? = null
    private var thread: Thread? = null

    /** Our own hostname. NOT the platform's `Android.local` — we must own the A record for it. */
    private val hostname = "$instance.local"
    private val serviceFqdn = "$instance.$serviceType.local"
    private val typeFqdn = "$serviceType.local"

    /** Hotspot IPv4. Kept so a previous build's cached A can be withdrawn; not advertised while [advertised6] is set. */
    @Volatile
    private var advertised: Inet4Address? = null
    /** The link-local IPv6 address of the hotspot interface — the only address we publish when it exists. */
    @Volatile
    private var advertised6: Inet6Address? = null

    private companion object {
        const val PORT = 5353
        const val GROUP = "224.0.0.251"
        const val TTL_HOST = 120
        const val TTL_PTR = 4500
        /** Class IN with the cache-flush bit — correct for records we are authoritative for. */
        const val CLASS_FLUSH = 0x8001
        const val CLASS_IN = 0x0001
        const val T_A = 1; const val T_PTR = 12; const val T_TXT = 16; const val T_SRV = 33
        const val T_AAAA = 28
        const val T_NSEC = 47
        const val T_ANY = 255
    }

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val nif = hotspotInterface() ?: run { log.e("no active hotspot interface — cannot advertise where the phone is"); running.set(false); return false }
        advertised = hotspotIpv4(nif)
        if (advertised == null) { log.e("hotspot interface ${nif.name} has no IPv4 address yet"); running.set(false); return false }
        advertised6 = hotspotLinkLocal6(nif)
        if (advertised6 == null) log.w("hotspot interface ${nif.name} has no link-local IPv6 — advertising IPv4 only; GM's IPv4 INPUT chain may drop the phone's dial-back")

        sock = try {
            MulticastSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                // Send-side egress only. The join below must NOT rely on it: `IP_ADD_MEMBERSHIP`
                // with `imr_ifindex=0` does its own route lookup regardless of `IP_MULTICAST_IF`,
                // and on this head unit `ip route get 224.0.0.251` is "Network is unreachable" —
                // which is why the single-arg join failed ENODEV here despite this line already
                // being present. The two-arg form passes the ifindex explicitly and skips the
                // lookup; /proc/net/igmp shows the system daemon already joined on br0 that way.
                networkInterface = nif
                joinGroup(InetSocketAddress(InetAddress.getByName(GROUP), PORT), nif)
                soTimeout = 500
            }
        } catch (t: Throwable) {
            log.e("mDNS bind failed: ${t.javaClass.simpleName}: ${t.message}"); running.set(false); return false
        }

        log.i("responder up: $serviceFqdn -> $hostname:$port @ " +
              (advertised6?.let { "AAAA-only [${it.hostAddress?.substringBefore('%')}] (IPv4 ${advertised?.hostAddress} withheld)" }
                  ?: "A-only ${advertised?.hostAddress}") +
              " (${nif.name})")
        thread = Thread({ loop() }, "mdns-responder").apply { isDaemon = true; start() }
        announce()
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // Retract before the socket closes. Without a goodbye our SRV/TXT/A linger in iOS's cache for
        // TTL_HOST (120 s) and the PTR for TTL_PTR (4500 s) — so a receiver that restarts precisely to
        // clear a wedged endpoint is re-discovered at its stale records and the restart accomplishes
        // nothing. This is a prerequisite for any "reset the stack to a clean state" recovery.
        goodbye()
        try { thread?.join(1200) } catch (_: InterruptedException) {}
        try { sock?.close() } catch (_: Throwable) {}
        sock = null; thread = null
        log.i("responder stopped")
    }

    /**
     * Re-assert every record without tearing the responder down — the cheapest recovery rung there
     * is, and the only one that addresses a peer holding a stale endpoint for us.
     */
    fun reannounce() {
        if (!running.get()) { log.w("reannounce with the responder down — ignored"); return }
        log.i("re-announcing (recovery)")
        announce()
    }

    /**
     * Withdraw every record we own: same set as [announce], TTL 0. Sent twice — mDNS goodbyes are
     * unacknowledged, and one lost packet leaves the stale record in place for its whole TTL.
     */
    private fun goodbye() {
        val pkt = buildResponse(ptr = true, srv = true, txt = true, addr = true, flushV4 = advertised6 != null, ttl0 = true)
        var sent = 0
        repeat(2) {
            if (send(pkt)) sent++
            try { Thread.sleep(120) } catch (_: InterruptedException) { return }
        }
        if (sent == 0) log.w("goodbye FAILED — our records linger in iOS's cache until they expire")
        else log.i("goodbye sent ($sent/2) — PTR/SRV/TXT/${if (advertised6 != null) "AAAA+A" else "A"} withdrawn at TTL 0")
    }

    /** Unsolicited announcements, so iOS learns SRV plus the one address we want it to dial. */
    private fun announce() {
        if (advertised6 != null) flushCachedA()
        val v6 = advertised6 != null
        val pkt = buildResponse(ptr = true, srv = true, txt = true, addr = true, nsecV6Only = v6)
        var sent = 0
        repeat(3) {
            if (send(pkt)) sent++
            // RFC 6762 §8.3: announcements are sent at least one second apart. At 250 ms all three
            // arrived inside one iOS coalescing window, so the repetition bought no loss margin.
            try { Thread.sleep(1000) } catch (_: InterruptedException) { return }
        }
        // Report what actually happened. Claiming success unconditionally hid a real failure mode:
        // called from the UI thread, every send throws NetworkOnMainThreadException and is swallowed.
        if (sent == 0) log.e("announce FAILED — no packet left the socket; iOS will never index us")
        else log.i("announced PTR + SRV + TXT + ${if (v6) "AAAA (A withheld)" else "A"} ($sent/3 sent)")
    }

    /**
     * Retract the hotspot A record a previous build left in iOS's cache. A dropped IPv4 SYN is not
     * an error iOS fails over from, so a cached A beside a live AAAA is the failure this experiment
     * exists to remove. Sent on its own, twice: the same packet must not also carry the "no A" NSEC.
     */
    private fun flushCachedA() {
        val pkt = buildResponse(flushV4 = true)
        repeat(2) {
            send(pkt)
            try { Thread.sleep(120) } catch (_: InterruptedException) { return }
        }
    }

    private fun loop() {
        val buf = ByteArray(9000)
        while (running.get()) {
            val p = DatagramPacket(buf, buf.size)
            try { sock?.receive(p) ?: break } catch (e: SocketTimeoutException) { continue }
            catch (t: Throwable) { if (running.get()) log.w("recv: ${t.message}"); continue }
            try { handleQuery(p.data, p.length) } catch (t: Throwable) {
                log.w("query handling: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun handleQuery(data: ByteArray, len: Int) {
        if (len < 12) return
        fun u16(o: Int) = ((data[o].toInt() and 0xFF) shl 8) or (data[o + 1].toInt() and 0xFF)
        if (u16(2) and 0x8000 != 0) return          // a response, not a query
        val qd = u16(4)
        if (qd == 0) return
        var pos = 12
        var wantPtr = false; var wantSrv = false; var wantTxt = false; var wantAddr = false
        var wantNsecV4Only = false; var wantNsecV6Only = false; var wantFlushV4 = false
        val v6 = advertised6 != null
        for (i in 0 until qd) {
            val (name, after) = dnsReadName(data, pos, len)
            if (after + 4 > len) return
            val qtype = u16(after)
            pos = after + 4
            val any = qtype == T_ANY
            when {
                name.equals(typeFqdn, true) && (qtype == T_PTR || any) -> {
                    wantPtr = true; wantSrv = true; wantTxt = true; wantAddr = true
                    if (v6) wantNsecV6Only = true
                }
                name.equals(serviceFqdn, true) -> {
                    if (qtype == T_SRV || any) { wantSrv = true; wantAddr = true; if (v6) wantNsecV6Only = true }
                    if (qtype == T_TXT || any) wantTxt = true
                }
                // With a link-local address the address set is the AAAA alone. An A question also
                // gets a TTL-0 A so a cached IPv4 record is flushed; that packet must not carry the
                // "no A" NSEC (the two contradict, and a bad NSEC makes iOS drop the whole response).
                // A pure AAAA question gets the NSEC so iOS does not sit out an A timeout.
                // With no link-local, an AAAA question gets the A plus "no AAAA", as before.
                name.equals(hostname, true) && (qtype == T_A || qtype == T_AAAA || any) -> {
                    wantAddr = true
                    if (v6 && (qtype == T_A || any)) wantFlushV4 = true
                    else if (v6) wantNsecV6Only = true
                    else if (qtype == T_AAAA || any) wantNsecV4Only = true
                }
            }
        }
        if (wantFlushV4) wantNsecV6Only = false
        if (!(wantPtr || wantSrv || wantTxt || wantAddr || wantFlushV4)) return
        log.i("query -> answering${if (wantPtr) " PTR" else ""}${if (wantSrv) " SRV" else ""}" +
              "${if (wantTxt) " TXT" else ""}" +
              (when {
                  wantFlushV4 -> " AAAA A/0"
                  wantAddr && v6 -> " AAAA"
                  wantAddr -> " A"
                  else -> ""
              }) +
              (when {
                  wantNsecV6Only -> " NSEC/no-A"
                  wantNsecV4Only -> " NSEC/no-AAAA"
                  else -> ""
              }))
        send(buildResponse(wantPtr, wantSrv, wantTxt, wantAddr,
            nsecV4Only = wantNsecV4Only, nsecV6Only = wantNsecV6Only, flushV4 = wantFlushV4))
    }

    private fun send(pkt: ByteArray): Boolean = try {
        sock?.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(GROUP), PORT))
        true
    } catch (t: Throwable) { log.w("send: ${t.javaClass.simpleName}: ${t.message}"); false }

    // ---- record construction ----------------------------------------------------------------------

    /**
     * [ttl0] builds the same record set with TTL 0 — an mDNS goodbye. See [goodbye].
     * [nsecV4Only] / [nsecV6Only] add the negative type bitmap to the ADDITIONAL section, which is
     * where RFC 6762 §6.1 puts it. The two are mutually exclusive, and neither may share a packet
     * with [flushV4]: a TTL-0 A next to an NSEC that denies A is the malformed denial iOS discards
     * wholesale.
     */
    private fun buildResponse(ptr: Boolean = false, srv: Boolean = false, txt: Boolean = false,
                              addr: Boolean = false, nsecV4Only: Boolean = false,
                              nsecV6Only: Boolean = false, flushV4: Boolean = false,
                              ttl0: Boolean = false): ByteArray {
        val answers = ByteArrayOutputStream()
        var n = 0
        if (ptr) { answers.write(recPtr(ttl0)); n++ }
        if (srv) { answers.write(recSrv(ttl0)); n++ }
        if (txt) { answers.write(recTxt(ttl0)); n++ }
        if (addr && advertised6 != null) { recAaaa(ttl0)?.let { answers.write(it); n++ } }
        if (addr && advertised6 == null) { recA(ttl0)?.let { answers.write(it); n++ } }
        if (flushV4) { recA(ttl0 = true)?.let { answers.write(it); n++ } }
        val additional = ByteArrayOutputStream()
        var ar = 0
        if (nsecV4Only) { additional.write(recNsec(intArrayOf(T_A), ttl0)); ar++ }
        if (nsecV6Only) { additional.write(recNsec(intArrayOf(T_AAAA), ttl0)); ar++ }
        val out = ByteArrayOutputStream()
        // id=0, flags=0x8400 (response + authoritative), qd=0, an=n, ns=0, ar=ar
        out.write(byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, (n shr 8).toByte(), (n and 0xFF).toByte(),
                              0, 0, (ar shr 8).toByte(), (ar and 0xFF).toByte()))
        out.write(answers.toByteArray())
        out.write(additional.toByteArray())
        return out.toByteArray()
    }

    private fun record(name: String, type: Int, cls: Int, ttl: Int, rdata: ByteArray): ByteArray {
        val b = ByteArrayOutputStream()
        b.write(encodeName(name))
        b.write(byteArrayOf((type shr 8).toByte(), (type and 0xFF).toByte()))
        b.write(byteArrayOf((cls shr 8).toByte(), (cls and 0xFF).toByte()))
        b.write(byteArrayOf((ttl ushr 24).toByte(), (ttl ushr 16).toByte(), (ttl ushr 8).toByte(), ttl.toByte()))
        b.write(byteArrayOf((rdata.size shr 8).toByte(), (rdata.size and 0xFF).toByte()))
        b.write(rdata)
        return b.toByteArray()
    }

    // PTR is shared (many instances of a type may exist) — no cache-flush bit.
    private fun recPtr(ttl0: Boolean = false) =
        record(typeFqdn, T_PTR, CLASS_IN, if (ttl0) 0 else TTL_PTR, encodeName(serviceFqdn))

    private fun recSrv(ttl0: Boolean = false): ByteArray {
        val rd = ByteArrayOutputStream()
        rd.write(byteArrayOf(0, 0, 0, 0))                                   // priority 0, weight 0
        rd.write(byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte()))
        rd.write(encodeName(hostname))
        return record(serviceFqdn, T_SRV, CLASS_FLUSH, if (ttl0) 0 else TTL_HOST, rd.toByteArray())
    }

    private fun recTxt(ttl0: Boolean = false): ByteArray {
        val rd = ByteArrayOutputStream()
        for ((k, v) in txt) {
            val kv = "$k=$v".toByteArray(Charsets.UTF_8)
            rd.write(kv.size); rd.write(kv)
        }
        if (txt.isEmpty()) rd.write(0)
        return record(serviceFqdn, T_TXT, CLASS_FLUSH, if (ttl0) 0 else TTL_HOST, rd.toByteArray())
    }

    private fun recA(ttl0: Boolean = false): ByteArray? =
        advertised?.let { record(hostname, T_A, CLASS_FLUSH, if (ttl0) 0 else TTL_HOST, it.address) }

    /** AAAA carrying the hotspot interface's link-local address (16 bytes, no scope — the scope is the link). */
    private fun recAaaa(ttl0: Boolean = false): ByteArray? =
        advertised6?.let { record(hostname, T_AAAA, CLASS_FLUSH, if (ttl0) 0 else TTL_HOST, it.address) }

    /**
     * NSEC for our hostname listing exactly [types] and nothing else. [types] is `A` when we have no
     * link-local address ("no AAAA"), and `AAAA` when we do ("no A"). See [handleQuery].
     *
     * RDATA is next-domain-name followed by a type bitmap (RFC 4034 §4.1.2). In mDNS the
     * next-domain field carries the owner name itself and is written UNCOMPRESSED. Window 0, bit 0
     * of the first bitmap byte is type 0, so type 1 (A) is 0x40 and type 28 (AAAA) is byte 3 bit
     * 0x08. The A-only form is the four bytes iOS already honored (`0, 1, 0x40`).
     *
     * Keep this minimal and exact: a malformed NSEC makes iOS discard the WHOLE response.
     */
    private fun recNsec(types: IntArray, ttl0: Boolean = false): ByteArray {
        val maxType = types.maxOrNull() ?: 0
        val len = maxType / 8 + 1
        val bits = ByteArray(len)
        for (t in types) bits[t / 8] = (bits[t / 8].toInt() or (0x80 shr (t % 8))).toByte()
        val rd = ByteArrayOutputStream()
        rd.write(encodeName(hostname))                  // next domain name = the owner
        rd.write(byteArrayOf(0, len.toByte()))
        rd.write(bits)
        return record(hostname, T_NSEC, CLASS_FLUSH, if (ttl0) 0 else TTL_HOST, rd.toByteArray())
    }

    /** Uncompressed name encoding. Compression pointers are legal but pointless at this size. */
    private fun encodeName(name: String): ByteArray {
        val b = ByteArrayOutputStream()
        for (label in name.trimEnd('.').split(".")) {
            val by = label.toByteArray(Charsets.UTF_8)
            b.write(by.size); b.write(by)
        }
        b.write(0)
        return b.toByteArray()
    }

    private fun hotspotIpv4(nif: NetworkInterface): Inet4Address? =
        nif.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()

    private fun hotspotLinkLocal6(nif: NetworkInterface): Inet6Address? =
        nif.inetAddresses.toList().filterIsInstance<Inet6Address>().firstOrNull { it.isLinkLocalAddress }
}

/**
 * Select the vehicle-hotspot interface without assuming the OEM's Linux bridge name.
 *
 * The original GM test unit exposed the hotspot as br0. Other AAOS builds commonly expose the same
 * network as wlan0, ap0, swlan0, or another vendor-specific multicast-capable interface. Prefer the
 * known bridge and Wi-Fi/AP names, but retain a private-IPv4 fallback so a new OEM name does not make
 * the receiver fail before it ever reaches the adapter.
 */
internal fun hotspotInterface(): NetworkInterface? = try {
    val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { nif ->
        try {
            nif.isUp && !nif.isLoopback && nif.supportsMulticast() && hotspotIpv4(nif) != null
        } catch (_: Throwable) { false }
    }
    candidates.maxWithOrNull(compareBy<NetworkInterface> { hotspotScore(it) }.thenBy { it.name })
} catch (_: Throwable) { null }

internal fun hotspotIpv4(nif: NetworkInterface): Inet4Address? =
    nif.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()

private fun hotspotScore(nif: NetworkInterface): Int {
    val name = nif.name.lowercase()
    // AP-role names outrank `wlan*`. On the Equinox EV (VCU) the hotspot is the bridge
    // `ap_br_swlan0` (members swlan0/swlan1) and `wlan0` is the STATION interface: it has no IPv4
    // until the car joins a Wi-Fi network — the owner's home network in the driveway, 2026-09-21 —
    // and then it tied `ap_br_swlan0` at 920 and won the alphabetical tie-break. Two sessions
    // advertised the home network's address and link-local to a phone sitting on the hotspot, and
    // our IPv6 connect-out to the phone, scoped to %wlan0, timed out both times. `wlan*` is still
    // a valid answer on builds whose AP *is* wlan0 — it just must never beat an explicit AP name.
    val nameScore = when {
        name == "br0" -> 1000
        name.startsWith("br") -> 950
        name.startsWith("ap") || name.startsWith("swlan") ||
            name.contains("softap") || name.contains("hotspot") -> 940
        name.contains("wifi") -> 850
        name.startsWith("wlan") -> 800
        else -> 100
    }
    val addressScore = hotspotIpv4(nif)?.let { address ->
        val b = address.address
        if ((b[0].toInt() and 0xff) == 10 ||
            ((b[0].toInt() and 0xff) == 172 && (b[1].toInt() and 0xff) in 16..31) ||
            ((b[0].toInt() and 0xff) == 192 && (b[1].toInt() and 0xff) == 168)) 20 else 0
    } ?: 0
    return nameScore + addressScore
}

/**
 * Decode a DNS name at [start] in [data] (bounded by [len]), following at most one compression
 * pointer chain. Shared by [MdnsResponder] and [MdnsInspect] so the wire-format edge cases —
 * notably the bounds check on a truncated compression pointer's second byte — are not duplicated
 * and cannot diverge between the two copies again.
 */
internal fun dnsReadName(data: ByteArray, start: Int, len: Int): Pair<String, Int> {
    val sb = StringBuilder(); var pos = start; var jumped = false; var after = start; var guard = 0
    while (pos < len && guard++ < 128) {
        val b = data[pos].toInt() and 0xFF
        if (b == 0) { if (!jumped) after = pos + 1; break }
        if (b and 0xC0 == 0xC0) {
            if (pos + 1 >= len) break
            val ptr = ((b and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
            if (!jumped) after = pos + 2
            pos = ptr; jumped = true; continue
        }
        pos += 1
        if (pos + b > len) break
        if (sb.isNotEmpty()) sb.append('.')
        sb.append(String(data, pos, b, Charsets.UTF_8)); pos += b
    }
    return Pair(sb.toString(), after)
}
