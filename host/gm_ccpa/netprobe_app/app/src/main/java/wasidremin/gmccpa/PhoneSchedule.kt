package wasidremin.gmccpa

import android.content.Context
import wasidremin.gmccpa.ocbm.Ocbm
import wasidremin.gmccpa.ocbm.OcbmClient

/**
 * Which iPhone the adapter calls first, by the time of day (docs/ops/08_FUTURE_TASKS.md T35, owner,
 * 2026-11-22: "my phone first priority all workdays until 6 and then weekends and evenings my wife's
 * phone").
 *
 * Each phone may have one [Window]: the days and the hours it goes first. The list in Settings ▸
 * CarPlay ▸ Phones (its ↑/↓) is the **base order** and decides at every other time, and between two
 * phones whose windows both cover the moment. The app keeps the base order here, because the adapter's
 * stored order is now the order of the last connect, not the driver's list.
 *
 * At every connect, before the session's Bluetooth starts, the app sends the order for that moment
 * ([forNow]); adapter firmware that says `"order_at_start":true` keeps it for that start (ocbmd's
 * `store_order`). Nothing changes mid-drive: the order is read when Bluetooth starts. A guess, not a
 * detection: a scheduled phone that is not in the car costs one page timeout (3.2 s with that
 * firmware) before the next phone is called.
 */
object PhoneSchedule {
    private const val PREFS = "phone_schedule"
    private const val P_BASE = "base_order"
    private const val P_WINDOW = "window:"

    const val DAY_MINUTES = 24 * 60
    /** Monday to Friday, as [Window.days] bits (bit 0 is Monday). */
    const val WEEKDAYS = 0b0011111
    const val WEEKEND = 0b1100000
    const val EVERY_DAY = 0b1111111

    /**
     * [days]: bit 0 Monday … bit 6 Sunday. [fromMin] to [toMin], minutes after midnight; a [toMin] at or
     * before [fromMin] runs past midnight into the next day, and equal ones are the whole day.
     */
    data class Window(val days: Int, val fromMin: Int, val toMin: Int)

    // ---- decisions (pure) ----------------------------------------------------------------------

    private fun onDay(days: Int, dayOfWeek: Int) = days and (1 shl (dayOfWeek - 1)) != 0

    /** Pure: whether [w] covers [dayOfWeek] (1 Monday … 7 Sunday, `java.time`'s numbering) at [minute]. */
    fun covers(w: Window, dayOfWeek: Int, minute: Int): Boolean {
        val yesterday = if (dayOfWeek == 1) 7 else dayOfWeek - 1
        return when {
            w.fromMin == w.toMin -> onDay(w.days, dayOfWeek)
            w.fromMin < w.toMin -> onDay(w.days, dayOfWeek) && minute >= w.fromMin && minute < w.toMin
            // Past midnight: the evening of a listed day, or the small hours after one.
            else -> (onDay(w.days, dayOfWeek) && minute >= w.fromMin) || (onDay(w.days, yesterday) && minute < w.toMin)
        }
    }

    /**
     * Pure: the order for this moment: the phones whose window [covers] it, in [base] order, then the
     * rest in [base] order.
     */
    fun orderNow(base: List<String>, windows: Map<String, Window>, dayOfWeek: Int, minute: Int): List<String> {
        val (now, rest) = base.partition { mac -> windows[mac]?.let { covers(it, dayOfWeek, minute) } == true }
        return now + rest
    }

    /**
     * Pure: the driver's list against the adapter's bonds: [stored] for the phones still bonded, then any
     * bond it does not name in the adapter's order (a phone paired since), as btd's own rule does.
     */
    fun base(stored: List<String>?, bonded: List<String>): List<String> {
        val kept = stored.orEmpty().filter { it in bonded }
        return kept + bonded.filter { it !in kept }
    }

    /** Pure: "6 PM", "6:30 PM", or "18:00" / "18:30" on a 24-hour car. */
    fun clock(minute: Int, h24: Boolean): String {
        val h = (minute / 60) % 24
        val m = minute % 60
        if (h24) return "%d:%02d".format(h, m)
        val h12 = if (h % 12 == 0) 12 else h % 12
        val ampm = if (h < 12) "AM" else "PM"
        return if (m == 0) "$h12 $ampm" else "$h12:%02d $ampm".format(m)
    }

    private val SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

    /** Pure: "Weekdays", "Weekends", "Every day", "Mon–Thu", "Mon, Wed, Fri". */
    fun daysText(days: Int): String = when (days and EVERY_DAY) {
        EVERY_DAY -> "Every day"
        WEEKDAYS -> "Weekdays"
        WEEKEND -> "Weekends"
        0 -> "No days"
        else -> {
            val on = (0..6).filter { days and (1 shl it) != 0 }
            if (on.size >= 3 && on.last() - on.first() == on.size - 1) "${SHORT[on.first()]}–${SHORT[on.last()]}"
            else on.joinToString(", ") { SHORT[it] }
        }
    }

    /** Pure: the window in words, as the phone's row shows it: "Weekdays until 6 PM". */
    fun describe(w: Window, h24: Boolean): String {
        val hours = when {
            w.fromMin == w.toMin -> "all day"
            w.fromMin == 0 -> "until ${clock(w.toMin, h24)}"
            w.toMin == 0 -> "from ${clock(w.fromMin, h24)}"
            else -> "${clock(w.fromMin, h24)} to ${clock(w.toMin, h24)}"
        }
        return "${daysText(w.days)} $hours"
    }

    /** Pure: the stored form, "days,from,to", and back; null for anything else. */
    fun encode(w: Window): String = "${w.days},${w.fromMin},${w.toMin}"

    fun decode(s: String?): Window? {
        val p = s?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return null
        if (p.size != 3) return null
        val (d, f, t) = p
        if (d !in 1..EVERY_DAY || f !in 0 until DAY_MINUTES || t !in 0 until DAY_MINUTES) return null
        return Window(d, f, t)
    }

    // ---- state ---------------------------------------------------------------------------------

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun windows(ctx: Context): Map<String, Window> =
        prefs(ctx).all.entries.mapNotNull { (k, v) ->
            if (!k.startsWith(P_WINDOW)) null else decode(v as? String)?.let { k.removePrefix(P_WINDOW) to it }
        }.toMap()

    /** The driver's list, against the adapter's [bonded] phones in its order. */
    fun baseOrder(ctx: Context, bonded: List<String>): List<String> =
        base(prefs(ctx).getString(P_BASE, null)?.split(',')?.filter { it.isNotEmpty() }, bonded)

    fun setBaseOrder(ctx: Context, order: List<String>) {
        prefs(ctx).edit().putString(P_BASE, order.joinToString(",")).apply()
    }

    /**
     * Save [mac]'s window, or clear it with null. The first window also fixes the base order from
     * [bonded] (the adapter's order now), so the list is not later read back from a scheduled order.
     */
    fun setWindow(ctx: Context, mac: String, w: Window?, bonded: List<String>) {
        val p = prefs(ctx)
        val e = p.edit()
        if (!p.contains(P_BASE)) e.putString(P_BASE, bonded.joinToString(","))
        if (w == null) e.remove(P_WINDOW + mac) else e.putString(P_WINDOW + mac, encode(w))
        e.apply()
    }

    /** A forgotten phone's window goes with it. */
    fun forget(ctx: Context, mac: String) {
        prefs(ctx).edit().remove(P_WINDOW + PhoneBook.normalize(mac)).apply()
    }

    /** The order for now on this head unit's clock, against the adapter's [bonded] phones. */
    fun forNow(ctx: Context, bonded: List<String>): List<String> {
        val now = java.time.LocalDateTime.now()
        return orderNow(baseOrder(ctx, bonded), windows(ctx), now.dayOfWeek.value, now.hour * 60 + now.minute)
    }

    /** Whether the driver's list is kept here (a window was ever set, or the arrows were used). */
    private fun ownsList(ctx: Context): Boolean = prefs(ctx).contains(P_BASE)

    /**
     * The phone the adapter will page first this connect: the order for now where this app sends it
     * ([atConnect]'s rule), else the adapter's own first. Null with no phone bonded. Settings per iPhone
     * (T39) are put in place for it before the subscribe.
     */
    fun expectedFirst(ctx: Context, mgmtInfo: String?): String? {
        val bonded = PhoneBook.devicesOf(mgmtInfo)
        if (bonded.size < 2 || !ownsList(ctx) || mgmtInfo?.contains("\"order_at_start\":true") != true) return bonded.firstOrNull()
        val now = java.time.LocalDateTime.now()
        return orderNow(baseOrder(ctx, bonded), windows(ctx).filterKeys { it in bonded }, now.dayOfWeek.value, now.hour * 60 + now.minute).first()
    }

    /**
     * At a connect, before `CT_SUBSCRIBE` starts the session's Bluetooth: send the order for now when it
     * differs from the adapter's, and the firmware keeps an order sent then. Blocking; on the link thread.
     * Logs positions in the driver's list, never a name or an address.
     */
    fun atConnect(ctx: Context, c: OcbmClient, mgmtInfo: String?) {
        val bonded = PhoneBook.devicesOf(mgmtInfo)
        if (bonded.size < 2 || !ownsList(ctx)) return
        val windows = windows(ctx).filterKeys { it in bonded }
        if (mgmtInfo?.contains("\"order_at_start\":true") != true) {
            if (windows.isNotEmpty()) log.w("phones: ${windows.size} schedule(s) set, but this adapter firmware cannot take an order before Bluetooth starts: update the adapter")
            return
        }
        val base = baseOrder(ctx, bonded)
        val now = java.time.LocalDateTime.now()
        val order = orderNow(base, windows, now.dayOfWeek.value, now.hour * 60 + now.minute)
        val first = base.indexOf(order.first()) + 1
        val covering = windows.count { covers(it.value, now.dayOfWeek.value, now.hour * 60 + now.minute) }
        if (order == bonded) {
            log.i("phones: list phone $first first, as the adapter has it ($covering of ${windows.size} schedule(s) cover now)")
            return
        }
        val st = c.mgmtAction(Ocbm.MGMT_SET_ORDER, order.joinToString(",").toByteArray(Charsets.US_ASCII))
        log.i("phones: list phone $first first for this start ($covering of ${windows.size} schedule(s) cover now): ${if (st == 0) "the adapter took it" else "NOT taken, status $st"}")
    }

    private val log by lazy { ProbeLog.sub("phone") }
}
