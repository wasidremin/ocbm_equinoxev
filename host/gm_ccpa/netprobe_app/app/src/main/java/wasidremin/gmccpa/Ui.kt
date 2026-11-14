package wasidremin.gmccpa

import kotlin.math.roundToInt
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import wasidremin.gmccpa.logging.CapturePrefs
import wasidremin.gmccpa.logging.LogCapture
import wasidremin.gmccpa.logging.UploadWindow
import wasidremin.gmccpa.ocbm.UsbIdentity
import java.io.File

/**
 * The launcher screen's chrome. Kept out of [MainActivity] so the session logic there is not
 * interleaved with view construction.
 *
 * Built in code, with no resources and no AndroidX, because `app/build.gradle` declares zero
 * dependencies on purpose and `tools/build_apk.sh` has no dependency resolution at all — it runs
 * kotlinc over a source directory. A Material import here would not be a style change, it would be a
 * new build system.
 *
 * **Nothing here is a fixed pixel size and nothing assumes the screen size.** The AAOS emulator hands
 * this Activity a 2400x960dp panel; the real head unit gives it a materially smaller area, and GM's
 * own chrome can take more of it at any time. So every dimension is derived from the space the root
 * view is actually given, re-derived whenever that changes, and the two headline strings autosize
 * themselves to fit rather than clipping or wrapping.
 */
object Palette {
    const val BG          = 0xFF0E1113.toInt()  // near-black; the dash is dark and stays dark
    const val SURFACE     = 0xFF171B1E.toInt()
    const val LINE        = 0xFF2A3136.toInt()
    const val TEXT        = 0xFFF2F4F5.toInt()
    const val TEXT_DIM    = 0xFF9AA4AA.toInt()
    // State colours. These carry the meaning at a glance — the colour is read first, the word second.
    const val IDLE        = 0xFF8A9298.toInt()  // grey   — nothing is happening
    const val WORKING     = 0xFFE8B341.toInt()  // amber  — something is in flight
    const val PHONE       = 0xFF58A6FF.toInt()  // blue   — the phone is in the picture
    const val LIVE        = 0xFF4CC38A.toInt()  // green  — CarPlay is through
    const val FAILED      = 0xFFE5534B.toInt()  // red    — it stopped for a reason
}

/**
 * The states the launcher can show, in the order a session actually walks through them.
 *
 * The first four come from [MainActivity.autoStart]; [PHONE_DETECTED] and [PAIRING] come from the
 * box over `CH_CTRL` (`CT_SESSION_EVENT` / `CT_PAIRING_CODE`, see `OcbmClient.handleCtrl`); the last
 * two come from the receiver when the phone's control connection lands.
 *
 * Labels are short and upper-case because this is the one thing that has to be readable at arm's
 * length in a moving vehicle. The sentence goes in the detail line, and the per-packet detail stays
 * in logcat (`adb logcat -s NETPROBE`), which remains the real instrument.
 */
enum class LinkState(val label: String, val color: Int) {
    IDLE          ("READY",              Palette.IDLE),
    SEARCHING     ("LOOKING FOR ADAPTER", Palette.WORKING),
    CLAIMING      ("CLAIMING ADAPTER",   Palette.WORKING),
    WAITING       ("WAITING FOR PHONE",  Palette.WORKING),
    PHONE_DETECTED("PHONE DETECTED",     Palette.PHONE),
    PAIRING       ("PAIRING",            Palette.PHONE),
    STARTING      ("CARPLAY STARTING",   Palette.PHONE),
    LIVE          ("CARPLAY RUNNING",    Palette.LIVE),
    /** Restart Session is tearing down and re-claiming. Amber: in flight, not failed, not idle. */
    RESTARTING    ("RESTARTING SESSION", Palette.WORKING),
    STOPPED       ("STOPPED",            Palette.IDLE),
    FAILED        ("FAILED",             Palette.FAILED),
}

/** Which action the status strip carries. Top-level and pure so the FAILED-keeps-Restart rule is unit-tested. */
internal enum class StripAction { BACK, RESTART, DONE }

/**
 * FAILED is the "phone didn't connect, press Restart" state (`RxPhase.STALLED` maps here): the
 * strip carries a primary Restart so the recovery is one tap away on every tab — and on the
 * intro layer via the restart button — not buried on the Troubleshooting page. A live session
 * always wins (Back to CarPlay); every other idle state keeps the quiet Done button.
 */
internal fun stripActionFor(sessionLive: Boolean, state: LinkState): StripAction = when {
    sessionLive -> StripAction.BACK
    state == LinkState.FAILED -> StripAction.RESTART
    else -> StripAction.DONE
}

/**
 * Things the app can ask the adapter to do, over `CH_MGMT` (0x0040).
 *
 * All four verbs are already implemented client-side in `OcbmClient.mgmtAction`; only `GET_INFO` is
 * device-verified (docs/06 §identity snapshot). The rest are wired here because the protocol offers
 * them, but treat a first run of REBOOT / RESTART_WIRELESS on hardware as an experiment.
 */
enum class BoxAction(val label: String) {
    INFO("Box Info"),
    RESTART_WIFI("Restart Wi-Fi"),
    REBOOT("Reboot Box"),
    /**
     * Clears the Bluetooth bond on the box AND this app's CarPlay pairing store, together.
     *
     * They must go together. The box's bond and the app's Ed25519 peer store are separate records of
     * the same relationship, and clearing one leaves the other claiming a pairing the phone no longer
     * has — a split brain that presents as pair-verify failing for reasons that look like broken
     * crypto. The old "Forget Phone" cleared only the box's half.
     */
    FORGET_PHONE("Forget Pairing"),
}

/**
 * The capture row. Separate from [BoxAction] because these act on the *instrument*, not the adapter —
 * mixing them would put "Reboot Box" one thumb-width from "Export Logs" in a moving vehicle.
 *
 * This row is the whole reason the logger exists: the faults it is meant to catch only happen when no
 * laptop is attached, so retrieving a capture cannot require one.
 */
enum class LogAction(val label: String) {
    EXPORT("Export Logs"),
    UPLOAD("Upload Logs"),
    SCOPE("Log Scope"),
    STATUS("Log Status"),
    /**
     * Car-side USB discriminator: deep dump of what the host stack exposes, then a 60 s replug
     * window that re-dumps on every bus change. Ride the existing Upload Logs path afterwards —
     * no adb needed.
     */
    USB_PROBE("USB Probe"),
    VEHICLE_PROBE("Vehicle Telemetry Probe"),
    /** Wipes captured log files on the head unit and restarts capture. Confirmation-gated. */
    CLEAR("Clear Logs"),
}

/** dp -> px, against the density the Activity is actually running at. */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/**
 * Pure normal-path launcher status: built-ins are automatic, user templates are optional
 * overrides, so no overrides is Ready — never "uncalibrated". A genuinely missing set
 * (no built-in AND no override) is a profile error. Host-testable; the Activity wrapper
 * in [LauncherUi] only supplies the missing-role list.
 */
fun launcherStatusText(missingRoles: List<String>): String =
    if (missingRoles.isEmpty()) {
        "Built-in Equinox templates are used automatically. Custom templates are optional overrides."
    } else {
        "Profile error: no template for ${missingRoles.joinToString(", ")} (built-in missing and no override)"
    }

/**
 * What the range row says, in the operator's terms rather than the enum's. Pure so the wording is
 * pinned in one place; the button's own line ([uploadLogsSubtitle]) must not disagree with it.
 */
internal fun uploadRangeSubtitle(window: UploadWindow): String =
    "Only lines from ${window.phrase} are sent. Uploads are always redacted."

/** The Upload logs button's line: name the range, or the tap is a surprise. */
internal fun uploadLogsSubtitle(window: UploadWindow): String =
    "Streams ${window.phrase} of redacted logs with determinate socket progress"

/**
 * The launcher screen.
 *
 * Settings sit behind the startup animation. The gear on that animation opens them; Done puts the
 * animation back when no session is live. A live session uses Back to CarPlay on the status strip.
 */
class LauncherUi(private val act: Activity) {
    /** A top-bar tile was tapped on the settings screen. Set by [MainActivity]. */
    var onBarTarget: ((BarTarget) -> Unit)? = null
    private var settingsBar: GmTopBarView? = null


    var onStart: () -> Unit = {}
    var onStop: () -> Unit = {}
    var onRecover: () -> Unit = {}
    var onRestart: () -> Unit = {}
    var onCredentials: (ssid: String, pass: String, chan: String) -> Unit = { _, _, _ -> }
    var onAdapterWifi: (Boolean) -> Unit = {}
    var adapterWifi: Boolean = false
        private set
    var onBoxAction: (BoxAction) -> Unit = {}
    /** The Settings page opened: re-read the adapter's paired phones. */
    var onPhonesWanted: () -> Unit = {}
    /** Forget one phone, already confirmed. */
    var onForgetOnePhone: (PhoneBook.Phone) -> Unit = {}
    /** Move CarPlay to one phone, already confirmed. */
    var onConnectPhone: (PhoneBook.Phone) -> Unit = {}
    /** The new connect order, every bonded address, first to connect first. */
    var onPhoneOrder: (List<String>) -> Unit = {}
    /** Advanced-page USB announce segment. Index 0 Off, 1 Image, 2 No medium. */
    var onUsbAnnounce: (Int) -> Unit = {}
    var onUsbAnnounceRetries: (Int) -> Unit = {}
    var onAdvancedOpened: () -> Unit = {}
    /** The USB bridge row's Open Files button. */
    var onOpenFiles: () -> Unit = {}
    var onLogAction: (LogAction) -> Unit = {}
    /** The Logs page's upload range was changed; [MainActivity] persists it. */
    var onUploadWindow: (UploadWindow) -> Unit = {}
    var onCaptureScope: (LogCapture.Scope) -> Unit = {}
    var onReturnToCarPlay: () -> Unit = {}
    var onScreenChanged: () -> Unit = {}
    var onRestartApp: () -> Unit = {}
    var onSidebarChanged: () -> Unit = {}
    var sessionIsLive: () -> Boolean = { false }
    var onClose: () -> Unit = {}

    var ssid: String = ""; private set
    var pass: String = ""; private set
    var chan: String = "36"; private set

    init {
        val p = act.getSharedPreferences(CRED_PREFS, Context.MODE_PRIVATE)
        ssid = p.getString(KEY_SSID, ssid) ?: ssid
        pass = p.getString(KEY_PASS, pass) ?: pass
        chan = p.getString(KEY_CHAN, chan) ?: chan
        AudioRoute.load(act)
    }

    private val intro = StartupAnimationView(act)
    private val introGear = IntroSettingsButton(act) { openIntroSettings() }
    /**
     * One-tap recovery on the failure screen. The intro layer covers settings when the phone
     * fails to connect, so the Troubleshooting-page Restart is unreachable; this button sits
     * bottom-right, visible only while FAILED. See [syncIntroRestart].
     */
    private val introRestart = IntroRestartButton(act) {
        uiLog.i("intro: restart tapped")
        onRestart()
    }
    private var introRestore: Runnable? = null
    private val uiLog = ProbeLog.sub("ui")

    private lateinit var settingsHost: LinearLayout
    private lateinit var scroller: ScrollView
    private lateinit var strip: EqStatusStrip
    private lateinit var rail: EqNavRail
    private lateinit var pages: List<LinearLayout>
    private lateinit var phoneRow: EqRow
    private lateinit var pairRow: EqRow
    private lateinit var startRow: EqRow
    private lateinit var restartButton: TextView
    private lateinit var applyRow: EqRow
    private lateinit var hotspotRow: EqRow
    private lateinit var hotspotValue: TextView
    private lateinit var infoRow: EqRow
    private lateinit var bridgeRow: EqRow
    private lateinit var announceRow: EqRow
    private lateinit var announceControl: EqSegmented
    private var announceIndex = 1
    private lateinit var announceRetriesRow: EqRow
    private lateinit var announceRetriesControl: EqSegmented
    private var announceRetriesIndex = 0
    private lateinit var scopeRow: EqRow
    private lateinit var logStatusRow: EqRow
    private lateinit var uploadLogsButton: wasidremin.gmccpa.upload.UploadActionButton
    private lateinit var uploadLogsRow: EqRow
    private lateinit var uploadWindowRow: EqRow
    /** Mirrors [wasidremin.gmccpa.upload.UploadCenter.logWindow]; kept in sync on every pick. */
    private var uploadWindow: UploadWindow = UploadWindow.DEFAULT
    private lateinit var launcherStatusRow: EqRow
    private lateinit var captureCurrentRow: EqRow
    private lateinit var captureCurrentButton: wasidremin.gmccpa.upload.UploadActionButton
    private lateinit var captureFullRow: EqRow
    private lateinit var captureFullButton: wasidremin.gmccpa.upload.UploadActionButton
    private lateinit var testConnectionRow: EqRow
    private lateinit var testConnectionButton: wasidremin.gmccpa.upload.UploadActionButton
    private lateinit var baseUrlRow: EqRow
    private lateinit var queueStatusRow: EqRow
    private lateinit var dataUsedRow: EqRow
    private lateinit var drainQueueButton: wasidremin.gmccpa.upload.UploadActionButton
    private val calib by lazy { CalibrationUi(act, { safeDialogWidth(it) }) { setDetail(it) } }
    private lateinit var wifiToggle: EqToggle
    private val queueListener: (Int) -> Unit = { _ -> refreshUploadQueueRow() }
    private var scopeControl: EqSegmented? = null
    private lateinit var audioRow: EqRow
    private lateinit var navRow: EqRow
    private lateinit var evRow: EqRow
    private lateinit var voiceRow: EqRow
    private lateinit var phonesCard: EqCard
    private lateinit var customTopBarControl: EqSegmented
    private lateinit var insightsPage: InsightsPage

    private var selectedTab = 0
    private var restartBusy = false
    private var pairingCode = ""
    private var lastState = LinkState.IDLE
    private var lastDetail = "starting…"
    private var logStatusText = ""
    private var adapterInfoText = ""
    private var displayResetDetail: String? = null
    private var alwaysHint: String? = null
    private var settingsTouchedAt = 0L
    private var lastAnimStage: StartupAnimationView.Stage? = null
    private var builtWidthPx = 0
    private var rebuilding = false
    private var safeLeft = 0
    private var safeRight = 0
    private var safeBottom = 0

    /** Tabs already layout-audited. Declared BEFORE [root]: [buildRoot] -> [showTab] ->
     * [auditLayout] touches it during construction, and a property declared after [root]
     * would still hold null at that point (startup NPE, 2026-10-16). */
    private val auditedTabs = mutableSetOf<Int>()

    val root: View = buildRoot()

    private fun persistCredentials() {
        act.getSharedPreferences(CRED_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SSID, ssid).putString(KEY_PASS, pass).putString(KEY_CHAN, chan).apply()
    }

    private fun buildRoot(): View {
        EqTheme.init(act, 0)
        val frame = object : FrameLayout(act) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                settingsTouchedAt = android.os.SystemClock.elapsedRealtime()
                return super.dispatchTouchEvent(ev)
            }
        }
        settingsHost = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            background = EqTheme.screenBackground()
        }
        frame.addView(settingsHost, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fillSettings()
        intro.setPhoneName(DisplayPrefs.lastPhoneName(act))
        intro.setOnLongClickListener {
            uiLog.i("intro: hidden for diagnostics")
            settingsTouchedAt = android.os.SystemClock.elapsedRealtime()
            hideIntroLayer()
            introRestore?.let { intro.removeCallbacks(it) }
            val restore = Runnable {
                if (sessionIsLive()) return@Runnable
                if (intro.alpha < 0.99f) return@Runnable
                showIntroLayer()
            }
            introRestore = restore
            intro.postDelayed(restore, 15_000)
            true
        }
        frame.addView(intro, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(introGear, FrameLayout.LayoutParams(act.dp(72), act.dp(72), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = act.dp(24)
            bottomMargin = act.dp(24)
        })
        introRestart.visibility = View.GONE
        frame.addView(introRestart, FrameLayout.LayoutParams(act.dp(88), act.dp(88), Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = act.dp(24)
            bottomMargin = act.dp(24)
        })
        settingsBar = TopBarHost.create(act) { t ->
            uiLog.i("settings bar: ${t.name} tapped")
            onBarTarget?.invoke(t)
        }.also {
            it.visibility = View.GONE
            frame.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1, Gravity.TOP))
        }
        frame.setOnApplyWindowInsetsListener { _, insets ->
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                val cut = insets.getInsets(android.view.WindowInsets.Type.displayCutout())
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                safeLeft = maxOf(cut.left, bars.left)
                safeRight = maxOf(cut.right, bars.right)
                safeBottom = maxOf(cut.bottom, bars.bottom)
            }
            settingsHost.setPadding(safeLeft, settingsHost.paddingTop, safeRight, safeBottom)
            (introGear.layoutParams as FrameLayout.LayoutParams).let { lp ->
                lp.leftMargin = safeLeft + act.dp(24)
                lp.bottomMargin = safeBottom + act.dp(24)
                introGear.layoutParams = lp
            }
            (introRestart.layoutParams as FrameLayout.LayoutParams).let { lp ->
                lp.rightMargin = safeRight + act.dp(24)
                lp.bottomMargin = safeBottom + act.dp(24)
                introRestart.layoutParams = lp
            }
            uiLog.i("settings safe insets l=$safeLeft r=$safeRight b=$safeBottom")
            insets
        }
        frame.requestApplyInsets()
        settingsHost.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            considerWidth(r - l - settingsHost.paddingLeft - settingsHost.paddingRight)
        }
        // Keep the settings UI inside the CarPlay calibration space (the same centered
        // latch + presentation insets the video path uses) so no row parks under the
        // panel slope or the right cutout. Re-applied on every frame layout because the
        // latch (VideoFrame) and the window both settle after first layout.
        frame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyCalibrationFrame(frame) }
        settingsHost.post { applyCalibrationFrame(frame) }
        return frame
    }

    /**
     * Positions [settingsHost] inside the CarPlay calibration space.
     *
     * The frame is the subscribe-time latch ([VideoFrame] block) centered in the live
     * window via [DisplayGeometry.fitLatched], further inset by the same presentation
     * [DisplayPrefs.Controls] the video path applies — so settings rows sit on exactly the
     * visible glass the picture uses. Guarded by equality: re-setting identical margins
     * would relayout forever. The intro splash stays full-bleed on purpose (transient).
     */
    private fun applyCalibrationFrame(frame: FrameLayout) {
        val rootW = frame.width
        val rootH = frame.height
        if (rootW <= 0 || rootH <= 0) return
        val latchedW = (VideoFrame.leftPx + VideoFrame.railPx + VideoFrame.viewWidth + VideoFrame.rightPx)
            .coerceAtLeast(1)
        val latchedH = (VideoFrame.topPx + VideoFrame.topInsetPx + VideoFrame.viewHeight + VideoFrame.bottomPx)
            .coerceAtLeast(1)
        val raw = DisplayGeometry.settingsFrame(
            rootW, rootH, latchedW, latchedH,
            DisplayPrefs.controls(act), act.resources.displayMetrics.density)
        // The custom top bar occupies the strip the CarPlay screen reserved before SUBSCRIBE
        // (VideoFrame.topPx, the top of the latched block), at the window top. Settings starts
        // below it, so the bar is visible here too and nothing hides under it.
        val insets = frame.rootWindowInsets
        val stripPx = if (DisplayPrefs.customTopBar(act) && VideoFrame.topPx > 0 && !TopBarHost.statusBarVisible(insets))
            (VideoFrame.topPx * DisplayGeometry.fitLatched(rootW, rootH, latchedW, latchedH).scale).roundToInt()
                .coerceIn(0, rootH / 3) else 0
        placeSettingsBar(stripPx, insets)
        val box = if (stripPx > 0)
            DisplayGeometry.Box(raw.x, raw.y + stripPx, raw.width, (raw.height - stripPx).coerceAtLeast(1))
        else raw
        val lp = settingsHost.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width == box.width && lp.height == box.height &&
            lp.leftMargin == box.x && lp.topMargin == box.y) return
        // System safe padding still applies *inside* the frame (transient bars); the frame
        // itself already keeps the host off the dead glass.
        settingsHost.setPadding(safeLeft, settingsHost.paddingTop, safeRight, safeBottom)
        lp.width = box.width
        lp.height = box.height
        lp.leftMargin = box.x
        lp.topMargin = box.y
        lp.gravity = Gravity.TOP or Gravity.START
        settingsHost.layoutParams = lp
        uiLog.i("settings frame root=${rootW}x${rootH} latched=${latchedW}x${latchedH} " +
            "frame=${box.width}x${box.height}@${box.x},${box.y}")
    }

    /** Sizes, shapes and shows (or hides) the settings-screen top bar. Idempotent. */
    private fun placeSettingsBar(stripPx: Int, insets: android.view.WindowInsets?) {
        val bar = settingsBar ?: return
        if (stripPx <= 0) { if (bar.visibility != View.GONE) bar.visibility = View.GONE; return }
        val glass = TopBarHost.glass(insets)
        if (bar.rightInsetPx != glass.rightInsetPx) bar.rightInsetPx = glass.rightInsetPx
        if (bar.slopeM != glass.slopeM) bar.slopeM = glass.slopeM
        bar.outline = DisplayPrefs.glassOutline(act, act.window.decorView.width.toFloat(),
            act.window.decorView.height.toFloat())
        val lp = bar.layoutParams as FrameLayout.LayoutParams
        if (lp.height != stripPx) { lp.height = stripPx; bar.layoutParams = lp }
        if (bar.visibility != View.VISIBLE) {
            bar.visibility = View.VISIBLE
            uiLog.i("settings bar: shown height=${stripPx}px slope=${glass.slopeM} rightInset=${glass.rightInsetPx}")
        }
    }

    fun safeDialogWidth(desired: Int): Int =
        minOf(desired, act.resources.displayMetrics.widthPixels - safeLeft - safeRight - act.dp(48))
            .coerceAtLeast(act.dp(320))

    private fun considerWidth(w: Int) {
        if (w <= 0 || rebuilding) return
        val baseline = if (builtWidthPx > 0) builtWidthPx
            else (1100f * act.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val first = builtWidthPx == 0
        if (first) builtWidthPx = w
        if (kotlin.math.abs(w - baseline) / baseline.toFloat() <= 0.10f) return
        rebuilding = true
        settingsHost.post {
            fillSettings(w)
            rebuilding = false
        }
    }

    private fun fillSettings(widthPx: Int = 0) {
        if (widthPx > 0) {
            EqTheme.init(act, widthPx)
            builtWidthPx = widthPx
        }
        settingsHost.removeAllViews()
        val version = runCatching {
            act.packageManager.getPackageInfo(act.packageName, 0).versionName
        }.getOrNull() ?: ""
        rail = EqNavRail(act, TAB_NAMES, { showTab(it) }, version)
        settingsHost.addView(rail, LinearLayout.LayoutParams(
            EqTheme.px(300f), ViewGroup.LayoutParams.MATCH_PARENT))

        scroller = ScrollView(act).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val column = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(EqTheme.px(36f), EqTheme.px(28f), EqTheme.px(36f), EqTheme.px(36f))
        }
        strip = EqStatusStrip(act)
        column.addView(strip, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = EqTheme.px(8f) })

        pages = TAB_NAMES.map { page(it) }
        buildCarPlay(pages[TAB_CARPLAY])
        buildScreenSound(pages[TAB_SCREEN])
        buildCluster(pages[TAB_CLUSTER])
        buildInsights(pages[TAB_INSIGHTS])
        buildTroubleshooting(pages[TAB_TROUBLE])
        buildAdvanced(pages[TAB_ADVANCED])
        for (p in pages) column.addView(p, LinearLayout.LayoutParams(-1, -2))
        this.scroller.addView(column, ViewGroup.LayoutParams(-1, -2))
        settingsHost.addView(this.scroller, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        showTab(selectedTab)
        refreshDynamic()
    }

    private fun page(title: String): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        addView(eqPageTitle(act, title))
        addView(EqLightLine(act), LinearLayout.LayoutParams(-1, EqTheme.px(2f)).apply {
            bottomMargin = EqTheme.px(4f)
        })
    }

    /**
     * Insights: the website's overview cards and charts, drawn from this head unit's own probe
     * segments. The page loads off the UI thread; see [InsightsPage].
     */
    private fun buildInsights(page: LinearLayout) {
        val dataRoot = File(act.getExternalFilesDir(null) ?: act.filesDir, "telemetry")
        insightsPage = InsightsPage(act, dataRoot)
        page.addView(insightsPage.view, LinearLayout.LayoutParams(-1, -2))
    }

    /**
     * The settings tabs (2026-11-12; until then one long "Settings" page). Everyday preferences only:
     * anything numeric, probe-like or destructive lives under Troubleshooting or Advanced.
     *
     * CarPlay: the phones, the voice button, the launcher and what CarPlay is told about the car.
     */
    private fun buildCarPlay(page: LinearLayout) {
        page.addView(eqSectionLabel(act, "Phones"))
        phonesCard = EqCard(act)
        page.addView(phonesCard)
        // The pages are rebuilt when the settings width settles; keep what was last shown.
        fillPhones(shownPhones, shownPhonesNote, shownCanChoose)
        page.addView(eqSectionLabel(act, "Voice"))
        val role = wasidremin.gmccpa.voice.AssistantRole
        voiceRow = EqRow(
            act, "Steering-wheel voice button", role.statusText(act),
            // The car decides; this only opens its own picker. Absent when the car has none.
            trailing = if (role.state(act).picker != null)
                eqButton(act, "Choose", EqButtonStyle.SECONDARY) { role.openPicker(act) } else null,
        )
        page.addView(EqCard(act).addRow(voiceRow))
        page.addView(eqSectionLabel(act, "Apple Maps"))
        val ev = wasidremin.gmccpa.ev.CarPlayEv
        evRow = EqRow(
            act, "EV battery for Apple Maps", ev.statusText(act),
            trailing = EqToggle(act, ev.enabled) { on ->
                ev.setEnabled(act, on)
                evRow.setSubtitle(ev.statusText(act))
            },
        )
        val gps = wasidremin.gmccpa.gps.CarGps
        lateinit var gpsRow: EqRow
        gpsRow = EqRow(
            act, "Car GPS for Apple Maps", gps.statusText(act),
            trailing = EqToggle(act, gps.enabled) { on ->
                gps.setEnabled(act, on)
                gpsRow.setSubtitle(gps.statusText(act))
            },
        )
        lateinit var nacsRow: EqRow
        nacsRow = EqRow(
            act, "Tesla (NACS) adapter", ev.nacsText(),
            trailing = EqSegmented(
                act, wasidremin.gmccpa.ev.EvForCarPlay.NacsAdapter.values().map { it.label }, ev.nacs.ordinal,
            ) { i ->
                ev.setNacs(act, wasidremin.gmccpa.ev.EvForCarPlay.NacsAdapter.values()[i])
                nacsRow.setSubtitle(ev.nacsText())
            },
        )
        page.addView(EqCard(act).addRow(evRow).addRow(nacsRow).addRow(gpsRow))
        page.addView(eqSectionLabel(act, "Visual app launcher"))
        launcherStatusRow = EqRow(act, "Visual app launcher", launcherStatusSubtitle())
        page.addView(EqCard(act).addRow(launcherStatusRow))
    }

    /** Screen & Sound: the picture's layout and what sits beside it, then audio and lyrics. */
    private fun buildScreenSound(page: LinearLayout) {
        page.addView(eqSectionLabel(act, "Screen"))
        val card = EqCard(act)
        val modes = listOf(
            ScreenMode.SYSTEM_UI, ScreenMode.STATUS_HIDDEN, ScreenMode.NAV_HIDDEN, ScreenMode.FULLSCREEN,
        )
        val modeIndex = modes.indexOf(DisplayPrefs.mode(act)).let { if (it < 0) 3 else it }
        card.addRow(EqRow(
            act, "Screen layout",
            "Which car bars stay visible around CarPlay.",
            trailing = EqSegmented(act, listOf("Car bars", "No top", "No bottom", "Full"), modeIndex) { i ->
                DisplayPrefs.setMode(act, modes[i])
                onScreenChanged()
            },
        ))
        card.addRow(EqRow(
            act, "Sidebar",
            "Home, media and voice buttons beside CarPlay. Restarts CarPlay.",
            trailing = EqToggle(act, DisplayPrefs.sidebar(act)) { on ->
                DisplayPrefs.setSidebar(act, on)
                onSidebarChanged()
            },
        ))
        customTopBarControl = EqSegmented(act, listOf("Off", "On"), if (DisplayPrefs.customTopBar(act)) 1 else 0) { i ->
            DisplayPrefs.setCustomTopBar(act, i == 1)
            refreshDynamic()
        }
        card.addRow(EqRow(
            act, "Custom top bar",
            "GM-style controls above CarPlay when the car hides its top bar. Off keeps today's picture.",
            trailing = customTopBarControl,
        ))
        card.addRow(mediaPaneRow())
        page.addView(card)
        val bt = AudioRoute.bluetooth
        audioRow = EqRow(
            act, "Audio source", audioSubtitle(bt),
            trailing = EqSegmented(act, listOf("Adapter", "Bluetooth"), if (bt) 1 else 0) { i ->
                AudioRoute.set(act, i == 1)
                audioRow.setSubtitle(audioSubtitle(i == 1))
            },
        )
        page.addView(eqSectionLabel(act, "Sound"))
        page.addView(EqCard(act).addRow(audioRow).addRow(EqRow(
            act, "Play Spotify on connect",
            "Opens Spotify in CarPlay and presses play once CarPlay is up. Skipped if music is already playing.",
            trailing = EqToggle(act, AudioRoute.autoPlay(act)) { on -> AudioRoute.setAutoPlay(act, on) },
        )))
        page.addView(eqSectionLabel(act, "Lyrics"))
        page.addView(EqCard(act).also { lyricsRows(it) })
    }

    /**
     * Cluster: GM's own cluster (turns through the Car App Library), then the app's window on the
     * driver display, where it shows CarPlay's map and draws its own cards.
     */
    private fun buildCluster(page: LinearLayout) {
        page.addView(eqSectionLabel(act, "GM cluster"))
        val nav = wasidremin.gmccpa.nav.ClusterNav
        navRow = EqRow(
            act, "Turn-by-turn in the cluster", nav.statusText(act),
            // Shown even where it cannot work, so the subtitle can say why.
            trailing = EqToggle(act, nav.enabled) { on ->
                nav.setEnabled(act, on)
                navRow.setSubtitle(nav.statusText(act))
            },
        )
        val mapsArtRow = EqRow(
            act, "Album art while Maps is on screen",
            "During a route, the cluster shows the album art while full-screen Apple Maps is on the " +
                "centre screen, and the turns everywhere else. Reads the screen every 2.5 s.",
            trailing = EqToggle(act, nav.artWhileMaps) { on -> nav.setArtWhileMaps(act, on) },
        )
        val driver = wasidremin.gmccpa.nav.DriverDisplay.also { it.load(act) }
        lateinit var driverRow: EqRow
        driverRow = EqRow(
            act, "CarPlay map on the driver display", driver.statusText(act),
            trailing = EqToggle(act, driver.enabled) { on ->
                driver.setEnabled(act, on)
                driverRow.setSubtitle(driver.statusText(act))
            },
        )
        page.addView(EqCard(act).addRow(navRow).addRow(mapsArtRow))
        page.addView(eqSectionLabel(act, "Driver display"))
        val content = wasidremin.gmccpa.nav.ClusterContent.also { it.load(act) }
        lateinit var contentRow: EqRow
        contentRow = EqRow(
            act, "Driver display shows", content.statusText(),
            trailing = EqSegmented(act, wasidremin.gmccpa.nav.ClusterContent.Choice.values().map { it.short }, content.choice.ordinal) { i ->
                content.set(act, wasidremin.gmccpa.nav.ClusterContent.Choice.values()[i])
                contentRow.setSubtitle(content.statusText())
            },
        )
        page.addView(EqCard(act).addRow(driverRow).addRow(contentRow).addRow(clusterCaptureRow()))
        page.addView(directionsAreaCard())
        driverLayoutCards(page)
    }

    /** Settings ▸ Playback: Look up lyrics (with Clear) and Lyrics in the top bar. */
    private fun lyricsRows(card: EqCard) {
        val lyrics = wasidremin.gmccpa.lyrics.BarLyrics
        lyrics.load(act)
        fun subtitle() = "Timed lyrics for the media pane and the top bar. Song titles, artists, albums and lengths go to lrclib.net. " +
            lyrics.usageText(act)
        lateinit var row: EqRow
        val clear = eqButton(act, "Clear", EqButtonStyle.SECONDARY) {
            val n = lyrics.clear(act)
            row.setSubtitle(subtitle())
            android.widget.Toast.makeText(act, "Cleared $n songs' lyrics", android.widget.Toast.LENGTH_SHORT).show()
        }
        val controls = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(clear, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = EqTheme.px(20f) })
            addView(EqToggle(act, lyrics.lookup) { on -> lyrics.setLookup(act, on); row.setSubtitle(subtitle()) })
        }
        row = EqRow(act, "Look up lyrics", subtitle(), trailing = controls)
        card.addRow(row)
        card.addRow(EqRow(
            act, "Lyrics in the top bar",
            "The line being sung, in the custom top bar, while the car is in Park. Needs Look up lyrics.",
            trailing = EqToggle(act, lyrics.topBar) { on -> lyrics.setTopBar(act, on) },
        ))
    }

    /** Settings ▸ Display ▸ Media pane: over CarPlay, beside it on a side and width, or off. Applies at the next connect. */
    private fun mediaPaneRow(): EqRow {
        val pane = wasidremin.gmccpa.pane.SplitPane
        lateinit var row: EqRow
        lateinit var value: android.widget.TextView
        fun subtitle(c: wasidremin.gmccpa.pane.SplitPane.Choice) = when (c.side) {
            wasidremin.gmccpa.pane.SplitPane.Side.OFF -> "No pane; CarPlay uses the whole screen."
            wasidremin.gmccpa.pane.SplitPane.Side.OVER ->
                "The song, its controls and lyrics, in any gear, over the right ${pane.OVER_PERCENT}% of CarPlay. " +
                    "Tap the glass right of CarPlay to show or hide it."
            else -> "The song, its controls and lyrics, in any gear; CarPlay moves over to the other ${100 - c.percent}%. " +
                "Tap the glass right of CarPlay, or CarPlay's own button, to show or hide it. Applies at the next connect."
        }
        val choice = pane.choice(act)
        value = eqButton(act, choice.label, EqButtonStyle.SECONDARY) {
            val cur = pane.choice(act)
            val next = pane.CHOICES[(pane.CHOICES.indexOf(cur) + 1) % pane.CHOICES.size]
            pane.setChoice(act, next)
            value.text = next.label
            row.setSubtitle(subtitle(next))
        }
        row = EqRow(act, "Media pane", subtitle(choice), trailing = value)
        return row
    }

    private var shownPhones: List<PhoneBook.Phone>? = null
    private var shownPhonesNote: String? = "Adapter not connected. Paired iPhones show here once it is."
    private var shownCanChoose = false

    /**
     * The adapter's paired iPhones, in the order it connects them. Null [phones] shows [note] in
     * place of the list (adapter not connected, or it did not answer). [canChoose] is false on
     * adapter firmware without Connect and the connect order.
     */
    /** The USB bridge row's subtitle; from any thread. */
    fun setBridge(text: String) = act.runOnUiThread {
        if (::bridgeRow.isInitialized) bridgeRow.setSubtitle(text)
    }

    fun setPhones(phones: List<PhoneBook.Phone>?, note: String? = null, canChoose: Boolean = false) = act.runOnUiThread {
        uiLog.i("phones: ${phones?.let { l -> "${l.size} paired, connected=${l.count { it.connected }}, choose=$canChoose" } ?: note}")
        shownPhones = phones
        shownPhonesNote = note
        shownCanChoose = canChoose
        if (act.isFinishing || !::phonesCard.isInitialized) return@runOnUiThread
        fillPhones(phones, note, canChoose)
    }

    private fun fillPhones(phones: List<PhoneBook.Phone>?, note: String?, canChoose: Boolean) {
        phonesCard.removeAllViews()
        if (phones == null) {
            phonesCard.addRow(EqRow(act, "Paired iPhones", note))
            return
        }
        val ordered = canChoose && phones.size > 1
        val macs = phones.map { it.mac }
        for ((i, p) in phones.withIndex()) {
            val status = listOfNotNull(
                if (p.connected) "Connected" else "Paired",
                p.model,
                if (ordered && i == 0) "connects first" else null,
            ).joinToString(" · ")
            val buttons = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            fun add(v: View) = buttons.addView(v, LinearLayout.LayoutParams(-2, -2).apply {
                if (buttons.childCount > 0) marginStart = EqTheme.px(16f)
            })
            // Every row has the same slots, and an unused one is invisible rather than gone, so the
            // buttons line up down the list.
            if (ordered) {
                add(arrowButton("↑", "Connect ${p.label} earlier", i > 0) { onPhoneOrder(PhoneBook.moved(macs, p.mac, -1)) })
                add(arrowButton("↓", "Connect ${p.label} later", i < phones.lastIndex) { onPhoneOrder(PhoneBook.moved(macs, p.mac, 1)) })
            }
            if (canChoose) {
                add(eqButton(act, "Connect", EqButtonStyle.SECONDARY) { confirmConnect(p, phones.firstOrNull { it.connected }) }
                    .apply { visibility = if (p.connected) View.INVISIBLE else View.VISIBLE })
            }
            add(eqButton(act, "Forget", EqButtonStyle.DANGER) { confirmForgetOne(p) })
            phonesCard.addRow(EqRow(act, p.label, status, trailing = buttons))
        }
        if (phones.size > 1 && !canChoose) {
            phonesCard.addRow(EqRow(
                act, "Which iPhone connects",
                "The adapter takes whichever phone reaches it first. Choosing needs adapter firmware " +
                    "from 2026-11-05 or later.",
            ))
        }
        phonesCard.addRow(EqRow(
            act, if (phones.isEmpty()) "No iPhones paired" else "Add an iPhone",
            "On the iPhone: Settings ▸ General ▸ CarPlay ▸ Available Cars, then pick this car.",
        ))
    }

    private fun arrowButton(label: String, description: String, enabled: Boolean, onClick: () -> Unit) =
        eqButton(act, label, EqButtonStyle.SECONDARY, onClick).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, EqTheme.sp(28f))
            minWidth = EqTheme.px(88f)
            setPadding(EqTheme.px(20f), 0, EqTheme.px(20f), 0)
            contentDescription = description
            visibility = if (enabled) View.VISIBLE else View.INVISIBLE
        }

    private fun confirmConnect(p: PhoneBook.Phone, live: PhoneBook.Phone?) {
        val ends = live?.let { "CarPlay on ${it.label} ends now. " } ?: ""
        val fallback = live?.let { "If ${p.label} is out of range or its Bluetooth is off, ${it.label} reconnects instead." }
            ?: "If ${p.label} is out of range or its Bluetooth is off, the adapter goes back to its usual order."
        EqDialog.confirm(
            act, "Connect ${p.label}",
            ends + "The adapter restarts Bluetooth and Wi-Fi and calls ${p.label} first. CarPlay should " +
                "be back in about 20 seconds.\n\n$fallback\n\nThis is for now. The order in the list " +
                "decides which iPhone connects on the next drive.",
            "Connect", false,
        ) { onConnectPhone(p) }
    }

    private fun confirmForgetOne(p: PhoneBook.Phone) {
        val live = if (p.connected) "CarPlay on this iPhone ends now.\n\n" else ""
        EqDialog.confirm(
            act, "Forget ${p.label}",
            live + "The adapter restarts Bluetooth and Wi-Fi for about 15 seconds, so a connected " +
                "iPhone drops and reconnects.\n\nAlso forget this car on that iPhone: Settings ▸ " +
                "General ▸ CarPlay.",
            "Forget", true,
        ) { onForgetOnePhone(p) }
    }

    /**
     * Normal launcher status. Built-in Equinox templates are automatic; user templates
     * are optional overrides — so a device with no overrides is Ready, not uncalibrated.
     * Only a genuinely missing template set (no built-in AND no override) is a profile error.
     */
    private fun launcherStatusSubtitle(): String {
        val byUrl = wasidremin.gmccpa.ocbm.AdapterSession.canOpenUrl()
        val missing = wasidremin.gmccpa.match.ScreenModel.ROLES.filter {
            !(byUrl && wasidremin.gmccpa.av.CarPlayUrls.forRole(it) != null) &&
                !wasidremin.gmccpa.av.VisionLauncher.isCalibrated(act, it)
        }
        val text = launcherStatusText(missing)
        return if (byUrl) "Maps, Now Playing, Spotify and Phone open by command from the adapter. $text" else text
    }

    private fun refreshLauncherStatusRow() = act.runOnUiThread {
        if (act.isFinishing || act.isDestroyed) return@runOnUiThread
        if (::launcherStatusRow.isInitialized) {
            val err = launcherStatusSubtitle().startsWith("Profile error")
            launcherStatusRow.subtitleView.setTextColor(if (err) EqTheme.DANGER else EqTheme.TEXT_DIM)
            launcherStatusRow.setSubtitle(launcherStatusSubtitle())
        }
    }

    /**
     * The driver display's directions area ([wasidremin.gmccpa.nav.DriverDisplay.Margins]): four
     * edge margins, saved as they move, outlined on the driver display for 30 s after each change
     * while CarPlay's map is there, declared at the next connect.
     */
    private fun directionsAreaCard(): EqCard {
        val dd = wasidremin.gmccpa.nav.DriverDisplay
        val card = EqCard(act)
        var m = dd.margins(act)
        val bars = ArrayList<Pair<android.widget.SeekBar, TextView>>()
        lateinit var head: EqRow
        fun subtitle() = (if (m.isZero) "Whole display. " else "Kept clear: $m. ") +
            "Keeps CarPlay's map controls clear of the gauges; the map still fills the display. " +
            "The outline shows on the driver display while CarPlay's map is there. Applies at the next connect."
        fun apply(to: wasidremin.gmccpa.nav.DriverDisplay.Margins) {
            m = to
            dd.setMargins(act, m)
            listOf(m.left, m.top, m.right, m.bottom).forEachIndexed { i, v -> bars.getOrNull(i)?.first?.progress = v }
            head.setSubtitle(subtitle())
        }
        val buttons = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(eqButton(act, "GM layout", EqButtonStyle.SECONDARY) { apply(dd.GM_LAYOUT) })
            addView(eqButton(act, "Reset", EqButtonStyle.SECONDARY) { apply(wasidremin.gmccpa.nav.DriverDisplay.Margins()) },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = EqTheme.px(12f) })
        }
        head = EqRow(act, "Directions area on the driver display", subtitle(), trailing = buttons)
        card.addRow(head)
        val edges = listOf("Left", "Top", "Right", "Bottom")
        fun value(i: Int) = when (i) { 0 -> m.left; 1 -> m.top; 2 -> m.right; else -> m.bottom }
        for (i in edges.indices) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(EqTheme.px(28f), EqTheme.px(8f), EqTheme.px(28f), EqTheme.px(8f))
            }
            val label = EqTheme.text(act, 18f, EqTheme.TEXT).apply { text = "${edges[i]}: ${value(i)}%" }
            row.addView(label)
            val bar = android.widget.SeekBar(act).apply {
                max = dd.MAX_EDGE; progress = value(i); contentDescription = "Directions area ${edges[i]}"
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(b: android.widget.SeekBar, v: Int, user: Boolean) {
                        label.text = "${edges[i]}: $v%"
                        if (!user) return
                        m = when (i) { 0 -> m.copy(left = v); 1 -> m.copy(top = v); 2 -> m.copy(right = v); else -> m.copy(bottom = v) }
                        dd.setMargins(act, m)
                        head.setSubtitle(subtitle())
                    }
                    override fun onStartTrackingTouch(b: android.widget.SeekBar) = Unit
                    override fun onStopTrackingTouch(b: android.widget.SeekBar) = Unit
                })
            }
            row.addView(bar, LinearLayout.LayoutParams(-1, EqTheme.px(48f)))
            bars += bar to label
            card.addRow(row)
        }
        return card
    }

    /**
     * What the app draws over CarPlay's map on the driver display ([wasidremin.gmccpa.nav.ClusterLayout]):
     * a miniature to drag each card on, size buttons for the card last touched, and a switch per
     * card. All of it is the app's own drawing, so it applies at once; only lane guidance waits for
     * the next connect, because it changes what the adapter asks the iPhone for.
     */
    private fun driverLayoutCards(page: LinearLayout) {
        val dd = wasidremin.gmccpa.nav.DriverDisplay
        val cl = wasidremin.gmccpa.nav.ClusterLayout.also { it.load(act) }
        var card = EqCard(act)
        lateinit var sizeRow: EqRow
        lateinit var editor: wasidremin.gmccpa.nav.ClusterLayoutEditor
        fun sizeText() = cl.place(act, editor.picked).let { "${editor.picked.label} card: ${it.scale}% of its normal size. Touch a card above to pick it." }
        editor = wasidremin.gmccpa.nav.ClusterLayoutEditor(act) { sizeRow.setSubtitle(sizeText()) }
        card.addRow(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(EqTheme.px(28f), EqTheme.px(20f), EqTheme.px(28f), EqTheme.px(8f))
            addView(EqTheme.text(act, 18f, EqTheme.TEXT_DIM).apply {
                text = "Drag a card to where it should sit. The cards on the driver display follow, with samples for 30 s."
            })
            addView(editor, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(12f) })
        })
        fun resize(by: Int) {
            val p = cl.place(act, editor.picked)
            cl.setPlace(act, editor.picked, p.copy(scale = p.scale + by))
            sizeRow.setSubtitle(sizeText())
            editor.invalidate()
        }
        sizeRow = EqRow(act, "Size", sizeText(), trailing = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(eqButton(act, "Smaller", EqButtonStyle.SECONDARY) { resize(-cl.SCALE_STEP) })
            addView(eqButton(act, "Larger", EqButtonStyle.SECONDARY) { resize(cl.SCALE_STEP) },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = EqTheme.px(12f) })
            addView(eqButton(act, "Reset", EqButtonStyle.SECONDARY) {
                for (slot in wasidremin.gmccpa.nav.ClusterLayout.Slot.values()) cl.setPlace(act, slot, null)
                sizeRow.setSubtitle(sizeText())
                editor.invalidate()
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = EqTheme.px(12f) })
        })
        card.addRow(sizeRow)
        page.addView(eqSectionLabel(act, "Layout"))
        page.addView(card)
        card = EqCard(act)
        card.addRow(EqRow(
            act, "Turn card",
            "During a route: a small pill with the next turn, growing to a full card in the last half mile, with " +
                "the exit number and the turn after it. Apple's map shows the arrival time.",
            trailing = EqToggle(act, dd.turnCard) { on -> dd.setTurnCard(act, on); editor.invalidate() },
        ))
        card.addRow(EqRow(
            act, "Dock the turn card",
            "The turn card as a panel in the bottom-right corner, in the look of the gauges on the left. Drag its " +
                "corner above: it runs off the right and bottom edges as the gauges do.",
            trailing = EqToggle(act, cl.dock) { on -> cl.setDock(act, on); sizeRow.setSubtitle(sizeText()); editor.invalidate() },
        ))
        lateinit var lanesRow: EqRow
        lanesRow = EqRow(
            act, "Lane guidance", cl.lanesText(),
            trailing = EqToggle(act, cl.lanes) { on -> cl.setLanes(act, on); lanesRow.setSubtitle(cl.lanesText()) },
        )
        card.addRow(lanesRow)
        card.addRow(EqRow(
            act, "Now playing",
            "Cover, title, artist and progress. Stays open with no route; during a route it opens for 8 s on a " +
                "new song, then folds to the cover with the title beside it.",
            trailing = EqToggle(act, cl.media) { on -> cl.setMedia(act, on); editor.invalidate() },
        ))
        card.addRow(EqRow(
            act, "Call status",
            "A ringing or active call takes the now-playing card's place, with who it is and how long it has run.",
            trailing = EqToggle(act, cl.call) { on -> cl.setCall(act, on); editor.invalidate() },
        ))
        card.addRow(EqRow(
            act, "Drive",
            "Energy over the last mile as a ring, with ticks for the last 10 miles, this month and lifetime, and a " +
                "bar per mile. Opens while you are stopped, with no route, and when the trend flips; folded otherwise. " +
                "During a route the turn card shows the battery you will arrive with. From EV Insights' drive log.",
            trailing = EqToggle(act, cl.drive) { on -> cl.setDrive(act, on); editor.invalidate() },
        ))
        page.addView(eqSectionLabel(act, "Cards"))
        page.addView(card)
    }

    private var displayDraft: DisplayPrefs.Controls? = null
    private lateinit var scribbleRow: EqRow

    /**
     * Screen-outline calibration (replaces the retired dp inset sliders) plus the remaining
     * numeric controls. Eight dots dragged onto the visible edge ([GlassOutline]) give the
     * largest visible picture rectangle and the shape the top bar fits to; manual per-edge
     * guessing cannot express a sloped top and a swooping cutout, so the sliders are gone.
     */
    private fun buildDisplayOverrides(page: LinearLayout) {
        var pending = displayDraft ?: DisplayPrefs.controls(act)
        fun update(c: DisplayPrefs.Controls) { pending = c; displayDraft = c }
        fun slider(title: String, value: Int, max: Int, offset: Int = 0, changed: (Int) -> Unit) {
            val row = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val label = EqTheme.text(act, 18f, EqTheme.TEXT).apply { text = "$title: ${value + offset}" }
            row.addView(label)
            row.addView(android.widget.SeekBar(act).apply {
                this.max = max; progress = value; contentDescription = title
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: android.widget.SeekBar, v: Int, user: Boolean) {
                        if (user) { changed(v); label.text = "$title: ${v + offset}" }
                    }
                    override fun onStartTrackingTouch(bar: android.widget.SeekBar) = Unit
                    override fun onStopTrackingTouch(bar: android.widget.SeekBar) = Unit
                })
            }, LinearLayout.LayoutParams(-1, EqTheme.px(48f)))
            page.addView(row)
        }
        page.addView(eqSectionLabel(act, "Visible screen · outline"))
        scribbleRow = EqRow(act, "Screen outline", scribbleSubtitle(),
            trailing = eqButton(act, "Set outline", EqButtonStyle.SECONDARY) {
                act.startActivity(android.content.Intent(act, ScribbleActivity::class.java))
            })
        page.addView(EqCard(act).addRow(scribbleRow)
            .addRow(EqRow(act, "Clear outline",
                "Falls back to the measured cutout bounds.",
                trailing = eqButton(act, "Clear", EqButtonStyle.SECONDARY) {
                    DisplayPrefs.clearScribble(act)
                    displayDraft = null
                    pending = DisplayPrefs.controls(act)
                    refreshScribbleRow()
                })))
        if (wasidremin.gmccpa.ocbm.AdapterWifi.enabled(act)) {
            slider("CarPlay UI · Smaller ↔ Larger (50–150%)", pending.uiPercent - 50, 100, 50) {
                update(pending.copy(uiPercent = it + 50))
            }
        } else page.addView(EqRow(act, "CarPlay UI calibration",
            "Available with adapter Wi-Fi and an updated receiver. The legacy host uses static display capabilities."))
        page.addView(EqRow(act, "Video sizing", "Preserves decoded aspect ratio inside the safe rectangle.",
            trailing = EqSegmented(act, listOf("Stretch", "Fit to window"), if (pending.fit) 1 else 0) {
                update(pending.copy(fit = it == 1))
            }))
        page.addView(EqRow(act, "Floating stats", "Drag the pill during projection; position is remembered.",
            trailing = EqToggle(act, pending.stats) { update(pending.copy(stats = it)) }))
        page.addView(EqRow(act, "Save & Reconnect", "Saves calibration and restarts the projection session, not the app.",
            trailing = eqButton(act, "Save & Reconnect", EqButtonStyle.SECONDARY) {
                if (DisplayPrefs.saveControls(act, pending)) {
                    displayDraft = null
                    onRestart()
                } else android.widget.Toast.makeText(act, "Could not save display settings; session unchanged", android.widget.Toast.LENGTH_LONG).show()
            }))
        val scales = DisplayPrefs.SCALE_PRESETS
        val scaleLabels = scales.map { "$it%" }
        val scaleIndex = scales.indexOf(DisplayPrefs.uiScale(act)).coerceAtLeast(0)
        // The right-margin presets retired with the sliders: a trace already keeps the whole video
        // (and the YAML safe area, forced to 0 at capture) clear of the cutout. Untraced sessions
        // honour the last saved margin pref; there is no UI left to change it — trace instead.
        page.addView(eqSectionLabel(act, "Picture"))
        val card = EqCard(act)
        card.addRow(EqRow(
            act, "Legacy video resolution scale",
            "Changes coded resolution. Separate from the edge trace above.",
            trailing = EqSegmented(act, scaleLabels, scaleIndex) { i ->
                DisplayPrefs.setUiScale(act, scales[i])
                refreshDynamic()
            },
        ))
        page.addView(card)
        applyRow = EqRow(
            act, "Apply size now",
            "Restarts CarPlay so this size is what the iPhone lays out.",
            trailing = eqButton(act, "Restart", EqButtonStyle.SECONDARY) { onRestart() },
        )
        page.addView(applyRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(16f) })
        page.addView(EqRow(
            act, "Restart app",
            "Restarts the app so screen changes apply from a clean slate.",
            trailing = eqButton(act, "Restart", EqButtonStyle.SECONDARY) { onRestartApp() },
        ), LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(16f) })
    }



    /**
     * User-triggered support actions. No preferences live here except the session-only
     * diagnostics switch (never persisted, OFF after process restart).
     */
    private fun buildTroubleshooting(page: LinearLayout) {
        pairRow = EqRow(act, pairingCode.ifEmpty { " " })
        // 30sp, not 40: at the panel scale cap (1.35x) 40sp renders ~54sp and the row becomes
        // a billboard that pushes the Status card — and its Restart button — off screen.
        pairRow.titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, EqTheme.sp(30f))
        pairRow.titleView.typeface = EqTheme.Weight.LIGHT.face
        pairRow.titleView.letterSpacing = 0.18f
        page.addView(pairRow, 0)

        phoneRow = EqRow(act, phoneTitle(), lastDetail)
        restartButton = eqButton(act, if (restartBusy) "Restarting…" else "Restart", EqButtonStyle.SECONDARY) {
            onRestart()
        }
        val restart = EqRow(
            act, "Restart CarPlay",
            "Reconnects the adapter and your iPhone (about 15 s)",
            trailing = restartButton,
        )
        val stop = EqRow(act, "Stop CarPlay", onClick = { onStop() })
        startRow = EqRow(act, "Start", onClick = { onStart() })
        logStatusRow = EqRow(act, "Log status", logStatusText.ifEmpty { null }, onClick = { onLogAction(LogAction.STATUS) })
        page.addView(eqSectionLabel(act, "Status"))
        page.addView(EqCard(act).addRow(phoneRow).addRow(restart).addRow(stop).addRow(startRow)
            .addRow(logStatusRow)
            .addRow(EqRow(act, "Recover", "Tries the automatic recovery steps now.", onClick = { onRecover() })))

        captureCurrentButton = wasidremin.gmccpa.upload.UploadActionButton(
            ctx = act,
            defaultLabel = "Capture Current Screen",
            defaultIcon = wasidremin.gmccpa.upload.UploadIcon.CAMERA,
            onStatusAnnounce = { msg -> setDetail(msg) },
            onTrigger = {
                triggerCalibrationCapture(captureCurrentButton)
            },
        ).apply {
            onRetry = {
                wasidremin.gmccpa.upload.UploadCenter.drainPendingCaptures(act) { st ->
                    captureCurrentButton.setState(st)
                }
            }
        }
        captureCurrentRow = EqRow(
            act,
            "Capture current CarPlay screen",
            "Open the screen you want in CarPlay, then capture it.",
            trailing = captureCurrentButton,
        )
        captureFullButton = wasidremin.gmccpa.upload.UploadActionButton(
            ctx = act,
            defaultLabel = "Capture Full Screen",
            defaultIcon = wasidremin.gmccpa.upload.UploadIcon.CAMERA,
            onStatusAnnounce = { msg -> setDetail(msg) },
            onTrigger = {
                triggerCalibrationCapture(
                    captureFullButton,
                    wasidremin.gmccpa.av.CarPlayFrameGrabber.CaptureButtonKind.CAPTURE_FULL_WINDOW,
                    captureFullRow,
                    "Capturing full screen…",
                )
            },
        ).apply {
            onRetry = {
                wasidremin.gmccpa.upload.UploadCenter.drainPendingCaptures(act) { st ->
                    captureFullButton.setState(st)
                }
            }
        }
        captureFullRow = EqRow(
            act,
            "Capture full screen",
            "Whole window including the top bar and sidebar, with the CarPlay picture placed in it.",
            trailing = captureFullButton,
        )
        page.addView(eqSectionLabel(act, "Capture"))
        page.addView(EqCard(act).addRow(captureCurrentRow).addRow(captureFullRow).addRow(clusterCaptureRow()))

        uploadLogsButton = wasidremin.gmccpa.upload.UploadActionButton(
            ctx = act,
            defaultLabel = "Upload Logs",
            defaultIcon = wasidremin.gmccpa.upload.UploadIcon.UPLOAD,
            onStatusAnnounce = { msg -> setDetail(msg) },
            onTrigger = { onLogAction(LogAction.UPLOAD) },
        )
        // The range selector sits directly above the button it governs, and the button's own
        // subtitle names the live choice. A window nobody can see is how someone uploads an hour
        // expecting the whole day — the label and the action have to say the same thing.
        uploadWindow = wasidremin.gmccpa.upload.UploadCenter.logWindow(act)
        uploadWindowRow = EqRow(
            act,
            "Upload range",
            uploadRangeSubtitle(uploadWindow),
            trailing = EqSegmented(
                act,
                UploadWindow.values().map { it.label },
                uploadWindow.ordinal,
            ) { i ->
                val picked = UploadWindow.values()[i]
                uploadWindow = picked
                uploadWindowRow.setSubtitle(uploadRangeSubtitle(picked))
                uploadLogsRow.setSubtitle(uploadLogsSubtitle(picked))
                onUploadWindow(picked)
            },
        )
        uploadLogsRow = EqRow(
            act, "Upload logs",
            uploadLogsSubtitle(uploadWindow),
            trailing = uploadLogsButton,
        )
        page.addView(eqSectionLabel(act, "Logs"))
        page.addView(EqCard(act)
            .addRow(uploadWindowRow)
            .addRow(uploadLogsRow)
            .addRow(EqRow(act, "Export logs", onClick = { onLogAction(LogAction.EXPORT) }))
            .addRow(EqRow(act, "Clear logs", titleColor = EqTheme.DANGER, onClick = { confirmClearLogs() })))
        page.addView(eqSectionLabel(act, "Call tests"))
        page.addView(wasidremin.gmccpa.calltest.CallTestsCard(act).build())
    }

    /**
     * Capture the driver display: CarPlay's map with the app's cards over it, saved and uploaded like
     * the other captures. A new row each time it is asked for, since it sits on two pages.
     */
    private fun clusterCaptureRow(): EqRow {
        lateinit var row: EqRow
        lateinit var button: wasidremin.gmccpa.upload.UploadActionButton
        button = wasidremin.gmccpa.upload.UploadActionButton(
            ctx = act,
            defaultLabel = "Capture Driver Display",
            defaultIcon = wasidremin.gmccpa.upload.UploadIcon.CAMERA,
            onStatusAnnounce = { msg -> setDetail(msg) },
            onTrigger = {
                row.subtitleView.setTextColor(EqTheme.TEXT_DIM)
                row.setSubtitle("Capturing the driver display…")
                wasidremin.gmccpa.av.CarPlayFrameGrabber.saveDriverDisplayAsync(act, onUploadState = { st -> button.setState(st) }) { res ->
                    res.onSuccess { (saved, mapFrame) ->
                        val text = "Saved ${saved.id} · ${saved.metadata.windowWidth}x${saved.metadata.windowHeight}" +
                            if (mapFrame) "" else " · no map frame yet, cards only"
                        row.subtitleView.setTextColor(EqTheme.OK)
                        row.setSubtitle(text)
                        refreshUploadQueueRow()
                        setDetail(text)
                    }.onFailure { t ->
                        val text = "Error: ${t.message ?: t.javaClass.simpleName}"
                        row.subtitleView.setTextColor(EqTheme.DANGER)
                        row.setSubtitle(text)
                        setDetail(text)
                    }
                }
            },
        ).apply {
            onRetry = {
                wasidremin.gmccpa.upload.UploadCenter.drainPendingCaptures(act) { st -> button.setState(st) }
            }
        }
        row = EqRow(
            act, "Capture driver display",
            "CarPlay's map with the app's cards, as the app draws them. GM's gauges are drawn by the car " +
                "on top and are not in it. Needs the CarPlay map on the driver display.",
            trailing = button,
        )
        return row
    }

    fun setUploadLogsState(state: wasidremin.gmccpa.upload.UploadState) = act.runOnUiThread {
        if (::uploadLogsButton.isInitialized) {
            uploadLogsButton.setState(state)
        }
    }

    fun refreshUploadQueueRow() = act.runOnUiThread {
        if (act.isFinishing || act.isDestroyed) return@runOnUiThread
        if (::queueStatusRow.isInitialized) {
            queueStatusRow.setSubtitle(queueStatusSubtitle())
        }
        if (::dataUsedRow.isInitialized) dataUsedRow.setSubtitle(dataUsedSubtitle())
    }

    private fun dataUsedSubtitle(): String = wasidremin.gmccpa.upload.DataMeter.let {
        it.describe(it.ledger(act), it.limitMb(act))
    }

    private fun queueStatusSubtitle(): String {
        val n = wasidremin.gmccpa.upload.UploadCenter.pendingCaptureCount(act)
        val probePending = wasidremin.gmccpa.telemetry.ProbeUpload.pendingReportCount(act)
        val totalSaved = wasidremin.gmccpa.av.CarPlayFrameGrabber.listSavedCaptures(act).size
        val logs = wasidremin.gmccpa.upload.LogsOnWifi.pending(act)
            ?.let { " · " + wasidremin.gmccpa.upload.LogsOnWifi.describe(it) }.orEmpty()
        return "$n queued ($probePending telemetry) · $totalSaved screen captures saved (max ${wasidremin.gmccpa.av.CarPlayFrameGrabber.MAX_STORED_CAPTURES})$logs"
    }

    private fun triggerCalibrationCapture(
        button: wasidremin.gmccpa.upload.UploadActionButton,
        kind: wasidremin.gmccpa.av.CarPlayFrameGrabber.CaptureButtonKind =
            wasidremin.gmccpa.av.CarPlayFrameGrabber.CaptureButtonKind.CAPTURE_CURRENT,
        targetRow: EqRow = captureCurrentRow,
        progressText: String = "Capturing stable frame…",
    ) {
        if (!wasidremin.gmccpa.av.CarPlayFrameGrabber.isSessionActive()) {
            val err = "Error: No active CarPlay session"
            targetRow.setSubtitle(err)
            targetRow.subtitleView.setTextColor(EqTheme.DANGER)
            button.setState(wasidremin.gmccpa.upload.UploadState.Failed("No CarPlay session", retryable = true))
            setDetail(err)
            return
        }
        targetRow.subtitleView.setTextColor(EqTheme.TEXT_DIM)
        targetRow.setSubtitle(progressText)
        wasidremin.gmccpa.av.CarPlayFrameGrabber.runCalibrationCaptureAsync(
            ctx = act,
            buttonKind = kind,
            onUploadState = { st -> button.setState(st) },
        ) { res ->
            res.onSuccess { saved ->
                val summary = "Saved ${saved.id} · ${saved.metadata.classification.name} · " +
                    "home=${saved.metadata.homePressesUsed} · ${saved.metadata.msToStableFrame} ms"
                targetRow.subtitleView.setTextColor(EqTheme.OK)
                targetRow.setSubtitle(summary)
                refreshUploadQueueRow()
                setDetail(summary)
            }.onFailure { t ->
                val err = "Error: ${t.message ?: t.javaClass.simpleName}"
                targetRow.subtitleView.setTextColor(EqTheme.DANGER)
                targetRow.setSubtitle(err)
                setDetail(err)
            }
        }
    }

    private fun buildAdvanced(page: LinearLayout) {
        wifiToggle = EqToggle(act, adapterWifi) { want ->
            wifiToggle.setChecked(!want)
            EqDialog.confirm(
                act, "Adapter Wi-Fi", "Changing this restarts the connection",
                if (want) "Turn on" else "Turn off", false,
            ) {
                wifiToggle.setChecked(want)
                setAdapterWifi(want)
                onAdapterWifi(want)
            }
        }
        val chevron = eqValueChevron(act, hotspotLabel())
        hotspotValue = chevron.getChildAt(0) as TextView
        hotspotRow = EqRow(act, "Vehicle hotspot", trailing = chevron, onClick = { showCredentialsDialog() })
        infoRow = EqRow(
            act, "Adapter info",
            adapterInfoText.ifEmpty { null },
            trailing = EqChevron(act),
            onClick = { onBoxAction(BoxAction.INFO) },
        )
        announceControl = EqSegmented(act, listOf("Off", "Image", "No medium"), announceIndex) { i ->
            onUsbAnnounce(i)
        }
        announceControl.isEnabled = false
        announceControl.alpha = 0.4f
        announceRow = EqRow(act, "USB announce", "Adapter not connected", trailing = announceControl)
        bridgeRow = EqRow(
            act, "USB bridge", "Checking…",
            trailing = eqButton(act, "Open Files", EqButtonStyle.SECONDARY) { onOpenFiles() },
        )
        announceRetriesControl = EqSegmented(act, listOf("0", "1", "2"), announceRetriesIndex) { i ->
            onUsbAnnounceRetries(i)
        }
        announceRetriesControl.isEnabled = false
        announceRetriesControl.alpha = 0.4f
        announceRetriesRow = EqRow(act, "Announce retries", "Adapter not connected", trailing = announceRetriesControl)
        page.addView(eqSectionLabel(act, "Adapter"))
        page.addView(EqCard(act)
            .addRow(EqRow(act, "Adapter Wi-Fi", "The iPhone joins the adapter.", trailing = wifiToggle))
            .addRow(hotspotRow)
            .addRow(infoRow)
            .addRow(bridgeRow)
            .addRow(announceRow)
            .addRow(announceRetriesRow)
            .addRow(EqRow(act, "Restart adapter Wi-Fi", titleColor = EqTheme.DANGER, onClick = { confirmRestartWifi() }))
            .addRow(EqRow(act, "Reboot adapter", titleColor = EqTheme.DANGER, onClick = { confirmReboot() })))

        page.addView(eqSectionLabel(act, "Display overrides"))
        buildDisplayOverrides(page)

        val granted = readLogsGranted()
        val scope = CapturePrefs.scope(act)
        scopeControl = EqSegmented(act, listOf("This app", "Whole car"), if (scope == LogCapture.Scope.WHOLE_OS) 1 else 0) { i ->
            onScopePicked(i)
        }
        scopeRow = EqRow(act, "Log scope", scopeSubtitle(granted), trailing = scopeControl)
        page.addView(eqSectionLabel(act, "Logging"))
        page.addView(EqCard(act)
            .addRow(scopeRow)
            .addRow(EqRow(act, "USB probe", onClick = { onLogAction(LogAction.USB_PROBE) }))
            .addRow(EqRow(act, "Vehicle Telemetry Probe", "Read-only census of the car's properties · parked only", onClick = { onLogAction(LogAction.VEHICLE_PROBE) })))

        baseUrlRow = EqRow(
            act,
            "Server connection",
            wasidremin.gmccpa.upload.UploadCenter.baseUrl(act),
            trailing = eqValueChevron(act, "Configure / Demo"),
            onClick = {
                wasidremin.gmccpa.upload.UploadCenter.showUploadSettingsAndDemoDialog(act) { updated ->
                    baseUrlRow.setSubtitle(updated)
                }
            },
        )
        testConnectionButton = wasidremin.gmccpa.upload.UploadActionButton(
            ctx = act,
            defaultLabel = "Test Connection",
            defaultIcon = wasidremin.gmccpa.upload.UploadIcon.CONNECTION,
            onStatusAnnounce = { msg -> setDetail(msg) },
            onTrigger = {
                wasidremin.gmccpa.upload.UploadCenter.testConnection(act) { st ->
                    testConnectionButton.setState(st)
                }
            },
        )
        testConnectionRow = EqRow(
            act,
            "Test Connection",
            "Verifies server reachability and drains any queued captures",
            trailing = testConnectionButton,
        )
        drainQueueButton = wasidremin.gmccpa.upload.UploadActionButton(
            ctx = act,
            defaultLabel = "Retry Queued",
            defaultIcon = wasidremin.gmccpa.upload.UploadIcon.UPLOAD,
            onStatusAnnounce = { msg -> setDetail(msg) },
            onTrigger = {
                wasidremin.gmccpa.upload.UploadCenter.drainPendingCaptures(act) { st ->
                    drainQueueButton.setState(st)
                }
            },
        )
        queueStatusRow = EqRow(
            act,
            "Pending uploads",
            queueStatusSubtitle(),
            trailing = drainQueueButton,
        )
        val autoTelemetryToggle = EqToggle(act, wasidremin.gmccpa.telemetry.ProbeUpload.isAutoUploadEnabled(act)) { on ->
            wasidremin.gmccpa.telemetry.ProbeUpload.setAutoUploadEnabled(act, on)
            // Recording carries on either way; this only decides whether segments leave the car.
            wasidremin.gmccpa.telemetry.ProbeService.ensureAutoStarted(act)
            setDetail(if (on) "EV Insights auto-upload on" else "EV Insights auto-upload off. Drives are still logged on the car.")
            refreshUploadQueueRow()
        }
        val meter = wasidremin.gmccpa.upload.DataMeter
        val limits = meter.LIMIT_CHOICES_MB
        dataUsedRow = EqRow(
            act,
            "Car data used this month",
            dataUsedSubtitle(),
            trailing = EqSegmented(
                act,
                limits.map { if (it > 0) "$it MB" else "No limit" },
                limits.indexOf(meter.limitMb(act)).let { if (it < 0) limits.indexOf(meter.DEFAULT_LIMIT_MB) else it },
            ) { i ->
                meter.setLimitMb(act, limits[i])
                dataUsedRow.setSubtitle(dataUsedSubtitle())
                // A raised limit lets a paused queue go at once.
                wasidremin.gmccpa.telemetry.ProbeUpload.enqueueAndDrain(act)
            },
        )
        val autoGpsToggle = EqToggle(act, wasidremin.gmccpa.telemetry.ProbeUpload.isAutoRawLocationEnabled(act)) { on ->
            wasidremin.gmccpa.telemetry.ProbeUpload.setAutoRawLocationEnabled(act, on)
            wasidremin.gmccpa.telemetry.ProbeService.ensureAutoStarted(act)
            setDetail(if (on) "EV Insights route GPS included in stream" else "EV Insights route GPS off")
        }
        wasidremin.gmccpa.upload.UploadCenter.removeQueueListener(queueListener)
        wasidremin.gmccpa.upload.UploadCenter.addQueueListener(queueListener)
        page.addView(eqSectionLabel(act, "Upload server & EV Insights"))
        page.addView(EqCard(act)
            .addRow(EqRow(
                act,
                "EV Insights auto-upload",
                "Sends each driven minute and a heartbeat while parked or charging. Off keeps everything on the car; the drive log still records.",
                trailing = autoTelemetryToggle,
            ))
            .addRow(dataUsedRow)
            .addRow(EqRow(
                act,
                "Include route GPS in EV Insights",
                "Includes local-only GPS speed and coordinates in telemetry uploads",
                trailing = autoGpsToggle,
            ))
            .addRow(baseUrlRow)
            .addRow(testConnectionRow)
            .addRow(queueStatusRow))

        page.addView(eqSectionLabel(act, "App"))
        page.addView(EqCard(act).addRow(EqRow(
            act, "Forget all iPhones",
            "Clears every pairing on the adapter and this app. Also forget this car on each iPhone.",
            titleColor = EqTheme.DANGER,
            onClick = { confirmForget() },
        )).addRow(EqRow(
            act, "Close app",
            titleColor = EqTheme.DANGER,
            onClick = {
                EqDialog.confirm(
                    act, "Close app", "Stops CarPlay and closes the app", "Close", true,
                ) { onClose() }
            },
        )))
    }

    private fun showTab(index: Int) {
        selectedTab = index
        refreshLauncherStatusRow()
        if (::pages.isInitialized) {
            pages.forEachIndexed { i, p -> p.visibility = if (i == index) View.VISIBLE else View.GONE }
        }
        if (::rail.isInitialized) rail.select(index)
        settingsBar?.selected = if (index == TAB_INSIGHTS) BarTarget.INSIGHTS else BarTarget.APP_SETTINGS
        if (::scroller.isInitialized) scroller.scrollTo(0, 0)
        if (index == TAB_CARPLAY) onPhonesWanted()
        if (index == TAB_ADVANCED) onAdvancedOpened()
        if (index == TAB_INSIGHTS && ::insightsPage.isInitialized) insightsPage.refresh()
        auditLayout(index)
    }

    private fun firstText(v: View): String? {
        if (v is TextView && v.text.isNotBlank()) return v.text.toString()
        if (v is ViewGroup) for (i in 0 until v.childCount) firstText(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun auditLayout(index: Int) {
        if (!::pages.isInitialized || !auditedTabs.add(index)) return
        val page = pages[index]
        page.post {
            // Audit against the calibration frame's right edge, not the window: settings
            // must fit the same visible glass the video uses, not just dodge system bars.
            val hostLp = settingsHost.layoutParams as? FrameLayout.LayoutParams
            val edge = (hostLp?.leftMargin ?: 0) + settingsHost.width - safeRight
            uiLog.i("layout audit tab=$index pageH=${page.height} safeRightEdge=$edge")
            val loc = IntArray(2)
            for (i in 0 until page.childCount) {
                val c = page.getChildAt(i)
                if (c.visibility != View.VISIBLE) continue
                c.getLocationInWindow(loc)
                val right = loc[0] + c.width
                uiLog.i("layout audit tab=$index #$i ${c.javaClass.simpleName} y=${loc[1]} h=${c.height} " +
                    "x=${loc[0]}..$right '${firstText(c)?.take(40) ?: ""}'" +
                    (if (right > edge) " PAST_SAFE_RIGHT" else ""))
            }
        }
    }

    /** Reflect a read or a verified write. [subtitle] is replaced when the link is down. */
    fun setUsbAnnounce(linked: Boolean, index: Int, subtitle: String) = act.runOnUiThread {
        if (!::announceControl.isInitialized) return@runOnUiThread
        announceIndex = index
        announceControl.isEnabled = linked
        announceControl.alpha = if (linked) 1f else 0.4f
        announceControl.setSelected(index, animate = false)
        announceRow.setSubtitle(if (linked) subtitle.ifEmpty { null } else "Adapter not connected")
    }

    fun usbAnnounceIndex(): Int = announceIndex

    fun setUsbAnnounceRetries(linked: Boolean, index: Int, subtitle: String) = act.runOnUiThread {
        if (!::announceRetriesControl.isInitialized) return@runOnUiThread
        announceRetriesIndex = index
        announceRetriesControl.isEnabled = linked
        announceRetriesControl.alpha = if (linked) 1f else 0.4f
        announceRetriesControl.setSelected(index, animate = false)
        announceRetriesRow.setSubtitle(if (linked) subtitle.ifEmpty { null } else "Adapter not connected")
    }

    fun usbAnnounceRetriesIndex(): Int = announceRetriesIndex

    fun confirmAnnounceReboot(onConfirm: () -> Unit, onCancel: () -> Unit) = act.runOnUiThread {
        EqDialog.confirm(
            act,
            "Adapter will restart",
            "CarPlay disconnects for about 30 seconds.",
            "Restart",
            false,
            onCancel = onCancel,
            onConfirm = onConfirm,
        )
    }

    /** A GONE row inside an [EqCard] leaves its hairline. Hide that divider with the row. */
    private fun setRowShown(row: View, shown: Boolean) {
        row.visibility = if (shown) View.VISIBLE else View.GONE
        val parent = row.parent as? LinearLayout ?: return
        val i = parent.indexOfChild(row)
        if (i > 0) parent.getChildAt(i - 1).visibility = row.visibility
    }

    fun openTabFromScript(index: Int, subtab: Int = 0, scrollY: Int = 0) = act.runOnUiThread {
        settingsTouchedAt = android.os.SystemClock.elapsedRealtime()
        introRestore?.let { intro.removeCallbacks(it) }
        hideIntroLayer()
        showTab(index.coerceIn(0, TAB_NAMES.size - 1))
        if (index == TAB_INSIGHTS && ::insightsPage.isInitialized) {
            insightsPage.selectSubtab(subtab)
        }
        // Bench only: a section far down a page, for a layout check without touch.
        if (scrollY > 0 && ::scroller.isInitialized) scroller.post { scroller.scrollTo(0, scrollY) }
    }

    private fun openIntroSettings() {
        uiLog.i("intro: settings")
        settingsTouchedAt = android.os.SystemClock.elapsedRealtime()
        introRestore?.let { intro.removeCallbacks(it) }
        hideIntroLayer()
        showTab(0)
    }

    private fun hideIntroLayer() {
        intro.visibility = View.GONE
        introGear.visibility = View.GONE
        introRestart.visibility = View.GONE
    }

    private fun showIntroLayer() {
        intro.visibility = View.VISIBLE
        introGear.visibility = View.VISIBLE
        syncIntroRestart()
    }

    /** The intro Restart button exists only for the failure screen (see [introRestart]). */
    private fun syncIntroRestart() {
        introRestart.visibility =
            if (intro.visibility == View.VISIBLE && lastState == LinkState.FAILED) View.VISIBLE else View.GONE
    }

    fun setAdapterWifi(on: Boolean) {
        adapterWifi = on
        if (::wifiToggle.isInitialized && wifiToggle.checked != on) wifiToggle.setChecked(on)
        if (::hotspotValue.isInitialized) hotspotValue.text = hotspotLabel()
        if (::hotspotRow.isInitialized) setRowShown(hotspotRow, !on)
    }

    private fun hotspotLabel(): String =
        if (ssid.isBlank() || pass.isBlank()) "Not set" else "$ssid · ch $chan"

    fun showCredentialsDialog() {
        val dialog = Dialog(act)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.6f)
        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = EqTheme.rounded(0xFF111826.toInt(), 28f, EqTheme.CARD_LINE)
            setPadding(EqTheme.px(40f), EqTheme.px(36f), EqTheme.px(40f), EqTheme.px(28f))
            addView(EqTheme.text(act, 28f, EqTheme.TEXT, EqTheme.Weight.LIGHT).apply { text = "Vehicle hotspot" })
        }
        fun field(labelText: String, value: String, numeric: Boolean): EditText {
            body.addView(EqTheme.text(act, 14f, EqTheme.ACCENT, EqTheme.Weight.MEDIUM).apply {
                text = labelText.uppercase()
                letterSpacing = 0.12f
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(18f) })
            val e = EditText(act).apply {
                setText(value)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, EqTheme.sp(22f))
                setTextColor(EqTheme.TEXT)
                setHintTextColor(EqTheme.TEXT_FAINT)
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER
                    else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                setSingleLine()
                background = null
            }
            body.addView(e, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(6f) })
            return e
        }
        val eSsid = field("Hotspot SSID", ssid, false)
        val ePass = field("Passphrase", pass, false)
        val eChan = field("Channel", chan, true)
        val buttons = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        buttons.addView(eqButton(act, "Cancel", EqButtonStyle.SECONDARY) { dialog.dismiss() })
        buttons.addView(eqButton(act, "Save", EqButtonStyle.PRIMARY) {
            ssid = eSsid.text.toString().trim()
            pass = ePass.text.toString().trim()
            chan = eChan.text.toString().trim()
            persistCredentials()
            if (::hotspotValue.isInitialized) hotspotValue.text = hotspotLabel()
            onCredentials(ssid, pass, chan)
            dialog.dismiss()
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = EqTheme.px(16f) })
        body.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(32f) })
        dialog.setContentView(body, ViewGroup.LayoutParams(safeDialogWidth(EqTheme.px(720f)), ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
    }

    fun setCredentials(newSsid: String?, newPass: String?, newChan: String?) = act.runOnUiThread {
        if (newSsid == null && newPass == null && newChan == null) return@runOnUiThread
        newSsid?.let { ssid = it }
        newPass?.let { pass = it }
        newChan?.let { chan = it }
        persistCredentials()
        if (::hotspotValue.isInitialized) hotspotValue.text = hotspotLabel()
    }

    /** The walk-away sentence is the status line. The generic waiting label has no page age. */
    private fun introStatus(state: LinkState, detail: String): String? =
        if (detail == "Waiting for your iPhone" || detail.startsWith("Looking for your iPhone")) detail
        else StartupAnimationView.statusFor(state)

    /**
     * The one-time Always line wins while it is up: the driver is looking at the permission
     * dialog, and the next phase report must not clear it. A failure detail is next, then the
     * display-reset note.
     */
    private fun startupDetail(state: LinkState, detail: String): String? = when {
        alwaysHint != null -> alwaysHint
        state == LinkState.FAILED -> detail
        displayResetDetail != null -> displayResetDetail
        else -> null
    }

    fun setState(state: LinkState, detail: String) = act.runOnUiThread {
        if (act.isFinishing) return@runOnUiThread
        val stage = StartupAnimationView.stageFor(state)
        val prevStage = lastAnimStage
        lastState = state
        lastDetail = detail
        if (state == LinkState.LIVE) {
            displayResetDetail = null
            alwaysHint = null
        }
        refreshDynamic()
        if (sessionIsLive() || state == LinkState.LIVE) {
            introRestore?.let { intro.removeCallbacks(it) }
            hideIntroLayer()
            lastAnimStage = stage
            return@runOnUiThread
        }
        val status = introStatus(state, detail)
        val animDetail = startupDetail(state, detail)
        intro.setStage(stage, status, animDetail)
        if (intro.visibility != View.VISIBLE && prevStage != null && stage != prevStage) {
            val idle = android.os.SystemClock.elapsedRealtime() - settingsTouchedAt
            if (idle >= 30_000L) showIntroLayer()
        }
        lastAnimStage = stage
    }

    fun setPhoneName(name: String?) = act.runOnUiThread {
        val clean = name?.trim()?.takeIf { it.isNotEmpty() } ?: return@runOnUiThread
        DisplayPrefs.setLastPhoneName(act, clean)
        intro.setPhoneName(clean)
        refreshDynamic()
    }

    fun concealIntro() {
        introRestore?.let { intro.removeCallbacks(it) }
        hideIntroLayer()
    }

    fun resetIntro() = act.runOnUiThread {
        if (act.isFinishing) return@runOnUiThread
        introRestore?.let { intro.removeCallbacks(it) }
        intro.reset()
        showIntroLayer()
    }

    fun setPairingCode(code: String) = act.runOnUiThread {
        if (act.isFinishing) return@runOnUiThread
        pairingCode = code
        refreshDynamic()
    }

    fun setDetail(detail: String) = act.runOnUiThread {
        if (act.isFinishing) return@runOnUiThread
        lastDetail = detail
        refreshDynamic()
    }

    fun setRestartBusy(busy: Boolean) = act.runOnUiThread {
        if (act.isFinishing) return@runOnUiThread
        restartBusy = busy
        if (::restartButton.isInitialized) restartButton.text = if (busy) "Restarting…" else "Restart"
        // The strip carries its own Restart copy while FAILED — keep its label in step.
        refreshDynamic()
    }

    /** Refreshes the strip. Back to CarPlay is shown only while a session is live. */
    fun setReturnToCarPlay(show: Boolean) = act.runOnUiThread {
        if (act.isFinishing) return@runOnUiThread
        // The CarPlay screen passes true when it wants the way back. The button still
        // follows the session, so a stale true cannot show it after the session ends.
        if (show && !(sessionIsLive() || lastState == LinkState.LIVE)) return@runOnUiThread
        refreshDynamic()
    }

    fun setLogStatus(text: String) = act.runOnUiThread {
        if (act.isFinishing) return@runOnUiThread
        logStatusText = text
        if (::logStatusRow.isInitialized) logStatusRow.setSubtitle(text)
    }

    fun setAdapterInfo(text: String) = act.runOnUiThread {
        if (act.isFinishing) return@runOnUiThread
        adapterInfoText = text
        if (::infoRow.isInitialized) infoRow.setSubtitle(text)
    }

    fun noteDisplayReset() = act.runOnUiThread {
        if (act.isFinishing) return@runOnUiThread
        displayResetDetail = "Display settings were reset"
        if (!sessionIsLive() && lastState != LinkState.LIVE) {
            val status = introStatus(lastState, lastDetail)
            intro.setStage(StartupAnimationView.stageFor(lastState), status, startupDetail(lastState, lastDetail))
        }
    }

    /**
     * First system-dispatched attach with permission not yet held. One line on the startup
     * animation, then the flag is saved so a later attach does not repeat it.
     */
    fun noteAlwaysHint() = act.runOnUiThread {
        if (act.isFinishing) return@runOnUiThread
        if (!UsbIdentity.shouldShowAlwaysHint(act)) return@runOnUiThread
        if (sessionIsLive() || lastState == LinkState.LIVE) return@runOnUiThread
        alwaysHint = "Tick 'Always' so CarPlay starts without asking"
        val status = introStatus(lastState, lastDetail)
        intro.setStage(StartupAnimationView.stageFor(lastState), status, alwaysHint)
        if (intro.visibility != View.VISIBLE) showIntroLayer()
        UsbIdentity.markAlwaysHintShown(act)
    }

    private fun refreshDynamic() {
        if (!::strip.isInitialized) return
        syncIntroRestart()
        val detail = if (lastState == LinkState.LIVE) liveDetail() else lastDetail
        strip.set(lastState.label, detail, stripColor(lastState))
        val sessionLive = sessionIsLive() || lastState == LinkState.LIVE
        strip.setAction(when (stripActionFor(sessionLive, lastState)) {
            StripAction.BACK -> eqButton(act, "Back to CarPlay", EqButtonStyle.PRIMARY) { onReturnToCarPlay() }
            StripAction.RESTART -> eqButton(act, if (restartBusy) "Restarting…" else "Restart", EqButtonStyle.PRIMARY) { onRestart() }
            StripAction.DONE -> eqButton(act, "Done", EqButtonStyle.SECONDARY) { showIntroLayer() }
        })
        if (::phoneRow.isInitialized) {
            phoneRow.titleView.text = phoneTitle()
            phoneRow.setSubtitle(lastDetail)
        }
        if (::pairRow.isInitialized) {
            pairRow.titleView.text = pairingCode
            pairRow.visibility = if (pairingCode.isEmpty()) View.GONE else View.VISIBLE
        }
        if (::startRow.isInitialized) {
            setRowShown(startRow, lastState == LinkState.IDLE || lastState == LinkState.STOPPED)
        }
        if (::applyRow.isInitialized) {
            applyRow.visibility = if (sizePending()) View.VISIBLE else View.GONE
        }
        if (::hotspotRow.isInitialized) {
            setRowShown(hotspotRow, !adapterWifi)
        }
        // Bound/unbound changes with no Settings interaction, so re-read it with the rest.
        if (::navRow.isInitialized) navRow.setSubtitle(wasidremin.gmccpa.nav.ClusterNav.statusText(act))
        // Changes when the driver comes back from the car's assistant picker.
        if (::voiceRow.isInitialized) voiceRow.setSubtitle(wasidremin.gmccpa.voice.AssistantRole.statusText(act))
        if (::customTopBarControl.isInitialized) {
            customTopBarControl.setSelected(if (DisplayPrefs.customTopBar(act)) 1 else 0, animate = false)
        }
        refreshScribbleRow()
    }

    fun onActivityResume() {
        refreshDynamic()
    }

    private fun phoneTitle(): String =
        DisplayPrefs.lastPhoneName(act) ?: "No phone connected"

    private fun liveDetail(): String {
        val phone = DisplayPrefs.lastPhoneName(act) ?: "iPhone"
        return "$phone · ${VideoFrame.width}×${VideoFrame.height} at ${VideoFrame.uiScalePercent}%"
    }

    private fun stripColor(state: LinkState): Int = when (state) {
        LinkState.LIVE -> EqTheme.OK
        LinkState.FAILED -> EqTheme.DANGER
        LinkState.PHONE_DETECTED, LinkState.PAIRING, LinkState.STARTING,
        LinkState.WAITING, LinkState.SEARCHING, LinkState.CLAIMING, LinkState.RESTARTING -> EqTheme.ACCENT
        else -> EqTheme.TEXT_FAINT
    }

    private fun scribbleSubtitle(): String =
        if (DisplayPrefs.scribbleEnabled(act))
            outlineSummary() + " Picture takes effect at the next connection."
        else "No outline — using the measured cutout bounds. Set the outline to fit CarPlay and the top bar exactly."

    /** Short summary for the row; the full note (with all eight points) stays in the log. */
    private fun outlineSummary(): String {
        val note = DisplayPrefs.scribbleNote(act) ?: return "Outline saved."
        if (!note.startsWith("outline ")) return "Old paint trace saved — set the outline to replace it."
        return "Outline saved: " + note.substringAfter(" rect ").substringBefore(" pts").let { "picture $it." }
    }

    private fun refreshScribbleRow() = act.runOnUiThread {
        if (act.isFinishing || act.isDestroyed) return@runOnUiThread
        if (::scribbleRow.isInitialized) scribbleRow.setSubtitle(scribbleSubtitle())
        // A just-saved (or cleared) outline reshapes the bar now, without waiting for a relayout.
        settingsBar?.outline = DisplayPrefs.glassOutline(act, act.window.decorView.width.toFloat(),
            act.window.decorView.height.toFloat())
    }

    private fun sizePending(): Boolean {
        if (!(sessionIsLive() || lastState == LinkState.LIVE)) return false
        if (DisplayPrefs.uiScale(act) != VideoFrame.uiScalePercent) return true
        // A trace forces the safe-area inset to 0 at capture; the legacy pref is then stale by
        // design and must not hold the Apply row visible.
        if (DisplayPrefs.scribbleEnabled(act)) return false
        val advertised = DisplayScale.even(DisplayPrefs.safeRightPx(act), VideoFrame.uiScalePercent)
            .coerceIn(0, (VideoFrame.width - 2).coerceAtLeast(0)) and 1.inv()
        return advertised != VideoFrame.safeRightPx
    }

    private fun audioSubtitle(bluetooth: Boolean): String =
        if (bluetooth) "The car plays your iPhone over Bluetooth; this app stays quiet."
        else "CarPlay audio plays through the car as its own source."

    private fun readLogsGranted(): Boolean =
        act.checkSelfPermission(android.Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    private fun scopeSubtitle(granted: Boolean): String =
        if (granted) "Whole car includes the rest of the head unit."
        else "Whole car needs a one-time adb grant"

    private fun onScopePicked(index: Int) {
        if (index == 1 && !readLogsGranted()) {
            scopeRow.setSubtitle("Whole car needs a one-time adb grant")
            val current = if (CapturePrefs.scope(act) == LogCapture.Scope.WHOLE_OS) 1 else 0
            scopeControl?.setSelected(current)
            return
        }
        scopeRow.setSubtitle(scopeSubtitle(true))
        onCaptureScope(if (index == 1) LogCapture.Scope.WHOLE_OS else LogCapture.Scope.OWN_PROCESS)
    }

    private fun confirmForget() {
        val running = if (sessionIsLive()) "A CarPlay session is RUNNING and this will end it.\n\n" else ""
        EqDialog.confirm(
            act, "Forget All iPhones",
            running + "Clear every iPhone's pairing on both the adapter and this app?\n\nYou must ALSO " +
                "forget this car on each iPhone — a one-sided pairing makes the next attempt fail in a " +
                "way that looks like broken encryption.",
            "Forget All", true,
        ) { onBoxAction(BoxAction.FORGET_PHONE) }
    }

    private fun confirmRestartWifi() {
        EqDialog.confirm(
            act, "Restart Wi-Fi",
            "Restart the adapter's wireless stack?\n\nThe radios go down for about five " +
                "seconds, Bluetooth takes ~12 s to come back, and a live session drops. This is " +
                "also the only way to re-apply changed hotspot credentials without unplugging.",
            "Restart Wi-Fi", true,
        ) { onBoxAction(BoxAction.RESTART_WIFI) }
    }

    private fun confirmReboot() {
        EqDialog.confirm(
            act, "Reboot Box",
            "Reboot the adapter?\n\nAny live CarPlay session drops immediately and the box " +
                "needs a fresh claim afterwards.",
            "Reboot Box", true,
        ) { onBoxAction(BoxAction.REBOOT) }
    }

    private fun confirmClearLogs() {
        EqDialog.confirm(
            act, "Clear Logs",
            "Delete all captured log files on this head unit and restart capture?\n\n" +
                "Logs already uploaded or exported are not affected. Use this before a clean " +
                "reproduction so the next upload contains only the new run.",
            "Clear", true,
        ) { onLogAction(LogAction.CLEAR) }
    }

    private companion object {
        const val CRED_PREFS = "hotspot_credentials"
        const val KEY_SSID = "ssid"
        const val KEY_PASS = "pass"
        const val KEY_CHAN = "chan"
    }
}

/**
 * Settings control drawn on the startup light bar. A stroke gear in the animation's blue, no
 * filled chip, so it sits in the corner without looking like a second screen.
 */
internal class IntroSettingsButton(
    ctx: android.content.Context,
    private val onTap: () -> Unit,
) : View(ctx) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4C9BFF.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    init {
        contentDescription = "Settings"
        isClickable = true
        setOnClickListener { onTap() }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = minOf(width, height) * 0.28f
        ink.strokeWidth = outer * 0.14f
        canvas.drawCircle(cx, cy, outer * 0.42f, ink)
        canvas.drawCircle(cx, cy, outer * 0.78f, ink)
        val teeth = 8
        for (i in 0 until teeth) {
            val a = Math.toRadians(i * (360.0 / teeth) - 90.0)
            val c = Math.cos(a).toFloat()
            val s = Math.sin(a).toFloat()
            canvas.drawLine(
                cx + c * outer * 0.62f, cy + s * outer * 0.62f,
                cx + c * outer, cy + s * outer, ink,
            )
        }
    }
}

/**
 * One-tap recovery on the intro failure screen: a circular arrow, same 72dp-class
 * footprint as [IntroSettingsButton], bottom-right. Shown only while FAILED (driven by
 * `LauncherUi.syncIntroRestart`); tapping it runs the same Restart Session path as the
 * Troubleshooting-page button.
 */
internal class IntroRestartButton(
    ctx: android.content.Context,
    private val onTap: () -> Unit,
) : View(ctx) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4C9BFF.toInt()
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    init {
        contentDescription = "Restart CarPlay"
        isClickable = true
        setOnClickListener { onTap() }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * 0.30f
        ink.strokeWidth = r * 0.18f
        val oval = android.graphics.RectF(cx - r, cy - r, cx + r, cy + r)
        // Nearly-closed arc, gap at the top-right where the arrowhead lands.
        canvas.drawArc(oval, 50f, 290f, false, ink)
        val tip = Math.toRadians(50.0)
        val tx = (cx + r * Math.cos(tip)).toFloat()
        val ty = (cy + r * Math.sin(tip)).toFloat()
        val head = r * 0.42f
        canvas.drawLine(tx, ty, tx - head, ty - head * 0.25f, ink)
        canvas.drawLine(tx, ty, tx - head * 0.25f, ty - head, ink)
    }
}

/**
 * The settings rail (2026-11-12). Insights stays at index 3 and CarPlay (with the phones) at 0,
 * because the CarPlay screen's bar and the `open_tab` bench action ask for those numbers.
 */
private val TAB_NAMES = listOf("CarPlay", "Screen & Sound", "Cluster", "Insights", "Troubleshooting", "Advanced")
private const val TAB_CARPLAY = 0
private const val TAB_SCREEN = 1
private const val TAB_CLUSTER = 2
private const val TAB_INSIGHTS = 3
private const val TAB_TROUBLE = 4
private const val TAB_ADVANCED = 5
