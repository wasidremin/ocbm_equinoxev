package wasidremin.gmccpa

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
import wasidremin.gmccpa.ocbm.UsbIdentity

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
    /** Wipes captured log files on the head unit and restarts capture. Confirmation-gated. */
    CLEAR("Clear Logs"),
}

/** dp -> px, against the density the Activity is actually running at. */
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/**
 * The launcher screen.
 *
 * Settings sit behind the startup animation. The gear on that animation opens them; Done puts the
 * animation back when no session is live. A live session uses Back to CarPlay on the status strip.
 */
class LauncherUi(private val act: Activity) {

    var onStart: () -> Unit = {}
    var onStop: () -> Unit = {}
    var onRecover: () -> Unit = {}
    var onRestart: () -> Unit = {}
    var onCredentials: (ssid: String, pass: String, chan: String) -> Unit = { _, _, _ -> }
    var onAdapterWifi: (Boolean) -> Unit = {}
    var adapterWifi: Boolean = false
        private set
    var onBoxAction: (BoxAction) -> Unit = {}
    /** Advanced-page USB announce segment. Index 0 Off, 1 Image, 2 No medium. */
    var onUsbAnnounce: (Int) -> Unit = {}
    var onAdvancedOpened: () -> Unit = {}
    var onLogAction: (LogAction) -> Unit = {}
    var onCaptureScope: (LogCapture.Scope) -> Unit = {}
    var onReturnToCarPlay: () -> Unit = {}
    var onScreenChanged: () -> Unit = {}
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
    private lateinit var announceRow: EqRow
    private lateinit var announceControl: EqSegmented
    private var announceIndex = 1
    private lateinit var scopeRow: EqRow
    private lateinit var logStatusRow: EqRow
    private lateinit var wifiToggle: EqToggle
    private var scopeControl: EqSegmented? = null
    private lateinit var audioRow: EqRow

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
        frame.addView(introGear, FrameLayout.LayoutParams(act.dp(72), act.dp(72), Gravity.TOP or Gravity.END).apply {
            topMargin = act.dp(20)
            marginEnd = act.dp(28)
        })
        settingsHost.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            considerWidth(r - l)
        }
        return frame
    }

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
        rail = EqNavRail(act, listOf("Connection", "Display", "Audio", "Logs", "Advanced"), { showTab(it) }, version)
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

        val connection = page("Connection")
        val display = page("Display")
        val audio = page("Audio")
        val logs = page("Logs")
        val advanced = page("Advanced")
        pages = listOf(connection, display, audio, logs, advanced)
        buildConnection(connection)
        buildDisplay(display)
        buildAudio(audio)
        buildLogs(logs)
        buildAdvanced(advanced)
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

    private fun buildConnection(page: LinearLayout) {
        pairRow = EqRow(act, pairingCode.ifEmpty { " " })
        pairRow.titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, EqTheme.sp(40f))
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
        page.addView(eqSectionLabel(act, "Status"))
        page.addView(EqCard(act).addRow(phoneRow).addRow(restart).addRow(stop).addRow(startRow))
        page.addView(eqSectionLabel(act, "Phone"))
        page.addView(EqCard(act).addRow(EqRow(
            act, "Forget pairing",
            "Clears the adapter and this app. Also forget this car on the iPhone.",
            titleColor = EqTheme.DANGER,
            onClick = { confirmForget() },
        )))
    }

    private fun buildDisplay(page: LinearLayout) {
        val scales = DisplayPrefs.SCALE_PRESETS
        val scaleLabels = scales.map { "$it%" }
        val scaleIndex = scales.indexOf(DisplayPrefs.uiScale(act)).coerceAtLeast(0)
        val margins = DisplayPrefs.SAFE_PRESETS
        val marginLabels = listOf("Off", "Small", "Medium", "Large", "Max")
        val marginIndex = margins.indexOf(DisplayPrefs.safeRightPx(act)).let { if (it < 0) 2 else it }
        val modes = listOf(
            ScreenMode.SYSTEM_UI, ScreenMode.STATUS_HIDDEN, ScreenMode.NAV_HIDDEN, ScreenMode.FULLSCREEN,
        )
        val modeIndex = modes.indexOf(DisplayPrefs.mode(act)).let { if (it < 0) 3 else it }
        page.addView(eqSectionLabel(act, "Picture"))
        val card = EqCard(act)
        card.addRow(EqRow(
            act, "CarPlay size",
            "Bigger icons and text. Applies at the next connection.",
            trailing = EqSegmented(act, scaleLabels, scaleIndex) { i ->
                DisplayPrefs.setUiScale(act, scales[i])
                refreshDynamic()
            },
        ))
        card.addRow(EqRow(
            act, "Right margin",
            "Keeps Now Playing off the right edge. Applies at the next connection.",
            trailing = EqSegmented(act, marginLabels, marginIndex) { i ->
                DisplayPrefs.setSafeRightPx(act, margins[i])
                refreshDynamic()
            },
        ))
        card.addRow(EqRow(
            act, "Screen layout",
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
        page.addView(card)
        applyRow = EqRow(
            act, "Apply size now",
            "Restarts CarPlay so this size is what the iPhone lays out.",
            trailing = eqButton(act, "Restart", EqButtonStyle.SECONDARY) { onRestart() },
        )
        page.addView(applyRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(16f) })
    }

    private fun buildAudio(page: LinearLayout) {
        val bt = AudioRoute.bluetooth
        audioRow = EqRow(
            act, "Audio source", audioSubtitle(bt),
            trailing = EqSegmented(act, listOf("Adapter", "Bluetooth"), if (bt) 1 else 0) { i ->
                AudioRoute.set(act, i == 1)
                audioRow.setSubtitle(audioSubtitle(i == 1))
            },
        )
        page.addView(eqSectionLabel(act, "Playback"))
        page.addView(EqCard(act).addRow(audioRow))
    }

    private fun buildLogs(page: LinearLayout) {
        val granted = readLogsGranted()
        val scope = CapturePrefs.scope(act)
        scopeControl = EqSegmented(act, listOf("This app", "Whole car"), if (scope == LogCapture.Scope.WHOLE_OS) 1 else 0) { i ->
            onScopePicked(i)
        }
        scopeRow = EqRow(act, "Log scope", scopeSubtitle(granted), trailing = scopeControl)
        logStatusRow = EqRow(act, "Log status", logStatusText.ifEmpty { null }, onClick = { onLogAction(LogAction.STATUS) })
        page.addView(eqSectionLabel(act, "Capture"))
        page.addView(EqCard(act)
            .addRow(EqRow(
                act, "Upload logs",
                trailing = eqButton(act, "Upload", EqButtonStyle.PRIMARY) { onLogAction(LogAction.UPLOAD) },
            ))
            .addRow(EqRow(act, "Export logs", onClick = { onLogAction(LogAction.EXPORT) }))
            .addRow(scopeRow)
            .addRow(logStatusRow)
            .addRow(EqRow(act, "USB probe", onClick = { onLogAction(LogAction.USB_PROBE) }))
            .addRow(EqRow(act, "Clear logs", titleColor = EqTheme.DANGER, onClick = { confirmClearLogs() })))
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
        page.addView(eqSectionLabel(act, "Adapter"))
        page.addView(EqCard(act)
            .addRow(EqRow(act, "Adapter Wi-Fi", "The iPhone joins the adapter.", trailing = wifiToggle))
            .addRow(hotspotRow)
            .addRow(infoRow)
            .addRow(announceRow)
            .addRow(EqRow(act, "Restart adapter Wi-Fi", titleColor = EqTheme.DANGER, onClick = { confirmRestartWifi() }))
            .addRow(EqRow(act, "Reboot adapter", titleColor = EqTheme.DANGER, onClick = { confirmReboot() }))
            .addRow(EqRow(
                act, "Recover",
                "Tries the automatic recovery steps now.",
                onClick = { onRecover() },
            )))

        page.addView(eqSectionLabel(act, "App"))
        page.addView(EqCard(act).addRow(EqRow(
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
        if (::pages.isInitialized) {
            pages.forEachIndexed { i, p -> p.visibility = if (i == index) View.VISIBLE else View.GONE }
        }
        if (::rail.isInitialized) rail.select(index)
        if (::scroller.isInitialized) scroller.scrollTo(0, 0)
        if (index == 4) onAdvancedOpened()
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

    fun confirmAnnounceReboot(onConfirm: () -> Unit, onCancel: () -> Unit) = act.runOnUiThread {
        EqDialog.confirm(
            act,
            "Adapter will restart",
            "CarPlay disconnects for about 30 seconds.",
            "Restart",
            false,
            onCancel,
            onConfirm,
        )
    }

    /** A GONE row inside an [EqCard] leaves its hairline. Hide that divider with the row. */
    private fun setRowShown(row: View, shown: Boolean) {
        row.visibility = if (shown) View.VISIBLE else View.GONE
        val parent = row.parent as? LinearLayout ?: return
        val i = parent.indexOfChild(row)
        if (i > 0) parent.getChildAt(i - 1).visibility = row.visibility
    }

    private fun openIntroSettings() {
        uiLog.i("intro: settings")
        settingsTouchedAt = android.os.SystemClock.elapsedRealtime()
        introRestore?.let { intro.removeCallbacks(it) }
        hideIntroLayer()
        showTab(1)
    }

    private fun hideIntroLayer() {
        intro.visibility = View.GONE
        introGear.visibility = View.GONE
    }

    private fun showIntroLayer() {
        intro.visibility = View.VISIBLE
        introGear.visibility = View.VISIBLE
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
        dialog.setContentView(body, ViewGroup.LayoutParams(EqTheme.px(720f), ViewGroup.LayoutParams.WRAP_CONTENT))
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
        val detail = if (lastState == LinkState.LIVE) liveDetail() else lastDetail
        strip.set(lastState.label, detail, stripColor(lastState))
        val sessionLive = sessionIsLive() || lastState == LinkState.LIVE
        strip.setAction(if (sessionLive) {
            eqButton(act, "Back to CarPlay", EqButtonStyle.PRIMARY) { onReturnToCarPlay() }
        } else {
            eqButton(act, "Done", EqButtonStyle.SECONDARY) { showIntroLayer() }
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

    private fun sizePending(): Boolean {
        if (!(sessionIsLive() || lastState == LinkState.LIVE)) return false
        if (DisplayPrefs.uiScale(act) != VideoFrame.uiScalePercent) return true
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
            act, "Forget Pairing",
            running + "Clear the pairing on both the adapter and this app?\n\nYou must ALSO forget " +
                "this car on the iPhone — a one-sided pairing makes the next attempt fail in a " +
                "way that looks like broken encryption.",
            "Forget Pairing", true,
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
