package wasidremin.gmccpa.lyrics

// Ported from enigy/carlink (Unlicense); dates and drives named in these tests are theirs.

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Asking LRCLIB: which recording's lyrics are taken, and in what order its lookups are tried. */
class LrcLibTest {
    /** LRCLIB as a table of path -> answer: a body, null for a 404, or an [IOException] to throw; records the paths asked. */
    private class FakeLrcLib(
        private val answers: Map<String, Any?>,
    ) : LrcLib.Api {
        val asked = mutableListOf<String>()

        override fun get(
            path: String,
            query: List<Pair<String, String>>,
        ): String? {
            asked += path
            return when (val a = answers[path]) {
                is IOException -> throw a
                else -> a as String?
            }
        }
    }

    private val key = LyricsKey("Come Together Now", "Matt and Kim", "Lightning", 134)
    private val record = """{"id": 9, "duration": 134.0, "instrumental": false, "syncedLyrics": "[00:05.00] come together"}"""
    private val searchHits = """[{"id": 9, "duration": 134.0, "instrumental": false, "syncedLyrics": "[00:05.00] from the search"}]"""
    private val unavailable = IOException("HTTP 503 from /api/get")

    @Test
    fun `LRCLIB's own record is taken first, and nothing else is asked`() {
        val api = FakeLrcLib(mapOf("get-cached" to record))
        assertEquals("[00:05.00] come together", LrcLib.lookUp(key, api))
        assertEquals(listOf("get-cached"), api.asked)
    }

    @Test
    fun `an api get answering 503 no longer stops a lookup the search can answer`() {
        // The 10-29 drive: /api/get answered HTTP 503 for most songs, and asked first it ended every lookup.
        val api = FakeLrcLib(mapOf("get-cached" to null, "search" to searchHits, "get" to unavailable))
        assertEquals("[00:05.00] from the search", LrcLib.lookUp(key, api))
        assertEquals("the outside lookup is not needed", listOf("get-cached", "search"), api.asked)
    }

    @Test
    fun `a step out of reach is passed over when a later one finds the lyrics`() {
        val api = FakeLrcLib(mapOf("get-cached" to IOException("HTTP 503 from /api/get-cached"), "search" to IOException("timeout"), "get" to record))
        assertEquals("[00:05.00] come together", LrcLib.lookUp(key, api))
        assertEquals(listOf("get-cached", "search", "get"), api.asked)
    }

    @Test
    fun `nothing found anywhere is none, but nothing found with a step out of reach is no answer`() {
        assertNull(LrcLib.lookUp(key, FakeLrcLib(mapOf("get-cached" to null, "search" to "[]", "get" to null))))
        try {
            LrcLib.lookUp(key, FakeLrcLib(mapOf("get-cached" to null, "search" to "[]", "get" to unavailable)))
            fail("a lookup that could not ask everywhere is asked again, not taken as none")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("HTTP 503 from /api/get"))
        }
    }

    // Shaped like LRCLIB's search for "Dirty Paws" by Of Monsters and Men (10-27): one recording per length.
    private val hits =
        JSONArray(
            """
            [
              {"id": 1, "albumName": "Live From Vatnagardar (EP)", "duration": 300.0, "instrumental": false, "syncedLyrics": "[00:18.69] live"},
              {"id": 2, "albumName": "Indie/Rock Playlist", "duration": 274.0, "instrumental": false, "syncedLyrics": "[00:10.00] playlist"},
              {"id": 3, "albumName": "My Head Is an Animal", "duration": 266.0, "instrumental": false, "syncedLyrics": null, "plainLyrics": "untimed"},
              {"id": 4, "albumName": "My Head Is An Animal (10th)", "duration": 266.0, "instrumental": false, "syncedLyrics": "[00:12.00] album"},
              {"id": 5, "albumName": "Karaoke", "duration": 299.0, "instrumental": true, "syncedLyrics": "[00:01.00] none"}
            ]
            """.trimIndent(),
        )

    @Test
    fun `the search hit whose length matches the song is taken`() {
        // The owner's 10-26 drive: the live cut, 300.5 s.
        assertEquals("[00:18.69] live", LrcLib.pick(hits, durationSec = 301))
        assertEquals("the timed one of two at that length", "[00:12.00] album", LrcLib.pick(hits, durationSec = 266))
        assertNull("nothing within 3 s", LrcLib.pick(hits, durationSec = 240))
    }

    @Test
    fun `with the length unknown the first timed hit is taken`() {
        assertEquals("[00:18.69] live", LrcLib.pick(hits, durationSec = 0))
    }

    @Test
    fun `instrumentals, JSON nulls and blanks have no timed lyrics`() {
        assertNull(LrcLib.synced(JSONObject("""{"instrumental": true, "syncedLyrics": "[00:01.00] x"}""")))
        assertNull(LrcLib.synced(JSONObject("""{"instrumental": false, "syncedLyrics": null}""")))
        assertNull(LrcLib.synced(JSONObject("""{"instrumental": false, "syncedLyrics": "  "}""")))
        assertEquals("[00:01.00] x", LrcLib.synced(JSONObject("""{"syncedLyrics": "[00:01.00] x"}""")))
    }

    @Test
    fun `the song is asked for by its first artist and its length in whole seconds`() {
        assertEquals("Of Monsters and Men", LrcLib.firstArtist("Of Monsters and Men, Chris Taylor"))
        assertEquals(
            LyricsKey("Dirty Paws", "Of Monsters and Men", "LIVE FROM VATNAGARÐAR", 301L),
            LyricsKey.of(" Dirty Paws ", "Of Monsters and Men", "LIVE FROM VATNAGARÐAR", 300_506L),
        )
        assertNull("no artist yet", LyricsKey.of(" Dirty Paws ", null, "LIVE FROM VATNAGARÐAR", 300_506L))
    }
}
