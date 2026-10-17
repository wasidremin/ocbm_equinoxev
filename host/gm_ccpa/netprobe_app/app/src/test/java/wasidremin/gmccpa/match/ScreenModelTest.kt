package wasidremin.gmccpa.match

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenModelTest {
    companion object {
        fun loadForTest(name: String): ScreenModel.Gray = load(name)

        private fun load(name: String): ScreenModel.Gray {
            // javax.imageio is not on the unit-test compile classpath (android.jar
            // bootclasspath), so decode the 8-bit RGBA PNGs directly via java.util.zip.
            val stream = ScreenModelTest::class.java.getResourceAsStream("/calib/$name")
                ?: throw AssertionError("missing test resource /calib/$name")
            val bytes = stream.use { it.readBytes() }
            val (w, h, argb) = decodePngRgba(bytes)
            return ScreenModel.downscale(argb, w, h)
        }

        private data class Decoded(val w: Int, val h: Int, val argb: IntArray)

        private fun decodePngRgba(bytes: ByteArray): Decoded {
            require(bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte()) { "not a PNG" }
            fun u32(p: Int): Int = ((bytes[p].toInt() and 0xFF) shl 24) or
                ((bytes[p + 1].toInt() and 0xFF) shl 16) or
                ((bytes[p + 2].toInt() and 0xFF) shl 8) or (bytes[p + 3].toInt() and 0xFF)
            var pos = 8
            var w = 0
            var h = 0
            val idat = java.io.ByteArrayOutputStream()
            while (pos + 8 <= bytes.size) {
                val len = u32(pos)
                val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
                val data = pos + 8
                when (type) {
                    "IHDR" -> {
                        w = u32(data)
                        h = u32(data + 4)
                        require(bytes[data + 8] == 8.toByte() && bytes[data + 9] == 6.toByte() &&
                            bytes[data + 10] == 0.toByte() && bytes[data + 11] == 0.toByte() &&
                            bytes[data + 12] == 0.toByte()) { "need 8-bit RGBA non-interlaced PNG" }
                    }
                    "IDAT" -> idat.write(bytes, data, len)
                    "IEND" -> break
                }
                pos = data + len + 4
            }
            require(w > 0 && h > 0) { "missing IHDR" }
            val raw = java.io.ByteArrayOutputStream().also { out ->
                java.util.zip.InflaterInputStream(java.io.ByteArrayInputStream(idat.toByteArray()))
                    .use { it.copyTo(out) }
            }.toByteArray()
            val stride = w * 4
            require(raw.size == h * (1 + stride)) { "bad IDAT size ${raw.size}" }
            val argb = IntArray(w * h)
            var prev = ByteArray(stride)
            var p = 0
            for (y in 0 until h) {
                val f = raw[p++].toInt()
                val cur = raw.copyOfRange(p, p + stride)
                p += stride
                for (i in cur.indices) {
                    val a = cur[i].toInt() and 0xFF
                    val b = prev[i].toInt() and 0xFF
                    val left = if (i >= 4) cur[i - 4].toInt() and 0xFF else 0
                    val upLeft = if (i >= 4) prev[i - 4].toInt() and 0xFF else 0
                    cur[i] = when (f) {
                        0 -> a
                        1 -> a + left
                        2 -> a + b
                        3 -> a + ((left + b) ushr 1)
                        4 -> a + paeth(left, b, upLeft)
                        else -> throw AssertionError("bad filter $f")
                    }.toByte()
                }
                prev = cur
                var k = 0
                for (x in 0 until w) {
                    val r = cur[k++].toInt() and 0xFF
                    val g = cur[k++].toInt() and 0xFF
                    val bl = cur[k++].toInt() and 0xFF
                    val al = cur[k++].toInt() and 0xFF
                    argb[y * w + x] = (al shl 24) or (r shl 16) or (g shl 8) or bl
                }
            }
            return Decoded(w, h, argb)
        }

        private fun paeth(a: Int, b: Int, c: Int): Int {
            val p = a + b - c
            val pa = kotlin.math.abs(p - a)
            val pb = kotlin.math.abs(p - b)
            val pc = kotlin.math.abs(p - c)
            return when {
                pa <= pb && pa <= pc -> a
                pb <= pc -> b
                else -> c
            }
        }

        val app by lazy { load("cap1_in_app.png") }
        val home by lazy { load("cap2_home_grid.png") }
        val dash by lazy { load("cap3_dashboard.png") }
    }

    private fun strip(role: String, ny: Float) =
        ScreenModel.cutTemplate(home, ScreenModel.Kind.STRIP, role, 0.029f, ny, "cap2", null)

    @Test fun templateSidesAreFixedScale() {
        assertEquals(18, ScreenModel.Kind.STRIP.side)
        assertEquals(38, ScreenModel.Kind.GRID.side)
        assertEquals(15, ScreenModel.Kind.GLYPH.side)
        assertEquals(688, home.width)
        assertEquals(241, home.height)
    }

    @Test fun stripIconsFoundOnEveryScreen() {
        for ((role, ny) in listOf("maps" to 0.331f, "spotify" to 0.441f, "phone" to 0.555f)) {
            val t = strip(role, ny)
            for ((label, g) in listOf("app" to app, "home" to home, "dash" to dash)) {
                val hit = ScreenModel.find(g, t, ScreenModel.STRIP_ROI, ScreenModel.Kind.STRIP.stride)
                assertTrue("$role on $label scored ${hit.score}", hit.passed && hit.score >= 0.95f)
                assertEquals("$role y on $label", ny, hit.ny, 0.02f)
            }
        }
    }

    @Test fun gridIconsFoundOnlyOnHomeGrid() {
        val grid = listOf("phone" to 0.186f, "music" to 0.351f, "maps" to 0.517f, "spotify" to 0.848f)
        for ((role, nx) in grid) {
            val t = ScreenModel.cutTemplate(home, ScreenModel.Kind.GRID, role, nx, 0.232f, "cap2", null)
            val onHome = ScreenModel.find(home, t, ScreenModel.GRID_ROI, ScreenModel.Kind.GRID.stride)
            assertTrue("$role on home scored ${onHome.score}", onHome.passed && onHome.score >= 0.95f)
            assertEquals("$role x on home", nx, onHome.nx, 0.01f)
            for ((label, g) in listOf("app" to app, "dash" to dash)) {
                val miss = ScreenModel.find(g, t, ScreenModel.GRID_ROI, ScreenModel.Kind.GRID.stride)
                assertTrue("$role false positive on $label: ${miss.score}", !miss.passed && miss.score < 0.70f)
            }
        }
    }

    @Test fun stripIconsDoNotCrossMatch() {
        val maps = strip("maps", 0.331f)
        val spotifySlot = ScreenModel.NRect(0f, 0.395f, 0.06f, 0.49f)
        val hit = ScreenModel.find(app, maps, spotifySlot, 1)
        assertTrue("maps vs spotify slot scored ${hit.score}", hit.score < 0.60f)
    }

    @Test fun glyphSeparatesDashboard() {
        val other = ScreenModel.cutTemplate(home, ScreenModel.Kind.GLYPH, "other", 0.029f, 0.915f, "cap2", null)
        val dashT = ScreenModel.cutTemplate(dash, ScreenModel.Kind.GLYPH, "dashboard", 0.029f, 0.915f, "cap3", null)
        assertEquals(ScreenModel.GLYPH_OTHER, other.name)
        assertEquals(ScreenModel.GLYPH_DASHBOARD, dashT.name)
        assertEquals(ScreenModel.Surface.NOT_DASHBOARD, ScreenModel.surface(home, dashT, other))
        assertEquals(ScreenModel.Surface.NOT_DASHBOARD, ScreenModel.surface(app, dashT, other))
        assertEquals(ScreenModel.Surface.DASHBOARD, ScreenModel.surface(dash, dashT, other))
        assertEquals(ScreenModel.Surface.UNKNOWN, ScreenModel.surface(dash, null, other))
    }

    @Test fun screenChangeIsWellAboveLauncherThreshold() {
        assertTrue(ScreenModel.changed(app, home) > 0.10f)
        assertTrue(ScreenModel.changed(dash, home) > 0.10f)
        assertEquals(0f, ScreenModel.changed(home, home), 0f)
    }

    @Test fun namingAndRouting() {
        assertEquals("grid.maps", ScreenModel.templateName(ScreenModel.Kind.GRID, "Maps"))
        assertEquals(ScreenModel.Kind.STRIP, ScreenModel.kindForPoint(0.03f, 0.5f))
        assertEquals(ScreenModel.Kind.GLYPH, ScreenModel.kindForPoint(0.03f, 0.9f))
        assertEquals(ScreenModel.Kind.GRID, ScreenModel.kindForPoint(0.5f, 0.5f))
    }

    @Test fun nativeSizeTemplateRoundTripsJson() {
        val t = strip("maps", 0.331f)
        assertEquals(t, IconMatcher.IconTemplate.fromJson(JSONObject(t.toJson().toString())))
    }
}
