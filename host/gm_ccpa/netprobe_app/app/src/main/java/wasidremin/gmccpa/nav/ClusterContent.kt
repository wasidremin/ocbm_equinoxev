package wasidremin.gmccpa.nav

import android.content.Context
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.ocbm.AdapterSession
import wasidremin.gmccpa.ocbm.Ocbm

/**
 * What the iPhone draws on the driver display's CarPlay stream (docs/ops/08_FUTURE_TASKS.md T23).
 * Settings ▸ Cluster ▸ "Driver display shows".
 *
 * Apple Maps offers a cluster three kinds of content on the one stream, and the adapter has always
 * been able to ask for each: the map, Apple's own turn card (the maneuver with its lane and junction
 * graphics, no map), and the bare cluster view, which is whatever Maps itself chooses. The app only
 * ever asked for the map. Map stays the default.
 */
object ClusterContent {
    private val log = ProbeLog.sub("cluster")
    private const val PREFS = "driver_display"
    private const val KEY = "content"

    /** [short] is the segment's label in Settings; [label] is for the log. */
    enum class Choice(val key: String, val short: String, val label: String, val command: Byte, val url: String) {
        MAP("map", "Map", "the map", Ocbm.CMD_NAV_START, "maps:/car/instrumentcluster/map"),
        CARD("card", "Turn card", "Apple's turn card", Ocbm.CMD_NAV_CARD, "maps:/car/instrumentcluster/instructioncard"),
        APP("app", "Apple's view", "Apple's cluster view", Ocbm.CMD_NAV_APP, "maps:/car/instrumentcluster"),
    }

    @Volatile var choice: Choice = Choice.MAP
        private set

    /** Apple's card fills the stream with the maneuver, so the app's own turn card steps aside. */
    val applesCardShowing: Boolean get() = choice == Choice.CARD

    fun load(ctx: Context) {
        choice = fromKey(ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
    }

    fun fromKey(key: String?): Choice = Choice.values().firstOrNull { it.key == key } ?: Choice.MAP

    /** Persist the choice and, if the stream is up, switch it now. */
    fun set(ctx: Context, c: Choice) {
        choice = c
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, c.key).apply()
        val live = AdapterSession.showClusterContent()
        log.i("driver display shows ${c.label} (Settings; ${if (live) "switched now" else "from the next time the map opens"})")
        DriverDisplayActivity.refreshPreview()
    }

    fun statusText(): String = when (choice) {
        Choice.MAP -> "Apple Maps' map, with this app's cards over it."
        Choice.CARD -> "Apple's own turn card in place of the map. This app's turn card is hidden while it shows."
        Choice.APP -> "Whatever Apple Maps chooses for a cluster. This app's cards stay over it."
    }
}
