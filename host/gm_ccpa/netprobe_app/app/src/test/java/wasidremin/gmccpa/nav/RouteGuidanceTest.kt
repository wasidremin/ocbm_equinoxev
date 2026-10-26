package wasidremin.gmccpa.nav

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The route merge behind the cluster: key names are the box's (`metadata.rs` route_guidance / maneuver). */
class RouteGuidanceTest {
    private fun rg(json: String) = JSONObject("""{"kind":"routeGuidance",$json}""")
    private fun mv(json: String) = JSONObject("""{"kind":"maneuver",$json}""")

    private fun RouteGuidance.route(json: String) = onRecord(RouteGuidance.KIND_ROUTE, rg(json))
    private fun RouteGuidance.turn(json: String) = onRecord(RouteGuidance.KIND_MANEUVER, mv(json))

    @Test fun currentIndexPicksTheTurnNotTheFirstManeuver() {
        val r = RouteGuidance()
        r.turn(""""index":0,"maneuverType":11,"afterManeuverRoadName":"Main St"""")
        r.turn(""""index":1,"maneuverType":2,"afterManeuverRoadName":"Oak Ave"""")
        r.turn(""""index":2,"maneuverType":1,"afterManeuverRoadName":"Elm St"""")
        r.route(""""routeGuidanceState":1,"currentManeuverIndex":1,"maneuverTotalCount":3""")
        val s = r.snapshot
        assertTrue(s.showable)
        assertEquals(1, s.current?.index)
        assertEquals(2, s.current?.type)
        assertEquals(2, s.next?.index)
    }

    @Test fun beforeAnyIndexTheLowestManeuverStands() {
        val r = RouteGuidance()
        r.turn(""""index":3,"maneuverType":2""")
        r.turn(""""index":0,"maneuverType":11""")
        assertEquals(0, r.snapshot.current?.index)
        // No 0x5201 yet: there is a turn, but no route state, so nothing goes to the cluster.
        assertFalse(r.snapshot.showable)
    }

    @Test fun maneuversArrivingBeforeTheSummaryAreKept() {
        // iOS can stream the list before the first 0x5201 of the route; that summary must not wipe it.
        val r = RouteGuidance()
        r.route(""""routeGuidanceState":0""")
        r.turn(""""index":0,"maneuverType":11""")
        r.route(""""routeGuidanceState":1,"currentManeuverIndex":0""")
        assertEquals(11, r.snapshot.current?.type)
    }

    @Test fun summaryIsADeltaManeuverIsAReplacement() {
        val r = RouteGuidance()
        r.route(""""routeGuidanceState":1,"destinationName":"Home","timeRemainingSec":600,"distanceToManeuverText":"0.5","distanceToManeuverUnits":1""")
        r.route(""""distanceToManeuverText":"0.4"""")
        assertEquals("Home", r.snapshot.destinationName)
        assertEquals(600L, r.snapshot.timeRemainingSec)
        assertEquals("0.4", r.snapshot.distanceToManeuverText)
        assertEquals(1, r.snapshot.distanceToManeuverUnits)
        // Same index again: the new record REPLACES it, so a road the new one omits is gone.
        r.turn(""""index":0,"maneuverType":2,"afterManeuverRoadName":"Oak Ave"""")
        r.turn(""""index":0,"maneuverType":1""")
        assertEquals(1, r.snapshot.current?.type)
        assertNull(r.snapshot.current?.afterRoadName)
    }

    @Test fun stateZeroClearsEverything() {
        val r = RouteGuidance()
        r.turn(""""index":0,"maneuverType":2""")
        r.route(""""routeGuidanceState":1,"destinationName":"Home","currentManeuverIndex":0""")
        assertTrue(r.snapshot.showable)
        val ended = r.route(""""routeGuidanceState":0""")
        assertNotNull(ended)
        assertFalse(ended!!.routeActive)
        assertNull(ended.current)
        assertNull(ended.destinationName)
        // A later route does not inherit the old list.
        r.route(""""routeGuidanceState":1,"currentManeuverIndex":0""")
        assertNull(r.snapshot.current)
    }

    @Test fun arrivedIsStillAnActiveRoute() {
        val r = RouteGuidance()
        r.turn(""""index":4,"maneuverType":12""")
        r.route(""""routeGuidanceState":2,"currentManeuverIndex":4""")
        assertTrue(r.snapshot.routeActive)
        assertTrue(r.snapshot.showable)
    }

    @Test fun shorterRerouteDropsTheOldTail() {
        val r = RouteGuidance()
        for (i in 0..5) r.turn(""""index":$i,"maneuverType":2""")
        r.route(""""routeGuidanceState":1,"currentManeuverIndex":4,"maneuverTotalCount":6""")
        assertEquals(5, r.snapshot.next?.index)
        // Reroute to a 5-entry list: index 5 belonged to the old route.
        r.route(""""routeGuidanceState":5,"maneuverTotalCount":5""")
        assertNull(r.snapshot.next)
    }

    @Test fun unchangedRecordReportsNoChange() {
        val r = RouteGuidance()
        assertNotNull(r.route(""""routeGuidanceState":1,"timeRemainingSec":60"""))
        assertNull(r.route(""""routeGuidanceState":1,"timeRemainingSec":60"""))
        assertNull(r.onRecord("nowPlaying", JSONObject("{}")))
    }

    @Test fun boundNeverEvictsWhatIsAhead() {
        val r = RouteGuidance()
        r.route(""""routeGuidanceState":1,"currentManeuverIndex":10""")
        for (i in 0..RouteGuidance.MAX_MANEUVERS + 20) r.turn(""""index":$i,"maneuverType":2""")
        assertEquals(10, r.snapshot.current?.index)
        assertEquals(11, r.snapshot.next?.index)
    }

    @Test fun clearForgetsTheRoute() {
        val r = RouteGuidance()
        r.turn(""""index":0,"maneuverType":2""")
        r.route(""""routeGuidanceState":1,"currentManeuverIndex":0""")
        val s = r.clear()
        assertFalse(s.routeActive)
        assertNull(r.snapshot.current)
    }
}
