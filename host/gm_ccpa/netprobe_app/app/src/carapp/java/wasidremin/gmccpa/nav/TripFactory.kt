package wasidremin.gmccpa.nav

import android.content.Context
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * [RouteGuidance.Snapshot] -> [Trip]. The shape is lvalen91/carlink's `navigation/TripBuilder.kt`
 * (public domain): the current step, an optional next step, the destination, and `setLoading(false)`.
 *
 * What differs is the source. carlink rebuilt the picture from the adapter's NaviJSON; here the
 * iAP2 fields arrive whole, so:
 * - the current step's distance is `0x5201`'s live distance-to-maneuver (about 1 Hz), not the
 *   maneuver's static between-maneuvers distance;
 * - the ETA is now + TimeRemainingToDestination, NOT the raw EstimatedTimeOfArrival param: the macOS
 *   host found that epoch is expressed against the destination's time zone and rendered a four-hour
 *   shifted clock (`MetadataWindow.swift`, 2026-07-12);
 * - step cue = the road AFTER the maneuver, as carlink does: the cluster has room for a road name,
 *   not Apple's full "Turn right onto…" sentence.
 */
internal object TripFactory {
    fun build(s: RouteGuidance.Snapshot, ctx: Context, nowMs: Long = System.currentTimeMillis()): Trip {
        val eta = eta(s, nowMs)
        val b = Trip.Builder()
        val cur = s.current
        if (cur != null) {
            // Live distance first; the maneuver's own display pair if a summary has not arrived yet.
            val d = NavDistance.of(s.distanceToManeuverText, s.distanceToManeuverUnits, s.distanceToManeuverM)
                ?: NavDistance.of(cur.distanceText, cur.distanceUnits, null)
                ?: ZERO
            b.addStep(step(cur, ctx), TravelEstimate.Builder(d, eta).build())
            // Preview of the turn after. Its own between-maneuvers distance is the honest figure the
            // phone gives; the cluster renders the current step's distance, this one is context.
            s.next?.let { n ->
                val nd = NavDistance.of(n.distanceText, n.distanceUnits, null) ?: d
                b.addStep(step(n, ctx), TravelEstimate.Builder(nd, eta).build())
            }
        }
        val remaining = NavDistance.of(s.distanceRemainingText, s.distanceRemainingUnits, s.distanceRemainingM)
        if (s.destinationName != null || remaining != null) {
            val dest = Destination.Builder()
            s.destinationName?.let { dest.setName(it) }
            val est = TravelEstimate.Builder(remaining ?: ZERO, eta)
            s.timeRemainingSec?.takeIf { it >= 0 }?.let { est.setRemainingTimeSeconds(it) }
            b.addDestination(dest.build(), est.build())
        }
        s.currentRoadName?.let { b.setCurrentRoad(it) }
        // Not ceremony: GM shows a spinner instead of the turn card while a Trip is loading (carlink).
        b.setLoading(false)
        return b.build()
    }

    private fun step(m: RouteGuidance.Maneuver, ctx: Context): Step {
        val sb = Step.Builder().setManeuver(ManeuverMap.build(m, ctx))
        (m.afterRoadName ?: m.description)?.let { sb.setCue(it) }
        m.afterRoadName?.let { sb.setRoad(it) }
        return sb.build()
    }

    /** now + time remaining, in the destination's zone when the phone stated its offset. */
    internal fun eta(s: RouteGuidance.Snapshot, nowMs: Long): ZonedDateTime {
        val zone: ZoneId = s.destTimeZoneOffsetMin
            ?.takeIf { it in -18 * 60..18 * 60 }
            ?.let { ZoneOffset.ofTotalSeconds(it * 60) }
            ?: ZoneId.systemDefault()
        val at = Instant.ofEpochMilli(nowMs).plusSeconds((s.timeRemainingSec ?: 0L).coerceAtLeast(0L))
        return ZonedDateTime.ofInstant(at, zone)
    }

    private val ZERO: Distance = Distance.create(0.0, Distance.UNIT_METERS)
}
