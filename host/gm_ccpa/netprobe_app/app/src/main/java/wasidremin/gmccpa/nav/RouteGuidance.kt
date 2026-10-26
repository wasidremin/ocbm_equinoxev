package wasidremin.gmccpa.nav

import org.json.JSONObject
import wasidremin.gmccpa.av.optIntOrNull
import wasidremin.gmccpa.av.optLongOrNull
import wasidremin.gmccpa.av.optStringOrNull

/**
 * The CarPlay route picture, merged from the two navigation kinds on the `:9004` metadata seam.
 *
 * The box already declares, subscribes and parses iAP2 route guidance (`features.rs` puts it in the
 * Proven tier), so these records reach the app on every navigated drive — doc 14 §14.8 caught them
 * arriving and being thrown away as `ignoredKinds`. This class is the consuming half.
 *
 * ## Two record kinds, two different merge rules
 * - `routeGuidance` (`0x5201`, `metadata.rs` `route_guidance`) is the trip summary, about once a
 *   second. It is a DELTA like `nowPlaying`: fields it omits keep their last value.
 * - `maneuver` (`0x5202`, `metadata.rs` `maneuver`) is ONE entry of the route's maneuver list, keyed
 *   by its `index`. iOS streams the whole list near route start, and `0x5201`'s
 *   `currentManeuverIndex` (the first element of RouteGuidanceManeuverCurrentList) says which entry
 *   is the next turn. An entry is REPLACED, never merged into another index — merging is how a stale
 *   "then onto X" from an earlier turn survives into the next one (the macOS host learned this,
 *   `MetadataWindow.swift`). Without the index the picture shows the route's first maneuver for the
 *   whole drive, which is the 2026-07-12 "Proceed to the route" bug the box comment records.
 *
 * Pure (org.json only) so the merge is unit-tested on the JVM. Thread-safe by its own monitor: the
 * records arrive on the `cp-meta` thread and [ClusterNav] reads [snapshot] from the main thread.
 */
class RouteGuidance {
    /** One `0x5202` entry. Distances are Apple's DISPLAY values: text WITHOUT units, plus the unit enum. */
    data class Maneuver(
        val index: Int,
        /** Apple ManeuverType 0..53 (`metadata` crate `types.rs`). Null when the record omitted it. */
        val type: Int? = null,
        val description: String? = null,
        val afterRoadName: String? = null,
        val distanceText: String? = null,
        val distanceUnits: Int? = null,
        /** 0 right-hand traffic, 1 left-hand. Decides U-turn and roundabout direction. */
        val drivingSide: Int? = null,
        val junctionType: Int? = null,
        /** Only sent when the subscribe asks for ExitInfo, which today's does not (doc 03 selector gap). */
        val exitInfo: String? = null,
    )

    data class Snapshot(
        /** RouteGuidanceState: 0 no route, 1 route set, 2 arrived, 3 loading, 4 locating, 5 rerouting, 6 proceed to route. */
        val state: Int? = null,
        val maneuverState: Int? = null,
        val currentRoadName: String? = null,
        val destinationName: String? = null,
        val timeRemainingSec: Long? = null,
        val distanceRemainingText: String? = null,
        val distanceRemainingUnits: Int? = null,
        /** Meters. Apple marks the raw meters "graphics only"; the display pair is what gets shown. */
        val distanceRemainingM: Long? = null,
        val distanceToManeuverText: String? = null,
        val distanceToManeuverUnits: Int? = null,
        val distanceToManeuverM: Long? = null,
        val destTimeZoneOffsetMin: Int? = null,
        val currentIndex: Int? = null,
        val maneuverCount: Int? = null,
        /** The next turn: the entry [currentIndex] names. */
        val current: Maneuver? = null,
        /** The turn after it, for a preview step. */
        val next: Maneuver? = null,
    ) {
        /** A route exists. Arrived counts: the destination card is still worth showing until it ends. */
        val routeActive: Boolean get() = state != null && state != NO_ROUTE

        /** Enough to put a turn on the cluster. */
        val showable: Boolean get() = routeActive && current != null
    }

    private val maneuvers = HashMap<Int, Maneuver>()
    @Volatile var snapshot: Snapshot = Snapshot(); private set

    /**
     * Apply one record. Returns the new snapshot when it differs from the last one, else null — the
     * ~1 Hz `0x5201` frames often change nothing a cluster shows twice.
     */
    @Synchronized
    fun onRecord(kind: String, json: JSONObject): Snapshot? {
        val prev = snapshot
        val next = when (kind) {
            KIND_ROUTE -> mergeRoute(prev, json)
            KIND_MANEUVER -> mergeManeuver(prev, json)
            else -> return null
        }
        snapshot = next
        return if (next == prev) null else next
    }

    /** Session over. Clears everything; the next route starts blank. */
    @Synchronized
    fun clear(): Snapshot {
        maneuvers.clear()
        snapshot = Snapshot()
        return snapshot
    }

    private fun mergeRoute(prev: Snapshot, json: JSONObject): Snapshot {
        val state = json.optIntOrNull("routeGuidanceState")
        // Route ended: everything goes, so a stale turn never outlives the route that owned it.
        if (state == NO_ROUTE) {
            maneuvers.clear()
            return Snapshot(state = NO_ROUTE)
        }
        // Deliberately NO clear on a route start or a new destination. The list can arrive BEFORE the
        // summary that announces its route, so clearing here would discard the new route's own
        // maneuvers. The clear above already ran when the previous route ended; a reroute or a
        // destination change re-sends entries by index, which replaces them, and the count prune
        // below drops the tail a shorter list no longer has.
        val base = prev
        val count = json.optIntOrNull("maneuverTotalCount") ?: base.maneuverCount
        if (count != null) maneuvers.keys.retainAll { it < count }
        val currentIndex = json.optIntOrNull("currentManeuverIndex") ?: base.currentIndex
        return withManeuvers(Snapshot(
            state = state ?: base.state,
            maneuverState = json.optIntOrNull("maneuverState") ?: base.maneuverState,
            currentRoadName = json.optStringOrNull("currentRoadName") ?: base.currentRoadName,
            destinationName = json.optStringOrNull("destinationName") ?: base.destinationName,
            timeRemainingSec = json.optLongOrNull("timeRemainingSec") ?: base.timeRemainingSec,
            distanceRemainingText = json.optStringOrNull("distanceRemainingText") ?: base.distanceRemainingText,
            distanceRemainingUnits = json.optIntOrNull("distanceRemainingUnits") ?: base.distanceRemainingUnits,
            distanceRemainingM = json.optLongOrNull("distanceRemainingM") ?: base.distanceRemainingM,
            distanceToManeuverText = json.optStringOrNull("distanceToManeuverText") ?: base.distanceToManeuverText,
            distanceToManeuverUnits = json.optIntOrNull("distanceToManeuverUnits") ?: base.distanceToManeuverUnits,
            distanceToManeuverM = json.optLongOrNull("distanceToManeuverM") ?: base.distanceToManeuverM,
            destTimeZoneOffsetMin = json.optIntOrNull("destTimeZoneOffsetMin") ?: base.destTimeZoneOffsetMin,
            currentIndex = currentIndex,
            maneuverCount = count,
        ))
    }

    private fun mergeManeuver(prev: Snapshot, json: JSONObject): Snapshot {
        // Every 0x5202 carries its Index. One that does not is placed at 0, as the macOS host does.
        val index = json.optIntOrNull("index") ?: 0
        maneuvers[index] = Maneuver(
            index = index,
            type = json.optIntOrNull("maneuverType"),
            description = json.optStringOrNull("description"),
            afterRoadName = json.optStringOrNull("afterManeuverRoadName"),
            distanceText = json.optStringOrNull("distanceText"),
            distanceUnits = json.optIntOrNull("distanceUnits"),
            drivingSide = json.optIntOrNull("drivingSide"),
            junctionType = json.optIntOrNull("junctionType"),
            exitInfo = json.optStringOrNull("exitInfo"),
        )
        // Bounded, but never by dropping what is still ahead: a long route streams its whole list at
        // once, and evicting the lowest index (the macOS host's rule) would evict the next turn.
        if (maneuvers.size > MAX_MANEUVERS) {
            val cur = prev.currentIndex ?: Int.MAX_VALUE
            val behind = maneuvers.keys.filter { it < cur }.sorted()
            val victim = behind.firstOrNull() ?: maneuvers.keys.maxOrNull()
            if (victim != null && victim != index) maneuvers.remove(victim)
        }
        return withManeuvers(prev)
    }

    /** Resolve [Snapshot.current] / [Snapshot.next] from the index the phone named. */
    private fun withManeuvers(s: Snapshot): Snapshot {
        // Until a 0x5201 names the current index, the lowest entry seen is the best guess — at route
        // start that is maneuver 0, the depart step.
        val cur = s.currentIndex ?: maneuvers.keys.minOrNull()
        return s.copy(
            current = cur?.let { maneuvers[it] },
            next = cur?.let { maneuvers[it + 1] },
        )
    }

    companion object {
        const val KIND_ROUTE = "routeGuidance"
        const val KIND_MANEUVER = "maneuver"

        const val NO_ROUTE = 0
        const val ROUTE_SET = 1
        const val ARRIVED = 2
        const val LOADING = 3
        const val LOCATING = 4
        const val REROUTING = 5
        const val PROCEED_TO_ROUTE = 6

        /** Far above any real route; only a runaway stream reaches it. */
        const val MAX_MANEUVERS = 512
    }
}
