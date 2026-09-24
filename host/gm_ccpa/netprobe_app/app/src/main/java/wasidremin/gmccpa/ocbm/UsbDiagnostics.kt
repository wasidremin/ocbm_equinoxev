package wasidremin.gmccpa.ocbm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import wasidremin.gmccpa.ProbeLog

/**
 * Car-side USB diagnostics that do not need adb.
 *
 * The Equinox wait produced exactly one conclusion per link attempt — "waiting for the OCBM
 * accessory 0x1314:0x2d00 to appear" — with no record of what the host actually did between the
 * dongle being plugged in and our next 2 s poll. The hotplug broadcast is asynchronous and the
 * resolver routes the device away first, so sampling [UsbManager.getDeviceList] on the claim loop
 * can miss a device that was enumerated and then disappeared. This class closes those gaps:
 *
 *  - **A hotplug watcher** (process-scoped, started from [wasidremin.gmccpa.MainActivity.onCreate]
 *    so it is alive whether or not a link is running): logs ACTION_USB_DEVICE_ATTACHED /
 *    ACTION_USB_DEVICE_DETACHED with the full VID:PID, product/manufacturer/serial, interface and
 *    endpoint walk, and whether we hold permission. This is the ground truth of what Android
 *    delivered to our UID, independent of the AAOS resolver and of any link attempt.
 *  - **A deep inventory** ([dumpDeep]): one line per device with every interface and endpoint,
 *    device class, bus#/#dev path, and permission state — everything needed to tell "the car's
 *    hub silicon" from "the dongle in a mode we do not match".
 *  - **A probe** ([runProbe]): deep dump, arm a replug window, re-dump on any inventory change or
 *    detach — the shape to press in the car ("USB Probe", replug the dongle, "Upload Logs") and
 *    compare with the emulator, where every stage of the same sequence is known-good.
 *
 * Logging is edge-triggered and rate-limited: attach/detach log immediately; inventory-change
 * logging coalesces per second so a flapping port cannot flood the capture ring; the probe's
 * replug window closes itself. Everything goes through [ProbeLog] so it lands in the existing
 * capture and the redacted Upload Logs path unchanged.
 */
object UsbDiagnostics {

    private val log = ProbeLog.sub("usbdiag")
    /** One watcher per process; a second Activity generation must not double-register. */
    private val started = AtomicBoolean(false)
    /** Set while a probe's replug window is open, so the watcher logs every event verbosely. */
    @Volatile private var verboseWindow = false
    /** Serializes probe runs; a second tap while one is live just extends nothing. */
    private val probeBusy = AtomicBoolean(false)

    private const val TAG_ATTACH = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
    private const val TAG_DETACH = "android.hardware.usb.action.USB_DEVICE_DETACHED"
    /** Replug window for [runProbe]: long enough to unplug/replug, short enough to self-close. */
    private const val PROBE_WINDOW_MS = 60_000L
    /** Inventory-change events coalesce to at most one line per second. */
    private const val CHANGE_MIN_INTERVAL_MS = 1_000L

    @Volatile private var lastChangeLogAt = 0L
    @Volatile private var lastFingerprint = ""

    private fun actionOf(dev: UsbDevice?): String =
        if (dev == null) "unknown" else "0x%04x:0x%04x".format(dev.vendorId, dev.productId)

    /** Is this one of the CarLinkit identities the app can ever use? */
    private fun isOcbmFamily(dev: UsbDevice): Boolean =
        dev.vendorId == UsbBulkTransport.VID_CARLINKIT

    /**
     * Register the hotplug watcher once per process. Attach/detach both come here; system
     * broadcasts, so a normal manifest receiver would need the runtime filter on some OEM builds —
     * this code-targeted receiver is the portable shape and needs no manifest change.
     */
    fun start(ctx: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val main = Handler(Looper.getMainLooper())

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                @Suppress("DEPRECATION")
                val dev: UsbDevice? = i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                when (i.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        // Always log, regardless of any link: this is the ground truth of what
                        // Android handed our UID, and the reference car failure is exactly that
                        // nothing ever arrives here.
                        val brief = dev?.let { describeBrief(it, usb) } ?: "(no EXTRA_DEVICE)"
                        log.i("ATTACH ${actionOf(dev)} — $brief")
                        if (dev != null && isOcbmFamily(dev)) {
                            log.i("ATTACH is the adapter family — OCBM=${dev.productId == UsbBulkTransport.PID_OCBM}, permission held=${usb.hasPermission(dev)}")
                            if (verboseWindow) dumpDeep(app, "post-attach")
                        }
                        noteChange(app)
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        // Serial when present: distinguishes a replug of the same unit from a
                        // different one on a multi-port hub, which matters when the car exposes
                        // more than one port through bridge silicon.
                        log.i("DETACH ${actionOf(dev)} — ${dev?.let { describeBrief(it, usb) } ?: "(no EXTRA_DEVICE)"}")
                        noteChange(app)
                    }
                }
            }
        }
        try {
            // Plain registration: USB_DEVICE_ATTACHED/DETACHED are protected system broadcasts, so
            // the targetSdk>=34 non-exported-flag requirement does not apply to them (and the
            // RECEIVER_* constants do not exist in the android-32 jar this build compiles against).
            app.registerReceiver(receiver, filter)
            log.i("hotplug watcher registered (attach+detach)")
        } catch (t: Throwable) {
            started.set(false)
            log.e("hotplug watcher registration FAILED: ${t.javaClass.simpleName}: ${t.message}")
        }

        // Belt and braces: the attach broadcast is asynchronous and could in principle be lost on a
        // busy AAOS build, so a 5 s poll reconciles the watcher with the authoritative deviceList.
        // One line only when the fingerprint actually changes.
        val poll = object : Runnable {
            override fun run() {
                try { noteChange(app) } catch (_: Throwable) {}
                main.postDelayed(this, 5_000)
            }
        }
        main.postDelayed(poll, 5_000)
    }

    /** Coalesced inventory-change logging shared by the watcher and the 5 s reconciler. */
    private fun noteChange(app: Context) {
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        val fp = try {
            usb.deviceList.values.sortedBy { it.deviceName }
                .joinToString(",") { "0x%04x:0x%04x".format(it.vendorId, it.productId) }
        } catch (t: Throwable) { return }
        if (fp == lastFingerprint) return
        val first = lastFingerprint.isEmpty()
        lastFingerprint = fp
        val now = SystemClock.elapsedRealtime()
        if (!first && now - lastChangeLogAt < CHANGE_MIN_INTERVAL_MS) return
        lastChangeLogAt = now
        if (fp.isEmpty()) {
            log.i("inventory changed -> EMPTY deviceList")
        } else {
            log.i("inventory changed (${fp.split(',').size} devices)${if (verboseWindow) ": $fp" else ""}")
            if (verboseWindow) dumpDeep(app, "inventory-change")
        }
    }

    /** One-line brief used by hotplug events. */
    private fun describeBrief(dev: UsbDevice, usb: UsbManager): String = try {
        val kind = when {
            dev.vendorId == UsbBulkTransport.VID_CARLINKIT && dev.productId == UsbBulkTransport.PID_OCBM -> "OCBM"
            dev.vendorId == UsbBulkTransport.VID_CARLINKIT && dev.productId == UsbBulkTransport.PID_NCM -> "NCM"
            dev.vendorId == UsbBulkTransport.VID_CARLINKIT && dev.productId == UsbBulkTransport.PID_STOCK -> "stock"
            else -> "other"
        }
        "product='${dev.productName}' mfr='${dev.manufacturerName}' serial=${dev.serialNumber ?: "-"} " +
            "ifaces=${dev.interfaceCount} class=${dev.deviceClass} perm=${usb.hasPermission(dev)} [$kind]"
    } catch (t: Throwable) {
        "describe failed (${t.javaClass.simpleName})"
    }

    /**
     * Deep dump: one line per device, then one line per interface with its endpoints. This is the
     * evidence needed to answer "what IS the car enumerating" without adb — e.g. whether the
     * dongle shows up as the NCM/IAP bridge class device, an unconfigured gadget, or not at all.
     */
    fun dumpDeep(ctx: Context, reason: String) {
        val app = ctx.applicationContext
        val usb = app.getSystemService(Context.USB_SERVICE) as UsbManager
        log.i("---- deep USB inventory ($reason) ----")
        val devices = try {
            usb.deviceList.values.sortedBy { it.deviceName }
        } catch (t: Throwable) {
            log.e("deviceList threw ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        if (devices.isEmpty()) {
            log.i("deviceList EMPTY — the host stack has exposed nothing to this UID")
            return
        }
        for (dev in devices) {
            val sb = StringBuilder()
            sb.append("0x%04x:0x%04x".format(dev.vendorId, dev.productId))
            sb.append(" '${dev.productName ?: "-"}'/'${dev.manufacturerName ?: "-"}'")
            sb.append(" serial=${dev.serialNumber ?: "-"}")
            sb.append(" devClass=${dev.deviceClass} devId=${dev.deviceId}")
            try { sb.append(" path=${dev.deviceName}") } catch (_: Throwable) {}
            sb.append(" perm=${usb.hasPermission(dev)}")
            log.i(sb.toString())
            for (i in 0 until dev.interfaceCount) {
                val iface = dev.getInterface(i)
                val eps = StringBuilder()
                for (e in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(e)
                    val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val type = when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "bulk"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "int"
                        UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "ctrl"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "isoc"
                        else -> "?"
                    }
                    eps.append(" ep0x%02x:%s/%s/%d".format(ep.address, dir, type, ep.maxPacketSize))
                }
                log.i("    iface $i class=0x%02x proto=%d alt=%d eps:${eps}".format(iface.interfaceClass, iface.interfaceProtocol, iface.alternateSetting))
            }
        }
        log.i("---- deep USB inventory end (${devices.size} devices) ----")
    }

    /**
     * The car-side probe: deep dump now, open a replug window, re-dump on every change, and close
     * with a summary that names what to do next. Self-closing; safe to tap twice.
     */
    fun runProbe(ctx: Context) {
        if (!probeBusy.compareAndSet(false, true)) {
            log.i("probe already running — replug the dongle, the window is open")
            return
        }
        Thread({
            try {
                dumpDeep(ctx, "usb_probe start")
                verboseWindow = true
                val before = fingerprint(ctx)
                log.i("probe: replug window open for ${PROBE_WINDOW_MS / 1000}s — unplug and replug the adapter NOW")
                val endAt = SystemClock.elapsedRealtime() + PROBE_WINDOW_MS
                var sawDetach = false
                var sawChange = false
                var last = before
                while (SystemClock.elapsedRealtime() < endAt) {
                    Thread.sleep(1_000)
                    val now = fingerprint(ctx)
                    if (now != last) {
                        sawChange = true
                        log.i("probe: inventory changed ($last -> $now)")
                        last = now
                        dumpDeep(ctx, "probe change")
                    }
                }
                verboseWindow = false
                val after = fingerprint(ctx)
                val ocbmSeen = after.split(',').any {
                    it.equals("0x1314:0x2d00", ignoreCase = true)
                }
                log.i("probe: window closed — changed=$sawChange ocbm-present=${ocbmSeen}")
                when {
                    ocbmSeen -> log.i("probe: the OCBM accessory IS visible to this UID — the blocker is downstream (permission / claim), not enumeration")
                    sawChange -> log.i("probe: the bus CHANGED during the window but never showed 0x1314:0x2d00 — capture the replug lines above; the car enumerates the dongle as something else")
                    else -> log.i("probe: NO bus change during the window — either the dongle was not replugged, or this port never re-enumerates it for Android (GM bridge silicon / port policy)")
                }
            } catch (t: Throwable) {
                log.e("probe failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                verboseWindow = false
                probeBusy.set(false)
            }
        }, "usb-diag-probe").apply { isDaemon = true }.start()
    }

    private fun fingerprint(ctx: Context): String = try {
        val usb = ctx.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
        usb.deviceList.values.sortedBy { it.deviceName }
            .joinToString(",") { "0x%04x:0x%04x".format(it.vendorId, it.productId) }
    } catch (t: Throwable) { "<error>" }
}
