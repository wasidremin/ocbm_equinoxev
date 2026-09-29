package wasidremin.gmccpa.ocbm

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean
import wasidremin.gmccpa.logging.SessionTrace

/**
 * The OCBM accessory over Android USB host mode.
 *
 * The box enumerates as VID 0x1314 / PID 0x2d00 with bDeviceClass 0, interface 0 class 0xFF, bulk
 * IN 0x81 / OUT 0x01 at 512-byte max packet (hardware-verified 2026-08-12; older docs said
 * 0x83/0x02 — the code is immune either way because it walks the interface). It is a RAW BYTE PIPE: there is no AOA control
 * handshake (51/52/53) to perform — claim interface 0 and start moving bytes. All framing is OCBM's.
 *
 * ## Standing state on the [SessionTrace.Board]
 *
 * Two entries, so a `## STATUS` block answers "is the adapter claimed and is anyone reading it":
 *  - [OcbmBoard.USB_CLAIM] — the interface: UP with iface/endpoints/mps from [open], DOWN when
 *    [stop] releases it, FAILED when a claim was attempted and refused. The *waiting* states before
 *    a claim (adapter absent, permission not held) are written under the same name by
 *    `OcbmProbe.awaitClaimable`, which owns that wait; one entry, one history.
 *  - [OcbmBoard.USB_READ] — the `ocbm-read` thread. FAILED on the two transport-dead exits in
 *    [readLoop]; those were already the only `E` lines in this class, and the Board entry is what
 *    keeps them visible in every later status block rather than only at the instant they happened.
 */
class UsbBulkTransport(
    private val ctx: Context,
    private val log: wasidremin.gmccpa.ProbeLog.Logger
) : RawBulkTransport {

    companion object {
        const val VID_CARLINKIT = 0x1314
        const val PID_OCBM = 0x2d00
        const val PID_NCM = 0x1520
        const val PID_STOCK = 0x1521

        const val ACTION_USB_PERMISSION = "wasidremin.gmccpa.USB_PERMISSION"

        @Volatile private var lastPermissionRequestAt = 0L

        /** When [requestPermissionEarly] last fired. The claim loop will not raise a second dialog inside [OcbmProbe]'s re-raise interval. */
        fun recentPermissionRequestAt(): Long = lastPermissionRequestAt

        /**
         * Raise the permission dialog without a live transport. The attach path calls this
         * before [wasidremin.gmccpa.ocbm.OcbmProbe] exists, so the grant can land while the
         * rest of bring-up is still starting. The result broadcast uses [ACTION_USB_PERMISSION].
         */
        fun requestPermissionEarly(ctx: Context, dev: UsbDevice) {
            lastPermissionRequestAt = android.os.SystemClock.elapsedRealtime()
            val app = ctx.applicationContext
            val mgr = app.getSystemService(Context.USB_SERVICE) as UsbManager
            try {
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pi = PendingIntent.getBroadcast(
                    app, 0,
                    Intent(ACTION_USB_PERMISSION).setPackage(app.packageName),
                    flags,
                )
                mgr.requestPermission(dev, pi)
            } catch (t: Throwable) {
                wasidremin.gmccpa.ProbeLog.sub("usb").w(
                    "requestPermission threw ${t.javaClass.simpleName}: ${t.message}",
                )
            }
        }

        private val permLock = Object()
        @Volatile private var grantedViaBroadcast = false
        private var permWatchArmed = false
        private var permWatch: BroadcastReceiver? = null

        /**
         * Listen for [ACTION_USB_PERMISSION] before the dialog is raised. The claim loop waits
         * on [awaitPermissionSignal] instead of sleeping out a full poll. The first arm of an
         * attempt clears a stale grant; a later arm (the claim loop, after the attach path)
         * does not, so a broadcast that already arrived is still visible.
         */
        fun armPermissionWatch(ctx: Context) {
            val app = ctx.applicationContext
            val created = synchronized(permLock) {
                if (!permWatchArmed) {
                    grantedViaBroadcast = false
                    permWatchArmed = true
                }
                if (permWatch != null) return
                val r = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        if (intent?.action != ACTION_USB_PERMISSION) return
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        synchronized(permLock) {
                            if (granted) grantedViaBroadcast = true
                            permLock.notifyAll()
                        }
                    }
                }
                permWatch = r
                r
            }
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    app.registerReceiver(created, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    app.registerReceiver(created, filter)
                }
            } catch (t: Throwable) {
                synchronized(permLock) { if (permWatch === created) permWatch = null }
                wasidremin.gmccpa.ProbeLog.sub("usb").w(
                    "permission watch register failed: ${t.javaClass.simpleName}: ${t.message}",
                )
            }
        }

        /** @return true once a grant broadcast has arrived for this attempt. */
        fun awaitPermissionSignal(timeoutMs: Long): Boolean = synchronized(permLock) {
            if (grantedViaBroadcast) return true
            try {
                permLock.wait(timeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            grantedViaBroadcast
        }

        /** Read and clear the grant-via-broadcast flag. */
        fun consumeGrantViaBroadcast(): Boolean = synchronized(permLock) {
            val v = grantedViaBroadcast
            grantedViaBroadcast = false
            v
        }

        fun disarmPermissionWatch(ctx: Context) {
            val app = ctx.applicationContext
            val r = synchronized(permLock) {
                val cur = permWatch
                permWatch = null
                permWatchArmed = false
                grantedViaBroadcast = false
                cur
            }
            if (r != null) runCatching { app.unregisterReceiver(r) }
        }

        /** Android's bulkTransfer is unreliable above 16 KiB on some platforms; frames reassemble anyway. */
        private const val READ_BUF = 16384
        private const val READ_TIMEOUT_MS = 250
        private const val WRITE_TIMEOUT_MS = 2000
        private const val WRITE_CHUNK = 16384
    }

    // Application context, never the Activity: the read/heartbeat threads outlive an Activity and
    // holding one here leaks it for the whole session.
    private val appCtx = ctx.applicationContext
    private val usb = appCtx.getSystemService(Context.USB_SERVICE) as UsbManager
    @Volatile private var conn: UsbDeviceConnection? = null
    @Volatile private var iface: UsbInterface? = null
    @Volatile private var epIn: UsbEndpoint? = null
    @Volatile private var epOut: UsbEndpoint? = null
    @Volatile private var readThread: Thread? = null
    private val running = AtomicBoolean(false)
    @Volatile private var handler: ((ByteArray, Int) -> Unit)? = null
    private val writeLock = Object()

    @Volatile
    var lastError: String? = null
        private set
    /**
     * True once [stop] found the read thread still inside `bulkTransfer` and left the interface
     * claimed. Sticky on purpose: the caller reads it AFTER stop() to explain why its next claim
     * fails ("another process holds it" — it is this one), and a second stop() must not clear it.
     */
    @Volatile var releaseDeferred = false; private set

    /** Consecutive write failures. The write path is the unambiguous liveness signal, not reads. */
    @Volatile
    var writeFailures = 0
        private set

    /** The OCBM accessory by VID/PID, or null. No logging: `OcbmProbe.awaitClaimable` polls this. */
    fun findQuiet(): UsbDevice? {
        val all = usb.deviceList.values
        return all.firstOrNull { it.vendorId == VID_CARLINKIT && it.productId == PID_OCBM }
    }

    /**
     * Every device [UsbManager.getDeviceList] currently hands this UID. Edge-logged by
     * [OcbmProbe.awaitClaimable] when the OCBM PID is absent — on the Equinox that wait produced
     * zero lines about *what* the host *does* see, so "USB not supported" and an empty Allow path
     * could not be told apart from NCM/stock mode.
     */
    fun inventory(): List<UsbDevice> = try {
        usb.deviceList.values.sortedBy { it.deviceName }
    } catch (t: Throwable) {
        log.w("deviceList threw ${t.javaClass.simpleName}: ${t.message}")
        emptyList()
    }

    fun describe(dev: UsbDevice): String = try {
        val kind = when {
            dev.vendorId == VID_CARLINKIT && dev.productId == PID_OCBM -> "OCBM"
            dev.vendorId == VID_CARLINKIT && dev.productId == PID_NCM -> "NCM-not-OCBM"
            dev.vendorId == VID_CARLINKIT && dev.productId == PID_STOCK -> "stock-not-OCBM"
            else -> "other"
        }
        "0x%04x:0x%04x %s (%s ifaces=%d class=%d)".format(
            dev.vendorId, dev.productId, dev.productName ?: dev.deviceName,
            kind, dev.interfaceCount, dev.deviceClass,
        )
    } catch (t: Throwable) {
        "0x%04x:0x%04x %s (${t.javaClass.simpleName})".format(
            dev.vendorId, dev.productId, dev.deviceName,
        )
    }

    fun inventoryFingerprint(): String =
        inventory().joinToString(",") { "0x%04x:0x%04x".format(it.vendorId, it.productId) }

    /** Cheap, authoritative check. The grant lives in system_server keyed by (device, uid). */
    fun hasPermission(dev: UsbDevice): Boolean = usb.hasPermission(dev)

    /**
     * Fire the permission request WITHOUT blocking on a broadcast.
     *
     * The dialog on this head unit frequently never appears, and when it does the result broadcast is
     * often lost — so a BLOCKING request is the wrong shape for a retry loop. The caller polls
     * [hasPermission] instead, which is authoritative regardless of whether the broadcast
     * arrives. Call this sparingly: each invocation can raise a system dialog.
     */
    fun requestPermissionAsync(dev: UsbDevice) = requestPermissionEarly(appCtx, dev)

    /**
     * Walk every interface and claim the one carrying the bulk pair. Rather than hardcoding endpoint addresses we pick
     * the interface that actually carries a bulk IN + bulk OUT pair — some units differ.
     */
    fun open(dev: UsbDevice): Boolean {
        val c = usb.openDevice(dev)
        if (c == null) {
            // A fault, not a degradation: the caller only gets here with the device present and
            // hasPermission() true, so a null connection is the framework refusing an open it said
            // it would allow.
            lastError = "openDevice returned null (permission?)"
            log.e("claim FAILED: $lastError")
            SessionTrace.Board.failed(OcbmBoard.USB_CLAIM, "${dev.deviceName}: $lastError")
            return false
        }
        var refused = 0
        // Vendor-specific interfaces first, then the rest; never mass storage. The GM-EV uDisk
        // composite (`accessory,mass_storage`, ccpa/rootfs/script/ocbm_udisk.sh) puts a second
        // bulk pair on IF1 (class 8) that the kernel's usb-storage owns — claiming it would either
        // fail or yank the disk GM enumerated us for, and HELLO at a SCSI endpoint never answers.
        val order = (0 until dev.interfaceCount)
            .filter { dev.getInterface(it).interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE }
            .sortedByDescending { dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC }
        for (i in order) {
            val itf = dev.getInterface(i)
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN && inEp == null) inEp = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT && outEp == null) outEp = ep
            }
            if (inEp != null && outEp != null) {
                if (!c.claimInterface(itf, true)) {
                    log.w("claimInterface(${itf.id}) FAILED — another process may hold it")
                    refused++
                    continue
                }
                conn = c; iface = itf; epIn = inEp; epOut = outEp
                val detail = "iface=%d class=0x%02x IN=0x%02x OUT=0x%02x mps=%d"
                    .format(itf.id, itf.interfaceClass, inEp.address, outEp.address, inEp.maxPacketSize)
                log.i("claimed interface ${itf.id} (class 0x%02x) IN=0x%02x OUT=0x%02x mps=%d"
                    .format(itf.interfaceClass, inEp.address, outEp.address, inEp.maxPacketSize))
                log.i("no AOA control handshake performed — OCBM is a raw byte pipe")
                noteClaimedIdentity(dev)
                SessionTrace.Board.up(OcbmBoard.USB_CLAIM, "${dev.deviceName} $detail")
                return true
            }
        }
        // Name the real reason. Before 2026-09-10 a bulk-pair interface that claimInterface()
        // REFUSED fell through to "no interface with a bulk IN+OUT pair", which sent the operator
        // looking at descriptors when the fault was another process holding the interface.
        lastError = if (refused > 0) "claimInterface refused on $refused bulk-pair interface(s) — another process holds it"
                    else "no interface with a bulk IN+OUT pair"
        log.e("claim FAILED: $lastError")
        SessionTrace.Board.failed(OcbmBoard.USB_CLAIM, "${dev.deviceName}: $lastError")
        c.close()
        return false
    }

    /** One identity line per successful claim. The serial is not readable before the grant. */
    private fun noteClaimedIdentity(dev: UsbDevice) {
        runCatching {
            val serial = try {
                dev.serialNumber?.trim().orEmpty()
            } catch (_: SecurityException) {
                ""
            }
            val ifaces = (0 until dev.interfaceCount).joinToString(" ") { i ->
                val itf = dev.getInterface(i)
                "[$i] ${itf.interfaceClass}/${itf.interfaceSubclass}/${itf.interfaceProtocol}"
            }
            log.i(
                "claimed dev: serial=${serial.ifEmpty { "<empty>" }} mfr=${dev.manufacturerName} " +
                    "product=${dev.productName} class=${dev.deviceClass}/${dev.deviceSubclass}/${dev.deviceProtocol} " +
                    "ifaces=$ifaces"
            )
            if (serial.isEmpty() || serial.startsWith("0123456789")) {
                log.w("serial is empty or starts with 0123456789")
            }
            if (serial.isEmpty()) return@runCatching
            val prefs = appCtx.getSharedPreferences(UsbIdentity.PREFS, Context.MODE_PRIVATE)
            val prev = prefs.getString(UsbIdentity.KEY_SERIAL, null)
            if (prev != null && prev != serial) log.i("serial changed")
            if (prev != serial) prefs.edit().putString(UsbIdentity.KEY_SERIAL, serial).apply()
        }.onFailure { log.w("claimed dev: identity read failed: ${it.message}") }
    }

    override fun setReadHandler(handler: (ByteArray, Int) -> Unit) { this.handler = handler }

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = Thread({ readLoop() }, "ocbm-read")
        t.isDaemon = true
        readThread = t
        t.start()
        SessionTrace.Board.up(OcbmBoard.USB_READ, "ocbm-read thread, ${READ_BUF}B reads, ${READ_TIMEOUT_MS}ms timeout")
    }

    private fun readLoop() {
        val buf = ByteArray(READ_BUF)
        while (running.get()) {
            // Any throw here would otherwise propagate out of the thread and Android's default
            // handler would kill the PROCESS — one malformed frame taking down the instrument
            // mid-session and leaving the box subscribed until its watchdog fires.
            try {
                val c = conn ?: break
                val ep = epIn ?: break
                val t0 = System.nanoTime()
                val n = try {
                    c.bulkTransfer(ep, buf, buf.size, READ_TIMEOUT_MS)
                } catch (t: Throwable) {
                    // Do not silently coerce to "idle" — a real failure must be visible.
                    log.w("read: bulkTransfer threw ${t.javaClass.simpleName}: ${t.message}")
                    -1
                }
                // bulkTransfer returns -1 for BOTH a timeout and a hard failure. A timeout takes ~the
                // full READ_TIMEOUT_MS; a device-gone failure (ENODEV) returns almost immediately. An
                // immediate -1 spun this loop at ~1000/s; detect it by elapsed time, back off, and
                // after a sustained run declare the transport dead so the session can tear down.
                if (n > 0) {
                    readFailures = 0; rapidMinusOne = 0; handler?.invoke(buf, n)
                } else {
                    val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                    if (elapsedMs < 10) {
                        if (++rapidMinusOne >= 50) {
                            log.e("read: rapid -1 x$rapidMinusOne — device gone, stopping")
                            SessionTrace.Board.failed(OcbmBoard.USB_READ, "rapid -1 x$rapidMinusOne — device gone (ENODEV): adapter unplugged, box rebooted, or gadget stalled")
                            break
                        }
                        Thread.sleep(20)
                    } else { rapidMinusOne = 0; Thread.sleep(1) }
                }
            } catch (t: Throwable) {
                log.e("read loop error (continuing): ${t.javaClass.simpleName}: ${t.message}")
                readFailures++
                if (readFailures >= 20) {
                    log.e("read: too many consecutive errors — stopping")
                    SessionTrace.Board.failed(OcbmBoard.USB_READ, "$readFailures consecutive read-loop errors — last: ${t.javaClass.simpleName}: ${t.message}")
                    break
                }
            }
        }
        log.i("read loop ended")
        onTransportDead?.let { if (running.get()) it() }
    }

    private var readFailures = 0
    private var rapidMinusOne = 0

    /** Invoked if the read loop dies while still nominally running, so the session can tear down. */
    @Volatile
    var onTransportDead: (() -> Unit)? = null

    override fun writeBulk(data: ByteArray): Boolean {
        val c = conn ?: return false
        val ep = epOut ?: return false
        synchronized(writeLock) {
            if (!running.get()) return false
            var off = 0
            while (off < data.size) {
                val len = minOf(WRITE_CHUNK, data.size - off)
                var thrown: Throwable? = null
                val n = try { c.bulkTransfer(ep, data, off, len, WRITE_TIMEOUT_MS) } catch (t: Throwable) { thrown = t; -1 }
                if (n < 0) {
                    writeFailures++
                    lastError = "bulkTransfer OUT failed at offset $off" + (thrown?.let { ": ${it.javaClass.simpleName}: ${it.message}" } ?: "")
                    log.w("write FAILED: $lastError (consecutive=$writeFailures)")
                    return false
                }
                off += n
                if (n == 0) { lastError = "bulkTransfer OUT wrote 0 bytes"; return false }
            }
        }
        writeFailures = 0
        return true
    }

    override fun stop() {
        // Deliberately NOT gated on the running CAS: if open() succeeded but start() was never
        // reached, the interface would otherwise stay claimed for the life of the process — a
        // claimed-but-unusable device that nothing can recover.
        running.set(false)
        val rt = readThread
        try { rt?.join(1500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        readThread = null
        val readAlive = rt?.isAlive == true
        // Take the write lock before releasing: bulkTransfer is not interruptible, so without this
        // the connection can be closed with native bulk I/O still in flight on it. On this gadget
        // that is exactly the stall that needs a power cycle.
        synchronized(writeLock) {
            val hadClaim = conn != null
            if (readAlive) {
                // The read thread is still parked in a bulkTransfer (gadget stall). Closing the
                // connection under a live native transfer is the exact power-cycle stall — defer the
                // release and just drop our refs. Leaks one fd until process exit; far better than a
                // wedged USB gadget.
                log.w("read thread did not exit in 1500ms — deferring interface release to avoid closing under a live transfer")
                releaseDeferred = true
                if (hadClaim) SessionTrace.Board.down(OcbmBoard.USB_CLAIM, "release DEFERRED — read thread stuck in a native transfer; one fd leaked until process exit")
                SessionTrace.Board.down(OcbmBoard.USB_READ, "stop requested but the thread is still inside bulkTransfer")
            } else {
                try { iface?.let { conn?.releaseInterface(it) } } catch (_: Throwable) {}
                try { conn?.close() } catch (_: Throwable) {}
                if (hadClaim) SessionTrace.Board.down(OcbmBoard.USB_CLAIM, "released")
                if (rt != null) SessionTrace.Board.down(OcbmBoard.USB_READ, "stopped")
            }
            conn = null; iface = null; epIn = null; epOut = null
        }
        log.i("usb transport closed")
    }

}
