package wasidremin.gmccpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Synthetic Equinox EV panel: 2914x1134, straight left/bottom at the window edge, top edge
 * sloping down left-to-right, right side swooping down to the bottom.
 */
class ScribbleSolverTest {
    private val W = 2914f
    private val H = 1134f
    private val seed = ScribbleSolver.Seed(0f, 0f, W, H)
    private val rnd = Random(42)

    private fun jitter(v: Float, amt: Float) = v + (rnd.nextFloat() * 2 - 1) * amt

    /** Strokes painting dead glass just OUTSIDE the visible edge. */
    private fun equinoxStrokes(): List<List<ScribbleSolver.Pt>> {
        val strokes = mutableListOf<List<ScribbleSolver.Pt>>()
        // Left edge: vertical scribble at x in [0,6]. Dense like a real finger stroke.
        strokes.add(List(240) { ScribbleSolver.Pt(jitter(3f, 3f), 60f + it * 4f) })
        // Bottom edge: horizontal scribble at y in [1128,1134].
        strokes.add(List(300) { ScribbleSolver.Pt(60f + it * 9.5f, jitter(1131f, 3f)) })
        // Top slope: dead paint above y = 20 + 150*x/W. Ends where the corner swoop takes
        // over — like a real trace, it must not overshoot into the swoop's territory (the two
        // would contradict each other there: top says dead, swoop says visible).
        val top = mutableListOf<ScribbleSolver.Pt>()
        for (x in 0 until 2400 step 12) {
            val edge = 20f + 150f * x / W
            repeat(8) { top.add(ScribbleSolver.Pt(jitter(x.toFloat(), 6f), jitter(edge - 12f, 8f))) }
        }
        strokes.add(top)
        // Right swoop: dead paint right of x = W - 40 - 245*smoothstep over lower 2/3.
        val right = mutableListOf<ScribbleSolver.Pt>()
        for (y in 180 until 1134 step 8) {
            val f = (y - 150f) / (1134f - 150f)
            val edge = W - 40f - 245f * f * f
            repeat(6) { right.add(ScribbleSolver.Pt(jitter(edge + 14f, 10f), jitter(y.toFloat(), 5f))) }
        }
        strokes.add(right)
        return strokes
    }

    @Test fun solvesEquinoxLikePanel() {
        val r = ScribbleSolver.solve(W, H, seed, equinoxStrokes())
        // Straight edges recovered (within finger jitter + safety margin).
        assertTrue("left=${r.l}", r.l in 0f..40f)
        assertTrue("bottom=${r.b}", r.b in 1090f..H)
        // Sloped top and swooping right cut into the rect.
        assertTrue("top=${r.t}", r.t in 0f..220f)
        assertTrue("right=${r.r}", r.r in 2500f..W)
        // Still a usable CarPlay rectangle.
        assertTrue(r.r - r.l >= 2000f)
        assertTrue(r.b - r.t >= 700f)
        // Every edge well covered (the central-range gates cap the maximum at ~0.83,
        // so 0.7 is the honest bar, not 0.8).
        for ((edge, cov) in r.coverage) assertTrue("$edge coverage=$cov", cov > 0.7f)
    }

    @Test fun emptyStrokesFallBackToSeed() {
        val r = ScribbleSolver.solve(W, H, seed, emptyList())
        assertEquals(0f, r.l)
        assertEquals(0f, r.t)
        assertEquals(W, r.r)
        assertEquals(H, r.b)
    }

    @Test fun untracedEdgesKeepSeed() {
        // Only the top traced: left/right/bottom must stay at the seed, not collapse.
        val top = mutableListOf<ScribbleSolver.Pt>()
        for (x in 0 until 2600 step 20) repeat(5) {
            top.add(ScribbleSolver.Pt(jitter(x.toFloat(), 9f), jitter(60f, 8f)))
        }
        val r = ScribbleSolver.solve(W, H, seed, listOf(top))
        assertTrue("left=${r.l}", r.l in 0f..40f)
        assertEquals(W, r.r, 1f)
        assertEquals(H, r.b, 1f)
        assertTrue("top=${r.t}", r.t in 30f..120f)
        assertTrue(r.coverage.getValue("top") > 0.8f)
        assertEquals(0f, r.coverage.getValue("left"))
    }

    @Test fun fractionsRoundTrip() {
        val r = ScribbleSolver.solve(W, H, seed, equinoxStrokes())
        val f = ScribbleSolver.toFractions(r, W, H)
        assertTrue(f.l in 0..1000 && f.t in 0..1000 && f.r in 0..1000 && f.b in 0..1000)
        assertTrue(f.l < f.r && f.t < f.b)
    }
}
