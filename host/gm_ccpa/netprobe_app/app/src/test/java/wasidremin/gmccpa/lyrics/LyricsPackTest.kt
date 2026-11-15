package wasidremin.gmccpa.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** The downloaded lyrics package ([LyricsPack]) and the list of songs that went without ([LyricsWanted]). */
class LyricsPackTest {
    @get:Rule val tmp = TemporaryFolder()

    private val lrcA = "[00:01.00]first line\n[00:05.00]second line\n"
    private val lrcB = "[00:02.00]the long cut\n[00:09.00]goes on\n"

    private fun gz(vararg lines: String): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(lines.joinToString("") { l -> l + "\n" }.toByteArray(Charsets.UTF_8)) }
        return ByteArrayInputStream(out.toByteArray())
    }

    private fun song(t: String, a: String, d: Int, lrc: String) =
        org.json.JSONObject().put("t", t).put("a", a).put("d", d).put("lrc", lrc).toString()

    private val head = """{"v":1,"id":"abc123","built":1700000000000,"songs":2}"""

    /** The same answers as `host/gm_ccpa/tools/lyrics_pack.py` gives: the server and the car must name a song alike. */
    @Test fun aSongIsNamedAsTheBuilderNamesIt() {
        for ((title, base) in listOf(
            "1979 - Remastered 2012" to "1979",
            "Brimful Of Asha - Norman Cook Remix Single Version" to "brimful of asha",
            "(I Can't Get No) Satisfaction" to "i can t get no satisfaction",
            "Yoü and I" to "you and i",
            "Music Sounds Better With You - Radio Edit" to "music sounds better with you",
            "Señorita (feat. X)" to "senorita",
            "Don't Stop Believin'" to "don t stop believin",
            "東京 - Live" to "東京",
            "P!nk [Remix]" to "p nk",
        )) assertEquals(title, base, LyricsPack.baseTitle(title))
        assertEquals(setOf("daryl", "hall", "john", "oates"), LyricsPack.artistTokens("Daryl Hall & John Oates"))
        assertEquals(setOf("beyonce", "jay"), LyricsPack.artistTokens("Beyoncé featuring Jay-Z"))
        assertTrue(LyricsPack.artistTokens("The The").isEmpty())
        assertTrue(LyricsPack.sameArtist("Hall & Oates", "Daryl Hall & John Oates"))
        assertTrue(LyricsPack.sameArtist("Queen", "Queen & David Bowie"))
        assertFalse(LyricsPack.sameArtist("Blondie", "Queen"))
        // No telling word on one side: the whole lines have to agree.
        assertTrue(LyricsPack.sameArtist("The The", "The The"))
        assertTrue(LyricsPack.sameArtist("U2", "U2"))
        assertFalse(LyricsPack.sameArtist("U2", "Queen"))
    }

    @Test fun theVersionClosestInLengthIsTakenAndOneTooFarIsNot() {
        assertEquals(1, LyricsPack.pick(listOf(200, 243, 301), 241))
        assertNull(LyricsPack.pick(listOf(200, 301), 241))
        assertEquals(0, LyricsPack.pick(listOf(200, 243), 0))   // the song's own length unknown: the first
        assertNull(LyricsPack.pick(listOf(0), 241))             // a version of unknown length cannot be trusted to fit
        assertNull(LyricsPack.pick(emptyList(), 241))
    }

    @Test fun aPackageIsImportedAndASongFoundByItsBareTitleArtistAndLength() {
        val pack = LyricsPack(tmp.newFolder("pack"))
        assertNull(pack.meta())
        assertNull(pack.find(LyricsKey("Song", "Artist", "", 200)))
        val m = pack.importFrom(gz(head, song("Call Me", "Blondie", 212, lrcA), song("Call Me", "Blondie", 487, lrcB),
            song("Call Me", "Someone Else", 212, "[00:01.00]not this one\n")), nowMs = 5)
        assertEquals("abc123", m.id)
        assertEquals(2, m.songs)
        assertEquals(5L, pack.meta()!!.fetchedMs)
        // As the iPhone names a remaster, a second off in length: the single.
        assertEquals(lrcA, pack.find(LyricsKey("Call Me - Remastered 2001", "Blondie", "Greatest", 213)))
        assertEquals(lrcB, pack.find(LyricsKey("Call Me (Theme from American Gigolo)", "Blondie", "", 486)))
        // A cut of another length, another act, another song: nothing rather than lyrics that do not fit.
        assertNull(pack.find(LyricsKey("Call Me", "Blondie", "", 300)))
        assertNull(pack.find(LyricsKey("Call Me", "Queen", "", 212)))
        assertNull(pack.find(LyricsKey("Call Me Maybe", "Blondie", "", 212)))
        // A second handle on the same folder reads what the first wrote.
        assertEquals(lrcA, LyricsPack(pack.let { tmp.root.resolve("pack") }).find(LyricsKey("Call Me", "Blondie", "", 212)))
        pack.clear()
        assertNull(pack.meta())
        assertNull(pack.find(LyricsKey("Call Me", "Blondie", "", 212)))
    }

    @Test fun aDownloadCutShortOrEmptyLeavesThePackageThatWasThere() {
        val pack = LyricsPack(tmp.newFolder("pack"))
        pack.importFrom(gz(head, song("Call Me", "Blondie", 212, lrcA)))
        val whole = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write((head + "\n" + song("Other", "Act", 100, lrcB) + "\n").toByteArray()) } }.toByteArray()
        for (bad in listOf(ByteArrayInputStream(whole.copyOf(whole.size / 2)), gz(head), gz(song("No", "Header", 1, lrcA)), ByteArrayInputStream(ByteArray(0)))) {
            assertTrue(runCatching { pack.importFrom(bad) }.isFailure)
            assertEquals(lrcA, pack.find(LyricsKey("Call Me", "Blondie", "", 212)))
            assertEquals("abc123", pack.meta()!!.id)
        }
        // A good one replaces it whole.
        pack.importFrom(ByteArrayInputStream(whole))
        assertNull(pack.find(LyricsKey("Call Me", "Blondie", "", 212)))
        assertEquals(lrcB, pack.find(LyricsKey("Other", "Act", "", 100)))
    }

    @Test fun lrclibIsAskedAlwaysOnWifiOnlyOrNever() {
        assertTrue(LyricsOnline.ALWAYS.mayAsk(onWifi = false))
        assertTrue(LyricsOnline.WIFI.mayAsk(onWifi = true))
        assertFalse(LyricsOnline.WIFI.mayAsk(onWifi = false))
        assertFalse(LyricsOnline.NEVER.mayAsk(onWifi = true))
        assertEquals(3, LyricsOnline.values().map { it.key }.toSet().size)
    }

    @Test fun songsThatWentWithoutAreListedOnceAndTheOldestGoFirst() {
        val w = LyricsWanted(tmp.newFile("wanted.ndjson"))
        val a = LyricsKey("One", "Act", "Album", 200)
        w.add(a); w.add(a); w.add(LyricsKey("Two", "Act", "", 0))
        assertEquals(listOf("One", "Two"), w.all().map { it.title })
        assertEquals(a, w.all().first())
        w.remove(listOf(a))
        assertEquals(listOf("Two"), w.all().map { it.title })
        for (i in 0 until LyricsWanted.MAX + 5) w.add(LyricsKey("Song $i", "Act", "", 100))
        assertEquals(LyricsWanted.MAX, w.all().size)
        assertEquals("Song ${LyricsWanted.MAX + 4}", w.all().last().title)
        assertFalse(w.all().any { it.title == "Two" })
    }

    @Test fun thePackageIsAskedForWithWhatIsAlreadyHeld() {
        assertEquals("https://h/x/lyrics?car=0123456789abcdef", LyricsPack.endpointFor("https://h/x/", "0123456789abcdef", null))
        assertEquals("https://h/x/lyrics?car=0123456789abcdef&have=abc", LyricsPack.endpointFor("https://h/x", "0123456789abcdef", "abc"))
    }
}
