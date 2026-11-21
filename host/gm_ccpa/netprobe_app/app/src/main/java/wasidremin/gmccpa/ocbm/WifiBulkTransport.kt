package wasidremin.gmccpa.ocbm

import wasidremin.gmccpa.ProbeLog
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.SocketFactory

/**
 * [RawBulkTransport] over the Wi-Fi link (T15): a TCP socket to the adapter's `:7020` on the car's hotspot,
 * the OCBM byte stream inside OCBW v1 records ([OcbwLink]). To [OcbmClient] it is the USB claim by another
 * road: the same unframed stream.
 *
 * The adapter accepts ONE host: USB and TCP race at ocbmd's start and the first host to speak gets the
 * link (`ccpa/ocbmd/src/wifi.rs`). A lost link (EOF, a failed record, a write stuck past
 * [WRITE_STALL_MS]) fires [onTransportDead] once, and [WifiLink] connects again.
 *
 * Ported from mammut3's enigy/carlink PR #2 (`ocbm/WifiBulkTransport.kt`, aca4733, Unlicense; NOTICE.md),
 * with this app's transport seam (four methods and [onTransportDead], as [UsbBulkTransport]).
 *
 * A vanished box: TCP keepalive is on, and a write that can't drain within [WRITE_STALL_MS] closes the
 * socket from the watchdog (Android sockets have no portable send timeout), which unblocks the writer.
 */
class WifiBulkTransport private constructor(
    private val socket: Socket,
    private val session: OcbwLink.Session,
    /** `host:port`, remembered for the next start ([WifiLink]); never logged. */
    val peer: String,
    private val log: ProbeLog.Logger,
) : RawBulkTransport {
    companion object {
        const val CONNECT_TIMEOUT_MS = 4000
        const val HANDSHAKE_TIMEOUT_MS = 4000
        const val WRITE_STALL_MS = 3000L
        private const val WRITE_CHUNK = 16384
        private const val WATCHDOG_PERIOD_MS = 500L

        /**
         * Connect to [host]:[port] and run the OCBW handshake with [key]. [socketFactory] binds the socket to
         * the network the box is on (`Network.socketFactory`), or the default route. Throws on any failure,
         * with the reason in the message: no route, refused (no listener: ocbmd has a USB host, or no key), a
         * timeout, or [OcbwLink.LinkException] (not a box, or a different link key).
         */
        fun connect(
            host: String,
            port: Int,
            key: ByteArray,
            log: ProbeLog.Logger,
            connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
            socketFactory: SocketFactory = SocketFactory.getDefault(),
        ): WifiBulkTransport {
            val s = socketFactory.createSocket()
            var linked = false
            try {
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(host, port), connectTimeoutMs)
                s.soTimeout = HANDSHAKE_TIMEOUT_MS
                val session = OcbwLink.hostHandshake(s.getInputStream(), s.getOutputStream(), key)
                s.soTimeout = 0
                linked = true
                return WifiBulkTransport(s, session, "$host:$port", log)
            } finally {
                if (!linked) runCatching { s.close() }
            }
        }
    }

    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
    private val output: OutputStream = socket.getOutputStream()
    private val running = AtomicBoolean(false)
    private val deadAnnounced = AtomicBoolean(false)
    private val writeLock = Any()

    @Volatile private var readThread: Thread? = null

    @Volatile private var watchdog: Thread? = null

    @Volatile private var handler: ((ByteArray, Int) -> Unit)? = null

    /** Called once, off the read thread, when the link is lost while it was running (as [UsbBulkTransport]'s). */
    @Volatile var onTransportDead: (() -> Unit)? = null

    /** System.nanoTime() when the write in progress began, 0 when none is. */
    @Volatile private var writeSince = 0L

    @Volatile var bytesRead: Long = 0
        private set

    @Volatile var records: Long = 0
        private set

    @Volatile var bytesWritten: Long = 0
        private set

    @Volatile var lastError: String? = null
        private set

    override fun setReadHandler(handler: (ByteArray, Int) -> Unit) {
        this.handler = handler
    }

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        readThread = Thread({ readLoop() }, "ocbm-wifi-read").apply { isDaemon = true }.also { it.start() }
        watchdog = Thread({ watchWrites() }, "ocbm-wifi-watchdog").apply { isDaemon = true }.also { it.start() }
    }

    private fun readLoop() {
        // Anything not caught below (a handler that throws) still ends the link: the finally announces it.
        var reason = "Wi-Fi read loop ended unexpectedly"
        try {
            while (running.get()) {
                val plain = session.rx.read(input)
                if (plain == null) {
                    reason = "the adapter closed the Wi-Fi link (EOF)"
                    return
                }
                records++
                bytesRead += plain.size
                if (plain.isNotEmpty()) handler?.invoke(plain, plain.size)
            }
            reason = "stopped"
        } catch (e: OcbwLink.LinkException) {
            reason = "Wi-Fi link record refused: ${e.message}"
        } catch (e: IOException) {
            reason = "Wi-Fi link read failed: ${e.message}"
        } finally {
            log.i("wifi read loop ended ($reason) — $records records, $bytesRead B")
            die(reason)
        }
    }

    /** Close a socket whose write has been stuck too long: the writer's blocked write() then throws. */
    private fun watchWrites() {
        while (running.get()) {
            try {
                Thread.sleep(WATCHDOG_PERIOD_MS)
            } catch (_: InterruptedException) {
                return
            }
            val since = writeSince
            if (since != 0L && System.nanoTime() - since > WRITE_STALL_MS * 1_000_000) {
                lastError = "a write didn't drain in $WRITE_STALL_MS ms (the adapter is gone from the hotspot?)"
                log.w("wifi: $lastError — closing the link")
                runCatching { socket.close() }
                return
            }
        }
    }

    private fun die(reason: String) {
        val wasRunning = running.getAndSet(false)
        if (!wasRunning) return
        lastError = lastError ?: reason
        runCatching { socket.close() }
        val h = onTransportDead
        if (h != null && deadAnnounced.compareAndSet(false, true)) {
            Thread({ runCatching { h() } }, "ocbm-dead").apply { isDaemon = true }.start()
        }
    }

    override fun writeBulk(data: ByteArray): Boolean {
        synchronized(writeLock) {
            if (!running.get()) return false
            try {
                writeSince = System.nanoTime()
                var off = 0
                while (off < data.size) {
                    val len = minOf(WRITE_CHUNK, data.size - off)
                    output.write(session.tx.seal(data, off, len))
                    off += len
                }
                output.flush()
                bytesWritten += data.size
                return true
            } catch (e: IOException) {
                lastError = lastError ?: "Wi-Fi link write failed: ${e.message}"
                log.w("wifi write FAILED: $lastError")
                return false
            } finally {
                writeSince = 0L
            }
        }
    }

    override fun stop() {
        running.set(false)
        runCatching { socket.close() } // unblocks a read() parked in readLoop
        listOf(readThread, watchdog).forEach { t ->
            if (t != null && t !== Thread.currentThread()) {
                t.interrupt()
                try {
                    t.join(1500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        readThread = null
        watchdog = null
        log.i("wifi transport closed ($records records in, $bytesWritten B out)")
    }
}
