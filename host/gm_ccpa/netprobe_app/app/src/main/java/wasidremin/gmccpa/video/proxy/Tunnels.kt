package wasidremin.gmccpa.video.proxy

// Ported from enigy/carlink `app/src/main/kotlin/com/carlink/video/proxy/Tunnels.kt` at a04f4c0 (Unlicense; NOTICE.md).

import wasidremin.gmccpa.ocbm.Ocbm
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Route A's tunnels to the phone's proxy, through the box: each a byte stream between the app and the proxy's
 * port on the phone. Out as `INPUT_TUNNEL` sub-frames ([send]), in as `CH_TUNNEL` messages ([onMessage]). The box
 * connects a tunnel only to the phone (OCBM `receiver::tunnel`).
 */
class Tunnels(
    /** Sends one `INPUT_TUNNEL` sub-frame `[op][id][data]`; false when the link dropped it. Any thread. */
    private val send: (op: Byte, id: Int, data: ByteArray) -> Boolean,
) {
    private val open = ConcurrentHashMap<Int, Tunnel>()
    private val lastId = AtomicInteger(0)

    /** A tunnel to the proxy at [address] (the phone's proxyUrl) and [port], once the box has connected it. Blocks. */
    fun open(
        address: String,
        port: Int,
    ): Tunnel {
        val tunnel = Tunnel(freeId())
        open[tunnel.id] = tunnel
        val host = address.toByteArray(Charsets.UTF_8)
        val data =
            ByteBuffer
                .allocate(2 + host.size)
                .putShort(port.toShort())
                .put(host)
                .array()
        if (!send(Ocbm.TUNNEL_OPEN, tunnel.id, data)) {
            open.remove(tunnel.id)
            throw IOException("the adapter link took no tunnel")
        }
        tunnel.awaitOpen()
        return tunnel
    }

    /** One message from the box's tunnel seam: [op] over `[id][data]`. Runs on the OCBM read thread: hands off only. */
    fun onMessage(
        op: Int,
        payload: ByteArray,
    ) {
        val tunnel = payload.firstOrNull()?.let { open[it.toInt() and 0xFF] } ?: return
        when (op.toByte()) {
            Ocbm.TUNNEL_OPENED -> tunnel.opened()
            Ocbm.TUNNEL_DATA -> tunnel.arrived(payload.copyOfRange(1, payload.size))
            Ocbm.TUNNEL_CLOSE -> {
                open.remove(tunnel.id)
                tunnel.ended(String(payload, 1, payload.size - 1, Charsets.UTF_8).ifEmpty { "closed by the adapter" })
            }
        }
    }

    /** The session ended: every tunnel ends with it. */
    fun closeAll(why: String) {
        for (t in open.values) t.ended(why)
        open.clear()
        announced.set(false)
    }

    /** How many tunnels are open now. */
    val openCount: Int get() = open.size

    private val announced = AtomicBoolean(false)

    /** True the first time a tunnel carries a stream since the last [closeAll], for one log line a session. */
    fun firstOpen(): Boolean = announced.compareAndSet(false, true)

    private fun freeId(): Int {
        repeat(MAX_ID) {
            val id = lastId.updateAndGet { it % MAX_ID + 1 }
            if (!open.containsKey(id)) return id
        }
        throw IOException("all $MAX_ID tunnels are open")
    }

    /** One tunnel: [input] carries the proxy's bytes, [output] this app's. Close it when done. */
    inner class Tunnel internal constructor(
        val id: Int,
    ) : Closeable {
        private val chunks = LinkedBlockingQueue<ByteArray>()
        private val queued = AtomicLong(0)
        private val openLatch = CountDownLatch(1)

        /** Why it ended, once it has. */
        @Volatile private var why: String? = null

        internal fun opened() = openLatch.countDown()

        internal fun arrived(bytes: ByteArray) {
            if (queued.addAndGet(bytes.size.toLong()) > MAX_QUEUED) {
                close()
                ended("its reader fell ${MAX_QUEUED / MIB} MiB behind")
                return
            }
            chunks.offer(bytes)
        }

        internal fun ended(reason: String) {
            if (why == null) why = reason
            openLatch.countDown()
            chunks.offer(EOF)
        }

        internal fun awaitOpen() {
            // A load the player cancels interrupts its thread: that is ExoPlayer's InterruptedIOException, a cancellation,
            // not an unexpected error (enigy/carlink's bench, 12 Nov).
            val answered =
                try {
                    openLatch.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    close()
                    throw InterruptedIOException("the tunnel's open was cancelled").apply { initCause(e) }
                }
            val refused = why
            if (!answered || refused != null) {
                close()
                throw if (answered) IOException("no tunnel: $refused") else SocketTimeoutException("the adapter didn't open a tunnel")
            }
        }

        val input: InputStream =
            object : InputStream() {
                private var chunk = NOTHING
                private var at = 0

                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
                }

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    if (len == 0) return 0
                    while (at >= chunk.size) {
                        if (chunk === EOF) return -1
                        chunk = chunks.poll(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: throw SocketTimeoutException("the phone's proxy went quiet")
                        at = 0
                        queued.addAndGet(-chunk.size.toLong())
                    }
                    val n = minOf(len, chunk.size - at)
                    chunk.copyInto(b, off, at, at + n)
                    at += n
                    return n
                }
            }

        val output: OutputStream =
            object : OutputStream() {
                override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    var at = off
                    while (at < off + len) {
                        why?.let { throw IOException("the tunnel ended: $it") }
                        val n = minOf(Ocbm.MAX_INPUT_TUNNEL, off + len - at)
                        if (!send(Ocbm.TUNNEL_DATA, id, b.copyOfRange(at, at + n))) throw IOException("the adapter link dropped tunnel data")
                        at += n
                    }
                }
            }

        override fun close() {
            if (open.remove(id) != null) send(Ocbm.TUNNEL_CLOSE, id, NOTHING)
            ended("closed here")
        }
    }

    companion object {
        private const val MAX_ID = 255
        private const val OPEN_TIMEOUT_MS = 8_000L
        private const val READ_TIMEOUT_MS = 20_000L
        private const val MIB = 1024L * 1024
        private const val MAX_QUEUED = 32 * MIB

        /** The end of a tunnel's bytes, by identity: never the same array as [NOTHING]. */
        private val EOF = ByteArray(0)
        private val NOTHING = ByteArray(0)
    }
}
