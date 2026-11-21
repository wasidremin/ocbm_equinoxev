package wasidremin.gmccpa.video

// Ported from enigy/carlink `app/src/main/kotlin/com/carlink/video/ExoVideoEngine.kt` at a04f4c0 (Unlicense;
// NOTICE.md). Changed here: the screen learns of the player and the picture's size from one main-thread
// callback ([onPictureChanged]) instead of Compose state flows, and Media3 is 1.8.0, the newest that builds
// against this app's compileSdk 35.

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import wasidremin.gmccpa.ProbeLog

/**
 * Route A's player (T34): Media3 ExoPlayer with its HLS module, for the AirPlay video the phone offers inside the
 * CarPlay session ([VideoPlayback] drives it). The player lives on the main thread, as ExoPlayer requires, and is
 * built only when the phone queues something, then freed at [stop]. [snapshot] is readable from any thread: it is
 * refreshed on every player event and twice a second while playing.
 *
 * Its sound goes to the media stream without asking for audio focus: the CarPlay audio keeps its own focus, and the
 * phone stops its own audio when it hands the video to the car. Errors are logged by code, never with the address.
 *
 * [mayPlay] is asked again on the main thread as the player starts: a play request decided on another thread can
 * reach the player after the car stopped being known to be parked, and then it pauses instead.
 */
class ExoVideoEngine(
    private val context: Context,
    /** Whether the car is known to be parked now. */
    private val mayPlay: () -> Boolean,
    /**
     * Where a stream is fetched from at its load: through the phone's network once it offered its proxy
     * ([wasidremin.gmccpa.video.proxy.TunnelDataSource]). Null: not loaded at all, since route A's video never comes
     * over the car's own network (enigy's owner, 12 Nov: the car's data plan; the same holds for this car).
     */
    private val network: () -> DataSource.Factory?,
) : VideoEngine {
    private val log = ProbeLog.sub("video")
    private val main = Handler(Looper.getMainLooper())

    /** The live player, for the screen to draw into ([VideoOverlayView]); null when nothing is loaded. */
    @Volatile var player: ExoPlayer? = null
        private set

    /** The video's size once known, for the screen's aspect ratio. */
    @Volatile var size: VideoSize? = null
        private set

    /** Told on the main thread when [player] or [size] changes. */
    @Volatile var onPictureChanged: (() -> Unit)? = null

    @Volatile private var snap = VideoEngine.Snapshot()

    @Volatile override var onFailed: ((code: String) -> Unit)? = null

    private val listener =
        object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()

            @OptIn(UnstableApi::class) // the HLS manifest's raw tags
            override fun onTimelineChanged(timeline: Timeline, reason: Int) = declineFairPlay()

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                size = videoSize.takeIf { it.width > 0 && it.height > 0 }
                onPictureChanged?.invoke()
            }

            override fun onPlayerError(error: PlaybackException) {
                log.w("the player failed: ${error.errorCodeName}")
                snap = snap.copy(failed = true, ready = false, rate = 0.0)
                report(error.errorCodeName)
            }
        }

    /**
     * Once the playlists are read: a FairPlay-protected item (the TV app's) is declined before any segment plays,
     * since this app can't play it. The player stops, and the queue hears it as a failure, [VideoEngine.FAIRPLAY].
     */
    @OptIn(UnstableApi::class) // the HLS manifest's raw tags
    private fun declineFairPlay() {
        val p = player ?: return
        val hls = p.currentManifest as? HlsManifest ?: return
        if (snap.failed || !fairPlay(hls.multivariantPlaylist.tags + hls.mediaPlaylist.tags)) return
        p.stop()
        log.i("the item is FairPlay-protected, which this app doesn't play: declined")
        snap = snap.copy(failed = true, ready = false, rate = 0.0)
        report(VideoEngine.FAIRPLAY)
    }

    /** Tell [onFailed], after the player's own callback returns: the queue may free the player in answer. */
    private fun report(code: String) {
        main.post { onFailed?.invoke(code) }
    }

    private val ticker =
        object : Runnable {
            override fun run() {
                publish()
                if (player?.isPlaying == true) main.postDelayed(this, TICK_MS)
            }
        }

    @OptIn(UnstableApi::class) // a media source over a data source of its own
    override fun load(url: String, startS: Double) = onMain {
        val viaPhone = network()
        if (viaPhone == null) {
            log.w("not loaded: the phone's network is gone, and route A's video never comes over the car's")
            onFailed?.invoke(NO_NETWORK)
            return@onMain
        }
        val p = player ?: build()
        val startMs = (startS * MS_PER_S).toLong().coerceAtLeast(0)
        p.setMediaSource(DefaultMediaSourceFactory(viaPhone).createMediaSource(mediaItem(url)), startMs)
        p.playWhenReady = false
        p.prepare()
        snap = VideoEngine.Snapshot(positionS = startS)
        log.i("loading (${if (isHls(url)) "HLS" else "by its extension"}), from $startS s, over the phone's network")
    }

    override fun setRate(rate: Double) = onMain {
        val p = player ?: return@onMain
        if (rate <= 0.0 || !mayPlay()) {
            p.pause()
        } else {
            p.setPlaybackSpeed(rate.toFloat())
            p.play()
        }
        publish()
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    override fun seek(toS: Double) = onMain {
        player?.seekTo((toS * MS_PER_S).toLong().coerceAtLeast(0))
    }

    override fun stop() = onMain {
        main.removeCallbacks(ticker)
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
        size = null
        snap = VideoEngine.Snapshot()
        onPictureChanged?.invoke()
    }

    override fun snapshot(): VideoEngine.Snapshot = snap

    private fun build(): ExoPlayer =
        ExoPlayer.Builder(context).build().also {
            it.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                false,
            )
            it.addListener(listener)
            player = it
            onPictureChanged?.invoke()
        }

    /** Main thread only: the player's state into [snap]. */
    private fun publish() {
        val p = player ?: return
        val duration = p.duration.takeIf { it != C.TIME_UNSET }?.let { it / MS_PER_S } ?: 0.0
        snap = VideoEngine.Snapshot(
            // Still ready at the item's end, its position at its duration, as LIVI reports an end: not ready reads
            // as a failure to the phone.
            ready = p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_ENDED,
            rate = if (p.isPlaying) p.playbackParameters.speed.toDouble() else 0.0,
            positionS = p.currentPosition / MS_PER_S,
            durationS = duration,
            bufferedS = p.bufferedPosition / MS_PER_S,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            failed = snap.failed && p.playbackState == Player.STATE_IDLE,
        )
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        private const val MS_PER_S = 1000.0
        private const val TICK_MS = 500L

        /**
         * Whether an HLS playlist's [tags] declare FairPlay keys: an EXT-X-KEY or EXT-X-SESSION-KEY for Apple's key
         * delivery. Pure.
         */
        fun fairPlay(tags: List<String>): Boolean =
            tags.any { tag ->
                (tag.startsWith("#EXT-X-KEY:") || tag.startsWith("#EXT-X-SESSION-KEY:")) &&
                    (tag.contains("com.apple.streamingkeydelivery", ignoreCase = true) || tag.contains("URI=\"skd://", ignoreCase = true))
            }

        /** [onFailed]'s code for a load with no phone network to come over. */
        const val NO_NETWORK = "NO_PHONE_NETWORK"

        /** The item for [url]: HLS when its path ends in `.m3u8` or has no extension (AirPlay video's usual form). */
        fun mediaItem(url: String): MediaItem {
            val b = MediaItem.Builder().setUri(url)
            if (isHls(url)) b.setMimeType(MimeTypes.APPLICATION_M3U8)
            return b.build()
        }

        /** Whether [url] is taken for an HLS playlist: a `.m3u8` path, or a path with no extension at all. Pure. */
        fun isHls(url: String): Boolean {
            val path = runCatching { java.net.URI(url).path }.getOrNull() ?: return false
            val last = path.substringAfterLast('/')
            return last.endsWith(".m3u8", ignoreCase = true) || !last.contains('.')
        }
    }
}
