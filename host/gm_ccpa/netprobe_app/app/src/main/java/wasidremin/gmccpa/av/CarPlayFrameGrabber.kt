package wasidremin.gmccpa.av

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.PixelCopy
import android.view.SurfaceView
import org.json.JSONObject
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.SessionHolder
import wasidremin.gmccpa.VideoFrame
import wasidremin.gmccpa.logging.LogExport
import wasidremin.gmccpa.ocbm.AdapterSession
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Single-responsibility frame capture for the CarPlay [SurfaceView].
 *
 * Uses [PixelCopy.request] on a dedicated [HandlerThread] (`cp-grabber`) and reuses a single
 * `ARGB_8888` [Bitmap] across calls so no per-capture bitmap allocation occurs during stability
 * polling or launcher matching.
 */
object CarPlayFrameGrabber {
    private val log = ProbeLog.sub("grab")

    private val pixelCopyThread by lazy {
        HandlerThread("cp-grabber").apply {
            isDaemon = true
            start()
        }
    }
    private val pixelCopyHandler by lazy { Handler(pixelCopyThread.looper) }
    private val workerPool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cp-capture-worker").apply { isDaemon = true }
    }

    private val bitmapLock = Any()
    private var reusableBitmap: Bitmap? = null

    const val THUMB_W = 64
    const val THUMB_H = 24
    const val DEFAULT_SETTLE_THRESHOLD = 2.0f / 255.0f
    const val DEFAULT_POLL_INTERVAL_MS = 60L
    const val DEFAULT_STABLE_TIMEOUT_MS = 1500L
    const val MAX_STORED_CAPTURES = 50

    sealed interface CaptureResult {
        data class Success(
            val bitmap: Bitmap,
            val width: Int,
            val height: Int,
            val timestampMs: Long,
            val elapsedMs: Long = 0L,
            val framesSampled: Int = 1,
            val lastMad: Float = 0f,
        ) : CaptureResult

        data class Failure(
            val reason: FailureReason,
            val detail: String,
            val elapsedMs: Long = 0L,
        ) : CaptureResult
    }

    enum class FailureReason {
        SESSION_DOWN,
        SURFACE_NOT_READY,
        PIXEL_COPY_FAILED,
        TIMEOUT_UNSTABLE,
    }

    enum class ScreenClassification {
        HOME_GRID,
        DASHBOARD,
        UNKNOWN,
    }

    enum class CaptureButtonKind(val wireName: String, val label: String) {
        CAPTURE_HOME("capture_home", "Capture CarPlay Home Screen"),
        CAPTURE_CURRENT("capture_current", "Capture Current Screen"),
        CAPTURE_FULL_WINDOW("capture_full_window", "Capture Full Screen"),
    }

    data class SavedCapture(
        val id: String,
        val dir: File,
        val pngFile: File,
        val jsonFile: File,
        val metadata: CaptureMetadata,
    )

    data class CaptureMetadata(
        val captureId: String,
        val timestampIso: String,
        val appVersion: String,
        val gitSha: String,
        val iosVersion: String?,
        val negotiatedWidth: Int,
        val negotiatedHeight: Int,
        val displayDensity: Float,
        val displayDensityDpi: Int,
        val buttonProduced: String,
        val classification: ScreenClassification,
        val homePressesUsed: Int,
        val msToStableFrame: Long,
        val framesSampled: Int,
        val settleMad: Float,
        /** Whole-window pixel size for `capture_full_window`; 0 for video-only captures. */
        val windowWidth: Int = 0,
        val windowHeight: Int = 0,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("captureId", captureId)
            put("timestamp", timestampIso)
            put("appVersion", appVersion)
            put("gitSha", gitSha)
            put("iosVersion", iosVersion ?: JSONObject.NULL)
            put("negotiatedWidth", negotiatedWidth)
            put("negotiatedHeight", negotiatedHeight)
            put("displayDensity", displayDensity.toDouble())
            put("displayDensityDpi", displayDensityDpi)
            put("button", buttonProduced)
            put("classification", classification.name)
            put("homePressesUsed", homePressesUsed)
            put("msFromPressToStableFrame", msToStableFrame)
            put("framesSampled", framesSampled)
            put("settleMad", settleMad.toDouble())
            put("windowWidth", windowWidth)
            put("windowHeight", windowHeight)
        }

        companion object {
            fun fromJson(obj: JSONObject): CaptureMetadata = CaptureMetadata(
                captureId = obj.optString("captureId", ""),
                timestampIso = obj.optString("timestamp", ""),
                appVersion = obj.optString("appVersion", ""),
                gitSha = obj.optString("gitSha", ""),
                iosVersion = if (obj.isNull("iosVersion")) null else obj.optString("iosVersion").takeIf { it.isNotEmpty() },
                negotiatedWidth = obj.optInt("negotiatedWidth", 0),
                negotiatedHeight = obj.optInt("negotiatedHeight", 0),
                displayDensity = obj.optDouble("displayDensity", 1.0).toFloat(),
                displayDensityDpi = obj.optInt("displayDensityDpi", 160),
                buttonProduced = obj.optString("button", ""),
                classification = runCatching {
                    ScreenClassification.valueOf(obj.optString("classification", "UNKNOWN"))
                }.getOrDefault(ScreenClassification.UNKNOWN),
                homePressesUsed = obj.optInt("homePressesUsed", 0),
                msToStableFrame = obj.optLong("msFromPressToStableFrame", 0L),
                framesSampled = obj.optInt("framesSampled", 1),
                settleMad = obj.optDouble("settleMad", 0.0).toFloat(),
                windowWidth = obj.optInt("windowWidth", 0),
                windowHeight = obj.optInt("windowHeight", 0),
            )
        }
    }

    /** True when either the adapter-Wi-Fi session or the legacy receiver session is active. */
    fun isSessionActive(): Boolean =
        AdapterSession.sessionUp || SessionHolder.cpRx?.sessionLive == true

    private fun obtainReusableBitmap(width: Int, height: Int): Bitmap {
        synchronized(bitmapLock) {
            val existing = reusableBitmap
            if (existing != null && !existing.isRecycled &&
                existing.width == width && existing.height == height &&
                existing.config == Bitmap.Config.ARGB_8888
            ) {
                return existing
            }
            existing?.recycle()
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                reusableBitmap = it
            }
        }
    }

    /**
     * Single-frame capture off the main thread using [PixelCopy.request] onto a reusable [Bitmap].
     * Callable from coroutines (`suspend fun capture()`) or via [captureBlocking] on background threads.
     */
    suspend fun capture(surfaceView: SurfaceView? = CarPlayActivity.liveSurfaceView()): CaptureResult =
        captureBlocking(surfaceView)

    /**
     * When [owned] is true the returned bitmap is a private copy made while [bitmapLock]
     * still protects the reusable buffer — the caller owns it and must recycle it.
     * Otherwise the bitmap is the borrowed reusable buffer: do not retain or recycle it.
     */
    fun captureBlocking(
        surfaceView: SurfaceView? = CarPlayActivity.liveSurfaceView(),
        owned: Boolean = false,
    ): CaptureResult {
        val startedAt = SystemClock.elapsedRealtime()
        if (!isSessionActive()) {
            return CaptureResult.Failure(
                FailureReason.SESSION_DOWN,
                "No active CarPlay session",
                SystemClock.elapsedRealtime() - startedAt,
            )
        }
        val view = surfaceView ?: return CaptureResult.Failure(
            FailureReason.SURFACE_NOT_READY,
            "CarPlay SurfaceView is not attached",
            SystemClock.elapsedRealtime() - startedAt,
        )
        val holder = view.holder
        val surface = holder?.surface
        if (surface == null || !surface.isValid || view.width <= 0 || view.height <= 0) {
            return CaptureResult.Failure(
                FailureReason.SURFACE_NOT_READY,
                "CarPlay Surface is not valid or has 0 size",
                SystemClock.elapsedRealtime() - startedAt,
            )
        }

        val targetW = (VideoPresentation.width.takeIf { it > 0 } ?: VideoFrame.width).coerceAtLeast(1)
        val targetH = (VideoPresentation.height.takeIf { it > 0 } ?: VideoFrame.height).coerceAtLeast(1)

        synchronized(bitmapLock) {
            val bmp = obtainReusableBitmap(targetW, targetH)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return CaptureResult.Failure(
                    FailureReason.PIXEL_COPY_FAILED,
                    "PixelCopy requires API 24+",
                    SystemClock.elapsedRealtime() - startedAt,
                )
            }
            val latch = CountDownLatch(1)
            val copyResult = AtomicInteger(PixelCopy.ERROR_UNKNOWN)
            try {
                PixelCopy.request(view, bmp, { code ->
                    copyResult.set(code)
                    latch.countDown()
                }, pixelCopyHandler)
            } catch (t: Throwable) {
                return CaptureResult.Failure(
                    FailureReason.PIXEL_COPY_FAILED,
                    "PixelCopy threw ${t.javaClass.simpleName}: ${t.message}",
                    SystemClock.elapsedRealtime() - startedAt,
                )
            }

            val completed = try {
                latch.await(1000L, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (!completed) {
                return CaptureResult.Failure(
                    FailureReason.PIXEL_COPY_FAILED,
                    "PixelCopy timed out after ${elapsed}ms",
                    elapsed,
                )
            }
            val status = copyResult.get()
            if (status != PixelCopy.SUCCESS) {
                return CaptureResult.Failure(
                    FailureReason.PIXEL_COPY_FAILED,
                    "PixelCopy failed with status=$status",
                    elapsed,
                )
            }
            val out = if (!owned) bmp else runCatching {
                bmp.copy(Bitmap.Config.ARGB_8888, false)
            }.getOrNull() ?: return CaptureResult.Failure(
                FailureReason.PIXEL_COPY_FAILED,
                "Owned snapshot copy failed",
                elapsed,
            )
            return CaptureResult.Success(
                bitmap = out,
                width = out.width,
                height = out.height,
                timestampMs = System.currentTimeMillis(),
                elapsedMs = elapsed,
                framesSampled = 1,
                lastMad = 0f,
            )
        }
    }

    /**
     * Capture repeatedly ~60 ms apart, downscale each frame to a 64x24 grayscale thumbnail,
     * and return the first frame whose mean absolute difference (normalized 0..1) from the
     * previous thumbnail is below [threshold] (default `2.0 / 255`).
     */
    suspend fun captureStableFrame(
        timeoutMs: Long = DEFAULT_STABLE_TIMEOUT_MS,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        threshold: Float = DEFAULT_SETTLE_THRESHOLD,
        surfaceView: SurfaceView? = CarPlayActivity.liveSurfaceView(),
    ): CaptureResult = captureStableFrameBlocking(timeoutMs, pollIntervalMs, threshold, surfaceView)

    /**
     * Stable-frame capture. When [owned] is true every sample is copied in-lock at capture
     * time and non-settled copies are recycled, so the returned settled frame is an exact
     * owned snapshot — never a buffer another capture overwrote after the fact.
     */
    fun captureStableFrameBlocking(
        timeoutMs: Long = DEFAULT_STABLE_TIMEOUT_MS,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        threshold: Float = DEFAULT_SETTLE_THRESHOLD,
        surfaceView: SurfaceView? = CarPlayActivity.liveSurfaceView(),
        owned: Boolean = false,
    ): CaptureResult {
        val startMs = SystemClock.elapsedRealtime()
        val deadline = startMs + timeoutMs
        var prevThumb: FloatArray? = null
        val curThumb = FloatArray(THUMB_W * THUMB_H)
        var samples = 0
        var lastMad = 1f

        while (SystemClock.elapsedRealtime() <= deadline) {
            val frameStart = SystemClock.elapsedRealtime()
            when (val res = captureBlocking(surfaceView, owned)) {
                is CaptureResult.Failure -> return res.copy(elapsedMs = SystemClock.elapsedRealtime() - startMs)
                is CaptureResult.Success -> {
                    samples++
                    synchronized(bitmapLock) {
                        downscaleGrayscale64x24(res.bitmap, curThumb)
                    }
                    val prev = prevThumb
                    if (prev != null) {
                        val mad = meanAbsoluteDifference(prev, curThumb)
                        lastMad = mad
                        if (mad < threshold) {
                            val totalElapsed = SystemClock.elapsedRealtime() - startMs
                            log.i("stable frame settled in ${totalElapsed}ms after $samples samples (mad=%.4f < %.4f)"
                                .format(Locale.US, mad, threshold))
                            return res.copy(
                                elapsedMs = totalElapsed,
                                framesSampled = samples,
                                lastMad = mad,
                            )
                        }
                        System.arraycopy(curThumb, 0, prev, 0, curThumb.size)
                    } else {
                        prevThumb = curThumb.copyOf()
                    }
                    if (owned) res.bitmap.recycle()
                }
            }
            val frameCost = SystemClock.elapsedRealtime() - frameStart
            val sleepMs = (pollIntervalMs - frameCost).coerceAtLeast(10L)
            if (SystemClock.elapsedRealtime() + sleepMs > deadline) break
            try {
                Thread.sleep(sleepMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return CaptureResult.Failure(
                    FailureReason.TIMEOUT_UNSTABLE,
                    "Interrupted while waiting for stable frame",
                    SystemClock.elapsedRealtime() - startMs,
                )
            }
        }
        val elapsed = SystemClock.elapsedRealtime() - startMs
        log.w("captureStableFrame timed out after ${elapsed}ms ($samples samples, lastMad=%.4f)"
            .format(Locale.US, lastMad))
        return CaptureResult.Failure(
            FailureReason.TIMEOUT_UNSTABLE,
            "Frame did not settle within ${timeoutMs}ms (samples=$samples, lastMad=%.4f)".format(Locale.US, lastMad),
            elapsed,
        )
    }

    /**
     * Downscale [bitmap] into [out] (length 64 * 24 = 1536) with normalized grayscale values in 0..1.
     */
    fun downscaleGrayscale64x24(bitmap: Bitmap, out: FloatArray) {
        require(out.size >= THUMB_W * THUMB_H)
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) {
            out.fill(0f)
            return
        }
        var idx = 0
        for (ty in 0 until THUMB_H) {
            val y = ((ty + 0.5f) * h / THUMB_H).toInt().coerceIn(0, h - 1)
            for (tx in 0 until THUMB_W) {
                val x = ((tx + 0.5f) * w / THUMB_W).toInt().coerceIn(0, w - 1)
                val px = bitmap.getPixel(x, y)
                val r = (px ushr 16) and 0xFF
                val g = (px ushr 8) and 0xFF
                val b = px and 0xFF
                // ITU-R BT.601 luma normalized to 0..1
                out[idx++] = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f
            }
        }
    }

    fun meanAbsoluteDifference(a: FloatArray, b: FloatArray): Float {
        val n = min(a.size, b.size)
        if (n == 0) return 0f
        var sum = 0f
        for (i in 0 until n) {
            sum += abs(a[i] - b[i])
        }
        return sum / n
    }

    /**
     * Crude heuristic classifier for HOME_GRID vs DASHBOARD vs UNKNOWN:
     * Divides the non-strip content area (x in 12%..95%, y in 12%..88%) into an 8x2 grid of cells
     * and checks how many cells look like distinct high-saturation icon tiles surrounded by darker/uniform
     * background vs large multi-cell cards (Dashboard).
     */
    fun classifyScreen(bitmap: Bitmap): ScreenClassification {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 160 || h < 120) return ScreenClassification.UNKNOWN

        val cols = 8
        val rows = 2
        val x0 = (w * 0.12f).toInt()
        val x1 = (w * 0.95f).toInt()
        val y0 = (h * 0.12f).toInt()
        val y1 = (h * 0.88f).toInt()
        val cellW = ((x1 - x0) / cols).coerceAtLeast(1)
        val cellH = ((y1 - y0) / rows).coerceAtLeast(1)

        var saturatedCells = 0
        var cardLikeCells = 0
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cx0 = x0 + c * cellW
                val cy0 = y0 + r * cellH
                var satCount = 0
                var brightCount = 0
                var samples = 0
                val stepY = (cellH / 8).coerceAtLeast(1)
                val stepX = (cellW / 8).coerceAtLeast(1)
                var y = cy0 + stepY / 2
                while (y < cy0 + cellH && y < h) {
                    var x = cx0 + stepX / 2
                    while (x < cx0 + cellW && x < w) {
                        val px = bitmap.getPixel(x, y)
                        val red = (px ushr 16) and 0xFF
                        val grn = (px ushr 8) and 0xFF
                        val blu = px and 0xFF
                        val maxC = max(red, max(grn, blu))
                        val minC = min(red, min(grn, blu))
                        val sat = if (maxC == 0) 0f else (maxC - minC).toFloat() / maxC.toFloat()
                        val v = maxC / 255f
                        if (sat > 0.45f && v > 0.35f) satCount++
                        if (v > 0.22f) brightCount++
                        samples++
                        x += stepX
                    }
                    y += stepY
                }
                if (samples > 0) {
                    val satRatio = satCount.toFloat() / samples
                    val brightRatio = brightCount.toFloat() / samples
                    // Icon cell: concentrated high-saturation square-ish region (not filling the entire cell)
                    if (satRatio in 0.10f..0.65f) saturatedCells++
                    // Dashboard card cell: broad filled region across the cell
                    if (brightRatio > 0.65f && satRatio < 0.35f) cardLikeCells++
                }
            }
        }
        return when {
            saturatedCells >= 5 -> ScreenClassification.HOME_GRID
            cardLikeCells >= 6 && saturatedCells <= 3 -> ScreenClassification.DASHBOARD
            else -> ScreenClassification.UNKNOWN
        }
    }

    // ---- Calibration workflows & Local-first storage --------------------------------------------

    fun capturesRootDir(ctx: Context): File = File(ctx.applicationContext.filesDir, "captures")

    /**
     * Save PNG + JSON sidecar to `filesDir/captures/<timestamp>/`, cap directory at 50 captures
     * (deleting oldest first).
     */
    fun saveCaptureLocally(
        ctx: Context,
        bitmap: Bitmap,
        buttonKind: CaptureButtonKind,
        classification: ScreenClassification,
        homePressesUsed: Int,
        msToStableFrame: Long,
        framesSampled: Int,
        settleMad: Float,
        windowWidth: Int = 0,
        windowHeight: Int = 0,
    ): SavedCapture {
        val appCtx = ctx.applicationContext
        val now = Date()
        val idFmt = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val baseId = idFmt.format(now)
        val root = capturesRootDir(appCtx)
        root.mkdirs()

        var dir = File(root, baseId)
        var suffix = 1
        while (dir.exists()) {
            dir = File(root, "${baseId}_$suffix")
            suffix++
        }
        dir.mkdirs()
        val captureId = dir.name

        val pngFile = File(dir, "capture.png")
        val jsonFile = File(dir, "metadata.json")

        synchronized(bitmapLock) {
            FileOutputStream(pngFile).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw IOException("Bitmap.compress(PNG) returned false")
                }
            }
        }

        val metadata = CaptureMetadata(
            captureId = captureId,
            timestampIso = isoFmt.format(now),
            appVersion = resolveAppVersion(appCtx),
            gitSha = resolveGitSha(appCtx),
            iosVersion = resolveIosVersion(),
            negotiatedWidth = VideoPresentation.width.takeIf { it > 0 } ?: VideoFrame.width,
            negotiatedHeight = VideoPresentation.height.takeIf { it > 0 } ?: VideoFrame.height,
            displayDensity = appCtx.resources.displayMetrics.density,
            displayDensityDpi = appCtx.resources.displayMetrics.densityDpi,
            buttonProduced = buttonKind.wireName,
            classification = classification,
            homePressesUsed = homePressesUsed,
            msToStableFrame = msToStableFrame,
            framesSampled = framesSampled,
            settleMad = settleMad,
            windowWidth = windowWidth,
            windowHeight = windowHeight,
        )
        jsonFile.writeText(metadata.toJson().toString(2), Charsets.UTF_8)
        enforceRetentionCap(root, MAX_STORED_CAPTURES)
        return SavedCapture(captureId, dir, pngFile, jsonFile, metadata)
    }

    /** Enforce the 50-capture cap in `filesDir/captures/`, deleting oldest directories first. */
    fun enforceRetentionCap(root: File, maxCaptures: Int = MAX_STORED_CAPTURES) {
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: return
        val excess = dirs.size - maxCaptures
        if (excess <= 0) return
        for (i in 0 until excess) {
            val old = dirs[i]
            old.deleteRecursively()
            runCatching { log.i("pruned oldest capture ${old.name} (cap=$maxCaptures)") }
        }
    }

    fun listSavedCaptures(ctx: Context): List<SavedCapture> {
        val root = capturesRootDir(ctx)
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val png = File(dir, "capture.png")
            val json = File(dir, "metadata.json")
            if (!png.isFile || !json.isFile) return@mapNotNull null
            val meta = runCatching {
                CaptureMetadata.fromJson(JSONObject(json.readText(Charsets.UTF_8)))
            }.getOrNull() ?: return@mapNotNull null
            SavedCapture(dir.name, dir, png, json, meta)
        }
    }

    /**
     * Execute the calibration capture workflow off the UI thread:
     * - For [CaptureButtonKind.CAPTURE_HOME]:
     *   1. Verify active CarPlay session
     *   2. Send AC Home (`Ocbm.NAV_HOME`)
     *   3. `captureStableFrame()`
     *   4. Classify result (`HOME_GRID`, `DASHBOARD`, `UNKNOWN`)
     *   5. If `DASHBOARD`, send AC Home once more and re-capture (`homePressesUsed = 2`)
     *   6. Save PNG + JSON sidecar locally and enqueue upload
     * - For [CaptureButtonKind.CAPTURE_CURRENT]:
     *   1. Verify active CarPlay session
     *   2. `captureStableFrame()` immediately
     *   3. Save PNG + JSON sidecar locally and enqueue upload
     * - For [CaptureButtonKind.CAPTURE_FULL_WINDOW]:
     *   1. Verify active CarPlay session
     *   2. `captureStableFrame()` immediately (owned copy, also used for classification)
     *   3. Composite the settled frame into a whole-window screenshot via
     *      `CarPlayActivity.captureWindowComposite` (video frame alone if the screen is gone)
     *   4. Save PNG + JSON sidecar locally and enqueue upload
     */
    fun runCalibrationCaptureAsync(
        ctx: Context,
        buttonKind: CaptureButtonKind,
        onUploadState: ((wasidremin.gmccpa.upload.UploadState) -> Unit)? = null,
        onResult: (Result<SavedCapture>) -> Unit,
    ) {
        val appCtx = ctx.applicationContext
        val main = Handler(android.os.Looper.getMainLooper())
        if (!isSessionActive()) {
            onUploadState?.let {
                wasidremin.gmccpa.upload.UploadCenter.emitState(
                    it,
                    wasidremin.gmccpa.upload.UploadState.Failed("No CarPlay session", retryable = true),
                )
            }
            main.post {
                onResult(Result.failure(IllegalStateException("No active CarPlay session")))
            }
            return
        }
        onUploadState?.let {
            wasidremin.gmccpa.upload.UploadCenter.emitState(it, wasidremin.gmccpa.upload.UploadState.Preparing)
        }
        workerPool.execute {
            val outcome = runCatching {
                runCalibrationCaptureBlocking(appCtx, buttonKind, onUploadState)
            }
            outcome.onFailure { t ->
                onUploadState?.let {
                    wasidremin.gmccpa.upload.UploadCenter.emitState(
                        it,
                        wasidremin.gmccpa.upload.UploadState.Failed(
                            t.message ?: t.javaClass.simpleName,
                            retryable = true,
                        ),
                    )
                }
            }
            main.post { onResult(outcome) }
        }
    }

    fun runCalibrationCaptureBlocking(
        ctx: Context,
        buttonKind: CaptureButtonKind,
        onUploadState: ((wasidremin.gmccpa.upload.UploadState) -> Unit)? = null,
    ): SavedCapture {
        val pressStartMs = SystemClock.elapsedRealtime()
        if (!isSessionActive()) {
            throw IllegalStateException("No active CarPlay session")
        }

        // Ensure CarPlayActivity has a live SurfaceView in foreground if the user tapped from Settings.
        val surfaceView = awaitLiveSurfaceView(ctx, timeoutMs = 2500L)
            ?: throw IllegalStateException("CarPlay video surface is not ready — return to CarPlay screen first")

        var homePresses = 0
        val finalCapture: CaptureResult.Success
        val classification: ScreenClassification

        if (buttonKind == CaptureButtonKind.CAPTURE_HOME) {
            sendAcHome()
            homePresses = 1
            // Brief initial delay so iOS starts transitioning before first stability pair
            try { Thread.sleep(80L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            val first = when (val r1 = captureStableFrameBlocking(surfaceView = surfaceView)) {
                is CaptureResult.Success -> r1
                is CaptureResult.Failure -> throw IllegalStateException("${r1.reason}: ${r1.detail}")
            }
            val firstClass = synchronized(bitmapLock) { classifyScreen(first.bitmap) }
            if (firstClass == ScreenClassification.DASHBOARD) {
                log.i("capture_home: first settled frame classified DASHBOARD — sending second AC Home")
                sendAcHome()
                homePresses = 2
                try { Thread.sleep(80L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                finalCapture = when (val r2 = captureStableFrameBlocking(surfaceView = surfaceView)) {
                    is CaptureResult.Success -> r2
                    is CaptureResult.Failure -> throw IllegalStateException("${r2.reason}: ${r2.detail}")
                }
                classification = synchronized(bitmapLock) { classifyScreen(finalCapture.bitmap) }
            } else {
                finalCapture = first
                classification = firstClass
            }
        } else {
            // CAPTURE_CURRENT borrows the reusable buffer; CAPTURE_FULL_WINDOW owns its copy
            // because the composite below reads it after the stability loop releases the lock.
            val owned = buttonKind == CaptureButtonKind.CAPTURE_FULL_WINDOW
            finalCapture = when (val r = captureStableFrameBlocking(surfaceView = surfaceView, owned = owned)) {
                is CaptureResult.Success -> r
                is CaptureResult.Failure -> throw IllegalStateException("${r.reason}: ${r.detail}")
            }
            classification = synchronized(bitmapLock) { classifyScreen(finalCapture.bitmap) }
        }

        val totalMs = SystemClock.elapsedRealtime() - pressStartMs
        // Full-window: hierarchy (top bar, rail) + the settled video frame at the surface rect.
        // Falls back to the video frame alone when the activity is gone.
        var windowW = 0
        var windowH = 0
        var saveBitmap = finalCapture.bitmap
        var composite: Bitmap? = null
        if (buttonKind == CaptureButtonKind.CAPTURE_FULL_WINDOW) {
            val act = CarPlayActivity.liveActivity()
            if (act != null) {
                composite = act.captureWindowComposite(finalCapture.bitmap)
                if (composite != null) {
                    windowW = composite.width
                    windowH = composite.height
                    saveBitmap = composite
                } else {
                    log.w("full-window: activity has no laid-out window — saving the video frame only")
                }
            } else {
                log.w("full-window: CarPlay screen is gone — saving the video frame only")
            }
        }
        try {
            val saved = saveCaptureLocally(
                ctx = ctx,
                bitmap = saveBitmap,
                buttonKind = buttonKind,
                classification = classification,
                homePressesUsed = homePresses,
                msToStableFrame = totalMs,
                framesSampled = finalCapture.framesSampled,
                settleMad = finalCapture.lastMad,
                windowWidth = windowW,
                windowHeight = windowH,
            )
            enqueueUpload(ctx, saved, onUploadState = onUploadState)
            return saved
        } finally {
            composite?.recycle()
            // An owned stable frame is a private copy: recycle it now that the PNG is written.
            // The borrowed reusable buffer must never be recycled.
            if (buttonKind == CaptureButtonKind.CAPTURE_FULL_WINDOW) finalCapture.bitmap.recycle()
        }
    }

    /** Send the existing AC Home HID event (`Ocbm.NAV_HOME`). */
    fun sendAcHome(): Boolean {
        val act = CarPlayActivity.liveActivity()
        if (act != null) {
            act.sendAcHomeFromTooling()
            return true
        }
        val sent = AdapterSession.sendNav(wasidremin.gmccpa.ocbm.Ocbm.NAV_HOME)
        log.i("sendAcHome (adapter direct) sent=$sent")
        return sent
    }

    /**
     * If `CarPlayActivity`'s surface is already valid, return it immediately.
     * If the user pressed the button from `MainActivity` while a session is live, bring
     * `CarPlayActivity` to the front and wait up to [timeoutMs] for its Surface + first decoded
     * frame to settle.
     */
    internal fun awaitLiveSurfaceView(ctx: Context, timeoutMs: Long): SurfaceView? {
        CarPlayActivity.liveSurfaceView()?.let { return it }
        if (!isSessionActive()) return null
        val launch = android.content.Intent(ctx, CarPlayActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(launch) }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val initialFrames = VideoPresentation.frames.get()
        while (SystemClock.elapsedRealtime() < deadline) {
            val sv = CarPlayActivity.liveSurfaceView()
            if (sv != null && (VideoPresentation.frames.get() > initialFrames ||
                    SystemClock.elapsedRealtime() + 300L >= deadline)
            ) {
                return sv
            }
            try { Thread.sleep(50L) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return CarPlayActivity.liveSurfaceView()
    }

    /**
     * Enqueue upload of [saved] to `{baseUrl}/api/captures` via [wasidremin.gmccpa.upload.UploadCenter].
     * Local files are already safely written in `filesDir/captures/<id>/`; if the server is
     * unreachable, `.pending_upload` stays in the capture directory and transitions to `Queued`.
     */
    fun enqueueUpload(
        ctx: Context,
        saved: SavedCapture,
        baseUrl: String = wasidremin.gmccpa.upload.UploadCenter.baseUrl(ctx),
        onUploadState: ((wasidremin.gmccpa.upload.UploadState) -> Unit)? = null,
    ) {
        wasidremin.gmccpa.upload.UploadCenter.uploadCapture(ctx, saved) { st ->
            onUploadState?.invoke(st)
        }
    }

    fun resolveAppVersion(ctx: Context): String = runCatching {
        @Suppress("DEPRECATION")
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    fun resolveGitSha(ctx: Context): String {
        val assetSha = runCatching {
            ctx.assets.open("build_sha").use { it.readBytes().toString(Charsets.UTF_8).trim() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (assetSha != null) return assetSha
        val ver = resolveAppVersion(ctx)
        val plus = ver.indexOf('+')
        return if (plus >= 0 && plus + 1 < ver.length) ver.substring(plus + 1) else "unknown"
    }

    fun resolveIosVersion(): String? {
        val json = SessionHolder.ocbmProbe?.client?.lastPhoneIdent?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val obj = JSONObject(json)
            val osName = obj.optString("osName", "").trim()
            val osVer = obj.optString("osVersion", "").trim()
            when {
                osName.isNotEmpty() && osVer.isNotEmpty() -> "$osName $osVer"
                osVer.isNotEmpty() -> osVer
                else -> null
            }
        }.getOrNull()
    }
}
