package wasidremin.gmccpa

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import wasidremin.gmccpa.av.CarPlayFrameGrabber
import wasidremin.gmccpa.av.CarPlayLauncher
import wasidremin.gmccpa.av.VisionLauncher
import wasidremin.gmccpa.match.IconMatcher
import wasidremin.gmccpa.match.IconTemplateStore
import wasidremin.gmccpa.match.ScreenModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Calibration UI for the vision launcher. Templates are cut from SAVED captures (PNG on disk), so
 * calibration never needs the live CarPlay surface and is repeatable. Workflow:
 *   1. Capture Current Screen on the Home grid, on the Dashboard (and optionally inside an app).
 *   2. Icon templates -> Calibrate from capture -> tap an icon -> press its name.
 *   3. Self-check -> every template against every saved capture.
 *   4. Test app launch -> Maps / Music / Spotify / Phone.
 */
class CalibrationUi(
    private val act: Activity,
    private val safeWidth: (Int) -> Int = { it },
    private val setDetail: (String) -> Unit,
) {

    // ---- shared helpers -------------------------------------------------------------------

    private fun lp(topDp: Float) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(topDp) }

    private fun dim(text: String): TextView = EqTheme.text(act, 17f, EqTheme.TEXT_DIM).apply { this.text = text }

    private fun dialogShell(title: String, subtitle: String?, fullWidth: Boolean = false): Pair<Dialog, LinearLayout> {
        val dialog = Dialog(act)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.6f)
        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = EqTheme.rounded(0xFF111826.toInt(), 28f, EqTheme.CARD_LINE)
            setPadding(EqTheme.px(40f), EqTheme.px(32f), EqTheme.px(40f), EqTheme.px(28f))
            addView(EqTheme.text(act, 28f, EqTheme.TEXT, EqTheme.Weight.LIGHT).apply { text = title })
            if (subtitle != null) addView(
                EqTheme.text(act, 16f, EqTheme.TEXT_DIM).apply { text = subtitle },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(10f) },
            )
        }
        val scroll = ScrollView(act).apply { addView(body) }
        val width = safeWidth(if (fullWidth) (act.resources.displayMetrics.widthPixels * 0.9f).toInt() else EqTheme.px(760f))
        dialog.setContentView(scroll, ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        return dialog to body
    }

    private fun buttonRow(vararg buttons: View): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        buttons.forEachIndexed { i, b ->
            addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = EqTheme.px(16f) })
        }
    }

    private fun grayOf(bmp: Bitmap): ScreenModel.Gray {
        val argb = IntArray(bmp.width * bmp.height)
        bmp.getPixels(argb, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return ScreenModel.downscale(argb, bmp.width, bmp.height)
    }

    private fun templateBitmap(t: IconMatcher.IconTemplate): Bitmap {
        val px = IntArray(t.width * t.height) { i ->
            val v = (t.pixels[i].coerceIn(0f, 1f) * 255f).toInt()
            Color.rgb(v, v, v)
        }
        return Bitmap.createBitmap(px, t.width, t.height, Bitmap.Config.ARGB_8888)
    }

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    /** One-line readiness summary; also used as the settings-row subtitle. */
    fun missingSummary(ctx: Context): String {
        val names = IconTemplateStore.listTemplates(ctx).map { it.name }.toSet()
        val missing = mutableListOf<String>()
        if (ScreenModel.GLYPH_DASHBOARD !in names) missing += ScreenModel.GLYPH_DASHBOARD
        if (ScreenModel.GLYPH_OTHER !in names) missing += ScreenModel.GLYPH_OTHER
        for (role in listOf("maps", "phone")) {
            if ("strip.$role" !in names && "grid.$role" !in names) missing += "$role (strip or grid)"
        }
        if (listOf("strip.music", "grid.music", "strip.spotify", "grid.spotify").none { it in names }) {
            missing += "music or spotify"
        }
        val legacy = names.count { !it.contains('.') }
        val legacyNote = if (legacy > 0) " · $legacy old v98 template(s) to delete" else ""
        return (if (missing.isEmpty()) "Ready: Maps, Music and Phone can launch" else "Missing: " + missing.joinToString(", ")) + legacyNote
    }

    // ---- template list --------------------------------------------------------------------

    fun showTemplates(onChanged: () -> Unit) {
        val (dialog, body) = dialogShell(
            "Template overrides",
            "Optional: your captures beat the built-in Equinox templates. Needs glyph.dashboard, glyph.other, and strip.<app> and/or grid.<app> for maps, music or spotify, and phone.",
        )
        body.addView(dim(missingSummary(act)), lp(14f))
        val templates = runCatching { IconTemplateStore.listTemplates(act) }.getOrElse { emptyList() }
        if (templates.isEmpty()) body.addView(dim("No templates yet. Use Calibrate from capture."), lp(14f))
        for (t in templates) {
            val legacy = !t.name.contains('.')
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(ImageView(act).apply {
                setImageBitmap(templateBitmap(t))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(EqTheme.px(56f), EqTheme.px(56f)))
            row.addView(
                EqTheme.text(act, 20f, if (legacy) EqTheme.DANGER else EqTheme.TEXT).apply {
                    text = if (legacy) "${t.name}  (v98 format, unusable: delete)" else "${t.name}  ${t.width}×${t.height}"
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = EqTheme.px(16f) },
            )
            row.addView(eqButton(act, "Delete", EqButtonStyle.DANGER) {
                IconTemplateStore.deleteTemplate(act, t.name)
                dialog.dismiss()
                onChanged()
                showTemplates(onChanged)
            })
            body.addView(row, lp(12f))
        }
        body.addView(
            buttonRow(
                eqButton(act, "Close", EqButtonStyle.SECONDARY) { dialog.dismiss() },
                eqButton(act, "Self-check", EqButtonStyle.SECONDARY) { dialog.dismiss(); runSelfCheck() },
                eqButton(act, "Calibrate from capture…", EqButtonStyle.PRIMARY) { dialog.dismiss(); pickCapture(onChanged) },
            ),
            lp(28f),
        )
        dialog.show()
    }

    // ---- calibrate from a saved capture ---------------------------------------------------

    private fun pickCapture(onChanged: () -> Unit) {
        val (dialog, body) = dialogShell(
            "Calibrate from capture",
            "Home grid shot: grid.* icons and glyph.other. Dashboard shot: glyph.dashboard. Strip icons show on every shot.",
        )
        val caps = CarPlayFrameGrabber.listSavedCaptures(act).take(12)
        if (caps.isEmpty()) body.addView(dim("No saved captures. Use Capture Current Screen first."), lp(14f))
        for (cap in caps) {
            body.addView(
                EqRow(
                    act,
                    cap.id,
                    "${cap.metadata.negotiatedWidth}×${cap.metadata.negotiatedHeight} · ${cap.metadata.buttonProduced}",
                    trailing = EqChevron(act),
                    onClick = { dialog.dismiss(); markCapture(cap, onChanged) },
                ),
                lp(6f),
            )
        }
        body.addView(buttonRow(eqButton(act, "Close", EqButtonStyle.SECONDARY) { dialog.dismiss() }), lp(24f))
        dialog.show()
    }

    private fun markCapture(cap: CarPlayFrameGrabber.SavedCapture, onChanged: () -> Unit) {
        val full = BitmapFactory.decodeFile(cap.pngFile.absolutePath)
        if (full == null) {
            setDetail("Error: could not decode ${cap.pngFile.name}")
            return
        }
        val gray = grayOf(full)
        // The capture's own UI scale: a template cut here is stored at the default scale like the built-ins.
        val geo = VisionLauncher.geometryOf(act, gray)
        val preview = CapturePreview(act, full, geo)
        val (dialog, body) = dialogShell(
            "Mark icons · ${cap.id}",
            "Tap an icon, check the blue box, then press its name. Left column = strip, bottom-left button = glyph, everything else = grid.",
            fullWidth = true,
        )
        body.addView(preview, lp(16f))
        val status = EqTheme.text(act, 18f, EqTheme.TEXT_DIM).apply { text = "Nothing selected" }
        body.addView(status, lp(12f))
        val roleRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        body.addView(roleRow, lp(12f))

        fun save(kind: ScreenModel.Kind, role: String) {
            val p = preview.marked ?: return
            val t = ScreenModel.cutTemplate(gray, kind, role, p.first, p.second, cap.id, isoNow(), geo)
            IconTemplateStore.saveTemplate(act, t)
            onChanged()
            val msg = "Saved ${t.name} (${t.width}×${t.height}) from ${cap.id} (UI scale %.2f)".format(Locale.US, geo.ui)
            status.text = msg
            status.setTextColor(EqTheme.OK)
            setDetail(msg)
        }

        preview.onMark = { nx, ny ->
            val kind = geo.kindForPoint(nx, ny)
            status.setTextColor(EqTheme.TEXT_DIM)
            status.text = "Selected ${kind.prefix} at (%.3f, %.3f). Press the matching name.".format(Locale.US, nx, ny)
            roleRow.removeAllViews()
            val roles: List<Pair<String, String>> = if (kind == ScreenModel.Kind.GLYPH) {
                listOf("Dashboard glyph" to "dashboard", "Home/app glyph" to "other")
            } else {
                ScreenModel.ROLES.map { r -> r.replaceFirstChar { c -> c.uppercase() } to r }
            }
            roles.forEachIndexed { i, (label, role) ->
                roleRow.addView(
                    eqButton(act, label, EqButtonStyle.PRIMARY) { save(kind, role) },
                    LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = EqTheme.px(12f) },
                )
            }
        }
        body.addView(buttonRow(eqButton(act, "Done", EqButtonStyle.SECONDARY) { dialog.dismiss() }), lp(24f))
        dialog.show()
    }

    // ---- self-check -----------------------------------------------------------------------

    fun runSelfCheck() {
        setDetail("Self-check running…")
        Thread({
            val report = StringBuilder()
            val templates = IconTemplateStore.listTemplates(act).filter { it.name.contains('.') }
            val dash = templates.find { it.name == ScreenModel.GLYPH_DASHBOARD }
            val other = templates.find { it.name == ScreenModel.GLYPH_OTHER }
            val caps = CarPlayFrameGrabber.listSavedCaptures(act).take(10)
            for (cap in caps) {
                val bmp = BitmapFactory.decodeFile(cap.pngFile.absolutePath) ?: continue
                val g = grayOf(bmp)
                bmp.recycle()
                val geo = VisionLauncher.geometryOf(act, g)
                report.append(cap.id).append("   surface=").append(ScreenModel.surface(g, dash, other, geo))
                    .append("   ui=%.3f".format(Locale.US, geo.ui)).append('\n')
                for (t in templates) {
                    if (t.name.startsWith("glyph.")) continue
                    val kind = if (t.name.startsWith("strip.")) ScreenModel.Kind.STRIP else ScreenModel.Kind.GRID
                    val hit = ScreenModel.find(g, t, kind, geo)
                    report.append(
                        "   %-16s %.3f %-5s (%.3f, %.3f)\n".format(
                            Locale.US, t.name, hit.score, if (hit.passed) "MATCH" else "-", hit.nx, hit.ny,
                        ),
                    )
                }
            }
            act.runOnUiThread {
                if (act.isFinishing || act.isDestroyed) return@runOnUiThread
                val (dialog, body) = dialogShell(
                    "Self-check",
                    "Expect strip.* MATCH on every shot that shows the strip, and grid.* MATCH only on Home grid shots. surface=DASHBOARD only on Dashboard shots.",
                    fullWidth = true,
                )
                body.addView(EqTheme.text(act, 15f, EqTheme.TEXT).apply {
                    typeface = Typeface.MONOSPACE
                    text = report.toString().ifEmpty { "No captures or no templates." }
                }, lp(16f))
                body.addView(buttonRow(eqButton(act, "Close", EqButtonStyle.SECONDARY) { dialog.dismiss() }), lp(24f))
                dialog.show()
                setDetail("Self-check done: ${caps.size} captures, ${templates.size} templates")
            }
        }, "calib-selfcheck").apply { isDaemon = true }.start()
    }

    // ---- test launch ----------------------------------------------------------------------

    fun showTestLaunch() {
        val (dialog, body) = dialogShell(
            "Test app launch",
            "Switches to CarPlay, then strip → Home toggle → grid. Taps only on a confident match. The record is saved and uploaded.",
        )
        body.addView(dim(missingSummary(act)), lp(14f))
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        ScreenModel.ROLES.forEachIndexed { i, role ->
            val ready = VisionLauncher.isCalibrated(act, role)
            row.addView(
                eqButton(act, role.replaceFirstChar { it.uppercase() }, if (ready) EqButtonStyle.PRIMARY else EqButtonStyle.SECONDARY) {
                    if (!VisionLauncher.isCalibrated(act, role)) {
                        setDetail("Error: $role has no strip or grid template")
                        return@eqButton
                    }
                    dialog.dismiss()
                    runLaunch(role)
                },
                LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = EqTheme.px(12f) },
            )
        }
        body.addView(row, lp(20f))
        body.addView(buttonRow(eqButton(act, "Close", EqButtonStyle.SECONDARY) { dialog.dismiss() }), lp(24f))
        dialog.show()
    }

    private fun runLaunch(role: String) {
        val started = VisionLauncher.launchAsync(act, role, "diagnostic") { rec ->
            val verify = rec.steps.lastOrNull { it.step == "verify" }
            val total = rec.steps.lastOrNull()?.detail?.substringBefore("ms")?.removePrefix("+") ?: "?"
            val msg = "Launch $role → ${rec.outcome}" +
                (verify?.score?.let { " (score %.3f)".format(Locale.US, it) } ?: "") +
                " · $total ms" + (rec.error?.let { " · $it" } ?: "")
            Toast.makeText(act.applicationContext, msg, Toast.LENGTH_LONG).show()
            setDetail(msg)
            CarPlayLauncher.uploadLaunch(act, rec) { res ->
                res.onFailure { t -> setDetail("$msg (upload failed: ${t.message ?: t.javaClass.simpleName})") }
            }
        }
        if (!started) setDetail("Launch ignored: one is already running, or pressed within 1.5 s")
    }
}

/** Screenshot preview that reports the tapped point in normalized frame coordinates. */
private class CapturePreview(
    ctx: Context,
    private val bmp: Bitmap,
    private val geo: ScreenModel.Geometry,
) : View(ctx) {
    var onMark: ((Float, Float) -> Unit)? = null
    var marked: Pair<Float, Float>? = null
        private set

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF4C9BFF.toInt()
        strokeWidth = 4f
    }
    private val roiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x66FFFFFF
        strokeWidth = 2f
    }
    private val dst = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w.toLong() * bmp.height / bmp.width).toInt())
    }

    override fun onDraw(c: Canvas) {
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawBitmap(bmp, null, dst, bitmapPaint)
        for (r in listOf(geo.stripRoi, geo.gridRoi, geo.glyphRoi)) {
            c.drawRect(r.x0 * width, r.y0 * height, r.x1 * width, r.y1 * height, roiPaint)
        }
        val m = marked ?: return
        val kind = geo.kindForPoint(m.first, m.second)
        val half = kind.sizeFullPx * geo.ui / 2f * width / bmp.width
        val cx = m.first * width
        val cy = m.second * height
        c.drawRect(cx - half, cy - half, cx + half, cy + half, boxPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action != MotionEvent.ACTION_UP || width == 0 || height == 0) return true
        var nx = (e.x / width).coerceIn(0f, 1f)
        val ny = (e.y / height).coerceIn(0f, 1f)
        if (geo.kindForPoint(nx, ny) != ScreenModel.Kind.GRID) nx = geo.stripCenterNx
        marked = nx to ny
        invalidate()
        onMark?.invoke(nx, ny)
        performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
