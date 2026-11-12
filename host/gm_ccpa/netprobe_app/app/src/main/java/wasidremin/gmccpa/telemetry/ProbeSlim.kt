package wasidremin.gmccpa.telemetry

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * The wire form of a telemetry report.
 *
 * A `report.json` is about 147 KB a minute, and two things made it that big: it is pretty-printed
 * JSON sent uncompressed, and 45% of it - the property census, the permission table, the
 * subscription list and four paragraphs of fixed text - is identical for every segment of one
 * capture. Here the unchanging part is split off, named by a hash of its own content, and sent once;
 * everything else goes compact and gzipped. A typical segment is then about 10 KB on the wire and
 * the receiver rebuilds the same document it stored before, so nothing downstream changes.
 */
object ProbeSlim {
    /** Top-level keys that do not change between the segments of one capture. */
    val STATIC_KEYS = listOf(
        "permissions", "properties", "subscriptions", "propertyListCaveat", "wheelTickContract",
        "confirmedFacts", "notTestedOrInconclusive",
    )
    /** Above this a report is not parsed on the head unit; it is gzipped as it stands. */
    const val MAX_SPLIT_BYTES = 8L * 1024 * 1024

    class Split(val staticId: String, private val staticJson: String, private val dynamic: JSONObject) {
        /** The gzipped request body, with the static part inline when the receiver has not seen it. */
        fun body(includeStatic: Boolean): ByteArray {
            val text = dynamic.toString()
            // Spliced as text rather than re-nested, so the bytes that were hashed are the bytes sent.
            val out = if (includeStatic) text.dropLast(1) + ",\"static\":" + staticJson + "}" else text
            return gzip(out.toByteArray(Charsets.UTF_8))
        }
    }

    /** Null when [report] has no static part to split off (not a telemetry report). */
    fun split(report: JSONObject): Split? {
        if (!report.has("properties")) return null
        val static = JSONObject()
        val dynamic = JSONObject()
        val keys = report.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            (if (key in STATIC_KEYS) static else dynamic).put(key, report.get(key))
        }
        val staticJson = static.toString()
        val id = staticId(staticJson)
        dynamic.put("staticId", id)
        return Split(id, staticJson, dynamic)
    }

    fun staticId(staticJson: String): String =
        MessageDigest.getInstance("SHA-256").digest(staticJson.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }

    fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 6 + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    /** A report too large to split: the file itself, gzipped, read in pieces. */
    fun gzipFile(file: File): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { gz -> file.inputStream().use { it.copyTo(gz, 64 * 1024) } }
        return out.toByteArray()
    }
}
