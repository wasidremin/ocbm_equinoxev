package wasidremin.gmccpa.video.proxy

// Ported from enigy/carlink `app/src/main/kotlin/com/carlink/video/proxy/EngineTls.kt` at a04f4c0 (Unlicense; NOTICE.md).

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * An origin's own TLS inside the proxy's tunnel, with Android's SSLEngine: the system's trusted certificates and
 * a hostname check, as any HTTPS fetch here. Driven over a pair of streams, since the tunnel is no socket. One
 * reader and one writer at a time (an HTTP request, then its response).
 */
class EngineTls private constructor(
    private val engine: SSLEngine,
    private val netIn: InputStream,
    private val netOut: OutputStream,
) : Closeable {
    /** Bytes from the network not yet unwrapped (read mode). */
    private var inNet: ByteBuffer = empty(engine.session.packetBufferSize)

    /** Unwrapped bytes not yet read (read mode). */
    private var inApp: ByteBuffer = empty(engine.session.applicationBufferSize)
    private var outNet: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)
    private var ended = false

    val input: InputStream =
        object : InputStream() {
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
                while (!inApp.hasRemaining() && !ended) {
                    if (!unwrapOnce()) ended = true
                    followHandshake()
                }
                if (!inApp.hasRemaining()) return -1
                val n = minOf(len, inApp.remaining())
                inApp.get(b, off, n)
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
            ) = wrap(ByteBuffer.wrap(b, off, len))
        }

    private fun handshake(host: String) {
        engine.beginHandshake()
        followHandshake()
        // Belt and braces over the engine's own endpoint check: the name must match the certificate.
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, engine.session)) {
            throw SSLPeerUnverifiedException("the certificate isn't for $host")
        }
    }

    /** Run the handshake's steps until it needs nothing more (also for a post-handshake message's answer). */
    private fun followHandshake() {
        var status = engine.handshakeStatus
        while (status != SSLEngineResult.HandshakeStatus.FINISHED && status != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            when (status) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrap(ByteBuffer.allocate(0))
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> if (!unwrapOnce()) throw EOFException("the origin closed during its TLS handshake")
                SSLEngineResult.HandshakeStatus.NEED_TASK -> runTasks()
                else -> return
            }
            status = engine.handshakeStatus
        }
    }

    private fun runTasks() {
        var task = engine.delegatedTask
        while (task != null) {
            task.run()
            task = engine.delegatedTask
        }
    }

    /** Unwrap one record, reading from the network as needed. False at the stream's end or the TLS close. */
    private fun unwrapOnce(): Boolean {
        var produced: SSLEngineResult.Status
        do {
            inApp.compact()
            val result = engine.unwrap(inNet, inApp)
            inApp.flip()
            produced = result.status
            val more =
                when (produced) {
                    SSLEngineResult.Status.BUFFER_UNDERFLOW -> readNet()
                    SSLEngineResult.Status.BUFFER_OVERFLOW -> growApp()
                    else -> false
                }
        } while (more)
        return produced == SSLEngineResult.Status.OK
    }

    /** More bytes from the network into [inNet]; false at the stream's end. */
    private fun readNet(): Boolean {
        inNet.compact()
        if (!inNet.hasRemaining()) {
            val bigger = ByteBuffer.allocate(inNet.capacity() * 2)
            inNet.flip()
            inNet = bigger.put(inNet)
        }
        val n = netIn.read(inNet.array(), inNet.arrayOffset() + inNet.position(), inNet.remaining())
        if (n > 0) inNet.position(inNet.position() + n)
        inNet.flip()
        return n > 0
    }

    private fun growApp(): Boolean {
        val bigger = ByteBuffer.allocate(maxOf(inApp.capacity() * 2, engine.session.applicationBufferSize)).put(inApp)
        bigger.flip()
        inApp = bigger
        return true
    }

    /** Wrap and send [src] whole (an empty one for a handshake step). */
    private fun wrap(src: ByteBuffer) {
        do {
            outNet.clear()
            val result = engine.wrap(src, outNet)
            when (result.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> outNet = ByteBuffer.allocate(outNet.capacity() * 2)
                SSLEngineResult.Status.CLOSED -> throw IOException("the origin's TLS is closed")
                else -> {
                    outNet.flip()
                    netOut.write(outNet.array(), outNet.arrayOffset(), outNet.limit())
                }
            }
            if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) runTasks()
        } while (src.hasRemaining() || result.status == SSLEngineResult.Status.BUFFER_OVERFLOW)
        netOut.flush()
    }

    override fun close() {
        runCatching {
            engine.closeOutbound()
            wrap(ByteBuffer.allocate(0))
        }
    }

    companion object {
        /** An empty buffer in read mode. Android's ByteBuffer.flip() is still Buffer's, so it can't chain. */
        private fun empty(size: Int): ByteBuffer = ByteBuffer.allocate(size).also { it.flip() }

        /** TLS to [host] over [input]/[output], the handshake done and the name checked. */
        fun open(
            input: InputStream,
            output: OutputStream,
            host: String,
            port: Int,
        ): EngineTls {
            val engine = SSLContext.getDefault().createSSLEngine(host, port)
            engine.useClientMode = true
            engine.sslParameters =
                engine.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    if (!host.contains(':') && host.any { it.isLetter() }) serverNames = listOf(SNIHostName(host))
                }
            return EngineTls(engine, input, output).also { it.handshake(host) }
        }
    }
}
