package wasidremin.gmccpa.video.proxy

// Ported from enigy/carlink `app/src/test/kotlin/com/carlink/video/proxy/ProxyBasicsTest.kt` at a04f4c0 (Unlicense; NOTICE.md).

import wasidremin.gmccpa.ocbm.Ocbm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The phone's proxy offer, route A's tunnels through the box, and the HTTP/1.1 they carry. */
class ProxyBasicsTest {
    private val offer =
        mapOf(
            "proxyUrl" to "https://fe80::1c2b:3d4e:5f60:7182",
            "proxyPort" to 50_123L,
            "proxyPsk" to ByteArray(32) { it.toByte() },
            "proxyPskIdentity" to "id".toByteArray(),
            "proxyAuthorization" to "Basic c2VjcmV0",
        )

    @Test
    fun `the phone's offer needs all five, and its secrets stay out of the log`() {
        val proxy = PhoneProxy.from(offer)
        assertEquals(50_123, proxy?.port)
        assertEquals("https://fe80::1c2b:3d4e:5f60:7182", proxy?.address)
        assertEquals("PhoneProxy(port=50123)", proxy.toString())
        for (key in offer.keys) assertNull("without $key", PhoneProxy.from(offer - key))
        assertNull(PhoneProxy.from(offer + ("proxyPort" to 0L)))
        assertNull(PhoneProxy.from(null))
    }

    private val sent = LinkedBlockingQueue<Triple<Byte, Int, ByteArray>>()
    private val tunnels = Tunnels { op, id, data -> sent.add(Triple(op, id, data)) }

    private fun openOne(): Pair<Tunnels.Tunnel, Int> {
        var opened: Tunnels.Tunnel? = null
        val opener = thread { opened = tunnels.open("https://fe80::1", 1234) }
        val (op, id, data) = checkNotNull(sent.poll(2, TimeUnit.SECONDS))
        assertEquals(Ocbm.TUNNEL_OPEN, op)
        assertEquals(1234, ByteBuffer.wrap(data).short.toInt() and 0xFFFF)
        assertEquals("https://fe80::1", String(data, 2, data.size - 2))
        tunnels.onMessage(Ocbm.TUNNEL_OPENED.toInt(), byteArrayOf(id.toByte()))
        opener.join(2_000)
        return checkNotNull(opened) to id
    }

    @Test
    fun `a tunnel opens when the box says so, and carries bytes both ways`() {
        val (tunnel, id) = openOne()
        tunnels.onMessage(Ocbm.TUNNEL_DATA.toInt(), byteArrayOf(id.toByte()) + "hello".toByteArray())
        val buf = ByteArray(16)
        val n = tunnel.input.read(buf)
        assertEquals("hello", String(buf, 0, n))
        tunnel.output.write(ByteArray(40_000) { 7 })
        val chunks = generateSequence { sent.poll() }.toList()
        assertEquals("split at the box's cap", listOf(16_384, 16_384, 7_232), chunks.map { it.third.size })
        assertTrue(chunks.all { it.first == Ocbm.TUNNEL_DATA && it.second == id })
        tunnels.onMessage(Ocbm.TUNNEL_CLOSE.toInt(), byteArrayOf(id.toByte()) + "the phone closed it".toByteArray())
        assertEquals(-1, tunnel.input.read(buf))
        assertEquals(0, tunnels.openCount)
    }

    @Test
    fun `a refused tunnel fails its open with the box's reason, and closing one tells the box once`() {
        var failure: IOException? = null
        val opener =
            thread {
                try {
                    tunnels.open("https://192.168.50.1", 22)
                } catch (e: IOException) {
                    failure = e
                }
            }
        val (_, id, _) = checkNotNull(sent.poll(2, TimeUnit.SECONDS))
        tunnels.onMessage(Ocbm.TUNNEL_CLOSE.toInt(), byteArrayOf(id.toByte()) + "not the phone's address".toByteArray())
        opener.join(2_000)
        assertTrue(failure?.message.orEmpty(), failure?.message.orEmpty().contains("not the phone's address"))
        sent.clear()
        val (tunnel, other) = openOne()
        tunnel.close()
        tunnel.close()
        assertEquals(listOf(Ocbm.TUNNEL_CLOSE to other), generateSequence { sent.poll() }.map { it.first to it.second }.toList())
        try {
            tunnel.output.write(1)
            fail("a closed tunnel takes no bytes")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("ended"))
        }
    }

    @Test
    fun `the session's end ends every tunnel`() {
        val (tunnel, _) = openOne()
        tunnels.closeAll("the session ended")
        assertEquals(-1, tunnel.input.read())
        assertEquals(0, tunnels.openCount)
    }

    @Test
    fun `a response head, its headers in any case, and CONNECT's answer`() {
        val head = Http1.readHead(ByteArrayInputStream("HTTP/1.1 206 Partial Content\r\ncontent-length: 5\r\nX-A: 1\r\nX-A: 2\r\n\r\nhello".toByteArray()))
        assertEquals(206, head.code)
        assertEquals("Partial Content", head.message)
        assertEquals("5", head.header("Content-Length"))
        assertEquals(listOf("1", "2"), head.headers["X-A"])
        val out = ByteArrayOutputStream()
        Http1.connect(ByteArrayInputStream("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()), out, "cdn.example.com:443", "Basic c2VjcmV0")
        assertEquals("CONNECT cdn.example.com:443 HTTP/1.1\r\nHost: cdn.example.com:443\r\nProxy-Authorization: Basic c2VjcmV0\r\n\r\n", out.toString())
        try {
            Http1.connect(ByteArrayInputStream("HTTP/1.1 407 Proxy Authentication Required\r\n\r\n".toByteArray()), ByteArrayOutputStream(), "x:443", "")
            fail("a refused CONNECT")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("407"))
        }
        assertEquals("[2001:db8::1]:443", Http1.authority("2001:db8::1", 443))
        assertEquals("cdn.example.com:80", Http1.authority("cdn.example.com", 80))
    }

    @Test
    fun `a body by its length, chunked, or to the end`() {
        fun bodyOf(raw: String): String {
            val input = ByteArrayInputStream(raw.toByteArray())
            val (body, _) = Http1.body(Http1.readHead(input), input)
            return body.readBytes().toString(Charsets.ISO_8859_1)
        }
        assertEquals("hello", bodyOf("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello, and more"))
        val chunked = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4;x=y\r\nWiki\r\n5\r\npedia\r\nE\r\n in\r\n\r\nchunks.\r\n0\r\nTrailer: t\r\n\r\n"
        assertEquals("Wikipedia in\r\n\r\nchunks.", bodyOf(chunked))
        assertEquals("all of it", bodyOf("HTTP/1.1 200 OK\r\n\r\nall of it"))
        val input = ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\nshort".toByteArray())
        val (body, length) = Http1.body(Http1.readHead(input), input)
        assertEquals(9L, length)
        try {
            body.readBytes()
            fail("a body cut short")
        } catch (expected: IOException) {
            assertFalse(expected.message.isNullOrEmpty())
        }
    }

    @Test
    fun `not HTTP, or a head cut short, is an error`() {
        val raws = listOf("SSH-2.0-x\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 5\r\n", "")
        val refused = raws.map { runCatching { Http1.readHead(ByteArrayInputStream(it.toByteArray())) }.exceptionOrNull() }
        assertTrue(refused.toString(), refused.all { it is IOException })
    }
}
