package wasidremin.gmccpa.video.proxy

// Ported from enigy/carlink `app/src/main/kotlin/com/carlink/video/proxy/TunnelDataSource.kt` at a04f4c0 (Unlicense;
// NOTICE.md). Changed here: the log names no host, origin or proxy (this log is uploaded: numbers only).

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import wasidremin.gmccpa.ProbeLog

/** A request's connection to an origin: its streams, and how to close everything under them. */
class Connection(
    val input: InputStream,
    val output: OutputStream,
    private val closer: () -> Unit,
) : Closeable {
    override fun close() = closer()
}

/**
 * How a request reaches its origin through the phone: a tunnel to the phone's proxy, the proxy's TLS-PSK
 * ([PskTls]), CONNECT to the origin ([Http1.connect]), and for an https origin its own TLS ([EngineTls]).
 */
object ProxyChain {
    private val log = ProbeLog.sub("video")

    fun open(
        tunnels: Tunnels,
        proxy: PhoneProxy,
        host: String,
        port: Int,
        https: Boolean,
    ): Connection {
        val tunnel =
            try {
                tunnels.open(proxy.address, proxy.port)
            } catch (e: IOException) {
                throw logged("no tunnel", e)
            }
        try {
            val psk = PskTls.connect(tunnel.input, tunnel.output, proxy.psk, proxy.identity)
            Http1.connect(psk.inputStream, psk.outputStream, Http1.authority(host, port), proxy.authorization)
            val pskLayer = Closeable { psk.close() }
            if (tunnels.firstOpen()) log.i("the phone's network carries the stream: a tunnel is open (${if (https) "https" else "http"})")
            if (!https) return Connection(psk.inputStream, psk.outputStream) { closeAll(pskLayer, tunnel) }
            val tls = EngineTls.open(psk.inputStream, psk.outputStream, host, port)
            return Connection(tls.input, tls.output) { closeAll(tls, pskLayer, tunnel) }
        } catch (e: IOException) {
            tunnel.close()
            throw logged("the tunnel failed", e)
        }
    }

    /**
     * [e], logged by its class only: no origin, no proxy address, no secret, and no exception message, which can
     * carry the origin's name.
     */
    private fun logged(
        what: String,
        e: IOException,
    ): IOException {
        log.w("over the phone's network: $what (${e.javaClass.simpleName})")
        return e
    }

    private fun closeAll(vararg layers: Closeable) {
        for (layer in layers) runCatching { layer.close() }
    }
}

/**
 * ExoPlayer's way to the stream over the phone's network: one HTTP/1.1 GET per open ([Http1]) on a fresh
 * [Connection] (no keep-alive), redirects followed, a range asked for when ExoPlayer wants one. Errors come out
 * as Media3's HTTP exceptions, so ExoPlayer retries and reports them as it does its own.
 */
@OptIn(UnstableApi::class)
class TunnelDataSource(
    /** Opens a connection to an origin's host and port, https or not. */
    private val connect: (host: String, port: Int, https: Boolean) -> Connection,
) : BaseDataSource(true) {
    private var spec: DataSpec? = null
    private var uri: Uri? = null
    private var connection: Connection? = null
    private var body: InputStream? = null
    private var headers: Map<String, List<String>> = emptyMap()
    private var left = C.LENGTH_UNSET.toLong()
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        transferInitializing(dataSpec)
        var target = dataSpec.uri
        var head = request(target, dataSpec)
        var hops = 0
        while (head.code in REDIRECTS && hops < MAX_REDIRECTS) {
            val location = head.header("Location") ?: break
            closeConnection()
            target = Uri.parse(resolve(target, location))
            head = request(target, dataSpec)
            hops++
        }
        uri = target
        headers = head.headers
        if (head.code !in OK_CODES) {
            closeConnection()
            throw HttpDataSource.InvalidResponseCodeException(head.code, head.message, null, head.headers, dataSpec, ByteArray(0))
        }
        val input = connection?.input ?: throw failure(dataSpec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, IOException("the connection closed"))
        val (stream, length) = Http1.body(head, input)
        body = stream
        val skip = if (head.code == Http1.OK) dataSpec.position else 0L
        skipBytes(stream, skip, dataSpec)
        left =
            when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                length >= 0 -> length - skip
                else -> C.LENGTH_UNSET.toLong()
            }
        opened = true
        transferStarted(dataSpec)
        return left
    }

    /** One GET for [target] on a fresh connection; its response's head. */
    private fun request(
        target: Uri,
        dataSpec: DataSpec,
    ): Http1.Head {
        val https = target.scheme.equals("https", ignoreCase = true)
        val host = target.host ?: throw failure(dataSpec, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, IOException("no host"))
        val port =
            when {
                target.port > 0 -> target.port
                https -> HTTPS_PORT
                else -> HTTP_PORT
            }
        try {
            val c = connect(host.trim('[', ']'), port, https)
            connection = c
            Http1.writeHead(c.output, "GET ${pathOf(target)} HTTP/1.1", requestHeaders(target, port, https, dataSpec))
            return Http1.readHead(c.input)
        } catch (e: IOException) {
            closeConnection()
            throw failure(dataSpec, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, e)
        }
    }

    private fun requestHeaders(
        target: Uri,
        port: Int,
        https: Boolean,
        dataSpec: DataSpec,
    ): List<Pair<String, String>> {
        val defaultPort = if (https) HTTPS_PORT else HTTP_PORT
        val host = target.host.orEmpty()
        val list = mutableListOf("Host" to if (port == defaultPort) host else "$host:$port")
        val asked = dataSpec.httpRequestHeaders
        if (asked.keys.none { it.equals("User-Agent", ignoreCase = true) }) list += "User-Agent" to USER_AGENT
        list += asked.toList()
        rangeOf(dataSpec)?.let { list += "Range" to it }
        list += "Accept-Encoding" to "identity"
        list += "Connection" to "close"
        return list
    }

    private fun skipBytes(
        stream: InputStream,
        count: Long,
        dataSpec: DataSpec,
    ) {
        var rest = count
        val scratch = ByteArray(SCRATCH)
        while (rest > 0) {
            val n = stream.read(scratch, 0, minOf(rest, SCRATCH.toLong()).toInt())
            if (n < 0) throw failure(dataSpec, PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, IOException("the body ended before the range"))
            rest -= n
        }
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (left == 0L) return C.RESULT_END_OF_INPUT
        val stream = body ?: return C.RESULT_END_OF_INPUT
        val want = if (left == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), left).toInt()
        val n =
            try {
                stream.read(buffer, offset, want)
            } catch (e: IOException) {
                throw failure(checkNotNull(spec), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, e, HttpDataSourceException.TYPE_READ)
            }
        if (n < 0) return C.RESULT_END_OF_INPUT
        if (left != C.LENGTH_UNSET.toLong()) left -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = headers

    override fun close() {
        body = null
        closeConnection()
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private fun closeConnection() {
        val c = connection
        connection = null
        runCatching { c?.close() }
    }

    /** Makes a [TunnelDataSource] per load, each request through the phone ([ProxyChain]). */
    class Factory(
        private val tunnels: Tunnels,
        private val proxy: PhoneProxy,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = TunnelDataSource { host, port, https -> ProxyChain.open(tunnels, proxy, host, port, https) }
    }

    companion object {
        private const val HTTP_PORT = 80
        private const val HTTPS_PORT = 443
        private const val MAX_REDIRECTS = 5
        private const val SCRATCH = 16 * 1024
        private val OK_CODES = 200..299
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val USER_AGENT = "AndroidXMedia3/${MediaLibraryInfo.VERSION}"

        /** [location] against [base], as a redirect means it; [location] itself when [base] isn't a java.net.URI. */
        fun resolve(
            base: Uri,
            location: String,
        ): String = runCatching { URI(base.toString()).resolve(location).toString() }.getOrDefault(location)

        /** Media3's HTTP error on [dataSpec]: [code] a PlaybackException code, [type] open or read. */
        fun failure(
            dataSpec: DataSpec,
            code: Int,
            cause: IOException,
            type: Int = HttpDataSourceException.TYPE_OPEN,
        ) = HttpDataSourceException(cause, dataSpec, code, type)

        /** The request target: the path (or `/`) and the query. */
        fun pathOf(target: Uri): String {
            val path = target.encodedPath?.takeIf { it.isNotEmpty() } ?: "/"
            return target.encodedQuery?.let { "$path?$it" } ?: path
        }

        /** A `Range` for the part [dataSpec] wants, or null for all of it. */
        fun rangeOf(dataSpec: DataSpec): String? {
            if (dataSpec.position == 0L && dataSpec.length == C.LENGTH_UNSET.toLong()) return null
            val end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) "" else (dataSpec.position + dataSpec.length - 1).toString()
            return "bytes=${dataSpec.position}-$end"
        }
    }
}
