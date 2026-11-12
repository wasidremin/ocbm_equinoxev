package wasidremin.gmccpa.telemetry

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class ProbeSlimTest {
    private fun report(events: Int = 3, model: String = "car") = JSONObject()
        .put("schemaVersion", 1).put("model", model).put("startWallTime", "2026-11-11T08:00:00.000-06:00")
        .put("permissions", JSONArray().put(JSONObject().put("name", "p").put("granted", true)))
        .put("properties", JSONArray().put(JSONObject().put("id", 291504647).put("name", "PERF_VEHICLE_SPEED")))
        .put("subscriptions", JSONArray().put(JSONObject().put("id", 291504647)))
        .put("propertyListCaveat", "caveat").put("wheelTickContract", "contract")
        .put("confirmedFacts", JSONArray().put("a")).put("notTestedOrInconclusive", JSONArray().put("b"))
        .put("rawLiveEvents", JSONArray().apply { repeat(events) { put(JSONObject().put("kind", "PROPERTY").put("n", it)) } })

    private fun gunzip(bytes: ByteArray): JSONObject =
        JSONObject(GZIPInputStream(ByteArrayInputStream(bytes)).readBytes().toString(Charsets.UTF_8))

    /** What the receiver does: cached static keys fill in what the body lacks, events stay last. */
    private fun merged(body: JSONObject, static: JSONObject): JSONObject {
        val out = JSONObject(body.toString())
        out.remove("static")
        for (k in static.keys()) if (!out.has(k)) out.put(k, static.get(k))
        return out
    }

    @Test fun `a report without a property census is not split`() {
        assertNull(ProbeSlim.split(JSONObject().put("device", "x")))
    }

    @Test fun `the first body carries the static part and later ones do not`() {
        val split = ProbeSlim.split(report())!!
        val first = gunzip(split.body(includeStatic = true))
        val later = gunzip(split.body(includeStatic = false))
        assertEquals(split.staticId, first.getString("staticId"))
        assertTrue(first.has("static"))
        assertEquals(ProbeSlim.STATIC_KEYS.toSet(), first.getJSONObject("static").keySet())
        assertFalse(later.has("static"))
        for (k in ProbeSlim.STATIC_KEYS) assertFalse("$k leaked into the dynamic part", later.has(k))
        assertEquals(3, later.getJSONArray("rawLiveEvents").length())
    }

    @Test fun `the receiver's merge gives back the original report`() {
        val original = report()
        val split = ProbeSlim.split(JSONObject(original.toString()))!!
        val first = gunzip(split.body(true))
        val rebuilt = merged(gunzip(split.body(false)), first.getJSONObject("static"))
        rebuilt.remove("staticId")
        assertTrue(original.similar(rebuilt))
    }

    @Test fun `the id is the hash of exactly the static bytes that are sent`() {
        val split = ProbeSlim.split(report())!!
        val text = GZIPInputStream(ByteArrayInputStream(split.body(true))).readBytes().toString(Charsets.UTF_8)
        val staticText = text.substringAfter(",\"static\":").dropLast(1)
        assertEquals(split.staticId, ProbeSlim.staticId(staticText))
        assertTrue(Regex("^[0-9a-f]{16}$").matches(split.staticId))
    }

    @Test fun `segments of one capture share an id and a different census does not`() {
        val a = ProbeSlim.split(report(events = 1))!!.staticId
        val b = ProbeSlim.split(report(events = 400))!!.staticId
        assertEquals(a, b)
        val other = report().put("properties", JSONArray().put(JSONObject().put("id", 1)))
        assertNotEquals(a, ProbeSlim.split(other)!!.staticId)
        // A dynamic field changing (a new boot, a new start time) must not change the id.
        assertEquals(a, ProbeSlim.split(report(model = "other"))!!.staticId)
    }

    @Test fun `the slim body is a small fraction of the pretty-printed report`() {
        val big = report(events = 20)
        val props = JSONArray()
        repeat(400) { props.put(JSONObject().put("id", 290000000 + it).put("name", "PROPERTY_$it").put("access", "READ").put("areas", JSONArray().put(0))) }
        big.put("properties", props)
        val pretty = big.toString(2).toByteArray().size
        val slim = ProbeSlim.split(big)!!.body(false).size
        assertTrue("slim=$slim pretty=$pretty", slim * 20 < pretty)
    }
}
