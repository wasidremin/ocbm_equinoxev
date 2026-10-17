package wasidremin.gmccpa.match

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Matcher test harness (Phase 3): synthetic templates and targets, no car, no Bitmap.
 * Every test runs on plain JVM JUnit — [IconMatcher] is Android-free by construction.
 */
class IconMatcherTest {

    /** Deterministic high-variance 32x32 pattern: diagonal weave plus a bright block. */
    private fun weave(w: Int = 32, h: Int = 32): FloatArray =
        FloatArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val base = ((x * 7 + y * 13) % 16) / 15f
            if (x in 20..27 && y in 4..11) 0.95f else base
        }

    private fun template(name: String = "weave", pixels: FloatArray = weave()): IconMatcher.IconTemplate =
        IconMatcher.IconTemplate(name, 32, 32, pixels)

    @Test fun identicalPatchesScoreOne() {
        val t = weave()
        assertEquals(1f, IconMatcher.normalizedCrossCorrelation(t, t.copyOf()), 1e-5f)
    }

    @Test fun scoreIsInvariantToBrightnessAndContrast() {
        val t = weave()
        // A day/night drift: scale + offset. NCC must not move.
        val drifted = FloatArray(t.size) { i -> (t[i] * 0.6f + 0.25f).coerceIn(0f, 1f) }
        assertEquals(1f, IconMatcher.normalizedCrossCorrelation(t, drifted), 1e-4f)
    }

    @Test fun invertedPatchScoresMinusOne() {
        val t = weave()
        val inv = FloatArray(t.size) { i -> 1f - t[i] }
        assertEquals(-1f, IconMatcher.normalizedCrossCorrelation(t, inv), 1e-4f)
    }

    @Test fun constantPatchScoresZero() {
        val t = weave()
        val flat = FloatArray(t.size) { 0.5f }
        assertEquals(0f, IconMatcher.normalizedCrossCorrelation(t, flat), 1e-6f)
    }

    @Test fun embeddedTemplateIsFoundAtExactLocation() {
        val tmpl = template()
        val targetW = 96
        val targetH = 64
        // Deterministic low-contrast background that only weakly correlates with the weave.
        val target = FloatArray(targetW * targetH) { i ->
            0.4f + 0.05f * (((i * 3 + i / targetW) % 7) / 6f)
        }
        val ex = 40
        val ey = 20
        for (y in 0 until 32) {
            System.arraycopy(tmpl.pixels, y * 32, target, (ey + y) * targetW + ex, 32)
        }
        val res = IconMatcher.findBest(tmpl, target, targetW, targetH)
        assertEquals(ex, res.x)
        assertEquals(ey, res.y)
        assertEquals(1f, res.score, 1e-5f)
        assertTrue(res.passed)
    }

    @Test fun stridedSearchFindsAlignedPlacement() {
        val tmpl = template()
        val targetW = 96
        val targetH = 64
        val target = FloatArray(targetW * targetH) { 0.35f }
        // Stride-aligned embed: the exact placement is on the stride grid, so stride 4 must
        // hit it dead-on with a perfect score (and visit ~16x fewer placements doing it).
        val ex = 40
        val ey = 20
        for (y in 0 until 32) {
            System.arraycopy(tmpl.pixels, y * 32, target, (ey + y) * targetW + ex, 32)
        }
        val res = IconMatcher.findBest(tmpl, target, targetW, targetH, stride = 4)
        assertEquals(ex, res.x)
        assertEquals(ey, res.y)
        assertEquals(1f, res.score, 1e-5f)
        assertTrue(res.passed)
    }

    @Test fun wrongTemplateFailsThreshold() {
        val target = weave()
        val other = template("other", FloatArray(32 * 32) { i ->
            val x = i % 32
            val y = i / 32
            if ((x / 4 + y / 4) % 2 == 0) 0.9f else 0.1f
        })
        val res = IconMatcher.findBest(other, target, 32, 32)
        assertFalse("checker vs weave should not pass (score=${res.score})", res.passed)
        assertTrue(res.score < other.threshold)
    }

    @Test fun oversizedTemplateCannotBeCompared() {
        val tmpl = template()
        val res = IconMatcher.findBest(tmpl, FloatArray(16 * 16) { 0.5f }, 16, 16)
        assertTrue(res.score.isNaN())
        assertFalse(res.passed)
    }

    @Test fun regionRestrictsSearch() {
        val tmpl = template()
        val targetW = 96
        val targetH = 64
        val target = FloatArray(targetW * targetH) { 0.35f }
        for (y in 0 until 32) {
            System.arraycopy(tmpl.pixels, y * 32, target, (10 + y) * targetW + 50, 32)
        }
        // Region excluding the embed: the best inside-region score must not be a pass at (50, 10).
        val res = IconMatcher.findBest(
            tmpl, target, targetW, targetH,
            region = IconMatcher.SearchRegion(0, 0, 40, 64),
        )
        assertTrue(res.x + 32 <= 40)
        assertFalse(res.passed)
    }

    @Test fun flatTemplateIsRejected() {
        try {
            IconMatcher.IconTemplate("flat", 8, 8, FloatArray(64) { 0.5f })
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun raggedPixelBufferIsRejected() {
        try {
            IconMatcher.IconTemplate("ragged", 8, 8, FloatArray(10) { 0.1f * it })
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun resampleIsIdentityAtSameSize() {
        val src = weave(16, 12)
        val out = IconMatcher.resampleGrayscale(src, 16, 12, 16, 12)
        assertArrayEquals(src, out, 1e-6f)
    }

    @Test fun resampleSinglePixelFills() {
        val out = IconMatcher.resampleGrayscale(floatArrayOf(0.7f), 1, 1, 4, 3)
        assertEquals(12, out.size)
        assertTrue(out.all { it == 0.7f })
    }

    @Test fun canonicalizeResamplesToCanonicalSize() {
        val t = IconMatcher.IconTemplate.canonicalize("big", weave(64, 48), 64, 48, sourceCaptureId = "cap-1")
        assertEquals(IconMatcher.TEMPLATE_W, t.width)
        assertEquals(IconMatcher.TEMPLATE_H, t.height)
        assertEquals(IconMatcher.TEMPLATE_W * IconMatcher.TEMPLATE_H, t.pixels.size)
        assertEquals("cap-1", t.sourceCaptureId)
    }

    @Test fun templateJsonRoundTrips() {
        val t = IconMatcher.IconTemplate(
            "home-maps", 32, 32, weave(),
            threshold = 0.9f, sourceCaptureId = "20261014-1", createdIso = "2026-10-14T12:00:00.000Z",
        )
        val parsed = IconMatcher.IconTemplate.fromJson(JSONObject(t.toJson().toString()))
        assertEquals(t, parsed)
        assertEquals(t.hashCode(), parsed.hashCode())
    }

    @Test fun templateJsonWithoutPixelsFails() {
        try {
            IconMatcher.IconTemplate.fromJson(JSONObject().put("name", "x").put("width", 32).put("height", 32))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun argbToGrayscaleMatchesBt601() {
        val red = 0xFFFF0000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val black = 0xFF000000.toInt()
        val out = IconMatcher.argbToGrayscale(intArrayOf(red, white, black))
        assertEquals(0.299f, out[0], 1e-3f)
        assertEquals(1f, out[1], 1e-6f)
        assertEquals(0f, out[2], 1e-6f)
    }

    @Test fun madSanity() {
        val a = FloatArray(100) { 0.2f }
        val b = FloatArray(100) { 0.3f }
        assertEquals(0.1f, IconMatcher.meanAbsoluteDifference(a, b), 1e-6f)
        assertEquals(0f, IconMatcher.meanAbsoluteDifference(a, a.copyOf()), 1e-6f)
    }

    @Test fun storeNameSanitization() {
        assertEquals("maps_nav", IconTemplateStore.sanitizeName("maps/nav"))
        assertEquals("template", IconTemplateStore.sanitizeName("..."))
        assertEquals("template", IconTemplateStore.sanitizeName(null))
        assertTrue(IconTemplateStore.sanitizeName("x".repeat(200)).length <= 64)
    }
}
