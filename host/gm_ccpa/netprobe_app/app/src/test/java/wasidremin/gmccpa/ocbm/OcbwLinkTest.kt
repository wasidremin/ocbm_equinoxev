package wasidremin.gmccpa.ocbm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import wasidremin.gmccpa.ProbeLog
import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * OCBW v1 against the adapter's Rust crate (`crates/ocbm-link`), byte for byte. Ported from mammut3's
 * enigy/carlink PR #2 (`OcbwLinkTest.kt`, aca4733, Unlicense; NOTICE.md).
 */
class OcbwLinkTest {
    private val key = ByteArray(32) { 0x11 }
    private val bn = ByteArray(32) { 0x22 }
    private val hn = ByteArray(32) { 0x33 }

    @Test fun theProofsKeysAndARecordAreTheRustCratesKnownAnswers() {
        assertEquals("37256608df8b1738ce01a824112515ab0f72420ad890eb5f28b8e1378ef3c276", OcbwLink.hostProof(key, bn, hn).toHex())
        assertEquals("6b0464993905c9cb244c394e2114ed5db546a3e01a882fa33e18e9b4a88b5ea0", OcbwLink.boxProof(key, hn, bn).toHex())
        val (h2b, _) = OcbwLink.sessionKeys(key, bn, hn)
        assertEquals("cdbc30ed5bb1352687e3175ad53c08496750fdf4cbce95eeee76e9f66c4fa29a", h2b.toHex())
        assertEquals("000000140cfc70b3b120a8adfad9270f9691f573c1d1ad5d", OcbwLink.Sealer(h2b).seal("OCBM".toByteArray()).toHex())
    }

    @Test fun theAdaptersAdvertisedIdIsTheRustCratesBoxId() {
        assertEquals("cbb6a0d2a8710450", OcbwLink.boxId(key))
    }

    @Test fun recordsOpenSplitAnywhereAndAReplayOrATamperEndsTheLink() {
        val (h2b, _) = OcbwLink.sessionKeys(key, bn, hn)
        val tx = OcbwLink.Sealer(h2b)
        val rx = OcbwLink.Opener(h2b)
        val r0 = tx.seal("zero".toByteArray())
        val r1 = tx.seal("one".toByteArray())
        rx.push(r0.copyOf(3))
        assertNull(rx.next())
        rx.push(r0.copyOfRange(3, r0.size) + r1)
        assertEquals("zero", String(rx.next()!!))
        assertEquals("one", String(rx.next()!!))
        rx.push(r1) // a replay
        assertThrowsLink { rx.next() }
        val rx2 = OcbwLink.Opener(h2b)
        val bad = OcbwLink.Sealer(h2b).seal("data".toByteArray()).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        rx2.push(bad)
        assertThrowsLink { rx2.next() }
    }

    /** ocbmd's `wifi::parse_provision` takes `[1][key 32]` and nothing else. */
    @Test fun theProvisioningBodyIsVersionOneAndTheKeyAlone() {
        val body = OcbwLink.provisionBody(key)
        assertEquals(33, body.size)
        assertEquals(1, body[0].toInt())
        assertArrayEquals(key, body.copyOfRange(1, 33))
    }

    @Test fun aSessionOverLoopbackCarriesTheStreamBothWaysAndAWrongKeyIsRefused() {
        ServerSocket(0).use { server ->
            val fromHost = LinkedBlockingQueue<String>()
            val boxDone = CountDownLatch(1)
            thread(isDaemon = true) {
                server.accept().use { s ->
                    val sess = OcbwLink.boxHandshake(s.getInputStream(), s.getOutputStream(), key)
                    fromHost.add(String(sess.rx.read(DataInputStream(s.getInputStream()))!!))
                    s.getOutputStream().write(sess.tx.seal("hello host".toByteArray()))
                    s.getOutputStream().flush()
                    boxDone.await(5, TimeUnit.SECONDS)
                }
            }
            val got = LinkedBlockingQueue<String>()
            val t = WifiBulkTransport.connect("127.0.0.1", server.localPort, key, ProbeLog.silent())
            t.setReadHandler { b, n -> got.add(String(b, 0, n)) }
            val dead = CountDownLatch(1)
            t.onTransportDead = { dead.countDown() }
            t.start()
            assertTrue(t.writeBulk("hello box".toByteArray()))
            assertEquals("hello box", fromHost.poll(5, TimeUnit.SECONDS))
            assertEquals("hello host", got.poll(5, TimeUnit.SECONDS))
            boxDone.countDown()
            assertTrue("the adapter closing ends the link", dead.await(5, TimeUnit.SECONDS))
            t.stop()
        }
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                server.accept().use { s -> runCatching { OcbwLink.boxHandshake(s.getInputStream(), s.getOutputStream(), key) } }
            }
            try {
                WifiBulkTransport.connect("127.0.0.1", server.localPort, ByteArray(32) { 8 }, ProbeLog.silent())
                fail("a host with another key must not link")
            } catch (_: java.io.IOException) {
            }
        }
    }

    private fun assertThrowsLink(block: () -> Unit) {
        try {
            block()
            fail("expected a LinkException")
        } catch (_: OcbwLink.LinkException) {
        }
    }
}
