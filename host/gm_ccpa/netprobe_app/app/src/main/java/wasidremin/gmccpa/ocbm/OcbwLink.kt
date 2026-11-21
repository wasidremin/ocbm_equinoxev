package wasidremin.gmccpa.ocbm

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * OCBW v1, the host side: OCBM over TCP on the car's hotspot (the adapter's `crates/ocbm-link` and
 * `ccpa/ocbmd/src/wifi.rs`; T15). A pre-shared-key handshake, then ChaCha20-Poly1305 records around the
 * unchanged OCBM byte stream.
 *
 * WHY. Over USB the stream rides a cable only the head unit holds. Over Wi-Fi it rides a hotspot
 * passengers share, and the stream carries a root console, file push and CarPlay's A/V keys. So
 * nobody gets a byte through without the 32-byte link key, and nobody on the hotspot reads one. The
 * key is set over USB once ([OcbwLink.provisionBody]) and never crosses the network.
 *
 * Ported from mammut3's enigy/carlink PR #2 (`ocbm/OcbwLink.kt`, aca4733, Unlicense; NOTICE.md). Here the
 * provisioning carries the key alone: the adapter already has the hotspot from `/script/sta_uplink.conf`
 * ([StaUplink]).
 *
 * ```text
 *   box  -> host   "OCBW" ver=1 box_nonce[32]                                   (37 B)
 *   host -> box    "OCBW" ver=1 host_nonce[32] HMAC(K, "OCBW host" | bn | hn)   (69 B)
 *   box  -> host   HMAC(K, "OCBW box" | hn | bn)                                 (32 B)
 * ```
 *
 * Session keys: HKDF-SHA256(ikm = K, salt = bn | hn), info "OCBW v1 host->box" / "OCBW v1 box->host".
 * Records: `[u32 BE ciphertext length][ciphertext | 16 B tag]`, nonce = 4 zero bytes + a per-direction
 * 64-bit little-endian counter from 0. A dropped, replayed or reordered record fails its tag and ends
 * the link. The known-answer vectors in `OcbwLinkTest` are the Rust crate's, byte for byte.
 */
object OcbwLink {
    val MAGIC = byteArrayOf('O'.code.toByte(), 'C'.code.toByte(), 'B'.code.toByte(), 'W'.code.toByte())
    const val VERSION: Byte = 1

    /** The TCP port the box listens on. */
    const val PORT = 7020

    /** The DNS-SD service type the box advertises on the hotspot (TXT `v=1`, `id=`[boxId]). */
    const val SERVICE_TYPE = "_ocbm._tcp"

    /** Largest record plaintext; OCBM frames are at most 16 B + 64 KiB. */
    const val MAX_RECORD = 64 * 1024
    const val TAG = 16
    const val KEY_LEN = 32
    private const val NONCE_LEN = 32
    private const val HELLO_LEN = 4 + 1 + NONCE_LEN
    private const val REPLY_LEN = HELLO_LEN + 32

    private val HOST_LABEL = "OCBW host".toByteArray(Charsets.US_ASCII)
    private val BOX_LABEL = "OCBW box".toByteArray(Charsets.US_ASCII)
    private val ID_LABEL = "OCBW id".toByteArray(Charsets.US_ASCII)
    private val H2B = "OCBW v1 host->box".toByteArray(Charsets.US_ASCII)
    private val B2H = "OCBW v1 box->host".toByteArray(Charsets.US_ASCII)

    private val rng = SecureRandom()

    fun random32(): ByteArray = ByteArray(KEY_LEN).also { rng.nextBytes(it) }

    fun hmac(
        key: ByteArray,
        vararg parts: ByteArray,
    ): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        parts.forEach { m.update(it) }
        return m.doFinal()
    }

    /** The box's public id for discovery: the first 8 bytes of HMAC(K, "OCBW id"), as 16 hex digits. */
    fun boxId(key: ByteArray): String = hmac(key, ID_LABEL).copyOf(8).toHex()

    fun hostProof(
        key: ByteArray,
        bn: ByteArray,
        hn: ByteArray,
    ): ByteArray = hmac(key, HOST_LABEL, bn, hn)

    fun boxProof(
        key: ByteArray,
        hn: ByteArray,
        bn: ByteArray,
    ): ByteArray = hmac(key, BOX_LABEL, hn, bn)

    /** HKDF-SHA256 for the two 32-byte direction keys (one HMAC block each): (host->box, box->host). */
    fun sessionKeys(
        key: ByteArray,
        bn: ByteArray,
        hn: ByteArray,
    ): Pair<ByteArray, ByteArray> {
        val prk = hmac(bn + hn, key)
        val one = byteArrayOf(1)
        return hmac(prk, H2B, one) to hmac(prk, B2H, one)
    }

    class LinkException(
        message: String,
    ) : IOException(message)

    /** The two directions after a handshake: [tx] seals what the host sends, [rx] opens what it receives. */
    class Session(
        val tx: Sealer,
        val rx: Opener,
    )

    /**
     * The host's side on a fresh connection. Blocking: give the socket a read timeout first. Throws
     * [LinkException] when the peer isn't an OCBW v1 box or can't prove it holds [key].
     */
    fun hostHandshake(
        input: InputStream,
        output: OutputStream,
        key: ByteArray,
        hostNonce: ByteArray = random32(),
    ): Session {
        require(key.size == KEY_LEN && hostNonce.size == NONCE_LEN)
        val din = DataInputStream(input)
        val hello = ByteArray(HELLO_LEN)
        din.readFully(hello)
        if (!hello.copyOfRange(0, 4).contentEquals(MAGIC) || hello[4] != VERSION) throw LinkException("the peer didn't open with OCBW v1")
        val bn = hello.copyOfRange(5, HELLO_LEN)
        val reply = ByteArray(REPLY_LEN)
        MAGIC.copyInto(reply)
        reply[4] = VERSION
        hostNonce.copyInto(reply, 5)
        hostProof(key, bn, hostNonce).copyInto(reply, HELLO_LEN)
        output.write(reply)
        output.flush()
        val bp = ByteArray(32)
        din.readFully(bp)
        if (!MessageDigest.isEqual(bp, boxProof(key, hostNonce, bn))) throw LinkException("the box's proof failed (a different link key)")
        val (h2b, b2h) = sessionKeys(key, bn, hostNonce)
        return Session(Sealer(h2b), Opener(b2h))
    }

    /** The box's side, for tests (a fake box on loopback). */
    fun boxHandshake(
        input: InputStream,
        output: OutputStream,
        key: ByteArray,
        boxNonce: ByteArray = random32(),
    ): Session {
        val hello = ByteArray(HELLO_LEN)
        MAGIC.copyInto(hello)
        hello[4] = VERSION
        boxNonce.copyInto(hello, 5)
        output.write(hello)
        output.flush()
        val reply = ByteArray(REPLY_LEN)
        DataInputStream(input).readFully(reply)
        if (!reply.copyOfRange(0, 4).contentEquals(MAGIC) || reply[4] != VERSION) throw LinkException("the peer didn't open with OCBW v1")
        val hn = reply.copyOfRange(5, HELLO_LEN)
        if (!MessageDigest.isEqual(reply.copyOfRange(HELLO_LEN, REPLY_LEN), hostProof(key, boxNonce, hn))) throw LinkException("the host's proof failed")
        output.write(boxProof(key, hn, boxNonce))
        output.flush()
        val (h2b, b2h) = sessionKeys(key, boxNonce, hn)
        return Session(Sealer(b2h), Opener(h2b))
    }

    private fun nonce(ctr: Long): ByteArray {
        val n = ByteArray(12)
        for (i in 0 until 8) n[4 + i] = (ctr ushr (8 * i)).toByte()
        return n
    }

    private fun cipher(
        mode: Int,
        key: ByteArray,
        ctr: Long,
    ): Cipher = Cipher.getInstance("ChaCha20-Poly1305").apply { init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce(ctr))) }

    /** Seals one direction's records. Not thread-safe: the caller serialises sends. */
    class Sealer(
        private val key: ByteArray,
    ) {
        private var ctr = 0L

        /** One record for [plain] (at most [MAX_RECORD] bytes), ready to write. */
        fun seal(
            plain: ByteArray,
            off: Int = 0,
            len: Int = plain.size,
        ): ByteArray {
            require(len <= MAX_RECORD) { "record plaintext over MAX_RECORD" }
            val ct = cipher(Cipher.ENCRYPT_MODE, key, ctr++).doFinal(plain, off, len)
            val out = ByteArray(4 + ct.size)
            out[0] = (ct.size ushr 24).toByte()
            out[1] = (ct.size ushr 16).toByte()
            out[2] = (ct.size ushr 8).toByte()
            out[3] = ct.size.toByte()
            ct.copyInto(out, 4)
            return out
        }
    }

    /** Opens the other direction's records from a stream that can split them anywhere. */
    class Opener(
        private val key: ByteArray,
    ) {
        private var ctr = 0L
        private var buf = ByteArray(0)

        fun push(
            bytes: ByteArray,
            len: Int = bytes.size,
        ) {
            buf += bytes.copyOf(len)
        }

        /** The next whole record's plaintext, null until one has arrived. Throws [LinkException] to end the link. */
        fun next(): ByteArray? {
            if (buf.size < 4) return null
            val len = ((buf[0].toInt() and 0xFF) shl 24) or ((buf[1].toInt() and 0xFF) shl 16) or ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            if (len < TAG || len > MAX_RECORD + TAG) throw LinkException("record of $len B is outside the limits")
            if (buf.size < 4 + len) return null
            val plain =
                try {
                    cipher(Cipher.DECRYPT_MODE, key, ctr).doFinal(buf, 4, len)
                } catch (_: AEADBadTagException) {
                    throw LinkException("a record failed authentication")
                }
            ctr++
            buf = buf.copyOfRange(4 + len, buf.size)
            return plain
        }

        /** Read exactly one record from [input] (blocking). Null at a clean EOF between records. */
        fun read(input: DataInputStream): ByteArray? {
            val head = ByteArray(4)
            val first = input.read()
            if (first < 0) return null
            head[0] = first.toByte()
            input.readFully(head, 1, 3)
            val len = ((head[0].toInt() and 0xFF) shl 24) or ((head[1].toInt() and 0xFF) shl 16) or ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
            if (len < TAG || len > MAX_RECORD + TAG) throw LinkException("record of $len B is outside the limits")
            val body = ByteArray(len)
            input.readFully(body)
            val plain =
                try {
                    cipher(Cipher.DECRYPT_MODE, key, ctr).doFinal(body)
                } catch (_: AEADBadTagException) {
                    throw LinkException("a record failed authentication")
                }
            ctr++
            return plain
        }
    }

    /** `MGMT_WIFI_LINK`'s argument (after the verb): `[ver 1][key 32]`. Empty clears the adapter's key. */
    fun provisionBody(key: ByteArray): ByteArray {
        require(key.size == KEY_LEN)
        return byteArrayOf(1) + key
    }
}

/** Lower-case hex, two digits a byte. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/** The bytes [s] spells in hex, or null when it doesn't. */
fun hexBytes(s: String): ByteArray? {
    val t = s.trim()
    if (t.length % 2 != 0) return null
    return runCatching { ByteArray(t.length / 2) { t.substring(2 * it, 2 * it + 2).toInt(16).toByte() } }.getOrNull()
}
