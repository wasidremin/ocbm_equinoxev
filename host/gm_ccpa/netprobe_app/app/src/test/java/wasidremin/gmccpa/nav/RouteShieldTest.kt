package wasidremin.gmccpa.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import wasidremin.gmccpa.nav.RouteShield.Kind
import wasidremin.gmccpa.nav.RouteShield.Shield

class RouteShieldTest {
    private fun shield(road: String) = RouteShield.parse(road)?.shield
    private fun rest(road: String) = RouteShield.parse(road)?.rest

    @Test fun interstateWithADirection() {
        assertEquals(Shield(Kind.INTERSTATE, "75"), shield("I-75 N"))
        assertEquals("N", rest("I-75 N"))
    }

    @Test fun thePhonesNonBreakingHyphenAndASpaceBothRead() {
        assertEquals(Shield(Kind.INTERSTATE, "285"), shield("I‑285 W"))
        assertEquals(Shield(Kind.US, "40"), shield("US 40"))
        assertEquals("", rest("US 40"))
    }

    @Test fun usRouteWithALetterSuffix() {
        assertEquals(Shield(Kind.US, "9W"), shield("US-9W"))
    }

    @Test fun stateRoutesKeepTheirState() {
        assertEquals(Shield(Kind.STATE, "400", "GA"), shield("GA-400 N"))
        assertEquals(Shield(Kind.STATE, "14", "MI"), shield("M-14 W"))
        assertEquals(Shield(Kind.STATE, "10", "KS"), shield("K-10"))
        assertEquals(Shield(Kind.STATE, "18"), shield("SR-18"))
    }

    @Test fun countyRoad() {
        assertEquals(Shield(Kind.COUNTY, "16"), shield("CR-16"))
    }

    @Test fun whatFollowsTheMarkerIsKept() {
        assertEquals("S / Main St", rest("I-95 S / Main St"))
    }

    @Test fun ordinaryStreetsAreLeftAlone() {
        assertNull(RouteShield.parse("Main St"))
        assertNull(RouteShield.parse("St 5"))
        assertNull(RouteShield.parse("Route 66 Diner Rd"))
        assertNull(RouteShield.parse("1st Ave"))
        assertNull(RouteShield.parse("Exit 12 · I-75 N"))
        assertNull(RouteShield.parse(null))
    }

    @Test fun aWordPrefixNeedsADirectionOrNothingAfter() {
        assertEquals(Shield(Kind.STATE, "9"), shield("Route 9 N"))
        assertEquals(Shield(Kind.STATE, "9"), shield("Highway 9"))
    }

    @Test fun threeFiguresMakeAWiderMarker() {
        assertEquals(10f, RouteShield.width(Shield(Kind.US, "40"), 10f), 0.001f)
        assertEquals(12.8f, RouteShield.width(Shield(Kind.INTERSTATE, "285"), 10f), 0.001f)
    }
}
