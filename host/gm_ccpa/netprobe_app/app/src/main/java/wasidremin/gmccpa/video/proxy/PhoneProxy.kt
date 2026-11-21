package wasidremin.gmccpa.video.proxy

// Ported from enigy/carlink `app/src/main/kotlin/com/carlink/video/proxy/PhoneProxy.kt` at a04f4c0 (Unlicense; NOTICE.md).

/**
 * The phone's offer to carry route A's video over its own network (`setProxyParameters`, sent once the box
 * offers video): a proxy on the phone's address that speaks TLS 1.3 with a pre-shared key, then HTTP CONNECT.
 * LIVI's receiver fetches every stream through it (protocol facts from its source), and a unit with no internet
 * of its own needs it.
 *
 * [address] is the proxy's URL as the phone sent it (`https://` and the phone's address); the box turns it into
 * a socket and refuses anything but the phone (OCBM `receiver::tunnel`). The key, its identity and the
 * authorization are secrets: [toString] leaves them out, and nothing logs them.
 */
class PhoneProxy(
    val address: String,
    val port: Int,
    val psk: ByteArray,
    val identity: ByteArray,
    val authorization: String,
) {
    override fun toString() = "PhoneProxy(port=$port)"

    companion object {
        private const val MAX_PORT = 65_535

        /** The offer in `setProxyParameters`'s params; null unless all five are there, as LIVI requires. */
        fun from(params: Map<*, *>?): PhoneProxy? {
            val p = params ?: return null
            val address = (p["proxyUrl"] as? String)?.takeIf { it.isNotBlank() }
            val port = (p["proxyPort"] as? Number)?.toInt()?.takeIf { it in 1..MAX_PORT }
            val psk = (p["proxyPsk"] as? ByteArray)?.takeIf { it.isNotEmpty() }
            val identity = (p["proxyPskIdentity"] as? ByteArray)?.takeIf { it.isNotEmpty() }
            val authorization = p["proxyAuthorization"] as? String
            if (address == null || port == null || psk == null) return null
            if (identity == null || authorization == null) return null
            return PhoneProxy(address, port, psk, identity, authorization)
        }
    }
}
