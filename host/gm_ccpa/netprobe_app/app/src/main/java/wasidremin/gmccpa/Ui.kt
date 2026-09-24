package wasidremin.gmccpa

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

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

/** Rounded-rectangle background, used for the pill buttons and the credential chip. */
private fun pillBg(fill: Int, stroke: Int, radiusPx: Int, strokePx: Int) =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx.toFloat()
        setColor(fill)
        if (stroke != Color.TRANSPARENT) setStroke(strokePx, stroke)
    }

/**
 * The launcher screen.
 *
 * Deliberately minimal: connection state, one supporting line, Start and Stop. The Wi-Fi credentials
 * and the adapter-control verbs are real inputs but are not glanceable information, so they sit
 * behind a chip and a secondary row rather than competing with the state readout.
 */
class LauncherUi(private val act: Activity) {

    /** Fired by the two primary buttons; wired up by [MainActivity]. */
    var onStart: () -> Unit = {}
    var onStop: () -> Unit = {}
    /**
     * Driver-triggered recovery. Deliberately next to Start/Stop rather than in the adapter row: it is
     * the thing to press when everything *looks* right and CarPlay still will not start, which is the
     * situation a driver actually finds themselves in.
     */
    var onRecover: () -> Unit = {}
    /**
     * Restart Session: drop the phone, release the box, wait, re-claim — the one action that fixed
     * things in the old app, and the one a driver needs when they cannot force-stop the app or reach
     * the adapter. Primary-sized on its own row: a fourth pill in the Start/Stop/Recover row measured
     * within ~5 % of the reference width, and a clipped button in a moving vehicle is worse than a
     * second row.
     */
    var onRestart: () -> Unit = {}
    /** Fired when the credentials dialog is confirmed, so the caller can push them into the probe. */
    var onCredentials: (ssid: String, pass: String, chan: String) -> Unit = { _, _, _ -> }
    /** Equinox switch. The caller persists it and tears the link down so the next Start is a fresh edge. */
    var onAdapterWifi: (Boolean) -> Unit = {}
    var adapterWifi: Boolean = false
        private set
    /** Fired by the secondary row; the caller decides whether there is a link to send it on. */
    var onBoxAction: (BoxAction) -> Unit = {}
    /** Fired by the capture row — export, scope toggle, status. */
    var onLogAction: (LogAction) -> Unit = {}
    /** The driver wants the CarPlay picture back after opening settings from that screen. */
    var onReturnToCarPlay: () -> Unit = {}
    /** A view option changed. The CarPlay screen applies bar visibility immediately. */
    var onScreenChanged: () -> Unit = {}
    /** The sidebar toggle changed. A live session has to restart; the width is fixed at subscribe. */
    var onSidebarChanged: () -> Unit = {}
    /** Close the app. The caller stops the session first. */
    var onClose: () -> Unit = {}

    // Credential state lives here as plain strings, NOT as EditTexts held across dialog lifetimes:
    // a dialog's views are torn down on dismiss, so keeping references to them means reading stale or
    // detached widgets on the next session start.
    //
    // PERSISTED, and shipped BLANK. Until 2026-09-21 these were process-memory only, prefilled with
    // the Silverado's hotspot. The Equinox EV's SSID differs from that default by one space
    // (`myChevrolet32D4` vs `myChevrolet 32D4`); the driver corrected it in the dialog, the phone
    // auto-joined, and then a Play update relaunched the process with the default back in place.
    // The next SUBSCRIBE pushed the wrong SSID as a config CHANGE, which made the box tear down and
    // rebuild its BT stack — mid-handoff, with the phone on the hotspot and a connect-out just
    // accepted — and the session after that handed the phone a network that does not exist. Two
    // drives lost to a value the user had already typed correctly. Blank is the safe default:
    // OcbmProbe REFUSES a credential-less SUBSCRIBE with a message naming the missing field, so a
    // fresh install cannot silently push a guess.
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

    private fun persistCredentials() {
        act.getSharedPreferences(CRED_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_SSID, ssid).putString(KEY_PASS, pass).putString(KEY_CHAN, chan).apply()
    }

    private val dot = View(act)
    private val stateText = TextView(act)
    private val detailText = TextView(act)
    private val pairText = TextView(act)
    private val credSummary = TextView(act)
    private lateinit var adapterPill: Button

    private lateinit var actions: LinearLayout
    private lateinit var returnRow: LinearLayout
    private lateinit var restartRow: LinearLayout
    /** Kept so [setRestartBusy] can relabel it; the only pill whose text changes. */
    private lateinit var restartPill: Button
    private lateinit var boxRow: LinearLayout
    private lateinit var logRow: LinearLayout
    private lateinit var logRow2: LinearLayout
    private lateinit var column: LinearLayout
    private lateinit var sessionPage: LinearLayout
    private lateinit var screenPage: LinearLayout
    private lateinit var audioPage: LinearLayout
    private lateinit var logsPage: LinearLayout
    private lateinit var audioAdapter: Button
    private lateinit var audioBluetooth: Button
    private lateinit var sidebarButton: Button
    private val screenModeButtons = mutableListOf<Button>()
    private val tabButtons = mutableListOf<Button>()
    private var selectedTab = 0

    /** Every button, with the sp size it should have at scale 1.0. Re-sized in [applyMetrics]. */
    private val pills = mutableListOf<Pair<Button, Float>>()

    /** Last scale applied, so a layout pass that changes nothing does no work. */
    private var lastScale = -1f

    val root: View = buildRoot()

    // ---- construction ---------------------------------------------------------------------------

    private fun pill(labelText: String, accent: Int, filled: Boolean, baseSp: Float,
                     onClick: () -> Unit): Button =
        Button(act).apply {
            text = labelText
            isAllCaps = true
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(if (filled) Palette.BG else accent)
            stateListAnimator = null       // the default elevation lift fights a flat pill
            setOnClickListener { onClick() }
            setTag(if (filled) accent else Color.TRANSPARENT)   // fill colour, re-read on re-style
            pills += this to baseSp
        }

    private fun buildRoot(): View {
        // Left rail is the earlier app's settings navigation: back, the pages, close, version.
        // The pages hold what used to be one column of pills, split the way that screen split
        // Phones / Control / Logs. ScrollView so a short panel degrades to a scroll instead of
        // clipping Stop off the bottom.
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.SURFACE)
        }
        root.addView(buildRail(), LinearLayout.LayoutParams(act.dp(120), ViewGroup.LayoutParams.MATCH_PARENT))

        val scroller = ScrollView(act).apply {
            isFillViewport = true
            setBackgroundColor(Palette.BG)
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        column = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // --- state: colour dot above the one word that matters --------------------------------
        // The dot sits ABOVE rather than beside the headline on purpose. Beside it, the row has to be
        // full-width for the headline's autosizer to have a bound to shrink against, which drags the
        // pair to the left edge while everything below stays centred. Stacked, both are centred and
        // the headline still gets a full-width slot to autosize within.
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Palette.IDLE)
        }
        column.addView(dot, LinearLayout.LayoutParams(0, 0))       // sized in applyMetrics
        stateText.apply {
            text = LinkState.IDLE.label
            setTextColor(Palette.TEXT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER_HORIZONTAL
            setSingleLine()
            letterSpacing = 0.02f
        }
        column.addView(stateText, LinearLayout.LayoutParams(-1, -2))

        // --- detail line: the sentence the old screen used as its entire UI -------------------
        detailText.apply {
            text = "starting…"
            setTextColor(Palette.TEXT_DIM)
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 2
        }
        column.addView(detailText, LinearLayout.LayoutParams(-1, -2))

        // --- pairing code: hidden until the box sends one -------------------------------------
        // When it does appear it is the most important thing on the screen: it has to be read off
        // this display and matched against a prompt on the iPhone.
        pairText.apply {
            setTextColor(Palette.PHONE)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
            letterSpacing = 0.28f
            setSingleLine()
            visibility = View.GONE
        }
        column.addView(pairText, LinearLayout.LayoutParams(-1, -2))

        sessionPage = page()
        // Shown only while the driver came here from the CarPlay screen and the session is still up.
        returnRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        returnRow.addView(pill("Back to CarPlay", Palette.LIVE, filled = true, baseSp = 26f) { onReturnToCarPlay() })
        sessionPage.addView(returnRow, LinearLayout.LayoutParams(-2, -2))

        actions = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(pill("Start", Palette.LIVE, filled = true, baseSp = 26f) { onStart() })
        actions.addView(pill("Stop", Palette.TEXT_DIM, filled = false, baseSp = 26f) { onStop() })
        actions.addView(pill("Recover", Palette.PHONE, filled = false, baseSp = 26f) { onRecover() })
        sessionPage.addView(actions, LinearLayout.LayoutParams(-2, -2))

        restartRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        restartPill = pill(RESTART_LABEL, Palette.WORKING, filled = false, baseSp = 26f) { onRestart() }
        restartRow.addView(restartPill)
        sessionPage.addView(restartRow, LinearLayout.LayoutParams(-2, -2))

        boxRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for (a in BoxAction.values()) {
            boxRow.addView(pill(a.label, Palette.LINE, filled = false, baseSp = 17f) { onBoxAction(a) }
                .also { it.setTextColor(Palette.TEXT_DIM) })
        }
        sessionPage.addView(boxRow, LinearLayout.LayoutParams(-2, -2))

        adapterPill = pill("Adapter Wi-Fi: OFF", Palette.PHONE, filled = false, baseSp = 17f) {
            setAdapterWifi(!adapterWifi)
            onAdapterWifi(adapterWifi)
        }.also { it.setTextColor(Palette.TEXT_DIM) }
        sessionPage.addView(adapterPill, LinearLayout.LayoutParams(-2, -2))

        credSummary.apply {
            setTextColor(Palette.TEXT_DIM)
            gravity = Gravity.CENTER
            setSingleLine()
            setOnClickListener { showCredentialsDialog() }
        }
        sessionPage.addView(credSummary, LinearLayout.LayoutParams(-2, -2))
        refreshCredSummary()
        column.addView(sessionPage, LinearLayout.LayoutParams(-1, -2))

        screenPage = page()
        screenPage.addView(sectionTitle("Screen"))
        screenPage.addView(bodyCopy(
            "Full screen hides the car's bars. The sidebar is the 108 dp icon rail, and turning it on or off restarts a live session so the picture matches."
        ))
        for (mode in ScreenMode.values()) {
            val b = pill(mode.label, Palette.LIVE, filled = false, baseSp = 18f) {
                DisplayPrefs.setMode(act, mode)
                refreshScreenSummary()
                onScreenChanged()
            }
            screenModeButtons += b
            screenPage.addView(b, LinearLayout.LayoutParams(-2, -2))
        }
        sidebarButton = pill("Sidebar: OFF", Palette.PHONE, filled = false, baseSp = 18f) {
            val on = !DisplayPrefs.sidebar(act)
            DisplayPrefs.setSidebar(act, on)
            refreshScreenSummary()
            onSidebarChanged()
        }
        screenPage.addView(sidebarButton, LinearLayout.LayoutParams(-2, -2))
        column.addView(screenPage, LinearLayout.LayoutParams(-1, -2))
        refreshScreenSummary()

        audioPage = page()
        audioPage.addView(sectionTitle("Audio source"))
        audioPage.addView(bodyCopy(
            "Adapter plays CarPlay through this app and selects it as the car's media source. Bluetooth leaves the stereo on the phone and this app stays quiet."
        ))
        audioAdapter = pill("Adapter", Palette.LIVE, filled = true, baseSp = 22f) { setAudioRoute(false) }
        audioBluetooth = pill("Bluetooth", Palette.PHONE, filled = false, baseSp = 22f) { setAudioRoute(true) }
        val audioRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        audioRow.addView(audioAdapter)
        audioRow.addView(audioBluetooth)
        audioPage.addView(audioRow, LinearLayout.LayoutParams(-2, -2))
        column.addView(audioPage, LinearLayout.LayoutParams(-1, -2))
        refreshAudioRoute()

        logsPage = page()
        logsPage.addView(sectionTitle("Logs"))
        // Six pills in one row clipped Clear Logs off the Equinox (2026-09-21). Three per row fits.
        logRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        logRow2 = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        LogAction.values().forEachIndexed { i, a ->
            (if (i < 3) logRow else logRow2)
                .addView(pill(a.label, Palette.LINE, filled = false, baseSp = 17f) { onLogAction(a) }
                    .also { it.setTextColor(Palette.TEXT_DIM) })
        }
        logsPage.addView(logRow, LinearLayout.LayoutParams(-2, -2))
        logsPage.addView(logRow2, LinearLayout.LayoutParams(-2, -2))
        column.addView(logsPage, LinearLayout.LayoutParams(-1, -2))

        showTab(0)
        scroller.addView(column, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroller, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            if (r - l != or_ - ol || b - t != ob - ot) applyMetrics(r - l, b - t)
        }
        return root
    }

    private fun page(): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun sectionTitle(text: String): TextView = TextView(act).apply {
        this.text = text
        setTextColor(Palette.TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
    }

    private fun bodyCopy(text: String): TextView = TextView(act).apply {
        this.text = text
        setTextColor(Palette.TEXT_DIM)
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
    }

    private fun buildRail(): LinearLayout {
        val rail = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Palette.SURFACE)
            setPadding(act.dp(8), act.dp(16), act.dp(8), act.dp(12))
        }
        rail.addView(railButton("Back") { onReturnToCarPlay() }, LinearLayout.LayoutParams(-1, -2))
        listOf("Session", "Screen", "Audio", "Logs").forEachIndexed { i, name ->
            val b = railButton(name) { showTab(i) }
            tabButtons += b
            rail.addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = act.dp(8) })
        }
        rail.addView(View(act), LinearLayout.LayoutParams(0, 0, 1f))
        rail.addView(railButton("Close") { onClose() }, LinearLayout.LayoutParams(-1, -2))
        val version = TextView(act).apply {
            val name = runCatching {
                act.packageManager.getPackageInfo(act.packageName, 0).versionName
            }.getOrNull() ?: ""
            text = name
            setTextColor(Palette.TEXT_DIM)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        rail.addView(version, LinearLayout.LayoutParams(-1, -2).apply { topMargin = act.dp(8) })
        return rail
    }

    private fun railButton(labelText: String, onClick: () -> Unit): Button =
        Button(act).apply {
            text = labelText
            isAllCaps = false
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Palette.TEXT)
            stateListAnimator = null
            setOnClickListener { onClick() }
            background = pillBg(Color.TRANSPARENT, Color.TRANSPARENT, act.dp(20), 0)
            setPadding(act.dp(4), act.dp(10), act.dp(4), act.dp(10))
        }.also {
            // LayoutParams applied by the caller via addView's default if we set them here.
        }

    private fun showTab(index: Int) {
        selectedTab = index
        val pages = listOf(sessionPage, screenPage, audioPage, logsPage)
        pages.forEachIndexed { i, p -> p.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabButtons.forEachIndexed { i, b ->
            val on = i == index
            b.setTextColor(if (on) Palette.BG else Palette.TEXT)
            b.background = pillBg(if (on) Palette.LIVE else Color.TRANSPARENT, Color.TRANSPARENT, act.dp(20), 0)
        }
    }

    private fun setAudioRoute(bluetooth: Boolean) {
        AudioRoute.set(act, bluetooth)
        refreshAudioRoute()
    }

    private fun refreshAudioRoute() {
        if (!::audioAdapter.isInitialized) return
        val bt = AudioRoute.bluetooth
        styleChoice(audioAdapter, !bt)
        styleChoice(audioBluetooth, bt)
    }

    private fun styleChoice(b: Button, on: Boolean) {
        b.setTextColor(if (on) Palette.BG else Palette.TEXT)
        b.tag = if (on) Palette.LIVE else Color.TRANSPARENT
        val h = b.minimumHeight.coerceAtLeast(act.dp(48))
        b.background = pillBg(if (on) Palette.LIVE else Color.TRANSPARENT, Palette.LIVE, h / 2, act.dp(2))
    }

    // ---- responsive sizing ----------------------------------------------------------------------

    /**
     * Derive every size from the area actually granted.
     *
     * The reference panel is 1100x620dp — comfortably smaller than the emulator's 2400x960 and
     * around what a head unit leaves an app after its own chrome. Bigger panels scale up (capped, so
     * a huge dash does not turn into a billboard), smaller ones scale down to a floor that keeps the
     * secondary row tappable.
     */
    private fun applyMetrics(wPx: Int, hPx: Int) {
        if (wPx <= 0 || hPx <= 0) return
        val d = act.resources.displayMetrics.density
        val wDp = wPx / d
        val hDp = hPx / d
        val s = minOf(wDp / 1100f, hDp / 620f).coerceIn(0.5f, 1.35f)
        if (kotlin.math.abs(s - lastScale) < 0.01f) return
        lastScale = s

        fun px(dpValue: Float) = (dpValue * s * d + 0.5f).toInt()
        fun sp(v: Float) = v * s

        column.setPadding(px(48f), px(32f), px(48f), px(32f))

        // Headline. Autosizing is the part that makes this survive an unknown panel: the text shrinks
        // itself down to the floor rather than clipping, so "LOOKING FOR ADAPTER" fits where it must.
        stateText.setAutoSizeTextTypeUniformWithConfiguration(
            maxOf(14, (sp(22f)).toInt()), maxOf(16, sp(64f).toInt()), 1,
            TypedValue.COMPLEX_UNIT_SP)

        val dotPx = px(26f)
        (dot.layoutParams as LinearLayout.LayoutParams).apply {
            width = dotPx; height = dotPx; bottomMargin = px(22f)
        }
        dot.requestLayout()

        detailText.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(24f))
        (detailText.layoutParams as LinearLayout.LayoutParams).topMargin = px(16f)

        pairText.setAutoSizeTextTypeUniformWithConfiguration(
            maxOf(14, sp(24f).toInt()), maxOf(16, sp(56f).toInt()), 1,
            TypedValue.COMPLEX_UNIT_SP)
        (pairText.layoutParams as LinearLayout.LayoutParams).topMargin = px(24f)

        (returnRow.layoutParams as LinearLayout.LayoutParams).topMargin = px(40f)
        (actions.layoutParams as LinearLayout.LayoutParams).topMargin = px(28f)
        (restartRow.layoutParams as LinearLayout.LayoutParams).topMargin = px(20f)
        (boxRow.layoutParams as LinearLayout.LayoutParams).topMargin = px(28f)
        (logRow.layoutParams as LinearLayout.LayoutParams).topMargin = px(16f)
        (logRow2.layoutParams as LinearLayout.LayoutParams).topMargin = px(8f)
        (adapterPill.layoutParams as LinearLayout.LayoutParams).topMargin = px(20f)
        (credSummary.layoutParams as LinearLayout.LayoutParams).topMargin = px(16f)

        for ((b, baseSp) in pills) {
            val primary = baseSp >= 20f
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(baseSp))
            // The primary pair is sized for a glance-and-stab while moving; this Activity is declared
            // distractionOptimized, so it is genuinely on screen in motion. The secondary row is
            // smaller on purpose — it is a parked-and-deliberate action, not a driving one.
            val h = px(if (primary) 88f else 60f)
            b.minimumHeight = h
            b.minHeight = h
            val padH = px(if (primary) 48f else 26f)
            b.setPadding(padH, 0, padH, 0)
            val fill = b.tag as Int
            val accent = if (fill != Color.TRANSPARENT) fill else
                if (primary) Palette.TEXT_DIM else Palette.LINE
            b.background = pillBg(fill, accent, h / 2, px(2f))
            // The first button of each row carries no left margin so every row stays centred.
            val parent = b.parent as? LinearLayout
            val stacked = parent?.orientation == LinearLayout.VERTICAL
            val firstInRow = parent?.getChildAt(0) === b
            (b.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                leftMargin = if (stacked || firstInRow) 0 else px(if (primary) 24f else 12f)
                if (stacked) topMargin = px(12f)
            }
        }

        credSummary.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp(19f))
        credSummary.background = pillBg(Color.TRANSPARENT, Palette.LINE, px(32f), px(2f))
        credSummary.setPadding(px(32f), px(16f), px(32f), px(16f))

        column.requestLayout()
    }

    // ---- credentials ----------------------------------------------------------------------------

    fun setAdapterWifi(on: Boolean) {
        adapterWifi = on
        if (::adapterPill.isInitialized) {
            adapterPill.text = if (on) "Adapter Wi-Fi: ON" else "Adapter Wi-Fi: OFF"
        }
        refreshCredSummary()
    }

    private fun refreshCredSummary() {
        credSummary.text = when {
            adapterWifi -> "Adapter Wi-Fi: the phone joins the adapter on channel 149"
            ssid.isBlank() || pass.isBlank() ->
                "Wi-Fi:  NOT SET — tap to enter the vehicle hotspot SSID and passphrase"
            else -> "Wi-Fi:  $ssid   ·   ch $chan   ·   tap to edit"
        }
    }

    /**
     * The credentials popup. Platform [AlertDialog] with the DeviceDefault dark alert theme — the
     * light default would flash a white sheet on a dark dash at night.
     */
    fun showCredentialsDialog() {
        val pad = act.dp(40)
        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, act.dp(24), pad, act.dp(8))
        }

        fun field(labelText: String, value: String, numeric: Boolean): EditText {
            body.addView(TextView(act).apply {
                text = labelText
                setTextColor(Palette.TEXT_DIM)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                isAllCaps = true
                letterSpacing = 0.08f
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = act.dp(20) })
            val e = EditText(act).apply {
                setText(value)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setTextColor(Palette.TEXT)
                inputType = if (numeric) InputType.TYPE_CLASS_NUMBER
                            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                setSingleLine()
            }
            body.addView(e, LinearLayout.LayoutParams(-1, -2))
            return e
        }

        // The passphrase is shown, not masked. It is a fixed vehicle-hotspot credential that has to be
        // read off this screen and typed into a phone during bring-up; masking it would help nobody
        // and would hide typos in the one value whose mistyping is silent — the phone simply never
        // joins, and per docs/06 §5.4 the dead network then poisons the next attempt.
        val eSsid = field("Hotspot SSID", ssid, numeric = false)
        val ePass = field("Passphrase", pass, numeric = false)
        val eChan = field("Channel", chan, numeric = true)

        AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Vehicle hotspot")
            .setView(body)
            .setPositiveButton("Save") { _, _ ->
                ssid = eSsid.text.toString().trim()
                pass = ePass.text.toString().trim()
                chan = eChan.text.toString().trim()
                persistCredentials()
                refreshCredSummary()
                onCredentials(ssid, pass, chan)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Set the credentials from outside the dialog — the `--es ssid/pass/chan` scripted path. */
    fun setCredentials(newSsid: String?, newPass: String?, newChan: String?) = act.runOnUiThread {
        if (newSsid == null && newPass == null && newChan == null) return@runOnUiThread
        newSsid?.let { ssid = it }
        newPass?.let { pass = it }
        newChan?.let { chan = it }
        persistCredentials()
        refreshCredSummary()
    }

    // ---- state ----------------------------------------------------------------------------------

    /**
     * The single UI update channel. [detail] is the free-form sentence the session code already
     * produces; [state] is what makes it glanceable.
     */
    fun setState(state: LinkState, detail: String) = act.runOnUiThread {
        if (act.isFinishing) return@runOnUiThread
        stateText.text = state.label
        (dot.background as GradientDrawable).setColor(state.color)
        detailText.text = detail
    }

    /** Show (or, on an empty code, hide) the box's pairing code. */
    fun setPairingCode(code: String) = act.runOnUiThread {
        if (act.isFinishing) return@runOnUiThread
        pairText.text = code
        pairText.visibility = if (code.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Free-form message shown in the detail line without disturbing the state word. */
    fun setDetail(detail: String) = act.runOnUiThread {
        if (!act.isFinishing) detailText.text = detail
    }

    /**
     * Relabel the restart pill while a restart is in flight. The pill stays enabled: a repeat press
     * is rejected by `MainActivity.requestRestart` with its own detail line, and a disabled pill on a
     * dark dash reads as missing rather than busy.
     */
    fun setRestartBusy(busy: Boolean) = act.runOnUiThread {
        if (!act.isFinishing) restartPill.text = if (busy) "Restarting…" else RESTART_LABEL
    }

    /** The gear on the CarPlay screen landed here. Show the way back while the session is still up. */
    fun setReturnToCarPlay(show: Boolean) = act.runOnUiThread {
        if (!act.isFinishing && ::returnRow.isInitialized) {
            returnRow.visibility = if (show) View.VISIBLE else View.GONE
        }
    }

    private fun refreshScreenSummary() {
        if (screenModeButtons.isEmpty()) return
        val selected = DisplayPrefs.mode(act)
        for (b in screenModeButtons) {
            val on = b.text.toString().equals(selected.label, ignoreCase = true)
            b.tag = if (on) Palette.LIVE else Color.TRANSPARENT
            b.setTextColor(if (on) Palette.BG else Palette.TEXT)
            val h = b.minimumHeight.coerceAtLeast(act.dp(48))
            b.background = pillBg(if (on) Palette.LIVE else Color.TRANSPARENT, Palette.LIVE, h / 2, act.dp(2))
        }
        val side = DisplayPrefs.sidebar(act)
        sidebarButton.text = if (side) "Sidebar: ON" else "Sidebar: OFF"
        sidebarButton.tag = if (side) Palette.PHONE else Color.TRANSPARENT
        sidebarButton.setTextColor(if (side) Palette.BG else Palette.TEXT)
    }

    /**
     * The view options and the sidebar toggle. A view option applies immediately if the CarPlay
     * screen is still up. The sidebar is the left rail (settings, home, media keys). It takes its
     * width out of the picture the phone lays out, which is fixed at subscribe. Turning the
     * sidebar on or off restarts a live session so the next subscribe uses the new width. It
     * does not change which GM bars are hidden.
     */
    private fun showScreenDialog() {
        val pad = act.dp(40)
        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, act.dp(8), pad, act.dp(8))
        }
        val selected = DisplayPrefs.mode(act)
        for (mode in ScreenMode.values()) {
            val on = mode == selected
            body.addView(Button(act).apply {
                text = mode.label
                tag = mode.value
                isAllCaps = true
                setTextColor(if (on) Palette.BG else Palette.TEXT)
                stateListAnimator = null
                background = pillBg(if (on) Palette.LIVE else Color.TRANSPARENT, Palette.LIVE, act.dp(40), act.dp(2))
                setOnClickListener {
                    DisplayPrefs.setMode(act, mode)
                    refreshScreenSummary()
                    onScreenChanged()
                    for (i in 0 until body.childCount) {
                        val b = body.getChildAt(i) as? Button ?: continue
                        val value = b.tag as? Int ?: continue
                        val match = value == mode.value
                        b.setTextColor(if (match) Palette.BG else Palette.TEXT)
                        b.background = pillBg(if (match) Palette.LIVE else Color.TRANSPARENT, Palette.LIVE, act.dp(40), act.dp(2))
                    }
                }
            }, LinearLayout.LayoutParams(-1, act.dp(72)).apply { topMargin = act.dp(12) })
        }
        val sideOn = booleanArrayOf(DisplayPrefs.sidebar(act))
        body.addView(Button(act).apply {
            fun label() = if (sideOn[0]) "Sidebar: ON" else "Sidebar: OFF"
            text = label()
            isAllCaps = true
            setTextColor(Palette.TEXT)
            stateListAnimator = null
            background = pillBg(Color.TRANSPARENT, Palette.PHONE, act.dp(40), act.dp(2))
            setOnClickListener {
                sideOn[0] = !sideOn[0]
                DisplayPrefs.setSidebar(act, sideOn[0])
                text = label()
                refreshScreenSummary()
                onSidebarChanged()
            }
        }, LinearLayout.LayoutParams(-1, act.dp(72)).apply { topMargin = act.dp(24) })

        AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Screen")
            .setMessage("Full screen hides the car's bars so CarPlay uses the whole panel. The sidebar is a 108 dp icon rail — Home, Settings, restart, voice, and media — and CarPlay is drawn in the space beside it. Turning it on or off restarts a live session so the picture matches the rail.")
            .setView(body)
            .setPositiveButton("Done", null)
            .show()
    }

    private companion object {
        const val RESTART_LABEL = "Restart Session"
        /** Vehicle hotspot credentials — survive process restarts and app updates. */
        const val CRED_PREFS = "hotspot_credentials"
        const val KEY_SSID = "ssid"
        const val KEY_PASS = "pass"
        const val KEY_CHAN = "chan"
    }
}
