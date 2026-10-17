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
 * The report file is streamed verbatim — `report.json` already embeds the raw live events as
 * `rawLiveEvents`, so this one file is everything the importer needs and the ZIP/CSV siblings stay
 * local-only transport. Redaction follows the capture's own explicit raw-GPS opt-in; this uploader
 * adds no coordinates of its own.
 */
object ProbeUpload {
    const val MAX_REPORT_BYTES = 60L * 1024 * 1024
    const val PENDING_MARKER = ".pending_upload"
    const val UPLOADED_MARKER = ".uploaded"
    private const val PREFS = "vehicle_telemetry_auto_prefs"
    private const val KEY_AUTO_UPLOAD = "auto_upload_enabled"
    private const val KEY_RAW_LOCATION = "auto_raw_location_opt_in"
    private val UNSAFE = Regex("[^A-Za-z0-9._-]+")

    data class UploadResult(val endpoint: String, val file: String, val bytes: Long)

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

    fun listPendingReportDirs(rootDir: File): List<File> {
        val dirs = rootDir.listFiles()?.filter { it.isDirectory } ?: return emptyList()
        return dirs
            .filter { dir ->
                val report = File(dir, "report.json")
                report.isFile && report.length() in 1..MAX_REPORT_BYTES &&
                    (File(dir, PENDING_MARKER).isFile || !File(dir, UPLOADED_MARKER).isFile)
            }
            .sortedBy { it.name }
    }

    fun pendingReportCount(ctx: Context): Int =
        listPendingReportDirs(telemetryRootDir(ctx)).size

    fun pruneOldReports(rootDir: File, maxKeep: Int = ProbeModel.MAX_PENDING_REPORTS) {
        val dirs = rootDir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return
        if (dirs.size <= maxKeep) return
        val uploaded = dirs.filter { File(it, UPLOADED_MARKER).isFile && !File(it, PENDING_MARKER).isFile }
        val toRemove = uploaded.take((dirs.size - maxKeep).coerceAtLeast(0))
        toRemove.forEach { runCatching { it.deleteRecursively() } }
    }

    /** Synchronous core, runs on the caller's thread (never the UI thread). Streams the file. */
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
        return wasidremin.gmccpa.upload.UploadCenter.executeWithOneRetry {
            onState?.let {
                wasidremin.gmccpa.upload.UploadCenter.emitState(
                    it,
                    wasidremin.gmccpa.upload.UploadState.InProgress(0L, size),
                )
            }
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = wasidremin.gmccpa.upload.UploadCenter.CONNECT_TIMEOUT_MS
                readTimeout = wasidremin.gmccpa.upload.UploadCenter.WRITE_READ_TIMEOUT_MS
                doOutput = true
                setFixedLengthStreamingMode(size)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Device", "gmccpa")
                setRequestProperty("X-Report-Name", name)
            }
            try {
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
                    reportFile.inputStream().use { input ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val r = input.read(buf)
                            if (r <= 0) break
                            out.write(buf, 0, r)
                        }
                    }
                    out.flush()
                }
                onState?.let {
                    wasidremin.gmccpa.upload.UploadCenter.emitState(
                        it,
                        wasidremin.gmccpa.upload.UploadState.Finalizing,
                    )
                }
                val code = connection.responseCode
                val body = try {
                    (if (code in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.readText()
                } catch (_: Exception) { null }
                if (code !in 200..299) {
                    throw wasidremin.gmccpa.upload.UploadCenter.HttpStatusException(
                        code,
                        "server returned HTTP $code for probe upload: ${body?.take(300)}",
                    )
                }
                UploadResult(endpoint, name, size)
            } finally {
                connection.disconnect()
            }
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
     * Queue a completed report directory and immediately attempt background upload + drain of any
     * older pending telemetry segments.
     */
    fun enqueueAndDrain(
        ctx: Context,
        reportDir: File? = null,
        activeDir: File? = null,
        onState: ((wasidremin.gmccpa.upload.UploadState) -> Unit)? = null,
    ) {
        val appCtx = ctx.applicationContext
        if (reportDir != null) {
            markReportPending(reportDir)
        }
        wasidremin.gmccpa.upload.UploadCenter.notifyQueueChanged(appCtx)
        if (!isAutoUploadEnabled(appCtx)) return
        ioExecutor.execute {
            val root = telemetryRootDir(appCtx)
            pruneOldReports(root)
            val pending = listPendingReportDirs(root)
                .filter { activeDir == null || it.absolutePath != activeDir.absolutePath }
            if (pending.isEmpty()) {
                wasidremin.gmccpa.upload.UploadCenter.notifyQueueChanged(appCtx)
                return@execute
            }
            val base = wasidremin.gmccpa.upload.UploadCenter.baseUrl(appCtx)
            onState?.let { wasidremin.gmccpa.upload.UploadCenter.emitState(it, wasidremin.gmccpa.upload.UploadState.Preparing) }
            for (dir in pending) {
                val reportFile = File(dir, "report.json")
                if (!reportFile.isFile) continue
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
                    if (!permanent) break
                }
            }
            pruneOldReports(root)
            wasidremin.gmccpa.upload.UploadCenter.notifyQueueChanged(appCtx)
        }
    }
}
