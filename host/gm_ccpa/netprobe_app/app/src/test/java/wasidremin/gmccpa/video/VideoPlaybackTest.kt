package wasidremin.gmccpa.video

// Ported from enigy/carlink `app/src/test/kotlin/com/carlink/video/VideoPlaybackTest.kt` at a04f4c0 (Unlicense; NOTICE.md).

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's Playback-channel messages as the box relays them (OCBM receiver::playback). */
class VideoPlaybackTest {
    /** A player that records what it was told and reports [now]. */
    private class FakeEngine : VideoEngine {
        val calls = mutableListOf<String>()
        var now = VideoEngine.Snapshot()

        override fun load(
            url: String,
            startS: Double,
        ) {
            calls += "load $startS"
        }

        override fun setRate(rate: Double) {
            calls += "rate $rate"
        }

        override fun seek(toS: Double) {
            calls += "seek $toS"
        }

        override fun stop() {
            calls += "stop"
        }

        override fun snapshot() = now

        override var onFailed: ((code: String) -> Unit)? = null

        /** The player failing, as ExoPlayer reports it. */
        fun fail(code: String) {
            now = now.copy(failed = true, ready = false, rate = 0.0)
            onFailed?.invoke(code)
        }
    }

    private val sent = mutableListOf<Pair<Int, JSONObject>>()
    private val texts = mutableListOf<String>()
    private val engine = FakeEngine()
    private var parked = true
    private val announced = mutableListOf<Pair<Boolean, VideoPlayback.Status>>()
    private var pauses = 0
    private var viaPhone = true
    private val playback =
        VideoPlayback(
            send = { stream, json ->
                texts += json
                sent.add(stream to JSONObject(json))
            },
            engine = engine,
            parked = { parked },
            announce = { allowed, status -> announced += allowed to status },
            pausePhone = { pauses++ },
            viaPhone = { viaPhone },
            log = wasidremin.gmccpa.ProbeLog.silent(),
        )

    private fun record(
        message: String,
        client: String = VideoPlayback.PLAYBACK_CLIENT,
    ) = JSONObject("""{"kind":"dataStream","stream":3,"client":"$client","message":$message}""")

    private val insert =
        """{"type":"insertPlayQueueItem","kind":"request","messageID":7,
           "item":{"uuid":"item-1","Start-Position":12.5,"Content-Location":"https://cdn.example.com/v/master.m3u8?sig=x"}}"""

    private fun rate(r: Double) = record("""{"type":"setRate","kind":"request","messageID":8,"rate":$r}""")

    @Test
    fun `a queued item is loaded and its request answered`() {
        playback.onRecord(record(insert))
        assertEquals(VideoPlayback.Item("item-1", 12.5, "https://cdn.example.com/v/master.m3u8?sig=x"), playback.state.item)
        assertEquals(listOf("load 12.5"), engine.calls)
        val (stream, answer) = sent.single()
        assertEquals(3, stream)
        assertEquals("response", answer.getString("kind"))
        assertEquals("insertPlayQueueItem", answer.getString("type"))
        assertEquals(7, answer.getInt("messageID"))
        assertFalse("only playbackInfo carries a value", answer.has("value"))
        assertFalse("nothing shows before play", playback.showing)
    }

    @Test
    fun `play when parked plays and shows the screen, and playbackInfo reports the player`() {
        playback.onRecord(record(insert))
        playback.onRecord(rate(1.0))
        assertEquals("rate 1.0", engine.calls.last())
        assertTrue(playback.showing)
        engine.now = VideoEngine.Snapshot(ready = true, rate = 1.0, positionS = 20.0, durationS = 90.0, bufferedS = 50.0)
        playback.onRecord(record("""{"type":"playbackInfo","kind":"request","messageID":9,"property":"x"}"""))
        val answer = sent.last().second
        assertEquals("x", answer.getString("property"))
        val value = answer.getJSONObject("value")
        assertTrue(value.getBoolean("readyToPlay"))
        assertEquals(1.0, value.getDouble("rate"), 0.0)
        assertEquals(20.0, value.getDouble("position"), 0.0)
        assertEquals(90.0, value.getDouble("duration"), 0.0)
        assertEquals("item-1", value.getString("uuid"))
        assertEquals(30.0, value.getJSONArray("loadedTimeRanges").getJSONObject(0).getDouble("duration"), 0.0)
        assertEquals(90.0, value.getJSONArray("seekableTimeRanges").getJSONObject(0).getDouble("duration"), 0.0)
    }

    @Test
    fun `answers keep whole reals as reals, so the box sends the phone plist reals`() {
        playback.onRecord(record(insert))
        engine.now = VideoEngine.Snapshot(ready = true, rate = 1.0, positionS = 20.0, durationS = 90.0, bufferedS = 50.0)
        playback.onRecord(record("""{"type":"playbackInfo","kind":"request","messageID":9}"""))
        val text = texts.last()
        for (real in listOf(""""rate":1.0""", """"position":20.0""", """"duration":90.0""", """"start":20.0""", """"duration":30.0""")) {
            assertTrue("$real in $text", text.contains(real))
        }
        assertTrue("an integer stays an integer: $text", text.contains(""""messageID":9,""") || text.endsWith(""""messageID":9}"""))
        val list = JSONArray(listOf(1, 2.0, true, "q\"s"))
        val mixed = JSONObject().put("a", list).put("b", JSONObject.NULL).put("c", JSONObject())
        assertEquals("""{"a":[1,2.0,true,"q\"s"],"b":null,"c":{}}""", VideoPlayback.jsonText(mixed))
        assertEquals("null", VideoPlayback.jsonText(Double.NaN))
        assertEquals("12345678901", VideoPlayback.jsonText(12_345_678_901L))
    }

    @Test
    fun `a CMTime start, as Safari sends it, is value over timescale`() {
        playback.onRecord(record(insert.replace("12.5", """{"flags":1,"value":125,"timescale":10,"epoch":0}""")))
        assertEquals(12.5, playback.state.item?.startS ?: -1.0, 0.0)
        assertEquals("load 12.5", engine.calls.last())
        assertEquals(1.5, VideoPlayback.seconds(JSONObject("""{"flags":3,"value":1500000000,"timescale":1000000000}""")) ?: -1.0, 0.0)
        assertNull("not valid", VideoPlayback.seconds(JSONObject("""{"flags":0,"value":125,"timescale":10}""")))
        assertNull("indefinite", VideoPlayback.seconds(JSONObject("""{"flags":17,"value":0,"timescale":1}""")))
        assertNull("no timescale", VideoPlayback.seconds(JSONObject("""{"flags":1,"value":125,"timescale":0}""")))
        assertEquals(3.0, VideoPlayback.seconds(3) ?: -1.0, 0.0)
        assertNull(VideoPlayback.seconds(null))
    }

    @Test
    fun `before the player is ready, playbackInfo says not ready at the item's start`() {
        playback.onRecord(record(insert))
        playback.onRecord(record("""{"type":"playbackInfo","kind":"request","messageID":9}"""))
        val value = sent.last().second.getJSONObject("value")
        assertFalse(value.getBoolean("readyToPlay"))
        assertEquals(12.5, value.getDouble("position"), 0.0)
        assertEquals(0, value.getJSONArray("seekableTimeRanges").length())
    }

    @Test
    fun `video plays only while the car is known to be parked`() {
        playback.onRecord(record(insert))
        parked = false // moving, or no car service reading yet
        playback.onRecord(rate(1.0))
        assertEquals("rate 0.0", engine.calls.last())
        assertFalse(playback.showing)
        assertEquals("a refused play presses the phone's Pause", 1, pauses)
        assertEquals(listOf(false to VideoPlayback.Status.AUDIO_ONLY), announced)
        assertFalse(playback.showRequested())
        playback.onRecord(rate(0.0)) // the phone followed the Pause
        parked = true
        playback.onRecord(rate(1.0))
        assertTrue(playback.showing)
        assertEquals("play start tells the phone", true to VideoPlayback.Status.VIDEO, announced.last())
        parked = false
        playback.onParked(false)
        assertEquals("rate 0.0", engine.calls.last())
        assertFalse("losing parked closes the screen", playback.showing)
        assertEquals("and presses Pause on a phone that plays", 2, pauses)
        assertEquals(false to VideoPlayback.Status.AUDIO_ONLY, announced.last())
        engine.calls.clear()
        parked = true
        playback.onParked(true)
        assertTrue("becoming parked plays nothing by itself", engine.calls.isEmpty())
        assertFalse(playback.showing)
        assertEquals(true to VideoPlayback.Status.VIDEO, announced.last())
    }

    @Test
    fun `the session's start tells the phone, idle with nothing queued`() {
        parked = false
        playback.onParked(false) // the session starts, its car state not known
        assertEquals(listOf(false to VideoPlayback.Status.IDLE), announced)
        parked = true
        playback.onParked(true)
        assertEquals(true to VideoPlayback.Status.IDLE, announced.last())
        playback.onParked(false)
        assertEquals("nothing plays, so no Pause", 0, pauses)
    }

    @Test
    fun `seek moves, stop frees the player, and a message that isn't a request gets no answer`() {
        playback.onRecord(record(insert))
        sent.clear()
        playback.onRecord(record("""{"type":"seek","time":30.0}"""))
        assertEquals("seek 30.0", engine.calls.last())
        assertTrue("no kind: request, no answer", sent.isEmpty())
        playback.onRecord(record("""{"type":"stop","kind":"request","messageID":10}"""))
        assertEquals("stop", engine.calls.last())
        assertNull(playback.state.item)
        assertEquals("stop", sent.single().second.getString("type"))
    }

    @Test
    fun `removePlayQueueItem drops the queued item and is answered, but not another item`() {
        playback.onRecord(record(insert))
        playback.onRecord(rate(1.0))
        sent.clear()
        playback.onRecord(record("""{"type":"removePlayQueueItem","kind":"request","messageID":11,"item":{"uuid":"item-2"}}"""))
        assertEquals("another item's uuid: the queued one stays", "item-1", playback.state.item?.uuid)
        assertTrue(playback.showing)
        playback.onRecord(record("""{"type":"removePlayQueueItem","messageID":12,"item":{"uuid":"item-1"}}"""))
        assertNull(playback.state.item)
        assertEquals("stop", engine.calls.last())
        assertFalse(playback.showing)
        assertEquals(listOf(11, 12), sent.map { it.second.getInt("messageID") })
        assertEquals("removePlayQueueItem", sent.last().second.getString("type"))
        playback.onRecord(record(insert))
        playback.onRecord(record("""{"type":"removePlayQueueItem"}"""))
        assertNull("no uuid: the queued item goes", playback.state.item)
        playback.onRecord(record(insert))
        announced.clear()
        playback.onRecord(rate(1.0))
        assertEquals("the next item's play start tells the phone again", listOf(true to VideoPlayback.Status.VIDEO), announced)
    }

    @Test
    fun `a property request is answered with a value, the logs as empty lists`() {
        playback.onRecord(record(insert))
        playback.onRecord(record("""{"type":"property","kind":"request","messageID":13,"property":"playbackAccessLog"}"""))
        val answer = sent.last().second
        assertEquals("playbackAccessLog", answer.getString("property"))
        assertEquals(13, answer.getInt("messageID"))
        assertTrue(texts.last(), texts.last().contains(""""value":[]"""))
        playback.onRecord(record("""{"type":"property","kind":"request","messageID":14,"property":"playbackErrorLog"}"""))
        val errorLog = sent.last().second.getJSONArray("value")
        assertEquals(0, errorLog.length())
        playback.onRecord(record("""{"type":"property","kind":"request","messageID":15,"property":"somethingNew"}"""))
        assertFalse("anything else has no value, as LIVI answers", sent.last().second.has("value"))
        assertEquals(15, sent.last().second.getInt("messageID"))
    }

    @Test
    fun `a player failure closes the screen, and the next item starts clean`() {
        playback.onRecord(record(insert))
        playback.onRecord(rate(1.0))
        assertTrue(playback.showing)
        engine.fail("ERROR_CODE_DRM_SCHEME_UNSUPPORTED")
        assertFalse(playback.showing)
        assertEquals("ERROR_CODE_DRM_SCHEME_UNSUPPORTED", playback.state.failed)
        assertEquals("the failure presses the phone's Pause, as Drive and Close do", 1, pauses)
        assertEquals("the phone hears what a drive makes it hear", false to VideoPlayback.Status.AUDIO_ONLY, announced.last())
        playback.onRecord(record(insert))
        assertNull(playback.state.failed)
    }

    /** enigy/carlink's bench, 11 Nov: the phone kept "playing" a FairPlay item the player failed on. */
    @Test
    fun `after a failure the item stays closed, and dropping it allows video again`() {
        playback.onRecord(record(insert))
        playback.onRecord(rate(1.0))
        engine.fail("ERROR_CODE_DRM_SCHEME_UNSUPPORTED")
        playback.onRecord(rate(0.0))
        playback.onRecord(rate(1.0))
        assertFalse("a play request for the failed item doesn't reopen its screen", playback.showing)
        assertEquals(false to VideoPlayback.Status.AUDIO_ONLY, announced.last())
        announced.clear()
        playback.onRecord(record("""{"type":"stop","kind":"request","messageID":9}"""))
        assertEquals("the phone dropped it: video may play again", listOf(true to VideoPlayback.Status.IDLE), announced)
        announced.clear()
        playback.onRecord(record("""{"type":"stop","kind":"request","messageID":10}"""))
        assertTrue("said once", announced.isEmpty())
    }

    @Test
    fun `iOS's request for the video screen, and the screen's Close`() {
        assertFalse("nothing queued", playback.showRequested())
        playback.onRecord(record(insert))
        assertTrue(playback.showRequested())
        assertTrue(playback.showing)
        playback.close()
        assertFalse(playback.showing)
        assertEquals("Close frees the player", "stop", engine.calls.last())
        assertEquals("nothing played, so no Pause", 0, pauses)
        playback.onRecord(rate(1.0))
        playback.close()
        assertEquals("Close presses Pause on a phone that plays", 1, pauses)
    }

    @Test
    fun `another channel's messages are left alone, and the session's end frees the player`() {
        playback.onRecord(record(insert, client = "BB493F61-A6B8-4769-8D74-80C23A9F71C4"))
        assertNull(playback.state.item)
        assertTrue(sent.isEmpty() && engine.calls.isEmpty())
        playback.onRecord(record(insert))
        playback.reset()
        assertNull(playback.state.item)
        assertEquals("stop", engine.calls.last())
    }

    @Test
    fun `HLS is told by its path`() {
        assertTrue(ExoVideoEngine.isHls("https://cdn.example.com/v/master.m3u8?sig=x"))
        assertTrue("no extension: AirPlay video's usual playlist", ExoVideoEngine.isHls("https://linear.tv.apple.com/stream/abc"))
        assertFalse(ExoVideoEngine.isHls("https://cdn.example.com/v/clip.mp4"))
    }

    /** enigy's owner, 12 Nov: route A's video comes through the phone's network only, never the car's (the car's data plan). */
    @Test
    fun `without the phone's network an item is declined, never loaded`() {
        viaPhone = false
        playback.onRecord(record(insert))
        assertTrue("nothing loaded", engine.calls.none { it.startsWith("load") })
        assertEquals(VideoPlayback.NO_PHONE_NETWORK, playback.state.failed)
        assertEquals(listOf(false to VideoPlayback.Status.AUDIO_ONLY), announced)
        assertEquals("insertPlayQueueItem", sent.single().second.getString("type"))
        playback.onRecord(rate(1.0))
        assertFalse(playback.showing)
        assertFalse(playback.showRequested())
        assertTrue(engine.calls.none { it.startsWith("load") })
        announced.clear()
        playback.onRecord(record("""{"type":"stop","kind":"request","messageID":20}"""))
        assertEquals("dropped: video may play again", listOf(true to VideoPlayback.Status.IDLE), announced)
        viaPhone = true
        playback.onRecord(record(insert))
        assertEquals("load 12.5", engine.calls.last())
    }

    /** enigy/carlink's bench, 12 Nov: in Drive the playlist began loading 50 ms before the refusal. */
    @Test
    fun `an item queued in Drive loads only once it may play`() {
        parked = false
        playback.onRecord(record(insert))
        assertTrue("nothing loaded in Drive", engine.calls.isEmpty())
        playback.onRecord(rate(1.0))
        assertTrue(engine.calls.none { it.startsWith("load") })
        assertEquals(1, pauses)
        playback.onRecord(rate(0.0))
        parked = true
        playback.onParked(true)
        playback.onRecord(rate(1.0))
        assertEquals(listOf("load 12.5", "rate 1.0"), engine.calls.filter { it.startsWith("load") || it == "rate 1.0" })
        assertTrue(playback.showing)
    }

    /** enigy/carlink's bench, 12 Nov: closed while paused, the phone kept its item and reopened it at its next play. */
    @Test
    fun `Close ends the hand-off whether the video plays or is paused`() {
        playback.onRecord(record(insert))
        playback.onRecord(rate(1.0))
        playback.onRecord(rate(0.0)) // paused
        playback.close()
        assertFalse(playback.showing)
        assertEquals("a paused phone gets no Pause", 0, pauses)
        assertEquals(false to VideoPlayback.Status.AUDIO_ONLY, announced.last())
        assertEquals(VideoPlayback.CLOSED_HERE, playback.state.failed)
        engine.now = VideoEngine.Snapshot(ready = true, positionS = 20.0, durationS = 90.0, bufferedS = 50.0) // ignored now
        playback.onRecord(record("""{"type":"playbackInfo","kind":"request","messageID":22}"""))
        val info = sent.last().second.getJSONObject("value")
        assertFalse("it answers as a failed player, so the phone lets go (r3)", info.getBoolean("readyToPlay"))
        assertEquals(0.0, info.getDouble("rate"), 0.0)
        assertEquals(0, info.getJSONArray("loadedTimeRanges").length())
        playback.onRecord(rate(1.0))
        assertFalse("its next play doesn't reopen it", playback.showing)
        announced.clear()
        playback.onRecord(record("""{"type":"removePlayQueueItem","kind":"request","messageID":21}"""))
        assertEquals(listOf(true to VideoPlayback.Status.IDLE), announced)
        playback.onRecord(record(insert))
        playback.onRecord(rate(1.0))
        playback.close()
        assertEquals("a playing one gets Pause, then the same", 1, pauses)
        assertEquals(false to VideoPlayback.Status.AUDIO_ONLY, announced.last())
    }

    @Test
    fun `a playlist with FairPlay keys is told apart from a clear one`() {
        val fairPlay = """#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI="skd://itunes.apple.com/P000000000/s1/e1",KEYFORMAT="com.apple.streamingkeydelivery",KEYFORMATVERSIONS="1""""
        assertTrue(ExoVideoEngine.fairPlay(listOf("#EXTM3U", fairPlay)))
        assertTrue(ExoVideoEngine.fairPlay(listOf("#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://key\"")))
        assertFalse("AES-128 with a plain key plays", ExoVideoEngine.fairPlay(listOf("#EXT-X-KEY:METHOD=AES-128,URI=\"https://cdn.example.com/k\"")))
        assertFalse(ExoVideoEngine.fairPlay(listOf("#EXTM3U", "#EXT-X-VERSION:6", "#EXT-X-STREAM-INF:BANDWIDTH=1")))
        playback.onRecord(record(insert))
        playback.onRecord(rate(1.0))
        engine.fail(VideoEngine.FAIRPLAY)
        assertFalse(playback.showing)
        assertEquals(false to VideoPlayback.Status.AUDIO_ONLY, announced.last())
    }
}
