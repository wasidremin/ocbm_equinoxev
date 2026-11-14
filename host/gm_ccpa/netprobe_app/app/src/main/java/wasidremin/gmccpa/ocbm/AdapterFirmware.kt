package wasidremin.gmccpa.ocbm

import android.content.Context
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.json.JSONObject
import wasidremin.gmccpa.ProbeLog

/**
 * Update the adapter from the app (docs/ops/08_FUTURE_TASKS.md T20).
 *
 * The app carries the adapter firmware it was built with: `assets/adapter_fw/manifest` (a version
 * id and one `<sha256> <mode> <path>` line per file) and the files beside it, named by
 * [stagedName]. An update:
 *  1. **Check.** The manifest goes to the adapter's RAM disk; `MGMT_FW` check makes the adapter hash
 *     its own copies and say which differ (`check.json`).
 *  2. **Stage.** Only the files that differ are pushed, into RAM.
 *  3. **Apply.** The adapter checks every hash and its free space, swaps the files in keeping each
 *     old one, and reboots (`apply.json`).
 *  4. **Confirm.** When the adapter is back with Bluetooth and its radio supervisor running
 *     ([noteHealth]), the app confirms. Unconfirmed after five minutes, or on the third boot, the
 *     adapter puts the old files back by itself (`fw_apply.sh boot`, armed from `ocbm_boot.sh`).
 *
 * The boot path and the update script are never part of an app update, so a bad update can always
 * be undone. Only firmware whose info says `"fw_update":true` takes part.
 */
object AdapterFirmware {
    private val log = ProbeLog.sub("fw")
    private const val ASSET_DIR = "adapter_fw"
    private const val MANIFEST = "manifest"

    data class Entry(val sha256: String, val mode: String, val path: String)
    data class Manifest(val version: String, val entries: List<Entry>) {
        fun text(nonce: String): String = buildString {
            append("version ").append(version).append('\n')
            append("nonce ").append(nonce).append('\n')
            for (e in entries) append(e.sha256).append(' ').append(e.mode).append(' ').append(e.path).append('\n')
        }
    }

    /** What the adapter said about its own files. */
    data class Check(
        val differs: List<String>,
        val freeKb: Long,
        val pending: Boolean,
        val prev: Boolean,
        val version: String,
    )

    /** The file name a path is staged under, on the adapter and in the app's assets. */
    fun stagedName(path: String): String = path.replace('/', '_')

    private val SHA = Regex("[0-9a-f]{64}")
    private val PATH = Regex("/(usr/sbin|usr/bin|script)/[A-Za-z0-9._-]+")
    private val NEVER = setOf("/script/ocbm_boot.sh", "/script/run_ocbmd.sh", "/script/ocbm_udisk.sh", "/script/fw_apply.sh")

    /** Parse the bundled manifest. Null if any line is malformed or names a path an update may not touch. */
    fun parseManifest(text: String): Manifest? {
        var version: String? = null
        val entries = mutableListOf<Entry>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val f = line.split(' ')
            when {
                f.size == 2 && f[0] == "version" && f[1].matches(Regex("[0-9a-f]{1,16}")) -> version = f[1]
                f.size == 3 && SHA.matches(f[0]) && (f[1] == "755" || f[1] == "644") &&
                    PATH.matches(f[2]) && f[2] !in NEVER && ".." !in f[2] -> entries += Entry(f[0], f[1], f[2])
                else -> return null
            }
        }
        if (version == null || entries.isEmpty() || entries.map { it.path }.toSet().size != entries.size) return null
        return Manifest(version, entries)
    }

    /** The version id a bundle carries: the first 12 hex of the SHA-256 of its entry lines. */
    fun versionOf(entries: List<Entry>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (e in entries) md.update("${e.sha256} ${e.mode} ${e.path}\n".toByteArray(Charsets.US_ASCII))
        return md.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

    /** `check.json`, or null when it is not this request's answer ([nonce]) or not well formed. */
    fun parseCheck(json: String, nonce: String): Result<Check>? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (o.optString("nonce") != nonce) return null
        if (!o.optBoolean("ok")) return Result.failure(IllegalStateException(o.optString("error", "the adapter refused")))
        val d = o.optJSONArray("differs") ?: return null
        return Result.success(Check(
            differs = (0 until d.length()).map { d.getString(it) },
            freeKb = o.optLong("free_kb", -1),
            pending = o.optBoolean("pending"),
            prev = o.optBoolean("prev"),
            version = o.optString("version"),
        ))
    }

    /** `apply.json`: null when not this request's answer; otherwise success or the adapter's reason. */
    fun parseApply(json: String, nonce: String): Result<String>? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (o.optString("nonce") != nonce) return null
        return if (o.optBoolean("ok")) Result.success(o.optString("version"))
        else Result.failure(IllegalStateException(o.optString("error", "the adapter refused")))
    }

    /**
     * The health that proves an update good: Bluetooth up, the radio supervisor (btd) running and
     * the root filesystem healthy. Reached only once the new ocbmd, btd and scripts are working.
     */
    fun healthy(bits: Int): Boolean {
        val need = Ocbm.BH_HCI_PRESENT or Ocbm.BH_CARPLAY_WIRELESS or Ocbm.BH_ROOTFS_OK
        return bits and need == need
    }

    // ---- With the adapter -----------------------------------------------------------------------

    fun bundled(ctx: Context): Manifest? = runCatching {
        ctx.assets.open("$ASSET_DIR/$MANIFEST").use { parseManifest(it.readBytes().toString(Charsets.US_ASCII)) }
    }.getOrNull()

    private fun asset(ctx: Context, e: Entry): ByteArray =
        ctx.assets.open("$ASSET_DIR/${stagedName(e.path)}").use { it.readBytes() }

    private fun newNonce(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    /** Run one script operation and wait for its answer file. */
    private fun <T> ask(c: OcbmClient, op: Byte, answer: String, timeoutMs: Long, parse: (String) -> T?): T? {
        val st = c.mgmtAction(Ocbm.MGMT_FW, byteArrayOf(op)) ?: return null.also { log.w("firmware op $op: no answer from the adapter") }
        if (st != 0) { log.w("firmware op $op: the adapter could not start it"); return null }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
            val bytes = c.filePull("${Ocbm.FW_DIR}/$answer", 3_000) ?: continue
            parse(bytes.toString(Charsets.UTF_8))?.let { return it }
        }
        log.w("firmware op $op: no $answer within ${timeoutMs / 1000}s")
        return null
    }

    /** Ask the adapter how its files compare with [m]. Null if it cannot say. */
    fun check(c: OcbmClient, m: Manifest): Result<Check>? {
        val nonce = newNonce()
        if (!c.filePush("${Ocbm.FW_DIR}/manifest", m.text(nonce).toByteArray(Charsets.US_ASCII), 420)) return null
        return ask(c, Ocbm.FW_OP_CHECK, "check.json", 30_000) { parseCheck(it, nonce) }
    }

    /**
     * Stage the files that differ and apply them. On success the adapter reboots within seconds and
     * the update waits for [noteHealth] to confirm it. [progress] gets one line per step.
     */
    fun update(ctx: Context, c: OcbmClient, m: Manifest, progress: (String) -> Unit): Result<String> {
        val chk = check(c, m) ?: return Result.failure(IllegalStateException("the adapter did not answer the check"))
        val st = chk.getOrElse { return Result.failure(it) }
        if (st.pending) return Result.failure(IllegalStateException("the last update is still waiting to be confirmed"))
        val todo = m.entries.filter { it.path in st.differs }
        if (todo.isEmpty()) return Result.success("already up to date")
        val kb = todo.sumOf { (asset(ctx, it).size / 1024) + 1 }
        log.i("firmware ${m.version}: ${todo.size} file(s) differ, ${kb} KB; adapter has ${st.freeKb} KB free")
        for ((i, e) in todo.withIndex()) {
            progress("Sending ${i + 1} of ${todo.size}: ${e.path.substringAfterLast('/')}")
            val bytes = asset(ctx, e)
            if (!c.filePush("${Ocbm.FW_DIR}/${stagedName(e.path)}", bytes, 420, 60_000)) {
                return Result.failure(IllegalStateException("could not send ${e.path}"))
            }
        }
        progress("Installing on the adapter")
        val nonce = newNonce()
        if (!c.filePush("${Ocbm.FW_DIR}/manifest", m.text(nonce).toByteArray(Charsets.US_ASCII), 420)) {
            return Result.failure(IllegalStateException("could not send the manifest"))
        }
        val r = ask(c, Ocbm.FW_OP_APPLY, "apply.json", 120_000) { parseApply(it, nonce) }
            ?: return Result.failure(IllegalStateException("the adapter did not report the install"))
        r.onSuccess { log.i("firmware ${m.version} installed (${todo.size} file(s)); the adapter is rebooting and rolls back unless confirmed") }
            .onFailure { log.w("firmware ${m.version} refused: ${it.message}") }
        return r
    }

    fun rollback(c: OcbmClient): Boolean {
        val ok = c.mgmtAction(Ocbm.MGMT_FW, byteArrayOf(Ocbm.FW_OP_ROLLBACK)) == 0
        log.i("firmware rollback requested — started=$ok")
        return ok
    }

    // ---- Confirmation ---------------------------------------------------------------------------

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "adapter-fw").apply { isDaemon = true } }
    @Volatile private var askedOf: OcbmClient? = null

    /**
     * Called with every `CT_BOX_HEALTH` (read thread). The first healthy report from a link asks the
     * adapter whether an update is waiting and confirms it. Once per link; never on the read thread.
     */
    fun noteHealth(c: OcbmClient, bits: Int) {
        if (!healthy(bits) || askedOf === c) return
        askedOf = c
        io.execute {
            val info = runCatching { c.mgmtGetInfo(3_000) }.getOrNull() ?: return@execute
            if (!info.contains("\"fw_pending\":true")) return@execute
            val ok = c.mgmtAction(Ocbm.MGMT_FW, byteArrayOf(Ocbm.FW_OP_CONFIRM)) == 0
            log.i("firmware update confirmed — the adapter is back with Bluetooth and its radios (health 0x%02x), sent=%s".format(bits, ok))
        }
    }

    /** What `MGMT_INFO` says about firmware, for Settings. */
    data class Info(val supported: Boolean, val version: String, val pending: Boolean, val prev: Boolean)

    fun parseInfo(json: String): Info {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return Info(false, "", false, false)
        return Info(o.optBoolean("fw_update"), o.optString("fw"), o.optBoolean("fw_pending"), o.optBoolean("fw_prev"))
    }
}
