package wasidremin.gmccpa.video.proxy

// Ported from enigy/carlink `app/src/test/kotlin/com/carlink/video/proxy/PskTlsTest.kt` at a04f4c0 (Unlicense; NOTICE.md).

import org.bouncycastle.tls.AbstractTlsServer
import org.bouncycastle.tls.BasicTlsPSKExternal
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.PRFAlgorithm
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.PskIdentity
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsPSKExternal
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.crypto.TlsCrypto
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.security.SecureRandom
import java.util.Vector
import kotlin.concurrent.thread

/**
 * The phone's proxy handshake against Bouncy Castle's own TLS 1.3 server, set up as LIVI describes the phone's:
 * one suite, an external pre-shared key, no certificate. The phone itself is a bench question.
 */
class PskTlsTest {
    private class PskServer(
        crypto: TlsCrypto,
        private val identity: ByteArray,
        private val psk: ByteArray,
    ) : AbstractTlsServer(crypto) {
        override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv13.only()

        override fun getSupportedCipherSuites(): IntArray = intArrayOf(CipherSuite.TLS_AES_256_GCM_SHA384)

        /** No certificate: the key is the only credential, as on the phone's proxy. */
        override fun getCredentials(): TlsCredentials? = null

        override fun getExternalPSK(identities: Vector<*>): TlsPSKExternal? =
            if (identities.any { (it as PskIdentity).identity.contentEquals(identity) }) {
                BasicTlsPSKExternal(identity, crypto.createSecret(psk), PRFAlgorithm.tls13_hkdf_sha384)
            } else {
                null
            }
    }

    private val identity = "phone-identity".toByteArray()
    private val psk = ByteArray(32) { (it * 7).toByte() }

    /** A server on its own thread that answers each line it gets with "echo: " and the line. */
    private fun serve(
        serverKey: ByteArray,
        clientIn: PipedInputStream,
        toServer: PipedOutputStream,
    ): Thread {
        val serverIn = PipedInputStream(toServer, PIPE)
        val toClient = PipedOutputStream(clientIn)
        return thread(isDaemon = true) {
            runCatching {
                val p = TlsServerProtocol(serverIn, toClient)
                p.accept(PskServer(BcTlsCrypto(SecureRandom()), identity, serverKey))
                val got = ByteArray(64)
                val n = p.inputStream.read(got)
                p.outputStream.write("echo: ".toByteArray() + got.copyOf(n))
                p.outputStream.flush()
                Thread.sleep(200)
            }
        }
    }

    @Test
    fun `the proxy handshake takes the phone's key, and bytes flow both ways`() {
        val clientIn = PipedInputStream(PIPE)
        val toServer = PipedOutputStream()
        val server = serve(psk, clientIn, toServer)
        val tls = PskTls.connect(clientIn, toServer, psk, identity)
        tls.outputStream.write("CONNECT x:443".toByteArray())
        tls.outputStream.flush()
        val got = ByteArray(64)
        val n = tls.inputStream.read(got)
        assertEquals("echo: CONNECT x:443", String(got, 0, n))
        server.join(2_000)
    }

    @Test
    fun `a wrong key fails the handshake`() {
        val clientIn = PipedInputStream(PIPE)
        val toServer = PipedOutputStream()
        serve(ByteArray(32), clientIn, toServer)
        val failure = runCatching { PskTls.connect(clientIn, toServer, psk, identity) }.exceptionOrNull()
        assertTrue("$failure", failure is IOException)
    }

    private companion object {
        const val PIPE = 1 shl 16
    }
}
