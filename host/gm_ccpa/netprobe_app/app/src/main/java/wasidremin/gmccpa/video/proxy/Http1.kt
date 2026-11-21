package wasidremin.gmccpa.video.proxy

// Ported from enigy/carlink `app/src/main/kotlin/com/carlink/video/proxy/Http1.kt` at a04f4c0 (Unlicense; NOTICE.md).

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Just enough HTTP/1.1 for route A's tunnel: the proxy's CONNECT, then GETs for a stream's playlists and segments. */
object Http1 {
    const val OK = 200
    private const val MAX_LINE = 16 * 1024
    private const val MAX_HEADERS = 200
    private const val HEX = 16

    /** A response's status and headers. */
    class Head(
        val code: Int,
        val message: String,
        val headers: Map<String, List<String>>,
    ) {
        /** A header's first value, by name in any case. */
        fun header(name: String): String? {
            val entry = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            return entry?.value?.firstOrNull()
        }
    }

    /** Send a request head: [line], [headers], then the blank line. */
    fun writeHead(
        out: OutputStream,
        line: String,
        headers: List<Pair<String, String>>,
    ) {
        val text =
            buildString {
                append(line).append("\r\n")
                for ((k, v) in headers) append(k).append(": ").append(v).append("\r\n")
                append("\r\n")
            }
        out.write(text.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    /** Read a response head through its blank line. */
    fun readHead(input: InputStream): Head {
        val status = readLine(input) ?: throw EOFException("no response")
        val parts = status.split(' ', limit = 3)
        val code = parts.getOrNull(1)?.toIntOrNull()?.takeIf { status.startsWith("HTTP/") } ?: throw IOException("not an HTTP response")
        return Head(code, parts.getOrNull(2).orEmpty(), readHeaders(input))
    }

    private fun readHeaders(input: InputStream): Map<String, List<String>> {
        val headers = LinkedHashMap<String, MutableList<String>>()
        var line = readLine(input)
        while (!line.isNullOrEmpty()) {
            val colon = line.indexOf(':')
            if (colon > 0) headers.getOrPut(line.substring(0, colon).trim()) { mutableListOf() } += line.substring(colon + 1).trim()
            if (headers.size > MAX_HEADERS) throw IOException("over $MAX_HEADERS headers")
            line = readLine(input)
        }
        if (line == null) throw EOFException("the response head was cut short")
        return headers
    }

    /** One line, without its CR LF; null at the stream's end before any byte. */
    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        var b = input.read()
        if (b < 0) return null
        while (b >= 0 && b != '\n'.code) {
            if (b != '\r'.code) line.write(b)
            if (line.size() > MAX_LINE) throw IOException("a line over $MAX_LINE B")
            b = input.read()
        }
        return line.toString(Charsets.ISO_8859_1.name())
    }

    /** The response's body and its length (-1 when not known): chunked, so long, or to the stream's end. */
    fun body(
        head: Head,
        input: InputStream,
    ): Pair<InputStream, Long> {
        if (head.header("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true) return Chunked(input) to -1L
        val length = head.header("Content-Length")?.trim()?.toLongOrNull()
        return if (length != null && length >= 0) Limited(input, length) to length else input to -1L
    }

    /** Ask the phone's proxy for a tunnel to [authority] (`host:port`); it must answer 200, as LIVI requires. */
    fun connect(
        input: InputStream,
        output: OutputStream,
        authority: String,
        authorization: String,
    ) {
        val headers =
            buildList {
                add("Host" to authority)
                if (authorization.isNotEmpty()) add("Proxy-Authorization" to authorization)
            }
        writeHead(output, "CONNECT $authority HTTP/1.1", headers)
        val head = readHead(input)
        if (head.code != OK) throw IOException("the phone's proxy refused the tunnel: ${head.code} ${head.message}")
    }

    /** `host:port`, an IPv6 host in brackets. */
    fun authority(
        host: String,
        port: Int,
    ): String = if (host.contains(':') && !host.startsWith('[')) "[$host]:$port" else "$host:$port"

    /** A chunked body, decoded. */
    private class Chunked(
        private val input: InputStream,
    ) : InputStream() {
        private var left = 0L
        private var done = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (left == 0L && !done) left = nextChunk()
            if (done || len == 0) return if (done) -1 else 0
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw EOFException("a chunk was cut short")
            left -= n
            if (left == 0L) readLine(input) // the CR LF after a chunk's data
            return n
        }

        /** The next chunk's size; at the last (0) the trailers are read and the body is done. */
        private fun nextChunk(): Long {
            val line = readLine(input) ?: throw EOFException("no chunk size")
            val size = line.substringBefore(';').trim().toLongOrNull(HEX)
            if (size == null || size < 0) throw IOException("a bad chunk size")
            if (size == 0L) {
                var trailer = readLine(input)
                while (!trailer.isNullOrEmpty()) trailer = readLine(input)
                done = true
            }
            return size
        }
    }

    /** A body of a known length. */
    private class Limited(
        private val input: InputStream,
        private var left: Long,
    ) : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            if (left <= 0L) return -1
            val n = input.read(b, off, minOf(len.toLong(), left).toInt())
            if (n < 0) throw EOFException("the body was cut short")
            left -= n
            return n
        }
    }
}
