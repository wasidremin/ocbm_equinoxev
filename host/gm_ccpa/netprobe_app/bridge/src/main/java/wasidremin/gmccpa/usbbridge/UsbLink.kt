package wasidremin.gmccpa.usbbridge

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import wasidremin.gmccpa.bridgeapi.BridgeProtocol

/**
 * One claimed adapter at a time. The pump moves bulk IN into a socket GM CCPA reads; writes arrive
 * as binder calls. Reads and writes are the ones GM CCPA's own `UsbBulkTransport` makes on a direct
 * claim (16 KiB, 250 ms read timeout, 2 s write timeout, 16 KiB OUT transfers), so the adapter sees
 * the same traffic either way, and the framing stays GM CCPA's.
 *
 * Closing never releases the interface under a transfer that is still running: on this gadget that
 * is the stall that needs a power cycle. A pump that does not leave its read within 1.5 s keeps the
 * claim until the process ends, as `UsbBulkTransport.stop` does.
 */
internal class UsbLink(private val usb: UsbManager) {
    private class Open(
        val name: String,
        val conn: UsbDeviceConnection,
        val iface: UsbInterface,
        val epIn: UsbEndpoint,
        val epOut: UsbEndpoint,
        val sink: ParcelFileDescriptor,
    ) {
        @Volatile var running = true
        @Volatile var pump: Thread? = null
        val closed = AtomicBoolean(false)
        val bytesIn = AtomicLong()
        val bytesOut = AtomicLong()
    }

    private val current = AtomicReference<Open?>()
    private val openLock = Any()
    private val writeLock = Any()

    @Volatile var lastError = ""
        private set

    @Volatile private var lastClose = "never opened"

    fun hasPermission(name: String?): Boolean {
        val dev = name?.let { usb.deviceList[it] } ?: return false
        return usb.hasPermission(dev)
    }

    fun open(name: String?): ParcelFileDescriptor? = synchronized(openLock) {
        current.getAndSet(null)?.let { shut(it, "superseded by a new open") }
        val dev = name?.let { usb.deviceList[it] } ?: return fail("$name is not attached")
        if (!usb.hasPermission(dev)) return fail("no USB permission for $name")
        val conn = usb.openDevice(dev) ?: return fail("openDevice($name) returned null")
        // UsbBulkTransport.open's rule: vendor-specific interfaces first, never mass storage. The
        // uDisk composite's IF1 is the disk GM admitted the adapter for, and the kernel owns it.
        val order = (0 until dev.interfaceCount)
            .filter { dev.getInterface(it).interfaceClass != UsbConstants.USB_CLASS_MASS_STORAGE }
            .sortedByDescending { dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC }
        for (i in order) {
            val itf = dev.getInterface(i)
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (e in 0 until itf.endpointCount) {
                val ep = itf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN && epIn == null) epIn = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT && epOut == null) epOut = ep
            }
            if (epIn == null || epOut == null) continue
            if (!conn.claimInterface(itf, true)) continue
            val pair = try {
                ParcelFileDescriptor.createSocketPair()
            } catch (e: IOException) {
                conn.releaseInterface(itf)
                conn.close()
                return fail("socket pair: ${e.message}")
            }
            // A burst of video must not stall the pump while GM CCPA is still reading the last one.
            // The kernel caps this at wmem_max.
            runCatching { Os.setsockoptInt(pair[0].fileDescriptor, OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, SEND_BUFFER) }
            val o = Open(dev.deviceName, conn, itf, epIn, epOut, pair[0])
            current.set(o)
            o.pump = Thread({ pump(o) }, "bridge-pump").apply { isDaemon = true; start() }
            lastError = ""
            val detail = "iface=%d class=0x%02x IN=0x%02x OUT=0x%02x".format(itf.id, itf.interfaceClass, epIn.address, epOut.address)
            Events.add("opened ${dev.deviceName} $detail")
            Log.i(TAG, "opened ${dev.deviceName} $detail")
            return pair[1]
        }
        conn.close()
        return fail("no claimable interface with a bulk IN+OUT pair on $name")
    }

    /** Bytes written, or -1. */
    fun write(data: ByteArray?): Int {
        if (data == null || data.size > BridgeProtocol.MAX_WRITE) return -1
        val o = current.get() ?: return -1
        synchronized(writeLock) {
            if (o.closed.get()) return -1
            var off = 0
            while (off < data.size) {
                val len = minOf(WRITE_CHUNK, data.size - off)
                val n = o.conn.bulkTransfer(o.epOut, data, off, len, WRITE_TIMEOUT_MS)
                if (n <= 0) return -1
                off += n
            }
            o.bytesOut.addAndGet(off.toLong())
            return off
        }
    }

    fun close(reason: String) {
        current.getAndSet(null)?.let { shut(it, reason) }
    }

    fun onDetached(name: String) {
        val o = current.get() ?: return
        if (o.name == name && current.compareAndSet(o, null)) shut(o, "adapter detached")
    }

    fun describe(): String {
        val o = current.get() ?: return "closed(last=$lastClose${if (lastError.isNotEmpty()) "; error=$lastError" else ""})"
        return "open(${o.name} in=${o.bytesIn.get() / 1024}KiB out=${o.bytesOut.get() / 1024}KiB)"
    }

    private fun pump(o: Open) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val out = FileOutputStream(o.sink.fileDescriptor)
        val buf = ByteArray(READ_BUF)
        var rapid = 0
        var why = "pump stopped"
        try {
            while (o.running) {
                val t0 = System.nanoTime()
                val n = o.conn.bulkTransfer(o.epIn, buf, buf.size, READ_TIMEOUT_MS)
                if (n > 0) {
                    rapid = 0
                    out.write(buf, 0, n)
                    o.bytesIn.addAndGet(n.toLong())
                    continue
                }
                // -1 is both a timeout and a dead device. A timeout takes the full 250 ms; ENODEV
                // returns at once, so a run of instant ones means the adapter is gone.
                if ((System.nanoTime() - t0) / 1_000_000 < 10) {
                    if (++rapid >= 50) {
                        why = "device gone (50 instant read failures)"
                        break
                    }
                    Thread.sleep(20)
                } else {
                    rapid = 0
                }
            }
        } catch (e: IOException) {
            why = "GM CCPA stopped reading (${e.message})"
        } catch (e: InterruptedException) {
            why = "pump interrupted"
        } catch (t: Throwable) {
            why = "pump error ${t.javaClass.simpleName}: ${t.message}"
        }
        if (o.running && current.compareAndSet(o, null)) shut(o, why)
    }

    /** Idempotent, from any thread, never holding [openLock] while it waits on the pump. */
    private fun shut(o: Open, reason: String) {
        if (!o.closed.compareAndSet(false, true)) return
        o.running = false
        val p = o.pump
        val onPump = p === Thread.currentThread()
        if (p != null && !onPump) runCatching { p.join(PUMP_JOIN_MS) }
        val stuck = p != null && !onPump && p.isAlive
        synchronized(writeLock) {
            if (stuck) {
                Events.add("release deferred: the read is still inside a transfer")
            } else {
                runCatching { o.conn.releaseInterface(o.iface) }
                runCatching { o.conn.close() }
            }
        }
        // GM CCPA sees EOF.
        runCatching { Os.shutdown(o.sink.fileDescriptor, OsConstants.SHUT_RDWR) }
        runCatching { o.sink.close() }
        lastClose = reason
        Events.add("closed ${o.name}: $reason (in=${o.bytesIn.get() / 1024}KiB out=${o.bytesOut.get() / 1024}KiB)")
        Log.i(TAG, "closed ${o.name}: $reason")
    }

    private fun fail(reason: String): ParcelFileDescriptor? {
        lastError = reason
        Events.add("open failed: $reason")
        Log.w(TAG, "open failed: $reason")
        return null
    }

    private companion object {
        const val TAG = "GmCcpaUsbBridge"
        const val READ_BUF = 16384
        const val READ_TIMEOUT_MS = 250
        const val WRITE_CHUNK = 16384
        const val WRITE_TIMEOUT_MS = 2000
        const val PUMP_JOIN_MS = 1500L
        const val SEND_BUFFER = 4 * 1024 * 1024
    }
}
