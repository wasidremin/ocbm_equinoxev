package wasidremin.gmccpa.telemetry

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProbeModelTest {
    @Test fun unsignedHex() {
        assertEquals("0xFFFFFFFF", ProbeModel.hex(-1))
        assertEquals("0x80000000", ProbeModel.hex(Int.MIN_VALUE))
        assertEquals("0x00000000", ProbeModel.hex(0))
    }
    @Test fun groupsUseOnlyGroupBits() {
        assertEquals("SYSTEM", ProbeModel.group(0x11600207))
        assertEquals("VENDOR", ProbeModel.group(0x2FE01234))
        assertEquals("BACKPORTED", ProbeModel.group(0x31400123))
        assertEquals("OTHER", ProbeModel.group(-1))
        assertEquals("OTHER", ProbeModel.group(0))
    }
    @Test fun primitiveAndArrayRendering() {
        assertEquals("true", ProbeModel.render(true))
        assertEquals("7", ProbeModel.render(7))
        assertEquals("null", ProbeModel.render(null))
        assertEquals("[1,2]", ProbeModel.render(intArrayOf(1, 2)))
        assertEquals("[1,2]", ProbeModel.render(longArrayOf(1, 2)))
        assertEquals("[true,false]", ProbeModel.render(booleanArrayOf(true, false)))
        assertEquals("[[1],null]", ProbeModel.render(arrayOf(intArrayOf(1), null)))
        assertEquals("NaN", ProbeModel.render(Float.NaN))
    }
    @Test fun permissionClassification() {
        assertEquals("UNDEFINED", ProbeModel.permissionClass(false, true, false, 2))
        assertEquals("NOT_DECLARED", ProbeModel.permissionClass(true, false, false, 1))
        assertEquals("GRANTED", ProbeModel.permissionClass(true, true, true, 1))
        assertEquals("DENIED", ProbeModel.permissionClass(true, true, false, 1))
        assertEquals("DENIED_SIGNATURE_OR_PRIVILEGED", ProbeModel.permissionClass(true, true, false, 2 or 16))
        assertEquals("DENIED", ProbeModel.permissionClass(true, true, false, 0))
    }
    @Test fun expectedReadResults() {
        assertEquals("READABLE", ProbeModel.readClass(0, 0f))
        assertEquals("PRESENT_BUT_UNAVAILABLE", ProbeModel.readClass(1, 0f))
        assertEquals("PRESENT_BUT_UNAVAILABLE", ProbeModel.readClass(0, null))
        assertEquals("READ_FAILED", ProbeModel.readClass(2, 0f))
        assertEquals("PRESENT_BUT_PERMISSION_DENIED", ProbeModel.readClass(null, null, SecurityException("denied")))
        assertEquals("READ_FAILED", ProbeModel.readClass(null, null, IllegalArgumentException("unsupported")))
        assertEquals("READ_FAILED", ProbeModel.readClass(null, 1))
    }
    @Test fun jsonSerializationIsTypedAndEscaped() {
        val original = JSONObject().put("value", ProbeModel.jsonValue(arrayOf("quote\"\n", intArrayOf(2, 3))))
            .put("null", ProbeModel.jsonValue(null)).put("error", ProbeModel.error(SecurityException("no access")))
        val parsed = JSONObject(original.toString())
        assertEquals("quote\"\n", parsed.getJSONArray("value").getString(0))
        assertEquals(3, parsed.getJSONArray("value").getJSONArray(1).getInt(1))
        assertTrue(parsed.isNull("null"))
        assertEquals("java.lang.SecurityException", parsed.getJSONObject("error").getString("class"))
    }
    @Test fun csvEscapesQuotesCommasAndNewlines() {
        assertEquals("\"a,\"\"b\"\"\nc\"", ProbeModel.csv("a,\"b\"\nc"))
        assertEquals("\"\"", ProbeModel.csv(null))
        assertEquals("\"1\"", ProbeModel.csv(1))
    }
    @Test fun rateIsConservativeAndClamped() {
        assertEquals(0f, ProbeModel.rate(1, 0f, 0f))
        assertEquals(1f, ProbeModel.rate(2, .1f, 10f))
        assertEquals(2f, ProbeModel.rate(2, 2f, 10f))
        assertEquals(.5f, ProbeModel.rate(2, .1f, .5f))
        assertNull(ProbeModel.rate(2, 10f, 20f))
        assertNull(ProbeModel.rate(2, Float.NaN, 10f))
        assertNull(ProbeModel.rate(2, 5f, 1f))
        assertNull(ProbeModel.rate(0, 0f, 0f))
    }
    @Test fun subscriptionStatisticsExcludeUnavailableExtrema() {
        val stats = ProbeStats()
        stats.add(1_000_000_000, 2f, 0)
        stats.add(2_000_000_000, 100f, 1)
        stats.add(3_000_000_000, 4f, 0)
        val j = JSONObject(stats.json().toString())
        assertEquals(3, j.getInt("callbacks"))
        assertEquals(2, j.getInt("availableCallbacks"))
        assertEquals(1.0, j.getDouble("observedHz"), 0.00001)
        assertEquals(2.0, j.getDouble("min"), 0.00001)
        assertEquals(4.0, j.getDouble("max"), 0.00001)
        assertEquals(2, j.getInt("distinctValues"))
        assertFalse(j.getBoolean("distinctIsLowerBound"))
    }
    @Test fun arrayStatsArePerElement() {
        val s = ProbeStats()
        s.add(1, longArrayOf(0, 10), 0)
        s.add(2, longArrayOf(1, 8), 0)
        val j = s.json()
        assertTrue(j.isNull("min"))
        assertEquals(8.0, j.getJSONObject("arrayElementStatistics").getJSONObject("1").getDouble("min"), 0.0)
        assertEquals(10.0, j.getJSONObject("arrayElementStatistics").getJSONObject("1").getDouble("max"), 0.0)
    }
    @Test fun headlineIdsCoverPublicCompileSdk() {
        assertEquals(ProbeModel.headlines.size, ProbeModel.compileIds.size)
        assertEquals(290521862, ProbeModel.compileIds["WHEEL_TICK"])
        assertTrue(ProbeModel.compileIds.values.all { ProbeModel.group(it) == "SYSTEM" })
        assertEquals(23, JSONArray(ProbeModel.headlines).length())
    }
}
