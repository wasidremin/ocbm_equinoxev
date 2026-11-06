package wasidremin.gmccpa.usbbridge

import android.content.Context
import android.content.pm.PackageManager
import java.util.concurrent.ConcurrentHashMap
import wasidremin.gmccpa.bridgeapi.BridgeProtocol

/**
 * Who may drive the adapter through the bridge. An open bridge would let any app on the head unit
 * talk to the CCPA, which runs what it is sent. The caller must be [BridgeProtocol.APP_PACKAGE],
 * signed by a pinned certificate or installed by Play. Play installs only the real listing under
 * that name; the pin covers a locally signed build, and stops a differently signed
 * `wasidremin.gmccpa` sideloaded while the real one is absent.
 */
internal class CallerCheck(private val ctx: Context, certs: String) {
    private val pinned = parseCerts(certs)

    // Two PackageManager calls per check, and a write runs per OCBM frame.
    private val verified = ConcurrentHashMap.newKeySet<Int>()

    /** Null when [uid] may call; otherwise why not. */
    fun rejectReason(uid: Int): String? {
        if (uid in verified) return null
        val pm = ctx.packageManager
        val packages = pm.getPackagesForUid(uid)?.toList().orEmpty()
        if (BridgeProtocol.APP_PACKAGE !in packages) return "uid $uid $packages is not ${BridgeProtocol.APP_PACKAGE}"
        val signed = pinned.any { pm.hasSigningCertificate(uid, it, PackageManager.CERT_INPUT_SHA256) }
        val installer = runCatching {
            pm.getInstallSourceInfo(BridgeProtocol.APP_PACKAGE).installingPackageName
        }.getOrNull()
        if (!signed && installer != PLAY_STORE) {
            return "${BridgeProtocol.APP_PACKAGE} (uid $uid) is not signed by a pinned certificate and was installed by $installer"
        }
        Events.add("serving uid $uid (${if (signed) "pinned certificate" else "installed by Play"})")
        verified += uid
        return null
    }

    /** UIDs can be reassigned after an uninstall: check again once the app has gone away. */
    fun forget() = verified.clear()

    companion object {
        private const val PLAY_STORE = "com.android.vending"

        /** "AA:BB:…" fingerprints, comma-separated; anything that is not 32 bytes of hex is dropped. */
        fun parseCerts(list: String): List<ByteArray> = list.split(',')
            .map { it.replace(":", "").trim() }
            .filter { it.length == 64 && it.all { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' } }
            .map { hex -> ByteArray(32) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }
    }
}
