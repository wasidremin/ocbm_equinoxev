package wasidremin.gmccpa.av

import android.content.Context
import android.graphics.Bitmap
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Counts in-flight work; never blocks. Pure, so it is host-testable. */
class PendingGate(private val max: Int) {
    private val n = AtomicInteger(0)

    fun tryAcquire(): Boolean {
        while (true) {
            val c = n.get()
            if (c >= max) return false
            if (n.compareAndSet(c, c + 1)) return true
        }
    }

    fun release() {
        n.updateAndGet { if (it > 0) it - 1 else 0 }
    }

    val inFlight: Int get() = n.get()
}

/**
 * Bounded, app-private visual evidence for explicit launcher diagnostics.
 *
 * Callers pass OWNED bitmap copies, never the grabber's reusable bitmap. saveOwned() takes
 * ownership and recycles them in every path. All evidence and export file I/O runs on one
 * worker thread, so write, prune, clear and export never race. No network here.
 */
object LaunchEvidence {
    const val MAX_ATTEMPTS = 12
    const val MAX_BYTES = 48L * 1024L * 1024L

    /** Queued + running writes. Each holds up to two ~10.6MB 2754x966 ARGB copies. */
    const val MAX_PENDING_WRITES = 2

    private const val TAG = "LaunchEvidence"
    private const val WORKER_NAME = "launch-evidence"
    private val ID = Regex("[A-Za-z0-9_-]{1,96}")

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, WORKER_NAME).apply { isDaemon = true }
    }
    private val gate = PendingGate(MAX_PENDING_WRITES)

    fun root(ctx: Context): File = File(ctx.applicationContext.filesDir, "launch_evidence")

    fun isValidId(raw: String): Boolean = ID.matches(raw)

    /**
     * Never throws and never blocks the caller. Returns false when evidence was skipped
     * (invalid id, too many writes pending, or executor rejected). Bitmaps are recycled in
     * every case. A false return must not change any launch outcome.
     */
    fun saveOwned(
        ctx: Context,
        attemptId: String,
        before: Bitmap?,
        verification: Bitmap?,
        json: String,
    ): Boolean {
        val appCtx = ctx.applicationContext
        fun releaseBitmaps() {
            before?.recycle()
            if (verification !== before) verification?.recycle()
        }
        if (!isValidId(attemptId)) {
            releaseBitmaps()
            Log.w(TAG, "evidence skipped: invalid attempt id")
            return false
        }
        if (!gate.tryAcquire()) {
            releaseBitmaps()
            Log.w(TAG, "evidence skipped for $attemptId: $MAX_PENDING_WRITES writes already pending")
            return false
        }
        return try {
            worker.execute {
                try {
                    writeAttempt(appCtx, attemptId, before, verification, json)
                } finally {
                    releaseBitmaps()
                    gate.release()
                }
            }
            true
        } catch (t: Throwable) {
            releaseBitmaps()
            gate.release()
            Log.w(TAG, "evidence skipped for $attemptId: ${t.javaClass.simpleName}")
            false
        }
    }

    private fun writeAttempt(
        appCtx: Context,
        id: String,
        before: Bitmap?,
        verification: Bitmap?,
        json: String,
    ) {
        val base = root(appCtx)
        val pending = File(base, ".$id.pending")
        val completed = File(base, id)
        try {
            base.mkdirs()
            require(!completed.exists()) { "Evidence already exists for $id" }
            pending.deleteRecursively()
            check(pending.mkdirs()) { "Could not create evidence directory" }
            if (before != null) writePng(File(pending, "before.png"), before)
            if (verification != null) writePng(File(pending, "verification.png"), verification)
            File(pending, "evidence.json").writeText(
                envelopeJson(id, json, before != null, verification != null).toString(2),
                Charsets.UTF_8,
            )
            check(pending.renameTo(completed)) { "Could not finalize evidence" }
            Log.i(TAG, "evidence saved for $id (before=${before != null}, verification=${verification != null})")
        } catch (error: Throwable) {
            pending.deleteRecursively()
            Log.w(TAG, "Evidence write failed for $id", error)
        } finally {
            runCatching { prune(base) }
        }
    }

    /** Deletes saved screenshots AND app-private diagnostic ZIPs (they contain screenshots). */
    fun clear(ctx: Context) {
        val appCtx = ctx.applicationContext
        worker.execute {
            root(appCtx).deleteRecursively()
            LaunchDiagnosticsBundle.exportsDir(appCtx).listFiles()?.forEach { f ->
                if (f.isFile && (f.name.endsWith(".zip") || f.name.endsWith(".part"))) f.delete()
            }
        }
    }

    /** Completed attempt ids, oldest first. */
    fun listAttempts(ctx: Context): List<String> =
        root(ctx.applicationContext).listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.map { it.name }
            ?.sorted()
            ?: emptyList()

    /** Run evidence/export file I/O on the evidence worker and wait. Never from main or the worker. */
    internal fun <T> runSerialized(block: () -> T): T {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "launch evidence I/O must not run on the main thread"
        }
        check(Thread.currentThread().name != WORKER_NAME) {
            "runSerialized called from the evidence worker"
        }
        try {
            return worker.submit(Callable { block() }).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun writePng(file: File, bitmap: Bitmap) {
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoding failed" }
        }
    }

    private fun sizeOf(file: File): Long =
        if (file.isFile) file.length() else file.listFiles()?.sumOf { sizeOf(it) } ?: 0L

    /** Runs only on the worker, after the current write has finished or failed. */
    private fun prune(base: File) {
        val entries = base.listFiles()
            ?.filter { it.isDirectory }
            ?.map { Entry(it.name, sizeOf(it)) }
            ?: return
        for (name in prunePlan(entries)) File(base, name).deleteRecursively()
    }

    // ---- pure, host-testable helpers ------------------------------------------------------

    data class Entry(val name: String, val bytes: Long)

    /**
     * Everything to delete: every stale ".<id>.pending" dir (safe because prune runs on the
     * single writer after its own write finished), plus the retention-plan prefix of
     * completed attempts. This is the ONLY deletion rule used by prune().
     */
    fun prunePlan(entries: List<Entry>): List<String> {
        val stale = entries
            .filter { it.name.startsWith(".") && it.name.endsWith(".pending") }
            .map { it.name }
        val completed = entries.filter { !it.name.startsWith(".") }.sortedBy { it.name }
        return stale + retentionPlan(
            completed.map { it.name },
            completed.associate { it.name to it.bytes },
        )
    }

    fun retentionPlan(namesInOrder: List<String>, sizes: Map<String, Long>): List<String> {
        val remaining = namesInOrder.toMutableList()
        var bytes = remaining.sumOf { sizes[it] ?: 0L }
        val drop = mutableListOf<String>()
        while (remaining.isNotEmpty() && (remaining.size > MAX_ATTEMPTS || bytes > MAX_BYTES)) {
            val oldest = remaining.removeAt(0)
            drop += oldest
            bytes -= (sizes[oldest] ?: 0L)
        }
        return drop
    }

    fun envelopeJson(attemptId: String, json: String, before: Boolean, verification: Boolean): JSONObject =
        JSONObject(json).apply {
            put("attemptId", attemptId)
            put("schemaVersion", 1)
            put("beforeFrameAvailable", before)
            put("verificationFrameAvailable", verification)
        }

    fun jsonScore(value: Float?): Double? =
        if (value == null || !value.isFinite()) null else value.toDouble()

    fun shouldSaveEvidence(saveScreenshots: Boolean, hasBefore: Boolean, hasVerify: Boolean): Boolean =
        saveScreenshots && (hasBefore || hasVerify)
}

/**
 * Explicit "Export launch diagnostics" packer: latest launch records plus saved evidence.
 * Runs on the evidence worker so it cannot race write/prune/clear. Call from a background
 * thread only. No probes, no logs, no network.
 */
object LaunchDiagnosticsBundle {
    const val MAX_RECORDS = 20
    const val MAX_KEPT_ZIPS = 3

    fun exportsDir(ctx: Context): File = File(ctx.applicationContext.filesDir, "exports")

    /** Null when there is nothing to export. Throws on I/O failure; no partial ZIP is left. */
    fun buildZip(ctx: Context): File? {
        val appCtx = ctx.applicationContext
        return LaunchEvidence.runSerialized { buildZipOnWorker(appCtx) }
    }

    private fun buildZipOnWorker(appCtx: Context): File? {
        val records = CarPlayLauncher.listLaunches(appCtx).take(MAX_RECORDS)
        val attempts = LaunchEvidence.listAttempts(appCtx)
        if (records.isEmpty() && attempts.isEmpty()) return null
        val dir = exportsDir(appCtx)
        dir.mkdirs()
        dir.listFiles()?.forEach { if (it.isFile && it.name.endsWith(".part")) it.delete() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }.format(java.util.Date())
        val part = File.createTempFile("launch-diagnostics-$stamp-", ".zip.part", dir)
        try {
            java.util.zip.ZipOutputStream(FileOutputStream(part)).use { out ->
                for (rec in records) {
                    out.putNextEntry(java.util.zip.ZipEntry("launches/${rec.id}.json"))
                    out.write(rec.toJson().toString(2).toByteArray(Charsets.UTF_8))
                    out.closeEntry()
                }
                val base = LaunchEvidence.root(appCtx)
                for (id in attempts) {
                    val attemptDir = File(base, id)
                    if (!attemptDir.isDirectory) continue
                    for (name in listOf("evidence.json", "before.png", "verification.png")) {
                        val f = File(attemptDir, name)
                        if (!f.isFile) continue
                        out.putNextEntry(java.util.zip.ZipEntry("evidence/$id/$name"))
                        f.inputStream().use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
            }
            val zip = File(dir, part.name.removeSuffix(".part"))
            check(part.renameTo(zip)) { "Could not finalize ${zip.name}" }
            pruneZips(dir)
            return zip
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
    }

    private fun pruneZips(dir: File) {
        val zips = dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?.sortedBy { it.name }?.toMutableList() ?: return
        while (zips.size > MAX_KEPT_ZIPS) zips.removeAt(0).delete()
    }
}
