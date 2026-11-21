package wasidremin.gmccpa.video.proxy

// Ported from enigy/carlink `app/src/main/kotlin/com/carlink/video/proxy/PskTls.kt` at a04f4c0 (Unlicense; NOTICE.md).

import org.bouncycastle.tls.AbstractTlsClient
import org.bouncycastle.tls.AlertDescription
import org.bouncycastle.tls.BasicTlsPSKExternal
import org.bouncycastle.tls.CertificateRequest
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.PRFAlgorithm
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsAuthentication
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsCredentials
import org.bouncycastle.tls.TlsFatalAlert
import org.bouncycastle.tls.TlsServerCertificate
import org.bouncycastle.tls.crypto.TlsCrypto
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.Vector

/**
 * The phone's proxy handshake: TLS 1.3 with its pre-shared key and nothing else. LIVI's facts: one suite,
 * TLS_AES_256_GCM_SHA384, the key and its identity from `setProxyParameters`, no certificate. Bouncy Castle's
 * TLS, since Android's own has no external pre-shared keys in TLS 1.3.
 */
object PskTls {
    /** Handshake over [input]/[output] (a tunnel to the proxy). The returned protocol's streams carry the rest. */
    fun connect(
        input: InputStream,
        output: OutputStream,
        psk: ByteArray,
        identity: ByteArray,
    ): TlsClientProtocol = TlsClientProtocol(input, output).apply { connect(Client(BcTlsCrypto(SecureRandom()), psk, identity)) }

    private class Client(
        crypto: TlsCrypto,
        private val psk: ByteArray,
        private val identity: ByteArray,
    ) : AbstractTlsClient(crypto) {
        override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv13.only()

        override fun getSupportedCipherSuites(): IntArray = intArrayOf(CipherSuite.TLS_AES_256_GCM_SHA384)

        override fun getExternalPSKs(): Vector<*> = Vector(listOf(BasicTlsPSKExternal(identity, crypto.createSecret(psk), PRFAlgorithm.tls13_hkdf_sha384)))

        override fun getAuthentication(): TlsAuthentication = NoCertificate
    }

    /** A certificate from the proxy means it didn't take the key: refused. */
    private object NoCertificate : TlsAuthentication {
        override fun notifyServerCertificate(serverCertificate: TlsServerCertificate): Unit = throw TlsFatalAlert(AlertDescription.bad_certificate)

        override fun getClientCredentials(certificateRequest: CertificateRequest): TlsCredentials? = null
    }
}
