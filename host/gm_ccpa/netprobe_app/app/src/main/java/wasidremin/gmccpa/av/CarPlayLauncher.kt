package wasidremin.gmccpa.av

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.match.IconMatcher
import wasidremin.gmccpa.match.IconTemplateStore
import wasidremin.gmccpa.ocbm.AdapterSession
import wasidremin.gmccpa.ocbm.Ocbm
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Icon-tap app launching on the CarPlay screen with matcher verification (Phase 4).
 *
 * Workflow per launch, all off the UI thread (touch sends block on the event-channel lock):
 *
 * 1. `home` — AC Home to a known screen, then a stable frame.
 * 2. `tap` — DOWN/UP at the target's normalized coordinates via [AdapterSession.sendTouch].
 * 3. `post_stable` — stable frame after the tap settles.
 * 4. `verify` — [IconMatcher.findBest] of the target's template on the post-tap working image.
 *
 * Every step lands in a [LaunchRecord] (`filesDir/launches/<id>.json`, plus logcat), saved
 * whether the launch succeeds or fails — a failed launch with its scores IS the diagnostic.
 * [uploadLaunch] POSTs the record JSON to `{baseUrl}/api/launches`; the file on disk is the
 * retry source, same local-first contract as the captures.
 */
object CarPlayLauncher {
    private val log = ProbeLog.sub("launch")

    /** Working-image resolution the verify step searches (content rect, grayscale 0..1). */
    const val WORK_W = 192
    const val WORK_H = 72

    /** Stride for the verify sweep: full precision is wasted on a 192x72 field. */
    const val VERIFY_STRIDE = 2

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cp-launcher").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    data class LaunchTarget(
        val name: String,
        /** Normalized 0..1 in the ADVERTISED geometry (same space as touch sends). */
        val nx: Float,
        val ny: Float,
        /** Template name in [IconTemplateStore] to verify post-tap; null skips verification. */
        val verifyTemplate: String? = null,
        val tapDwellMs: Long = 120L,
        val settleTimeoutMs: Long = CarPlayFrameGrabber.DEFAULT_STABLE_TIMEOUT_MS,
    ) {
        init {
            require(name.isNotBlank()) { "target name must not be blank" }
            require(nx in 0f..1f && ny in 0f..1f) {
                "tap point must be in 0..1 (got n=($nx, $ny))"
            }
            require(tapDwellMs in 40L..1000L) { "tapDwellMs=$tapDwellMs outside 40..1000ms" }
        }
    }

    /** NOT_FOUND = no confident match, nothing tapped but the Home toggle. UNVERIFIED = tapped, screen did not change. */
    enum class Outcome { SUCCESS, VERIFY_FAILED, FAILED, NOT_FOUND, UNVERIFIED }

    data class LaunchStep(
        val tIso: String,
        val step: String,
        val detail: String,
        val score: Float? = null,
        val passed: Boolean? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("t", tIso)
            put("step", step)
            put("detail", detail)
            if (score != null) put("score", score.toDouble()) else put("score", JSONObject.NULL)
            if (passed != null) put("passed", passed) else put("passed", JSONObject.NULL)
        }

        companion object {
            fun fromJson(obj: JSONObject): LaunchStep = LaunchStep(
                tIso = obj.optString("t", ""),
                step = obj.optString("step", ""),
                detail = obj.optString("detail", ""),
                score = if (obj.isNull("score")) null else obj.optDouble("score").toFloat(),
                passed = if (obj.isNull("passed")) null else obj.optBoolean("passed"),
            )
        }
    }

    /**
     * [verificationBasis] names what the verifier actually established; the strip
     * verifier only sees a screen transition plus surface classification, which is NOT
     * independent target-app identification ([targetAppIdentityConfirmed] stays false).
     * `false` means "not independently established by this verifier", not "the app
     * did not open". [outcome] keeps its legacy meaning for compatibility.
     */
    data class LaunchRecord(
        val id: String,
        val startedIso: String,
        val targetName: String,
        val nx: Float,
        val ny: Float,
        val verifyTemplate: String?,
        val steps: List<LaunchStep>,
        val outcome: Outcome,
        val error: String? = null,
        val verificationBasis: String = "transition_and_surface_only",
        val targetAppIdentityConfirmed: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("started", startedIso)
            put("target", targetName)
            put("nx", nx.toDouble())
            put("ny", ny.toDouble())
            put("verifyTemplate", verifyTemplate ?: JSONObject.NULL)
            put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
            put("outcome", outcome.name)
            put("error", error ?: JSONObject.NULL)
            put("verificationBasis", verificationBasis)
            put("targetAppIdentityConfirmed", targetAppIdentityConfirmed)
        }

        companion object {
            fun fromJson(obj: JSONObject): LaunchRecord {
                val steps = mutableListOf<LaunchStep>()
                val arr = obj.optJSONArray("steps")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        steps += LaunchStep.fromJson(arr.getJSONObject(i))
                    }
                }
                return LaunchRecord(
                    id = obj.optString("id", ""),
                    startedIso = obj.optString("started", ""),
                    targetName = obj.optString("target", ""),
                    nx = obj.optDouble("nx", 0.0).toFloat(),
                    ny = obj.optDouble("ny", 0.0).toFloat(),
                    verifyTemplate = if (obj.isNull("verifyTemplate")) null
                        else obj.optString("verifyTemplate").takeIf { it.isNotEmpty() },
                    steps = steps,
                    outcome = runCatching {
                        Outcome.valueOf(obj.optString("outcome", "FAILED"))
                    }.getOrDefault(Outcome.FAILED),
                    error = if (obj.isNull("error")) null
                        else obj.optString("error").takeIf { it.isNotEmpty() },
                    verificationBasis = obj.optString(
                        "verificationBasis", "transition_and_surface_only"
                    ).takeIf { it.isNotEmpty() } ?: "transition_and_surface_only",
                    targetAppIdentityConfirmed = obj.optBoolean("targetAppIdentityConfirmed", false),
                )
            }
        }
    }

    fun launchesRootDir(ctx: Context): File = File(ctx.applicationContext.filesDir, "launches")

    /**
     * DOWN/UP tap at normalized coordinates. Returns false when there is no live channel
     * (not an error — same contract as [AdapterSession.sendTouch]).
     */
    fun tapBlocking(nx: Float, ny: Float, dwellMs: Long = 120L): Boolean {
        require(nx in 0f..1f && ny in 0f..1f) { "tap point must be in 0..1" }
        if (!AdapterSession.sendTouch(Ocbm.TOUCH_DOWN, nx, ny)) return false
        try {
            Thread.sleep(dwellMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            AdapterSession.sendTouch(Ocbm.TOUCH_UP, nx, ny)
            return false
        }
        return AdapterSession.sendTouch(Ocbm.TOUCH_UP, nx, ny)
    }

    fun launchAsync(
        ctx: Context,
        target: LaunchTarget,
        onStep: ((LaunchStep) -> Unit)? = null,
        onDone: (LaunchRecord) -> Unit,
    ) {
        val appCtx = ctx.applicationContext
        worker.execute {
            val record = launchBlocking(appCtx, target, onStep)
            main.post { onDone(record) }
        }
    }

    fun launchBlocking(
        ctx: Context,
        target: LaunchTarget,
        onStep: ((LaunchStep) -> Unit)? = null,
    ): LaunchRecord {
        val appCtx = ctx.applicationContext
        val id = newLaunchId()
        val steps = mutableListOf<LaunchStep>()
        fun step(name: String, detail: String, score: Float? = null, passed: Boolean? = null) {
            val s = LaunchStep(isoNow(System.currentTimeMillis()), name, detail, score, passed)
            steps += s
            log.i("launch $id [$name] $detail" + (score?.let { " (score=%.3f)".format(it) } ?: ""))
            onStep?.invoke(s)
        }

        var outcome = Outcome.FAILED
        var error: String? = null
        try {
            if (!CarPlayFrameGrabber.isSessionActive()) {
                throw IllegalStateException("No active CarPlay session")
            }
            val surface = CarPlayActivity.liveSurfaceView()
                ?: throw IllegalStateException("CarPlay video surface is not ready — return to CarPlay screen first")

            CarPlayFrameGrabber.sendAcHome()
            try {
                Thread.sleep(80L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("Interrupted before pre-tap capture")
            }
            val pre = stableOrThrow(surface, target.settleTimeoutMs)
            step("home", "AC Home sent; pre-tap frame stable after ${pre.framesSampled} samples (${pre.lastMad})")

            if (!tapBlocking(target.nx, target.ny, target.tapDwellMs)) {
                throw IllegalStateException("Tap DOWN/UP refused — the event channel is down")
            }
            step("tap", "tap DOWN/UP at n=(%.3f, %.3f)".format(target.nx, target.ny))

            val post = stableOrThrow(surface, target.settleTimeoutMs)
            step("post_stable", "post-tap frame stable after ${post.framesSampled} samples (${post.lastMad})")

            val templateName = target.verifyTemplate
            if (templateName == null) {
                outcome = Outcome.SUCCESS
                step("verify", "no verify template — tap sent and screen re-settled")
            } else {
                val template = IconTemplateStore.loadTemplate(appCtx, templateName)
                    ?: throw IllegalStateException("Verify template \"$templateName\" not found in store")
                val working = workingImage(post.bitmap)
                val match = IconMatcher.findBest(
                    template, working, WORK_W, WORK_H, stride = VERIFY_STRIDE)
                if (match.passed) {
                    outcome = Outcome.SUCCESS
                } else {
                    outcome = Outcome.VERIFY_FAILED
                    error = "Verify \"${template.name}\" scored %.3f < %.2f at (${match.x}, ${match.y})"
                        .format(match.score, template.threshold)
                }
                step("verify", "template \"${template.name}\" at (${match.x}, ${match.y})",
                    score = match.score, passed = match.passed)
            }
        } catch (t: Throwable) {
            if (error == null) error = "${t.javaClass.simpleName}: ${t.message}"
            log.w("launch $id failed: $error")
        }
        val record = LaunchRecord(
            id = id,
            startedIso = isoNow(System.currentTimeMillis()),
            targetName = target.name,
            nx = target.nx,
            ny = target.ny,
            verifyTemplate = target.verifyTemplate,
            steps = steps.toList(),
            outcome = outcome,
            error = error,
        )
        saveRecord(appCtx, record)
        return record
    }

    fun listLaunches(ctx: Context): List<LaunchRecord> {
        val root = launchesRootDir(ctx.applicationContext)
        val files = root.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name } ?: return emptyList()
        return files.mapNotNull { f ->
            runCatching { LaunchRecord.fromJson(JSONObject(f.readText(Charsets.UTF_8))) }.getOrNull()
        }
    }

    /**
     * Upload one launch record to `{baseUrl}/api/launches` (JSON body). Terminal 4xx (other than
     * 408/429) is a non-retryable [wasidremin.gmccpa.upload.UploadState.Failed]; anything
     * transient retries once via [wasidremin.gmccpa.upload.UploadPolicy], then reports
     * retryable failure. The on-disk record is always kept.
     */
    fun uploadLaunch(
        ctx: Context,
        record: LaunchRecord,
        baseUrl: String = wasidremin.gmccpa.upload.UploadCenter.baseUrl(ctx),
        onState: ((wasidremin.gmccpa.upload.UploadState) -> Unit)? = null,
        callback: (Result<String>) -> Unit,
    ) {
        val appCtx = ctx.applicationContext
        onState?.let {
            wasidremin.gmccpa.upload.UploadCenter.emitState(it, wasidremin.gmccpa.upload.UploadState.Preparing)
        }
        worker.execute {
            val res = runCatching { uploadLaunchSync(baseUrl, record, onState) }
            res.onSuccess { ref ->
                onState?.let {
                    wasidremin.gmccpa.upload.UploadCenter.emitState(
                        it, wasidremin.gmccpa.upload.UploadState.Success(ref))
                }
            }.onFailure { t ->
                onState?.let {
                    wasidremin.gmccpa.upload.UploadCenter.emitState(
                        it, wasidremin.gmccpa.upload.UploadPolicy.classifyFailure(t, allowQueue = false))
                }
            }
            main.post { callback(res) }
        }
    }

    // ---- internals --------------------------------------------------------------------------

    private fun stableOrThrow(
        surface: android.view.SurfaceView,
        timeoutMs: Long,
    ): CarPlayFrameGrabber.CaptureResult.Success {
        return when (val res = CarPlayFrameGrabber.captureStableFrameBlocking(
            timeoutMs = timeoutMs, surfaceView = surface)) {
            is CarPlayFrameGrabber.CaptureResult.Success -> res
            is CarPlayFrameGrabber.CaptureResult.Failure ->
                throw IllegalStateException("${res.reason}: ${res.detail}")
        }
    }

    /**
     * Content-rect working image: same 12%..95% x / 12%..88% y rect the classifier and matcher
     * agree on, center-sampled down to [WORK_W]x[WORK_H] grayscale. Sampling (not averaging) is
     * deliberate — it matches `downscaleGrayscale64x24`'s contract, so a template captured from
     * one frame compares against the same pixels it was taken from.
     */
    internal fun workingImage(bitmap: Bitmap): FloatArray {
        val w = bitmap.width
        val h = bitmap.height
        require(w > 0 && h > 0) { "frame has no pixels" }
        val x0 = (w * 0.12f).toInt().coerceIn(0, w - 1)
        val x1 = (w * 0.95f).toInt().coerceIn(x0 + 1, w)
        val y0 = (h * 0.12f).toInt().coerceIn(0, h - 1)
        val y1 = (h * 0.88f).toInt().coerceIn(y0 + 1, h)
        val out = FloatArray(WORK_W * WORK_H)
        var idx = 0
        for (ty in 0 until WORK_H) {
            val y = (y0 + ((ty + 0.5f) * (y1 - y0) / WORK_H).toInt()).coerceIn(0, h - 1)
            for (tx in 0 until WORK_W) {
                val x = (x0 + ((tx + 0.5f) * (x1 - x0) / WORK_W).toInt()).coerceIn(0, w - 1)
                val px = bitmap.getPixel(x, y)
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                out[idx++] = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f
            }
        }
        return out
    }

    internal fun saveRecord(ctx: Context, record: LaunchRecord) {
        val root = launchesRootDir(ctx)
        root.mkdirs()
        File(root, "${record.id}.json").writeText(record.toJson().toString(2), Charsets.UTF_8)
        log.i("launch ${record.id} -> ${record.outcome}" +
            (record.error?.let { ": $it" } ?: ""))
    }

    private fun uploadLaunchSync(
        baseUrl: String,
        record: LaunchRecord,
        onState: ((wasidremin.gmccpa.upload.UploadState) -> Unit)?,
    ): String {
        val normalized = baseUrl.trim().trimEnd('/')
        require(normalized.startsWith("https://", ignoreCase = true)) {
            "launch upload requires an HTTPS URL"
        }
        val payload = record.toJson().toString(2).toByteArray(Charsets.UTF_8)
        val total = payload.size.toLong()
        val endpoint = "$normalized/api/launches"
        return wasidremin.gmccpa.upload.UploadPolicy.executeWithOneRetry(
            onRetry = { first ->
                ProbeLog.sub("launch").w(
                    "transient launch upload failure (${first.javaClass.simpleName}) — retrying once")
            },
        ) {
            onState?.let {
                wasidremin.gmccpa.upload.UploadCenter.emitState(
                    it, wasidremin.gmccpa.upload.UploadState.InProgress(0L, total))
            }
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = wasidremin.gmccpa.upload.UploadCenter.CONNECT_TIMEOUT_MS
                readTimeout = wasidremin.gmccpa.upload.UploadCenter.WRITE_READ_TIMEOUT_MS
                doOutput = true
                setFixedLengthStreamingMode(total)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            try {
                wasidremin.gmccpa.upload.CountingRequestOutputStream(
                    delegate = conn.outputStream,
                    bytesTotal = total,
                ) { sent, tot ->
                    onState?.let {
                        wasidremin.gmccpa.upload.UploadCenter.emitState(
                            it, wasidremin.gmccpa.upload.UploadState.InProgress(sent, tot))
                    }
                }.use { out ->
                    out.write(payload)
                    out.flush()
                }
                onState?.let {
                    wasidremin.gmccpa.upload.UploadCenter.emitState(
                        it, wasidremin.gmccpa.upload.UploadState.Finalizing)
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw wasidremin.gmccpa.upload.UploadPolicy.HttpStatusException(code, "HTTP $code")
                }
                conn.inputStream.close()
                log.i("uploaded launch ${record.id} ($total B) to $endpoint")
                record.id
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun newLaunchId(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    private fun isoNow(nowMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(nowMs))
}
