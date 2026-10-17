package wasidremin.gmccpa.av

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceView
import org.json.JSONObject
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.VideoFrame
import wasidremin.gmccpa.match.IconMatcher
import wasidremin.gmccpa.match.IconTemplateStore
import wasidremin.gmccpa.match.ScreenModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

/**
 * Strip-first, grid-fallback, vision-verified CarPlay app launcher.
 *
 * 1. Stable frame. Search the recent-apps strip for strip.<role>; on a match, tap it (no Home).
 * 2. Else search the grid on the current frame for grid.<role>.
 * 3. Else tap the strip's Home/Dashboard toggle (max [MAX_TOGGLES]); if the glyph says Dashboard,
 *    toggle again; otherwise search the grid.
 * 4. No confident match -> NOT_FOUND. The only blind tap ever sent is the toggle.
 *
 * AC Home is NOT used: the box drops it while dPadSupport=false
 * ("nav 0x06 dropped — D-Pad not advertised", 2026-10-15 log).
 * After every tap we wait for the screen to CHANGE before waiting for it to settle, otherwise the
 * stable-frame gate can return the pre-tap frame before the iPhone has reacted.
 */
object VisionLauncher {
    private val log = ProbeLog.sub("vlaunch")
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "cp-vlaunch").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    const val DEBOUNCE_MS = 1500L
    const val GLOBAL_TIMEOUT_MS = 4000L
    const val MAX_TOGGLES = 2
    const val CHANGE_MAD = 0.015f
    const val CHANGE_WAIT_MS = 1200L
    const val POLL_MS = 60L
    const val SURFACE_WAIT_MS = 2500L
    const val MIN_AFTER_TAP_MS = 600L
    const val QUIET_MS = 400L
    const val SETTLE_CAP_MS = 2500L
    const val VERIFY_GRACE_MS = 1500L

    @Volatile private var lastStartMs = 0L
    @Volatile var busy = false
        private set

    /**
     * Explicit diagnostic mode: temporarily saves before/after CarPlay images per attempt.
     * Session-only by construction — plain memory, never persisted, back to OFF on restart.
     * Text/JSON launch records are written regardless of this switch.
     */
    @Volatile var saveScreenshots: Boolean = false

    const val VERIFICATION_BASIS = "transition_and_surface_only"

    /** Reused across frames on the single worker thread; avoids ~10 MB of garbage per frame. */
    private var argbBuf: IntArray? = null

    private class Abort(msg: String) : Exception(msg)

    private class Run(val ctx: Context, val role: String, val source: String) {
        val id = "v" + stamp("yyyyMMdd-HHmmss-SSS")
        val startedIso = stamp("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        val t0 = SystemClock.elapsedRealtime()
        val steps = mutableListOf<CarPlayLauncher.LaunchStep>()
        var tapNx = 0f
        var tapNy = 0f
        var used: String? = null
        /** Monotonic elapsed-ms marks (SystemClock vs t0 — durations, never wall-clock math). */
        val marks = mutableMapOf<String, Long>()
        var touchSent: Boolean? = null
        var matchName: String? = null
        var matchScore: Float? = null
        var matchNx = 0f
        var matchNy = 0f
        var stripSource = "missing"
        var gridSource = "missing"
        var glyphDashSource = "missing"
        var glyphOtherSource = "missing"
        var verifyDashScore: Float? = null
        var verifyOtherScore: Float? = null
        /** At most these two owned images per attempt; handed to LaunchEvidence or recycled. */
        var beforeOwned: Bitmap? = null
        var verifyOwned: Bitmap? = null
        var evidenceError: String? = null

        fun elapsed(): Long = SystemClock.elapsedRealtime() - t0

        fun mark(name: String) {
            marks[name] = elapsed()
        }

        fun noteEvidenceError(detail: String) {
            evidenceError = if (evidenceError == null) detail else "$evidenceError; $detail"
        }

        fun step(name: String, detail: String, score: Float? = null, passed: Boolean? = null) {
            val line = "+${elapsed()}ms $detail"
            steps += CarPlayLauncher.LaunchStep(stamp("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"), name, line, score, passed)
            log.i("$id [$name] $line" + (score?.let { " score=%.3f".format(Locale.US, it) } ?: ""))
        }
    }

    private fun stamp(pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    fun isCalibrated(ctx: Context, role: String): Boolean =
        IconTemplateStore.loadTemplate(ctx, ScreenModel.templateName(ScreenModel.Kind.STRIP, role)) != null ||
            IconTemplateStore.loadTemplate(ctx, ScreenModel.templateName(ScreenModel.Kind.GRID, role)) != null

    /** Returns false when ignored (already running, or within [DEBOUNCE_MS] of the last start). */
    fun launchAsync(
        ctx: Context,
        role: String,
        source: String = "diagnostic",
        onDone: ((CarPlayLauncher.LaunchRecord) -> Unit)? = null,
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (busy || now - lastStartMs < DEBOUNCE_MS) {
            log.i("launch $role ignored (busy=$busy, ${now - lastStartMs} ms since last start)")
            return false
        }
        lastStartMs = now
        busy = true
        val appCtx = ctx.applicationContext
        worker.execute {
            val rec = try {
                launchBlocking(appCtx, role, source)
            } finally {
                busy = false
            }
            if (onDone != null) main.post { onDone(rec) }
        }
        return true
    }

    fun launchBlocking(
        ctx: Context,
        role: String,
        source: String = "diagnostic",
    ): CarPlayLauncher.LaunchRecord {
        val r = Run(ctx.applicationContext, role.lowercase(), source)
        var outcome = CarPlayLauncher.Outcome.FAILED
        var error: String? = null
        try {
            outcome = attempt(r)
        } catch (t: Throwable) {
            val msg = "${t.javaClass.simpleName}: ${t.message}"
            error = msg
            r.step("error", msg)
        }
        if (outcome == CarPlayLauncher.Outcome.NOT_FOUND && error == null) {
            error = "no confident match; nothing tapped except the Home toggle"
        }
        val record = CarPlayLauncher.LaunchRecord(
            id = r.id,
            startedIso = r.startedIso,
            targetName = r.role,
            nx = r.tapNx,
            ny = r.tapNy,
            verifyTemplate = r.used,
            steps = r.steps.toList(),
            outcome = outcome,
            error = error,
            verificationBasis = VERIFICATION_BASIS,
            targetAppIdentityConfirmed = false,
        )
        runCatching { CarPlayLauncher.saveRecord(r.ctx, record) }
        finishEvidence(r, outcome)
        log.i("${r.id} ${r.role} -> $outcome in ${r.elapsed()} ms")
        return record
    }

    /**
     * Hands owned frames to the evidence writer, or recycles them. Evidence failure never
     * changes the launch outcome — the record above is already saved either way.
     */
    private fun finishEvidence(r: Run, outcome: CarPlayLauncher.Outcome) {
        val before = r.beforeOwned
        val verify = r.verifyOwned
        r.beforeOwned = null
        r.verifyOwned = null
        if (!LaunchEvidence.shouldSaveEvidence(saveScreenshots, before != null, verify != null)) {
            before?.recycle()
            if (verify !== before) verify?.recycle()
            return
        }
        if (!LaunchEvidence.saveOwned(r.ctx, r.id, before, verify, evidenceJson(r, outcome))) {
            log.w("${r.id} evidence skipped for ${r.id}")
        }
    }

    private fun evidenceJson(r: Run, outcome: CarPlayLauncher.Outcome): String {
        fun putScore(obj: JSONObject, name: String, value: Float?) {
            val safe = LaunchEvidence.jsonScore(value)
            if (safe != null) obj.put(name, safe) else obj.put(name, JSONObject.NULL)
        }
        return JSONObject().apply {
            put("role", r.role)
            put("source", r.source)
            put("appVersion", CarPlayFrameGrabber.resolveAppVersion(r.ctx))
            put("gitSha", CarPlayFrameGrabber.resolveGitSha(r.ctx))
            put("frameWidth", VideoFrame.width)
            put("frameHeight", VideoFrame.height)
            put("templateSourceStrip", r.stripSource)
            put("templateSourceGrid", r.gridSource)
            put("templateSourceGlyphDashboard", r.glyphDashSource)
            put("templateSourceGlyphOther", r.glyphOtherSource)
            put("matchName", r.matchName ?: JSONObject.NULL)
            putScore(this, "matchScore", r.matchScore)
            put("matchNx", r.matchNx.toDouble())
            put("matchNy", r.matchNy.toDouble())
            if (r.touchSent != null) put("touchSent", r.touchSent) else put("touchSent", JSONObject.NULL)
            put("captureMs", r.marks["capture"] ?: JSONObject.NULL)
            put("matchMs", r.marks["match"] ?: JSONObject.NULL)
            put("tapMs", r.marks["tap"] ?: JSONObject.NULL)
            put("verifyMs", r.marks["verify"] ?: JSONObject.NULL)
            put("outcome", outcome.name)
            put("verificationBasis", VERIFICATION_BASIS)
            put("targetAppIdentityConfirmed", false)
            putScore(this, "verifyDashboardScore", r.verifyDashScore)
            putScore(this, "verifyOtherScore", r.verifyOtherScore)
            put("evidenceError", r.evidenceError ?: JSONObject.NULL)
        }.toString()
    }

    private fun attempt(r: Run): CarPlayLauncher.Outcome {
        val strip = IconTemplateStore.loadTemplate(r.ctx, ScreenModel.templateName(ScreenModel.Kind.STRIP, r.role))
        val grid = IconTemplateStore.loadTemplate(r.ctx, ScreenModel.templateName(ScreenModel.Kind.GRID, r.role))
        val glyphDash = IconTemplateStore.loadTemplate(r.ctx, ScreenModel.GLYPH_DASHBOARD)
        val glyphOther = IconTemplateStore.loadTemplate(r.ctx, ScreenModel.GLYPH_OTHER)
        r.stripSource = IconTemplateStore.sourceOf(r.ctx, ScreenModel.templateName(ScreenModel.Kind.STRIP, r.role))
        r.gridSource = IconTemplateStore.sourceOf(r.ctx, ScreenModel.templateName(ScreenModel.Kind.GRID, r.role))
        r.glyphDashSource = IconTemplateStore.sourceOf(r.ctx, ScreenModel.GLYPH_DASHBOARD)
        r.glyphOtherSource = IconTemplateStore.sourceOf(r.ctx, ScreenModel.GLYPH_OTHER)
        r.step("templates", "strip=${r.stripSource} grid=${r.gridSource} glyph=${r.glyphDashSource}/${r.glyphOtherSource}" +
            " evidence=${if (saveScreenshots) "on" else "off"}")
        if (strip == null && grid == null) {
            throw Abort("no strip.${r.role} or grid.${r.role} template available")
        }

        val surface = CarPlayFrameGrabber.awaitLiveSurfaceView(r.ctx, SURFACE_WAIT_MS)
            ?: throw Abort("CarPlay surface not ready")
        var frame = stable(r, surface)
        r.beforeOwned = frame.owned
        r.mark("capture")
        r.step("frame0", "stable ${frame.gray.width}x${frame.gray.height} at 1/${ScreenModel.SCALE}; " +
            "surface=${ScreenModel.surface(frame.gray, glyphDash, glyphOther)}")

        // 1. Recent-apps strip: one tap, no Home.
        if (strip != null) {
            val hit = ScreenModel.find(frame.gray, strip, ScreenModel.STRIP_ROI, ScreenModel.Kind.STRIP.stride)
            recordMatch(r, strip.name, hit)
            r.step("strip", "${strip.name} best at (%.3f, %.3f)".format(Locale.US, hit.nx, hit.ny), hit.score, hit.passed)
            if (hit.passed) {
                tap(r, hit.nx, hit.ny, strip.name, isTarget = true)
                val post = afterChange(r, surface, frame.gray)
                if (post == null) {
                    r.step("verify", "screen did not change within $CHANGE_WAIT_MS ms (already in ${r.role}?)", passed = false)
                    return CarPlayLauncher.Outcome.UNVERIFIED
                }
                val graced = graceUntilNotDashboard(r, surface, post, glyphDash, glyphOther)
                if (graced !== post) post.owned?.recycle()
                r.verifyOwned = graced.owned
                val ev = ScreenModel.surfaceDetailed(graced.gray, glyphDash, glyphOther)
                r.verifyDashScore = ev.dashboardScore
                r.verifyOtherScore = ev.otherScore
                r.mark("verify")
                val ok = ev.surface != ScreenModel.Surface.DASHBOARD
                r.step("verify", "screen changed; surface=${ev.surface}", passed = ok)
                return if (ok) CarPlayLauncher.Outcome.SUCCESS else CarPlayLauncher.Outcome.VERIFY_FAILED
            }
        }

        // 2. Grid on the current frame (already on Home).
        if (grid != null) {
            gridAttempt(r, surface, frame.gray, grid, glyphDash, glyphOther)?.let { return it }
        }

        // 3. Home/Dashboard toggle in the strip, then the grid.
        for (i in 1..MAX_TOGGLES) {
            checkBudget(r)
            tap(r, ScreenModel.TOGGLE_NX, ScreenModel.TOGGLE_NY, "toggle", isTarget = false)
            val settled = afterChange(r, surface, frame.gray) ?: stable(r, surface)
            // Intermediate toggle frames are not evidence: only before + verification are kept.
            settled.owned?.recycle()
            frame = Frame(settled.gray, null)
            val surf = ScreenModel.surface(frame.gray, glyphDash, glyphOther)
            r.step("toggle$i", "surface=$surf")
            if (surf == ScreenModel.Surface.DASHBOARD) continue
            if (grid != null) {
                gridAttempt(r, surface, frame.gray, grid, glyphDash, glyphOther)?.let { return it }
            }
            if (surf == ScreenModel.Surface.NOT_DASHBOARD) break
        }
        return CarPlayLauncher.Outcome.NOT_FOUND
    }

    private fun recordMatch(r: Run, name: String, hit: ScreenModel.Hit) {
        r.matchName = name
        r.matchScore = hit.score
        r.matchNx = hit.nx
        r.matchNy = hit.ny
        r.mark("match")
    }

    /** Null = no confident grid match (nothing tapped). */
    private fun gridAttempt(
        r: Run,
        surface: SurfaceView,
        frame: ScreenModel.Gray,
        grid: IconMatcher.IconTemplate,
        glyphDash: IconMatcher.IconTemplate?,
        glyphOther: IconMatcher.IconTemplate?,
    ): CarPlayLauncher.Outcome? {
        val hit = ScreenModel.find(frame, grid, ScreenModel.GRID_ROI, ScreenModel.Kind.GRID.stride)
        recordMatch(r, grid.name, hit)
        r.step("grid", "${grid.name} best at (%.3f, %.3f)".format(Locale.US, hit.nx, hit.ny), hit.score, hit.passed)
        if (!hit.passed) return null
        checkBudget(r)
        tap(r, hit.nx, hit.ny, grid.name, isTarget = true)
        val post = afterChange(r, surface, frame)
        if (post == null) {
            r.step("verify", "no screen change after grid tap", passed = false)
            return CarPlayLauncher.Outcome.VERIFY_FAILED
        }
        val graced = graceUntilNotDashboard(r, surface, post, glyphDash, glyphOther)
        if (graced !== post) post.owned?.recycle()
        r.verifyOwned = graced.owned
        val still = ScreenModel.find(graced.gray, grid, ScreenModel.GRID_ROI, ScreenModel.Kind.GRID.stride)
        val ev = ScreenModel.surfaceDetailed(graced.gray, glyphDash, glyphOther)
        r.verifyDashScore = ev.dashboardScore
        r.verifyOtherScore = ev.otherScore
        r.mark("verify")
        val ok = !still.passed && ev.surface != ScreenModel.Surface.DASHBOARD
        r.step("verify", "grid icon gone=${!still.passed}; surface=${ev.surface}", still.score, ok)
        return if (ok) CarPlayLauncher.Outcome.SUCCESS else CarPlayLauncher.Outcome.VERIFY_FAILED
    }

    private fun checkBudget(r: Run) {
        if (r.elapsed() > GLOBAL_TIMEOUT_MS) throw Abort("global timeout ${GLOBAL_TIMEOUT_MS} ms before next tap")
    }

    private fun tap(r: Run, nx: Float, ny: Float, what: String, isTarget: Boolean) {
        val x = nx.coerceIn(0f, 1f)
        val y = ny.coerceIn(0f, 1f)
        val sent = CarPlayLauncher.tapBlocking(x, y, 120L)
        r.touchSent = sent
        r.mark("tap")
        if (!sent) throw Abort("touch channel down")
        if (isTarget) {
            r.tapNx = x
            r.tapNy = y
            r.used = what
        }
        r.step("tap", "$what at (%.3f, %.3f)".format(Locale.US, x, y))
    }

    /** Settled frame plus, in diagnostic mode, an owned full-resolution copy of that exact frame. */
    private data class Frame(val gray: ScreenModel.Gray, val owned: Bitmap?)

    private fun stable(r: Run, surface: SurfaceView): Frame {
        if (saveScreenshots) {
            when (val res = CarPlayFrameGrabber.captureStableFrameBlocking(
                timeoutMs = 1500L, surfaceView = surface, owned = true)) {
                is CarPlayFrameGrabber.CaptureResult.Success -> return Frame(toGray(res.bitmap), res.bitmap)
                is CarPlayFrameGrabber.CaptureResult.Failure ->
                    // Evidence must not change the launch: fall back to the borrowed path below.
                    r.noteEvidenceError("owned frame: ${res.reason}: ${res.detail}")
            }
        }
        return when (val res = CarPlayFrameGrabber.captureStableFrameBlocking(timeoutMs = 1500L, surfaceView = surface)) {
            is CarPlayFrameGrabber.CaptureResult.Success -> Frame(toGray(res.bitmap), null)
            is CarPlayFrameGrabber.CaptureResult.Failure -> throw Abort("${res.reason}: ${res.detail}")
        }
    }

    /**
     * Post-tap wait: first a screen change vs [before], then a quiet window — at least
     * [MIN_AFTER_TAP_MS] since the tap AND [QUIET_MS] of consecutive quiet samples — so a
     * pause mid-transition (the stream only sends frames on change) is not read as settled.
     * Null = never changed. Thresholds and outcome rules are unchanged.
     */
    private fun afterChange(r: Run, surface: SurfaceView, before: ScreenModel.Gray): Frame? {
        val tapAt = SystemClock.elapsedRealtime()
        val quiet = QuietWindow(tapAt, MIN_AFTER_TAP_MS, QUIET_MS,
            CarPlayFrameGrabber.DEFAULT_SETTLE_THRESHOLD)
        var prev: ScreenModel.Gray? = null
        var changedAt = -1L
        var samples = 0
        while (true) {
            val now = SystemClock.elapsedRealtime()
            if (changedAt < 0 && now - tapAt > CHANGE_WAIT_MS) return null
            if (changedAt >= 0 && now - tapAt > SETTLE_CAP_MS) {
                r.step("settle", "not quiet by +${now - tapAt}ms ($samples samples); using current frame")
                break
            }
            val g = when (val res = CarPlayFrameGrabber.captureBlocking(surface)) {
                is CarPlayFrameGrabber.CaptureResult.Success -> toGray(res.bitmap)
                is CarPlayFrameGrabber.CaptureResult.Failure -> throw Abort("${res.reason}: ${res.detail}")
            }
            samples++
            val sameSize = g.width == before.width && g.height == before.height
            if (sameSize && changedAt < 0 && ScreenModel.changed(before, g) > CHANGE_MAD) changedAt = now
            if (changedAt >= 0) {
                val p = prev
                val mad = if (p != null && p.width == g.width && p.height == g.height) ScreenModel.changed(p, g) else 1f
                if (quiet.offer(now, mad)) {
                    r.step("settle", "change at +${changedAt - tapAt}ms, quiet at +${now - tapAt}ms, $samples samples")
                    break
                }
            }
            prev = g
            try {
                Thread.sleep(POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw Abort("interrupted")
            }
        }
        return stable(r, surface)
    }

    /** Verification only: if the frame reads DASHBOARD, keep observing up to VERIFY_GRACE_MS. Never taps. */
    private fun graceUntilNotDashboard(
        r: Run,
        surface: SurfaceView,
        first: Frame,
        dash: IconMatcher.IconTemplate?,
        other: IconMatcher.IconTemplate?,
    ): Frame {
        var current = first
        if (ScreenModel.surface(current.gray, dash, other) != ScreenModel.Surface.DASHBOARD) return current
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < VERIFY_GRACE_MS) {
            try {
                Thread.sleep(250L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            val next = stable(r, surface)
            if (current !== first) current.owned?.recycle()
            current = next
            if (ScreenModel.surface(current.gray, dash, other) != ScreenModel.Surface.DASHBOARD) break
        }
        r.step("grace", "+${SystemClock.elapsedRealtime() - start}ms surface=" +
            ScreenModel.surface(current.gray, dash, other))
        return current
    }

    private fun toGray(bmp: Bitmap): ScreenModel.Gray {
        val w = bmp.width
        val h = bmp.height
        var buf = argbBuf
        if (buf == null || buf.size != w * h) {
            buf = IntArray(w * h)
            argbBuf = buf
        }
        bmp.getPixels(buf, 0, w, 0, 0, w, h)
        return ScreenModel.downscale(buf, w, h)
    }
}
