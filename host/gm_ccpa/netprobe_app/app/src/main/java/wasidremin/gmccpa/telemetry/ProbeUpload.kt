package wasidremin.gmccpa.telemetry

import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Uploads completed vehicle-telemetry `report.json` segments to the receiver's `/probe` endpoint,
 * with durable `.pending_upload` queue markers so offline/transient-failure segments automatically
 * drain and retry when connectivity returns.
 *
 * `report.json` already embeds the raw live events as `rawLiveEvents`, so this one file is
 * everything the importer needs and the ZIP/CSV siblings stay local-only transport. It goes out in
 * the slim wire form ([ProbeSlim]): gzipped, with the part that never changes inside one capture
 * sent once. Redaction follows the capture's own explicit raw-GPS opt-in; this uploader adds no
 * coordinates of its own.
 *
 * **A segment directory is in exactly one state**, told by its marker file:
 *  - `.pending_upload` - waiting to be sent;
 *  - `.uploaded` - sent;
 *  - `.skipped` - deliberately not sent (a parked minute the thinning rule dropped, uploads switched
 *    off, or a backlog too old to be worth mobile data);
 *  - no marker - the segment being written now, or one orphaned when the head unit killed the app.
 *    Its `report.json` was written when the segment opened and holds no events, so it is never sent.
 *
 * Only `.pending_upload` is ever sent, and everything that is not pending is fair game for pruning
 * (never the directory being written - see [activeDir]).
 */
object ProbeUpload {
    const val MAX_REPORT_BYTES = 60L * 1024 * 1024
    const val PENDING_MARKER = ".pending_upload"
    const val UPLOADED_MARKER = ".uploaded"
    const val SKIPPED_MARKER = ".skipped"
    /** A queued segment older than this is not worth sending: nobody is waiting for it. */
    const val MAX_BACKLOG_AGE_MS = 24L * 60 * 60 * 1000
    /** At most this many queued segments are sent when a connection comes back (about 1 MB). */
    const val MAX_BACKLOG_SEGMENTS = 60
    private const val PREFS = "vehicle_telemetry_auto_prefs"
    private const val KEY_AUTO_UPLOAD = "auto_upload_enabled"
    private const val KEY_RAW_LOCATION = "auto_raw_location_opt_in"
    private val UNSAFE = Regex("[^A-Za-z0-9._-]+")

    /** @param bytes what went on the wire (gzipped), not the size of `report.json` */
    data class UploadResult(val endpoint: String, val file: String, val bytes: Long)

    /**
     * The directory [VehicleProbe] is writing into, if any. Drains and pruning are started from
     * several places that cannot know it (boot, the launcher, a settings toggle), so it is published
     * here rather than passed around: the live segment must never be uploaded or deleted.
     */
    @Volatile var activeDir: File? = null

    /** Static parts the receiver has acknowledged in this process. See [uploadSync]. */
    private val knownStatic = java.util.Collections.synchronizedSet(HashSet<String>())

    fun isAutoUploadEnabled(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_UPLOAD, true)

    fun setAutoUploadEnabled(ctx: Context, enabled: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTO_UPLOAD, enabled).apply()
    }

    fun isAutoRawLocationEnabled(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_RAW_LOCATION, true)

    fun setAutoRawLocationEnabled(ctx: Context, enabled: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_RAW_LOCATION, enabled).apply()
    }

    fun telemetryRootDir(ctx: Context): File =
        File(ctx.applicationContext.getExternalFilesDir(null) ?: ctx.applicationContext.filesDir, "telemetry")

    fun endpointFor(baseUrl: String): String = baseUrl.trim().trimEnd('/') + "/probe"

    fun sanitizeName(raw: String?): String {
        val cleaned = (raw ?: "").trim().replace(UNSAFE, "_").trim('.', '_').take(64)
        return cleaned.ifEmpty { "report" }
    }

    fun checkHttps(baseUrl: String) {
        require(baseUrl.trim().startsWith("https://", ignoreCase = true)) {
            "probe report upload requires an HTTPS URL"
        }
    }

    fun markReportPending(reportDir: File): Boolean {
        val report = File(reportDir, "report.json")
        if (!report.isFile || report.length() !in 1..MAX_REPORT_BYTES) return false
        return runCatching {
            File(reportDir, SKIPPED_MARKER).delete()
            File(reportDir, PENDING_MARKER).writeText(reportDir.name, Charsets.UTF_8)
            true
        }.getOrDefault(false)
    }

    fun markReportUploaded(reportDir: File, serverRef: String) {
        runCatching {
            File(reportDir, PENDING_MARKER).delete()
            File(reportDir, UPLOADED_MARKER).writeText(serverRef, Charsets.UTF_8)
        }
    }

    /** Close a segment without sending it. [reason] is a short word for whoever reads the folder. */
    fun markReportSkipped(reportDir: File, reason: String) {
        runCatching {
            File(reportDir, PENDING_MARKER).delete()
            File(reportDir, SKIPPED_MARKER).writeText(reason, Charsets.UTF_8)
        }
    }

    fun listPendingReportDirs(rootDir: File): List<File> {
        val dirs = rootDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs
            .filter { dir ->
                val report = File(dir, "report.json")
                File(dir, PENDING_MARKER).isFile && report.isFile && report.length() in 1..MAX_REPORT_BYTES
            }
            .sortedBy { it.name }
    }

    fun pendingReportCount(ctx: Context): Int =
        listPendingReportDirs(telemetryRootDir(ctx)).size

    /**
     * Keep the newest [maxKeep] segment directories. Anything not waiting to be sent may go, oldest
     * first; [keep] (the segment being written) never does.
     */
    fun pruneOldReports(rootDir: File, maxKeep: Int = ProbeModel.MAX_PENDING_REPORTS, keep: File? = activeDir) {
        val dirs = rootDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return
        if (dirs.size <= maxKeep) return
        val removable = dirs.filter { !File(it, PENDING_MARKER).isFile && it.absolutePath != keep?.absolutePath }
        val toRemove = removable.take((dirs.size - maxKeep).coerceAtLeast(0))
        toRemove.forEach { runCatching { it.deleteRecursively() } }
    }

    /**
     * Which queued segments are still worth sending. [pending] is oldest first, each with the time
     * its report was written. Returns (send, drop): the newest [maxCount] that are younger than
     * [maxAgeMs] are sent, in order; the rest are dropped.
     *
     * Without this the queue had no ceiling: a car that spent a week without a connection sent the
     * whole week the moment it found one.
     */
    fun <T> selectBacklog(
        pending: List<Pair<T, Long>>,
        nowMs: Long,
        maxAgeMs: Long = MAX_BACKLOG_AGE_MS,
        maxCount: Int = MAX_BACKLOG_SEGMENTS,
    ): Pair<List<T>, List<T>> {
        val fresh = pending.filter { nowMs - it.second <= maxAgeMs }
        val send = fresh.takeLast(maxCount.coerceAtLeast(0))
        val sendSet = send.toSet()
        return send.map { it.first } to pending.filter { it !in sendSet }.map { it.first }
    }

    /**
     * Synchronous core, runs on the caller's thread (never the UI thread).
     *
     * The body is the slim form of the report. The static part travels with the first segment that
     * uses it; after the receiver has answered 200 for it once in this process it is left out, and if
     * the receiver ever answers 409 (it was restarted, or its copy aged out) the same segment is sent
     * again with the static part inline.
     */
    @Throws(IOException::class)
    fun uploadSync(
        reportFile: File,
        reportName: String,
        baseUrl: String,
        onState: ((wasidremin.gmccpa.upload.UploadState) -> Unit)? = null,
    ): UploadResult {
        checkHttps(baseUrl)
        require(reportFile.isFile) { "no completed report.json at ${reportFile.absolutePath}" }
        val size = reportFile.length()
        require(size in 1..MAX_REPORT_BYTES) { "report.json size $size out of range (1..$MAX_REPORT_BYTES)" }
        val endpoint = endpointFor(baseUrl)
        val name = sanitizeName(reportName)
        val split = if (size <= ProbeSlim.MAX_SPLIT_BYTES) {
            runCatching { ProbeSlim.split(org.json.JSONObject(reportFile.readText(Charsets.UTF_8))) }.getOrNull()
        } else null
        return wasidremin.gmccpa.upload.UploadCenter.executeWithOneRetry {
            var includeStatic = split != null && split.staticId !in knownStatic
            var body = split?.body(includeStatic) ?: ProbeSlim.gzipFile(reportFile)
            var response = post(endpoint, name, body, onState)
            if (response.first == 409 && split != null && !includeStatic) {
                // The receiver no longer holds this capture's static part: send it again, once.
                knownStatic.remove(split.staticId)
                includeStatic = true
                body = split.body(true)
                response = post(endpoint, name, body, onState)
            }
            val (code, text) = response
            if (code !in 200..299) {
                throw wasidremin.gmccpa.upload.UploadCenter.HttpStatusException(
                    code,
                    "server returned HTTP $code for probe upload: ${text?.take(300)}",
                )
            }
            split?.let { knownStatic.add(it.staticId) }
            UploadResult(endpoint, name, body.size.toLong())
        }
    }

    /** One gzipped POST. Returns the status and the response text; counts the bytes it sent. */
    private fun post(
        endpoint: String,
        name: String,
        body: ByteArray,
        onState: ((wasidremin.gmccpa.upload.UploadState) -> Unit)?,
    ): Pair<Int, String?> {
        val size = body.size.toLong()
        onState?.let {
            wasidremin.gmccpa.upload.UploadCenter.emitState(it, wasidremin.gmccpa.upload.UploadState.InProgress(0L, size))
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = wasidremin.gmccpa.upload.UploadCenter.CONNECT_TIMEOUT_MS
            readTimeout = wasidremin.gmccpa.upload.UploadCenter.WRITE_READ_TIMEOUT_MS
            doOutput = true
            setFixedLengthStreamingMode(size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Content-Encoding", "gzip")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Device", "gmccpa")
            setRequestProperty("X-Report-Name", name)
        }
        try {
            wasidremin.gmccpa.upload.DataMeter.record(wasidremin.gmccpa.upload.DataMeter.Stream.TELEMETRY, size)
            wasidremin.gmccpa.upload.CountingRequestOutputStream(
                delegate = connection.outputStream,
                bytesTotal = size,
            ) { sent, tot ->
                onState?.let {
                    wasidremin.gmccpa.upload.UploadCenter.emitState(
                        it,
                        wasidremin.gmccpa.upload.UploadState.InProgress(sent, tot),
                    )
                }
            }.use { out ->
                out.write(body)
                out.flush()
            }
            onState?.let {
                wasidremin.gmccpa.upload.UploadCenter.emitState(it, wasidremin.gmccpa.upload.UploadState.Finalizing)
            }
            val code = connection.responseCode
            val text = try {
                (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.readText()
            } catch (_: Exception) { null }
            return code to text
        } finally {
            connection.disconnect()
        }
    }

    private val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "probe-upload-io").apply { isDaemon = true } }

    fun upload(
        reportFile: File,
        reportName: String,
        baseUrl: String,
        onState: ((wasidremin.gmccpa.upload.UploadState) -> Unit)? = null,
        callback: (Result<UploadResult>) -> Unit,
    ) {
        reportFile.parentFile?.let { markReportPending(it) }
        onState?.let { wasidremin.gmccpa.upload.UploadCenter.emitState(it, wasidremin.gmccpa.upload.UploadState.Preparing) }
        ioExecutor.execute {
            val res = runCatching { uploadSync(reportFile, reportName, baseUrl, onState) }
            res.onSuccess { r ->
                reportFile.parentFile?.let { markReportUploaded(it, r.file) }
                onState?.let {
                    wasidremin.gmccpa.upload.UploadCenter.emitState(
                        it,
                        wasidremin.gmccpa.upload.UploadState.Success(r.file),
                    )
                }
            }.onFailure { t ->
                onState?.let {
                    wasidremin.gmccpa.upload.UploadCenter.emitState(
                        it,
                        wasidremin.gmccpa.upload.UploadCenter.classifyFailure(t, allowQueue = true),
                    )
                }
            }
            callback(res)
        }
    }

    /**
     * Queue a completed report directory (if one is given) and send what is waiting, in the
     * background.
     *
     * Nothing is sent while "EV Insights auto-upload" is off, and nothing is sent over the car's
     * connection once the month's data limit is reached ([wasidremin.gmccpa.upload.DataMeter]); in
     * both cases the folder is still pruned, because the capture now runs regardless of the upload
     * switch and would otherwise fill the disk.
     */
    fun enqueueAndDrain(
        ctx: Context,
        reportDir: File? = null,
        activeDir: File? = null,
        onState: ((wasidremin.gmccpa.upload.UploadState) -> Unit)? = null,
    ) {
        val appCtx = ctx.applicationContext
        wasidremin.gmccpa.upload.DataMeter.attach(appCtx)
        if (reportDir != null) {
            markReportPending(reportDir)
        }
        wasidremin.gmccpa.upload.UploadCenter.notifyQueueChanged(appCtx)
        ioExecutor.execute {
            val root = telemetryRootDir(appCtx)
            val live = activeDir ?: this.activeDir
            if (!isAutoUploadEnabled(appCtx)) {
                pruneOldReports(root, keep = live)
                return@execute
            }
            val (pending, stale) = selectBacklog(
                listPendingReportDirs(root)
                    .filter { live == null || it.absolutePath != live.absolutePath }
                    .map { it to File(it, "report.json").lastModified() },
                System.currentTimeMillis(),
            )
            stale.forEach { markReportSkipped(it, "stale") }
            if (pending.isEmpty()) {
                pruneOldReports(root, keep = live)
                wasidremin.gmccpa.upload.UploadCenter.notifyQueueChanged(appCtx)
                return@execute
            }
            val base = wasidremin.gmccpa.upload.UploadCenter.baseUrl(appCtx)
            onState?.let { wasidremin.gmccpa.upload.UploadCenter.emitState(it, wasidremin.gmccpa.upload.UploadState.Preparing) }
            for (dir in pending) {
                val reportFile = File(dir, "report.json")
                if (!reportFile.isFile) continue
                // Checked per segment: a long drain must stop the moment it crosses the limit.
                if (!wasidremin.gmccpa.upload.DataMeter.allowBackground(appCtx)) break
                try {
                    val res = uploadSync(reportFile, dir.name, base, onState)
                    markReportUploaded(dir, res.file)
                    onState?.let {
                        wasidremin.gmccpa.upload.UploadCenter.emitState(
                            it,
                            wasidremin.gmccpa.upload.UploadState.Success(res.file),
                        )
                    }
                } catch (t: Throwable) {
                    markReportPending(dir)
                    onState?.let {
                        wasidremin.gmccpa.upload.UploadCenter.emitState(
                            it,
                            wasidremin.gmccpa.upload.UploadCenter.classifyFailure(t, allowQueue = true),
                        )
                    }
                    // A permanently rejected segment (4xx other than 408/429) must not block newer
                    // segments behind it; transient failures stop the drain and retry later.
                    val permanent = (t as? wasidremin.gmccpa.upload.UploadCenter.HttpStatusException)
                        ?.let { it.code in 400..499 && it.code != 408 && it.code != 429 } == true
                    if (permanent) markReportSkipped(dir, "rejected") else break
                }
            }
            pruneOldReports(root, keep = live)
            wasidremin.gmccpa.upload.UploadCenter.notifyQueueChanged(appCtx)
        }
    }

    /**
     * Close a segment that will not be sent, and tidy the folder. Used for the parked minutes the
     * thinning rule drops and for every segment while uploads are switched off.
     */
    fun skipAndPrune(ctx: Context, reportDir: File, reason: String) {
        val appCtx = ctx.applicationContext
        markReportSkipped(reportDir, reason)
        ioExecutor.execute { pruneOldReports(telemetryRootDir(appCtx)) }
    }
}
