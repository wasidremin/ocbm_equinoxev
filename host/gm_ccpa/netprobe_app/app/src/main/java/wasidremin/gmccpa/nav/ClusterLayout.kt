package wasidremin.gmccpa.nav

import android.content.Context
import android.graphics.Color
import wasidremin.gmccpa.ProbeLog

/**
 * What the app draws over CarPlay's map on the driver display, and where (Settings ▸ Driver display).
 *
 * Three places, each dragged and sized in Settings: the turn card (with the "then" turn and the lane
 * strip stacked under it), the now-playing card (which a call takes over while it lasts) and the
 * drive card ([DriveCard]: energy now and over the last ten miles). One of the three can instead hold
 * the bottom-right panel ([docked]), in the look of the car's gauges. All are
 * the app's own drawing, so nothing here is declared to CarPlay and every change applies at once.
 * The driver display takes no touch; the cards only show.
 */
object ClusterLayout {
    // Lazy, with the colours below, so the geometry is tested without Android.
    private val log by lazy { ProbeLog.sub("cluster") }
    private const val PREFS = "driver_display"
    private const val KEY_MEDIA = "media_card"
    private const val KEY_CALL = "call_card"
    private const val KEY_LANES = "lane_guidance"
    private const val KEY_OLD_TURN_AREA = "turn_card_area"
    /** Before 2026-11-14: whether the turn card was docked. Read once, as the default for [KEY_DOCK_BY]. */
    private const val KEY_DOCK = "turn_dock"
    private const val KEY_DOCK_PLACE = "slot_turn_dock"
    /** Which card holds the panel, per turn card in use: `dock_ours`, `dock_apple`, `dock_off`. */
    private const val KEY_DOCK_BY = "dock_"
    private const val DOCK_NONE = "none"
    private const val KEY_DRIVE = "drive_card"

    /** A card's top-left corner in percent of the driver display, and its size in percent of the default. */
    data class Place(val x: Int, val y: Int, val scale: Int = 100) {
        fun clamped() = Place(x.coerceIn(0, 100), y.coerceIn(0, 100), scale.coerceIn(MIN_SCALE, MAX_SCALE))
    }
    const val MIN_SCALE = 50
    const val MAX_SCALE = 200
    const val SCALE_STEP = 10

    /**
     * [base] is the card's height as a share of the display's height at 100%, [aspect] its width over
     * its height, and [stack] the height of everything that can hang under it, in card heights.
     */
    enum class Slot(val key: String, val label: String, val base: Float, val aspect: Float, val stack: Float, val default: Place) {
        /** Left of GM's directions area and above the Equinox's gauges, which take the bottom third. */
        TURN("slot_turn", "Turn", 0.22f, 2.9f, 1.82f, Place(2, 6)),
        /** The right side, under the speed-limit sign Apple's map draws in its top-right corner. */
        MEDIA("slot_media", "Now playing", 0.22f, 2.6f, 1f, Place(70, 30)),
        /** Under now playing. A little wider than it: its lines are sentences, not a title. */
        DRIVE("slot_drive", "Drive", 0.22f, 3.0f, 1f, Place(68, 54)),
    }

    /** A card in window pixels. What hangs under it reaches to [top] + [height] * [Slot.stack]. */
    data class Box(val left: Float, val top: Float, val width: Float, val height: Float)

    /**
     * Pure: [slot]'s card at [p] in a [w] x [h] window. The card and everything under it stay inside
     * the window: pushed back in when [p] would hang them over an edge, shrunk when they cannot fit.
     */
    fun rect(w: Int, h: Int, slot: Slot, p: Place): Box {
        val c = p.clamped()
        var ch = h * slot.base * c.scale / 100f
        var cw = ch * slot.aspect
        val k = minOf(1f, w / cw, h / (ch * slot.stack))
        ch *= k; cw *= k
        val left = (w * c.x / 100f).coerceIn(0f, w - cw)
        val top = (h * c.y / 100f).coerceIn(0f, h - ch * slot.stack)
        return Box(left, top, cw, ch)
    }

    /**
     * The docked turn card's top-left corner. The panel runs from it off the right and bottom edges
     * of the display, as the Equinox's gauges do on the left, so GM's mask trims it the same way.
     * From a photo of the car (2026-11-11) the glass shows roughly 3..95% of the window across and
     * 7..88% down, and the gauges' top edge sits 54% down: this mirrors them.
     */
    val DOCK_DEFAULT = Place(70, 54)
    /**
     * Pure: the card holding the panel when Settings stored [stored] for [turn]. The turn card can
     * hold it only when it is this app's own; with nothing stored, a turn card docked before
     * 2026-11-14 ([oldDock]) stays docked.
     */
    fun dockedFor(turn: ClusterContent.Turn, stored: String?, oldDock: Boolean): Slot? {
        val slot = when (stored) {
            null -> if (oldDock && turn == ClusterContent.Turn.OURS) Slot.TURN else null
            else -> Slot.values().firstOrNull { it.key == stored }
        }
        return slot?.takeIf { it != Slot.TURN || turn == ClusterContent.Turn.OURS }
    }

    /** The drive card while the turn card is docked where it would sit: the turn card's free corner. */
    val DRIVE_DOCKED_DEFAULT = Place(2, 6)
    /** How far the docked panel's left edge leans out per pixel down, measured off the gauges. */
    const val DOCK_SLANT = 0.43f

    /** Pure: the docked card's content box at [p]; the panel behind it reaches the window's edges. */
    fun dockRect(w: Int, h: Int, p: Place): Box {
        val c = p.clamped()
        val ch = h * Slot.TURN.base * c.scale / 100f
        return Box((w * c.x / 100f).coerceIn(w * 0.10f, w * 0.95f), (h * c.y / 100f).coerceIn(h * 0.10f, h * 0.95f), ch * Slot.TURN.aspect, ch)
    }

    /** [slot]'s card at [p], docked or free as Settings has it. */
    fun boxAt(w: Int, h: Int, slot: Slot, p: Place): Box =
        if (slot == docked) dockRect(w, h, p) else rect(w, h, slot, p)

    fun box(ctx: Context, w: Int, h: Int, slot: Slot): Box = boxAt(w, h, slot, place(ctx, slot))

    /** Everything [slot] can cover: the card and its stack, or the docked panel out to the edges. */
    fun footprint(ctx: Context, w: Int, h: Int, slot: Slot): Box {
        val b = box(ctx, w, h, slot)
        return if (slot == docked) Box(b.left, b.top, w - b.left, h - b.top) else Box(b.left, b.top, b.width, b.height * slot.stack)
    }

    fun place(ctx: Context, slot: Slot): Place {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (slot == docked) {
            return parse(prefs.getString(KEY_DOCK_PLACE, null), 3)?.let { Place(it[0], it[1], it[2]).clamped() } ?: DOCK_DEFAULT
        }
        parse(prefs.getString(slot.key, null), 3)?.let { return Place(it[0], it[1], it[2]).clamped() }
        // Before 2026-11-10 the turn card had an area of four edge margins; its corner is kept.
        if (slot == Slot.TURN) parse(prefs.getString(KEY_OLD_TURN_AREA, null), 4)?.let { return Place(it[0], it[1]).clamped() }
        if (slot == Slot.DRIVE && dock) return DRIVE_DOCKED_DEFAULT
        return slot.default
    }

    fun setPlace(ctx: Context, slot: Slot, p: Place?) {
        val e = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        val inDock = slot == docked
        val key = if (inDock) KEY_DOCK_PLACE else slot.key
        if (p == null) { e.remove(key); if (!inDock) e.remove(KEY_OLD_TURN_AREA) }
        else p.clamped().let { e.putString(key, "${it.x},${it.y},${it.scale}") }
        e.apply()
        DriverDisplay.preview()
    }

    private fun parse(raw: String?, n: Int): List<Int>? =
        raw?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == n }

    /** Now playing on the driver display. On by default. */
    @Volatile var media = true
        private set
    /** A call takes the now-playing card's place while it rings or is active. On by default. */
    @Volatile var call = true
        private set
    /**
     * Lane arrows under the turn card. Off by default: the lanes come only when the adapter asks the
     * iPhone for the `extended` metadata tier at connect ([metadataTier]), and an iPhone that refuses
     * that request sends no metadata at all for the session ([onSilentSession] then turns this off).
     */
    @Volatile var lanes = false
        private set
    /** What Settings stored for the panel, by [ClusterContent.Turn]'s ordinal. */
    private val dockStored = arrayOfNulls<String>(ClusterContent.Turn.values().size)
    @Volatile private var oldDock = false

    /**
     * The card holding the bottom-right panel, in the look of the car's gauges, or null. Remembered
     * for each turn card in use (owner, 2026-11-14): with Apple's turn card on the feed this app's own
     * is hidden, and the panel it would hold can take now playing or the drive card instead.
     */
    val docked: Slot? get() = ClusterContent.turn.let { dockedFor(it, dockStored[it.ordinal], oldDock) }

    /** The turn card is the one docked. */
    val dock: Boolean get() = docked == Slot.TURN
    /** The drive card: arrival battery during a route, else this drive against the usual. On by default. */
    @Volatile var drive = true
        private set
    @Volatile private var app: Context? = null

    fun load(ctx: Context) {
        if (app != null) return
        val a = ctx.applicationContext
        val p = a.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        media = p.getBoolean(KEY_MEDIA, true)
        call = p.getBoolean(KEY_CALL, true)
        lanes = p.getBoolean(KEY_LANES, false)
        oldDock = p.getBoolean(KEY_DOCK, false)
        for (t in ClusterContent.Turn.values()) dockStored[t.ordinal] = p.getString(KEY_DOCK_BY + t.key, null)
        drive = p.getBoolean(KEY_DRIVE, true)
        app = a
    }

    private fun save(ctx: Context, key: String, on: Boolean) {
        load(ctx)
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(key, on).apply()
    }

    fun setMedia(ctx: Context, on: Boolean) {
        save(ctx, KEY_MEDIA, on); media = on
        log.i("now playing on the driver display ${if (on) "ON" else "off"} (Settings)")
        if (on) DriverDisplay.preview() else DriverDisplayActivity.refreshPreview()
    }

    fun setDrive(ctx: Context, on: Boolean) {
        save(ctx, KEY_DRIVE, on); drive = on
        log.i("drive card on the driver display ${if (on) "ON" else "off"} (Settings)")
        if (on) DriverDisplay.preview() else DriverDisplayActivity.refreshPreview()
    }

    /** Put [slot]'s card in the bottom-right panel, or nothing, for the turn card now in use. */
    fun setDocked(ctx: Context, slot: Slot?) {
        load(ctx)
        val turn = ClusterContent.turn
        val value = slot?.key ?: DOCK_NONE
        dockStored[turn.ordinal] = value
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_DOCK_BY + turn.key, value).apply()
        log.i("bottom-right panel holds ${docked?.label ?: "nothing"} with the turn card ${turn.short} (Settings)")
        DriverDisplay.preview()
    }

    /** The turn card in use changed, and with it what the panel holds. */
    fun onTurnChanged() {
        log.i("bottom-right panel holds ${docked?.label ?: "nothing"} with the turn card ${ClusterContent.turn.short}")
        DriverDisplayActivity.refreshPreview()
    }

    fun setCall(ctx: Context, on: Boolean) {
        save(ctx, KEY_CALL, on); call = on
        log.i("call status on the driver display ${if (on) "ON" else "off"} (Settings)")
    }

    fun setLanes(ctx: Context, on: Boolean) {
        save(ctx, KEY_LANES, on); lanes = on
        log.i("lane guidance on the driver display ${if (on) "ON" else "off"} (Settings; metadata tier $metadataTier at the next connect)")
        if (on) DriverDisplay.preview() else DriverDisplayActivity.refreshPreview()
    }

    /** The tier the subscribe document asks the adapter for. `0x5204` lanes are in `extended` only. */
    val metadataTier: String get() = if (lanes) "extended" else "proven"

    fun lanesText(): String =
        (if (lanes) "On. " else "Off. ") + "Lane arrows under the turn card where Apple Maps has them. Asks the iPhone " +
            "for more data from the next connect (with a cable, unplug and replug the phone). If a drive then " +
            "shows no now-playing and no turns, this turns itself off for the next connect."

    /**
     * A session ran without one metadata record. With lanes on, the larger Identify is the suspect, so
     * the next connect goes back to the proven one.
     */
    fun onSilentSession() {
        val a = app ?: return
        if (!lanes) return
        log.w("no metadata all session with lane guidance on — turning it off, the next connect asks for the proven tier")
        save(a, KEY_LANES, false); lanes = false
    }

    /** The cards' colours. They follow the car's day and night as CarPlay's map does. */
    data class Palette(val card: Int, val text: Int, val dim: Int, val faint: Int, val track: Int, val accent: Int)

    private val DAY by lazy { Palette(
        card = Color.argb(232, 250, 250, 252), text = Color.rgb(0x14, 0x16, 0x1A), dim = Color.rgb(0x5A, 0x60, 0x68),
        faint = Color.argb(70, 0x14, 0x16, 0x1A), track = Color.argb(46, 0, 0, 0), accent = Color.rgb(0x00, 0x7A, 0xFF),
    ) }
    private val NIGHT by lazy { Palette(
        card = Color.argb(218, 24, 26, 30), text = Color.WHITE, dim = Color.rgb(0xB8, 0xC2, 0xCC),
        faint = Color.argb(80, 255, 255, 255), track = Color.argb(60, 255, 255, 255), accent = Color.rgb(0x0A, 0x84, 0xFF),
    ) }
    fun palette(night: Boolean): Palette = if (night) NIGHT else DAY

    /** A docked card: the gauges' own black, white figures, blue-grey units and orange bar, day and night. */
    val GAUGE = Palette(
        card = 0xFF0B0C0F.toInt(), text = 0xFFFFFFFF.toInt(), dim = 0xFFD0D4DA.toInt(),
        faint = 0x50FFFFFF, track = 0xFF1E2A44.toInt(), accent = 0xFFF26A21.toInt(),
    )
    const val GAUGE_UNIT = 0xFF6F8FB8.toInt()
    /** The blue of the gauges' power bar, for a docked card's own bar and line. */
    const val GAUGE_BAR = 0xFF3F6FD8.toInt()
    const val GAUGE_BAR_BRIGHT = 0xFF7FB4FF.toInt()

    /** The green of a highway exit sign and of an answered call. */
    const val GREEN = 0xFF2B8A3E.toInt()
    /** The same green and a warning amber, as they read on the night card and on the day card. */
    const val GREEN_NIGHT = 0xFF30D158.toInt()
    const val AMBER = 0xFFD9760A.toInt()
    const val AMBER_NIGHT = 0xFFFF9F0A.toInt()
}
