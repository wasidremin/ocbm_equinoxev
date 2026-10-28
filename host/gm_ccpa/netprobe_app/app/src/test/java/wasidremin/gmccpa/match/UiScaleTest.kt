package wasidremin.gmccpa.match

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The launcher at CarPlay UI scales other than the default, on the Equinox's own frames.
 *
 * `cap5_strip_2470_ui83.png` is the strip of the 2026-10-28 capture: 240x93 mm reported for a
 * 2470x960 picture (the old slider's 83%), which CarPlay drew 1.276x larger — strip icons 111 px
 * instead of 87, spacing 143 instead of 112. Every top-bar launch that evening was NOT_FOUND.
 * `cap4_strip_2342_default.png` is the strip of 2026-10-23 with no physical size reported.
 * Both are the left 216 px of the coded frame only (the rest of a capture can show where the car
 * is), padded back to the coded width here.
 */
class UiScaleTest {
    companion object {
        private fun strip(name: String, codedW: Int): ScreenModel.Gray {
            val c = ScreenModelTest.loadForTest(name)
            val w = codedW / ScreenModel.SCALE
            val px = FloatArray(w * c.height) { 0.5f }
            for (y in 0 until c.height) System.arraycopy(c.px, y * c.width, px, y * w, c.width)
            return ScreenModel.Gray(w, c.height, px)
        }

        val default2342 by lazy { strip("cap4_strip_2342_default.png", 2342) }
        val large2470 by lazy { strip("cap5_strip_2470_ui83.png", 2470) }

        private val builtIns: Map<String, IconMatcher.IconTemplate> by lazy {
            File("src/main/assets/" + IconTemplateStore.BUILTIN_ASSET_DIR).listFiles()!!
                .filter { it.name.endsWith(".json") }
                .map { IconMatcher.IconTemplate.fromJson(JSONObject(it.readText())) }
                .associateBy { it.name }
        }

        /** What the launcher reads the scale from: strip icons and the toggle glyphs. */
        val anchors by lazy { builtIns.values.filter { !it.name.startsWith("grid.") } }

        private val measuredLarge by lazy { ScreenModel.measureUi(large2470, anchors) }

        // Centres measured by hand on the 2026-10-28 capture (full-res px of the coded frame).
        private val LARGE_STRIP_Y = mapOf("maps" to 337, "spotify" to 480, "phone" to 623)
        private const val LARGE_STRIP_X = 102
        private const val LARGE_TOGGLE_X = 102
        private const val LARGE_TOGGLE_Y = 860
    }

    @Test fun fixedScaleTemplatesMissTheLargerUi() {
        // The field failure: at the default scale nothing in the strip matches (the car scored 0.36–0.45).
        val geo = ScreenModel.geometry(large2470, 1f)
        for (role in LARGE_STRIP_Y.keys) {
            val hit = ScreenModel.find(large2470, builtIns.getValue("strip.$role"), ScreenModel.Kind.STRIP, geo)
            assertFalse("strip.$role passed at 1.0 (${hit.score})", hit.passed)
        }
        assertEquals(ScreenModel.Surface.UNKNOWN,
            ScreenModel.surface(large2470, builtIns[ScreenModel.GLYPH_DASHBOARD], builtIns[ScreenModel.GLYPH_OTHER], geo))
    }

    @Test fun measuresTheDefaultScale() {
        val frames = listOf("2342 default" to default2342, "cap1" to ScreenModelTest.app,
            "cap2" to ScreenModelTest.home, "cap3" to ScreenModelTest.dash)
        for ((label, g) in frames) {
            val fit = ScreenModel.measureUi(g, anchors)
            assertTrue("$label: $fit", fit.passed)
            assertEquals("$label: $fit", 1f, fit.ui, 0.03f)
        }
    }

    @Test fun predictedScaleIsConfirmedWithoutASweep() {
        // The launcher's fast path: the scale the physical size predicts already matches.
        val fit = ScreenModel.anchorFit(large2470, anchors, ScreenModel.expectedUi(2470, 240))
        assertTrue(fit.toString(), fit.passed)
        assertEquals(1f, ScreenModel.expectedUi(2470, 0), 0f)
    }

    @Test fun measuresTheLargerUi() {
        val t0 = System.nanoTime()
        val fit = ScreenModel.measureUi(large2470, anchors)
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("large2470: $fit in $ms ms")
        assertTrue(fit.toString(), fit.passed)
        // Hand-measured 1.276 (icon edges, spacing and centre agree). Matching at 1/4 resolution
        // reads within half a template pixel, about 4%, and the sweep is only the fallback.
        assertEquals(fit.toString(), 1.276f, fit.ui, 0.06f)
    }

    @Test fun stripIconsFoundAtThePredictedScale() {
        val geo = ScreenModel.geometry(large2470, ScreenModel.expectedUi(2470, 240))
        for ((role, y) in LARGE_STRIP_Y) {
            val hit = ScreenModel.find(large2470, builtIns.getValue("strip.$role"), ScreenModel.Kind.STRIP, geo)
            assertTrue("strip.$role scored ${hit.score}", hit.passed)
            assertEquals("strip.$role y", y.toFloat(), hit.ny * 960, 10f)
            assertEquals("strip.$role x", LARGE_STRIP_X.toFloat(), hit.nx * 2470, 10f)
        }
    }

    @Test fun toggleTapLandsOnTheToggle() {
        for (ui in listOf(ScreenModel.expectedUi(2470, 240), measuredLarge.ui)) {
            val geo = ScreenModel.geometry(large2470, ui)
            val tx = geo.toggleNx * 2470
            val ty = geo.toggleNy * 960
            val miss = hypot(tx - LARGE_TOGGLE_X, ty - LARGE_TOGGLE_Y)
            assertTrue("ui=$ui: toggle tap ($tx, $ty) is $miss px from the toggle", miss < 12f)
        }
        // The fixed point the car tapped, (0.029, 0.915), was 35 px off: the edge of a 77 px button.
        val old = hypot(0.029f * 2470 - LARGE_TOGGLE_X, 0.915f * 960 - LARGE_TOGGLE_Y)
        assertTrue("old tap was $old px off", old > 30f)
    }

    @Test fun nightGlyphReadsInverted() {
        // The capture is at night: a white 3x3 grid on dark, against a template cut dark-on-light.
        val geo = ScreenModel.geometry(large2470, ScreenModel.expectedUi(2470, 240))
        val ev = ScreenModel.surfaceDetailed(large2470, builtIns[ScreenModel.GLYPH_DASHBOARD], builtIns[ScreenModel.GLYPH_OTHER], geo)
        assertEquals(ev.toString(), ScreenModel.Surface.DASHBOARD, ev.surface)
    }

    @Test fun templateCutAtTheLargerUiIsStoredAtTheDefaultSize() {
        val geo = ScreenModel.geometry(large2470, measuredLarge.ui)
        val t = ScreenModel.cutTemplate(large2470, ScreenModel.Kind.STRIP, "maps", 0.04f,
            LARGE_STRIP_Y.getValue("maps") / 960f, "cap5", null, geo)
        assertEquals(ScreenModel.Kind.STRIP.side, t.width)
        assertEquals(ScreenModel.Kind.STRIP.side, t.height)
        // ...so it finds Maps on the default-scale frame too (centre 313 px there).
        val hit = ScreenModel.find(default2342, t, ScreenModel.Kind.STRIP, ScreenModel.geometry(default2342))
        assertTrue("cross-scale maps scored ${hit.score}", hit.passed)
        assertEquals(313f, hit.ny * 960, 10f)
    }

    @Test fun sliderPercentIsTheDrawnScale() {
        assertEquals(0, ScreenModel.physicalWidthMmFor(2470, 100))
        for (p in listOf(50, 83, 99, 101, 128, 150)) {
            val mm = ScreenModel.physicalWidthMmFor(2470, p)
            assertTrue("$p% -> $mm mm", mm > 0)
            assertEquals("$p% -> $mm mm", p / 100f, ScreenModel.expectedUi(2470, mm), 0.01f)
        }
        // Smaller means smaller: 83% must report a LARGER panel than the default, not the 240 mm
        // that drew 1.28x (the default is ~306 mm at 2470 px).
        assertTrue(ScreenModel.physicalWidthMmFor(2470, 83) > 2470 / ScreenModel.DEFAULT_PX_PER_MM)
        assertTrue(abs(ScreenModel.expectedUi(2470, 240) - 1.276f) < 0.01f)
    }
}
