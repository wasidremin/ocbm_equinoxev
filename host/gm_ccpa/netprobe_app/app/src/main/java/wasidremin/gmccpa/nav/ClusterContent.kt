package wasidremin.gmccpa.nav

import android.content.Context
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.ocbm.AdapterSession
import wasidremin.gmccpa.ocbm.Ocbm

/**
 * What the iPhone draws on the driver display's CarPlay feed (docs/ops/08_FUTURE_TASKS.md T23), and
 * which turn card the driver gets.
 *
 * Apple Maps offers a cluster three kinds of content on the one feed, and the adapter can ask for
 * each: the map, Apple's own turn card (the maneuver with its lane and junction graphics, no map),
 * and the bare cluster view, which is whatever Maps itself chooses. Settings asks two questions
 * (owner, 2026-11-14), not one:
 *
 * - **The feed's picture** ([Background]): the map, or Apple's view. Settings ▸ Cluster ▸ "Feed shows".
 * - **The turn card** ([Turn]): none, this app's own drawn over the picture, or Apple's. Apple's card
 *   is feed content, so it takes the picture's place while it is chosen: the feed carries one thing
 *   at a time, and the map and Apple's card together would need a second feed.
 *
 * [choice] is what the feed is asked for, from the two.
 */
object ClusterContent {
    private val log = ProbeLog.sub("cluster")
    private const val PREFS = "driver_display"
    /** Before 2026-11-14 this one key held map, card or app. It now holds the picture; `card` reads as Apple's card over the map. */
    private const val KEY = "content"
    private const val KEY_APPLES_CARD = "apples_turn_card"

    /** [short] is the segment's label in Settings; [label] is for the log. */
    enum class Choice(val key: String, val short: String, val label: String, val command: Byte, val url: String) {
        MAP("map", "Map", "the map", Ocbm.CMD_NAV_START, "maps:/car/instrumentcluster/map"),
        CARD("card", "Turn card", "Apple's turn card", Ocbm.CMD_NAV_CARD, "maps:/car/instrumentcluster/instructioncard"),
        APP("app", "Apple's view", "Apple's cluster view", Ocbm.CMD_NAV_APP, "maps:/car/instrumentcluster"),
    }

    /** The feed's picture when Apple's turn card is not in its place. */
    enum class Background(val choice: Choice) { MAP(Choice.MAP), APP(Choice.APP) }

    /** Which turn card. [key] names the bottom-right panel's setting for it ([ClusterLayout.docked]). */
    enum class Turn(val key: String, val short: String) { OFF("off", "Off"), OURS("ours", "This app's"), APPLE("apple", "Apple's") }

    @Volatile var background: Background = Background.MAP
        private set
    @Volatile var applesCard = false
        private set

    /** What the feed is asked for. */
    val choice: Choice get() = choice(background, applesCard)

    /** Pure: Apple's card takes the picture's place while it is chosen. */
    fun choice(background: Background, applesCard: Boolean): Choice = if (applesCard) Choice.CARD else background.choice

    /** Apple's card fills the feed with the maneuver, so the app's own turn card steps aside. */
    val applesCardShowing: Boolean get() = applesCard

    /** Pure: the turn card in use. */
    fun turn(applesCard: Boolean, ownCard: Boolean): Turn = when {
        applesCard -> Turn.APPLE
        ownCard -> Turn.OURS
        else -> Turn.OFF
    }

    val turn: Turn get() = turn(applesCard, DriverDisplay.turnCard)

    fun load(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = fromKey(p.getString(KEY, null))
        background = if (stored == Choice.APP) Background.APP else Background.MAP
        applesCard = p.getBoolean(KEY_APPLES_CARD, stored == Choice.CARD)
    }

    fun fromKey(key: String?): Choice = Choice.values().firstOrNull { it.key == key } ?: Choice.MAP

    private fun apply(ctx: Context, what: String) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, background.choice.key).putBoolean(KEY_APPLES_CARD, applesCard).apply()
        val live = AdapterSession.showClusterContent()
        log.i("$what: the driver display's feed shows ${choice.label} (Settings; ${if (live) "switched now" else "from the next time the feed opens"})")
        DriverDisplayActivity.refreshPreview()
    }

    /** Persist the picture and, if the feed is up and Apple's card is not in its place, switch it now. */
    fun setBackground(ctx: Context, b: Background) {
        background = b
        apply(ctx, "feed picture ${b.choice.label}")
    }

    /** Persist the turn card and switch the feed if the choice changes what it carries. */
    fun setTurn(ctx: Context, t: Turn) {
        if (t != Turn.APPLE) DriverDisplay.setTurnCard(ctx, t == Turn.OURS)
        applesCard = t == Turn.APPLE
        apply(ctx, "turn card ${t.short}")
        ClusterLayout.onTurnChanged()
    }

    fun backgroundText(): String = when {
        applesCard -> "Apple's turn card has the feed while it is chosen below. ${if (background == Background.MAP) "The map" else "Apple's view"} comes back when it is not."
        background == Background.MAP -> "Apple Maps' map, with this app's cards over it."
        else -> "Whatever Apple Maps chooses for a cluster, with this app's cards over it."
    }

    fun turnText(feedOn: Boolean): String = when (turn) {
        Turn.OFF -> "No turn card. Apple's map still shows the route and the arrival time."
        Turn.OURS -> "This app's card over the feed's picture: a small pill with the next turn, growing to a full card in the last " +
            "half mile, with the exit number and the turn after it."
        Turn.APPLE -> "Apple's own card, with its lane and junction pictures, in place of the map: the feed carries one or the " +
            "other." + if (feedOn) "" else " Needs CarPlay on the driver display, above."
    }
}
