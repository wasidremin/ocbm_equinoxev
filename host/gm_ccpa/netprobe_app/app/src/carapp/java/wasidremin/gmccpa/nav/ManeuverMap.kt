package wasidremin.gmccpa.nav

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.Maneuver
import androidx.core.graphics.drawable.IconCompat
import wasidremin.gmccpa.R

/**
 * Apple ManeuverType (iAP2 `0x5202` param 3, values 0..53) -> Car App Library `Maneuver.TYPE_*`.
 *
 * The table is lvalen91/carlink's `navigation/ManeuverMapper.kt` (public domain), cross-checked there
 * against GM's own RouteStateMachine mapping and live drives; the Apple names are this repo's
 * `metadata` crate `types.rs` `ManeuverType`, which agree index for index.
 *
 * On the VCU radios this table IS the cluster picture: GM's VMSPlugin forwards only
 * `setManeuverType(ManeuverTypeToTurnType(type))` and the cluster draws its own glyph for that enum
 * (gminfo_resources `projection/cluster_navigation.md`, firmware-verified on a CT5). The icon set by
 * [build] is ignored there; it is carried because every path proven on a GM car had one.
 *
 * Roundabout exits are encoded in the TYPE (RoundaboutExit1..19 = 28..46), so the exit number needs
 * no extra iAP2 parameter. The `_WITH_ANGLE` variants carlink added need param 11
 * (JunctionElementExitAngle), which the box does not parse yet; carlink also found the CT5 glyph atlas
 * has no directional roundabouts, so the plain ENTER_AND_EXIT type loses nothing there.
 */
internal object ManeuverMap {
    /** [drivingSide] is Apple's DrivingSide: 0 right-hand traffic, 1 left-hand. Null reads as right-hand. */
    fun type(appleType: Int?, drivingSide: Int?): Int {
        val left = drivingSide == 1
        return when (appleType) {
            // Car App Library has no "no maneuver"; straight is the nearest renderable.
            0 -> Maneuver.TYPE_STRAIGHT                                    // noTurn
            1 -> Maneuver.TYPE_TURN_NORMAL_LEFT                            // leftTurn
            2 -> Maneuver.TYPE_TURN_NORMAL_RIGHT                           // rightTurn
            3 -> Maneuver.TYPE_STRAIGHT                                    // straightAhead
            4 -> uTurn(left)                                               // uTurn
            5 -> Maneuver.TYPE_STRAIGHT                                    // followRoad
            6 -> if (left) Maneuver.TYPE_ROUNDABOUT_ENTER_CW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW // enterRoundabout
            7 -> if (left) Maneuver.TYPE_ROUNDABOUT_EXIT_CW else Maneuver.TYPE_ROUNDABOUT_EXIT_CCW   // exitRoundabout
            8 -> if (left) Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT else Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT // offRamp
            9 -> if (left) Maneuver.TYPE_ON_RAMP_NORMAL_LEFT else Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT   // onRamp
            10 -> Maneuver.TYPE_DESTINATION                                // arriveEndOfNavigation
            11 -> Maneuver.TYPE_DEPART                                     // proceedToRouteStart
            12 -> Maneuver.TYPE_DESTINATION                                // arriveAtDestination
            13 -> Maneuver.TYPE_KEEP_LEFT                                  // keepLeft
            14 -> Maneuver.TYPE_KEEP_RIGHT                                 // keepRight
            15, 16, 17 -> Maneuver.TYPE_FERRY_BOAT                         // enter/exit/changeFerry
            18 -> uTurn(left)                                              // uTurnToRoute
            19 -> if (left) Maneuver.TYPE_ROUNDABOUT_ENTER_CW else Maneuver.TYPE_ROUNDABOUT_ENTER_CCW // roundaboutUTurn
            20 -> Maneuver.TYPE_TURN_NORMAL_LEFT                           // endOfRoadLeft
            21 -> Maneuver.TYPE_TURN_NORMAL_RIGHT                          // endOfRoadRight
            22 -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT                       // highwayOffRampLeft
            23 -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT                      // highwayOffRampRight
            24 -> Maneuver.TYPE_DESTINATION_LEFT                           // arriveAtDestinationLeft
            25 -> Maneuver.TYPE_DESTINATION_RIGHT                          // arriveAtDestinationRight
            26 -> uTurn(left)                                              // uTurnWhenPossible
            27 -> Maneuver.TYPE_DESTINATION                                // arriveEndOfDirections
            in 28..46 -> if (left) Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
                else Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW           // roundaboutExit1..19
            47 -> Maneuver.TYPE_TURN_SHARP_LEFT                            // sharpLeftTurn
            48 -> Maneuver.TYPE_TURN_SHARP_RIGHT                           // sharpRightTurn
            49 -> Maneuver.TYPE_TURN_SLIGHT_LEFT                           // slightLeftTurn
            50 -> Maneuver.TYPE_TURN_SLIGHT_RIGHT                          // slightRightTurn
            51 -> Maneuver.TYPE_KEEP_RIGHT                                 // changeHighway (carlink: fork)
            52 -> Maneuver.TYPE_KEEP_LEFT                                  // changeHighwayLeft
            53 -> Maneuver.TYPE_KEEP_RIGHT                                 // changeHighwayRight
            else -> Maneuver.TYPE_UNKNOWN                                  // absent, or a type newer than 53
        }
    }

    /** RoundaboutExit1..19 carry the exit number in the type itself. */
    fun roundaboutExit(appleType: Int?): Int? = if (appleType != null && appleType in 28..46) appleType - 27 else null

    private fun uTurn(left: Boolean) = if (left) Maneuver.TYPE_U_TURN_RIGHT else Maneuver.TYPE_U_TURN_LEFT

    fun build(m: RouteGuidance.Maneuver, ctx: Context): Maneuver {
        val type = type(m.type, m.drivingSide)
        val b = Maneuver.Builder(type)
        // The Builder REJECTS an ENTER_AND_EXIT maneuver without an exit number.
        roundaboutExit(m.type)?.let { b.setRoundaboutExitNumber(it) }
        // A resource icon, not a bitmap: the host keys resource icons by id, while a bitmap gets a
        // fresh cache id per updateTrip and is re-encoded every second (carlink rev 156).
        b.setIcon(CarIcon.Builder(IconCompat.createWithResource(ctx, icon(type))).build())
        return b.build()
    }

    @DrawableRes
    fun icon(type: Int): Int = when (type) {
        Maneuver.TYPE_TURN_NORMAL_LEFT -> R.drawable.nav_turn_left
        Maneuver.TYPE_TURN_NORMAL_RIGHT -> R.drawable.nav_turn_right
        Maneuver.TYPE_TURN_SLIGHT_LEFT, Maneuver.TYPE_KEEP_LEFT, Maneuver.TYPE_FORK_LEFT,
        Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT, Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT,
        Maneuver.TYPE_ON_RAMP_NORMAL_LEFT, Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT, Maneuver.TYPE_MERGE_LEFT -> R.drawable.nav_slight_left
        Maneuver.TYPE_TURN_SLIGHT_RIGHT, Maneuver.TYPE_KEEP_RIGHT, Maneuver.TYPE_FORK_RIGHT,
        Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT, Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT,
        Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT, Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT, Maneuver.TYPE_MERGE_RIGHT -> R.drawable.nav_slight_right
        Maneuver.TYPE_TURN_SHARP_LEFT -> R.drawable.nav_sharp_left
        Maneuver.TYPE_TURN_SHARP_RIGHT -> R.drawable.nav_sharp_right
        Maneuver.TYPE_U_TURN_LEFT -> R.drawable.nav_uturn_left
        Maneuver.TYPE_U_TURN_RIGHT -> R.drawable.nav_uturn_right
        Maneuver.TYPE_ROUNDABOUT_ENTER_CCW, Maneuver.TYPE_ROUNDABOUT_EXIT_CCW,
        Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW -> R.drawable.nav_roundabout_ccw
        Maneuver.TYPE_ROUNDABOUT_ENTER_CW, Maneuver.TYPE_ROUNDABOUT_EXIT_CW,
        Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW -> R.drawable.nav_roundabout_cw
        Maneuver.TYPE_DESTINATION, Maneuver.TYPE_DESTINATION_LEFT,
        Maneuver.TYPE_DESTINATION_RIGHT, Maneuver.TYPE_DESTINATION_STRAIGHT -> R.drawable.nav_destination
        Maneuver.TYPE_FERRY_BOAT -> R.drawable.nav_ferry
        else -> R.drawable.nav_straight
    }
}
