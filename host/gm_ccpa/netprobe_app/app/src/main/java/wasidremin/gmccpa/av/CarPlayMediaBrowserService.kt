package wasidremin.gmccpa.av

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.service.media.MediaBrowserService
import android.view.KeyEvent
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.logging.SessionTrace
import wasidremin.gmccpa.pair.NativeCore
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

/**
 * Registers the app as an AAOS **media source**, and publishes the CarPlay now-playing state to it.
 *
 * ## Why this exists at all
 *
 * AAOS does not decide "this app plays audio, therefore it is a media source". It enumerates media
 * sources by querying for services that answer `android.media.browse.MediaBrowserService`. Without one,
 * the app plays audio perfectly and is still invisible to the source switcher and the homescreen Media
 * card — selecting FM then has no way back to us except relaunching the app by hand.
 *
 * This is not theoretical: with the service registered, `dumpsys car_service` shows
 * `Current playback media component: wasidremin.gmccpa/.av.CarPlayMediaBrowserService` and six controllers
 * subscribed to the session below (device-observed 2026-09-08). They were being answered with a
 * constant title "CarPlay" and `STATE_STOPPED` while HEVC and AAC were streaming. Title, art,
 * and position follow the phone. At connect time the card reports `STATE_BUFFERING` with
 * rate 1, which is how the original app gets CarMediaService to select it before FM is
 * already playing. Real user controls follow the phone over HID; source-change probes stay local.
 *
 * ## Framework class, not the AndroidX one
 *
 * This extends the platform's `android.service.media.MediaBrowserService`, NOT
 * `MediaBrowserServiceCompat`. `tools/build_apk.sh` compiles against `android.jar` + `kotlin-stdlib`
 * only — there is no AndroidX on the classpath and no Gradle dependency resolution — so the compat
 * class is unavailable. The framework class has answered the same intent action since API 21 and AAOS
 * accepts it; the compat library's value is pre-21 support and Media3 features we do not use.
 *
 * ## Deliberately not browsable
 *
 * CarPlay is a projection surface: the media library lives on the phone and is drawn by the phone. So
 * [onLoadChildren] returns an empty list. That is a legitimate media source — AAOS shows it as a source
 * with playback controls rather than a browse tree. Returning `null` instead would make AAOS treat the
 * connection as failed and drop us from the source list.
 *
 * A real browse tree would need iAP2 `MediaLibraryUpdate` (0x4C04), which is `Tier::Capability` and so
 * requires `tier: all` — a declaration iOS has already REFUTED on the wireless tunnel arm with an
 * unrecoverable `0x1D03`. The stub is the correct end state here, not a placeholder.
 *
 * ## Where the data comes from
 *
 * The `:9004` seam ([MetadataSeam]) feeds [NowPlayingState], which calls [publish] and
 * [publishPlaybackState] here. AAOS also binds this service on its own schedule, so the seam
 * reader cannot hold an instance. Hence the process-wide [live] weak reference, the same idiom
 * as `CarPlayActivity.live`. [ensureStarted] additionally starts it in the foreground at adapter
 * connect, before any phone traffic, the way the original app starts its media service at
 * CONNECTING.
 */
class CarPlayMediaBrowserService : MediaBrowserService() {

    private val log = ProbeLog.sub("mbs")
    /** Last `card: metadata` line, so a repeated title does not log again until the art or title moves. */
    private var cardLogLine: String? = null
    private var session: MediaSession? = null
    /** How many times AAOS has bound this service. Guarded by [bindLock]. */
    private var browserBinds = 0
    private val bindLock = Any()

    @Volatile private var artBitmap: Bitmap? = null
    @Volatile private var artFor: ByteArray? = null
    @Volatile private var foreground = false

    /**
     * Transport sends block on the native event-channel lock, and callbacks arrive on a binder thread.
     * One daemon thread keeps a slow phone from tying up the binder pool.
     */
    private val tx = Executors.newSingleThreadScheduledExecutor { Thread(it, "cp-media-btn").apply { isDaemon = true } }
    private var pendingTransport: java.util.concurrent.ScheduledFuture<*>? = null
    private var pendingCommand: String? = null
    private var pendingCaller: String? = null
    private var pendingAt = 0L
    private var commandGeneration = 0
    private var lastFocusCommandAt = -2_000L
    private var focusGeneration = 0
    private var pausedByFocusLoss = false
    private var pausedByTransientLoss = false
    private var focusLossAt = 0L
    /** Last pushed PlaybackState, so ~2 Hz ticks that change nothing do not re-baseline AAOS
     *  interpolation (re-baselining on jittered phone timestamps is what froze the card's seconds). */
    private var lastPushedState = -1
    private var lastPushedPos = 0L
    private var lastPushedAt = 0L
    private var lastPushedRate = 0f
    private var userPaused = false
    private var focusPaused = false
    private var seenContent = false
    private var knownStatus: Int? = null
    private var stateLogLine: String? = null
    private var frozenPosition = 0L
    // Not exported: instrumentation can substitute only the HID sink, leaving policy/framework real.
    private var hidSender: (Int) -> Boolean = { index ->
        if (wasidremin.gmccpa.ocbm.AdapterWifi.enabled(this))
            wasidremin.gmccpa.ocbm.AdapterSession.sendMediaButton(index.toByte())
        else NativeCore.mediaButton(index)
    }

    companion object {
        /** Any stable non-empty id. AAOS only uses it to scope onLoadChildren. */
        private const val ROOT_ID = "carplay_root"

        /**
         * Album art is downscaled to this before it reaches the session. iOS ships up to ~1400 px
         * (~100 KB JPEG), which is several MB decoded, and `MediaSession.setMetadata` would downscale
         * it again anyway against `config_mediaMetadataBitmapMaxSize`. 512 px covers every card on
         * this head unit.
         */
        private const val ART_MAX_PX = 512

        private const val ACTIONS = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS

        /** Ticks more than this far off the extrapolated position count as a seek and are pushed. */
        private const val SEEK_DEDUP_MS = 2_000L
        /** [SessionTrace.Board] entry. Bound/unbound is AAOS's decision, so the board is the only
         *  place "is the app in the source switcher right now" is answerable from a capture. */
        private const val BOARD = "media-browser"

        @Volatile private var live: WeakReference<CarPlayMediaBrowserService>? = null

        /**
         * Last snapshot, so a service bound AFTER the session came up still shows the current track.
         * AAOS binds Media Center on its own schedule, which is routinely later than RECORD.
         */
        @Volatile private var last: NowPlayingState.Snapshot? = null
        @Volatile private var sessionUp = false
        /**
         * Set at adapter bind (CONNECTING), before the service's [onCreate] may have run.
         * [onCreate] reads it so a late start still publishes the preparing state.
         */
        @Volatile private var wantPreparing = false
        /** Reset on every session. A status-less track warns once per session, not once per process. */
        @Volatile private var warnedNoPlaybackStatus = false
        /**
         * Diagnostic arbitration interval only. It must never suppress real user commands.
         */
        @Volatile private var arbitrationUntil = 0L
        private const val ARBITRATION_MS = 5_000L
        fun onMediaFocus(change: Int, voice: Boolean) {
            live?.get()?.let { service -> service.tx.execute { service.handleFocus(change, voice) } }
        }

        /** Sidebar and car card share user-intent bookkeeping and focus-before-PLAY ordering. */
        fun userMediaButton(index: Byte, caller: String = "sidebar") {
            live?.get()?.let { service -> service.tx.execute { service.forward(index.toInt(), caller) } }
        }
        private val seenCallers = HashSet<String>()
        /**
         * Null until the first `setMediaSource` lookup. False means this process already learned
         * the method is absent, and later calls return without reflecting again.
         */
        @Volatile var sourceSwitchable: Boolean? = null

        /** One "session not up" line per gap, so a 2 Hz tick cannot flood the capture. */
        private var cardDropLogged = false

        /** Seam entry point — the displayed metadata changed. Callable from any thread. */
        fun publish(s: NowPlayingState.Snapshot) {
            last = s
            if (!sessionUp) {
                if (!cardDropLogged) {
                    cardDropLogged = true
                    ProbeLog.sub("mbs").i("card: update dropped — session not up")
                }
                return
            }
            live?.get()?.publishNow(s)
        }

        /** Seam entry point — the ~2 Hz elapsed-only tick. Never rebuilds `MediaMetadata`. */
        fun publishPlaybackState(s: NowPlayingState.Snapshot) {
            last = s
            if (!sessionUp) return
            live?.get()?.publishState(s, "tick")
        }

        /**
         * The adapter is connecting, or a CarPlay session just came up. Publish
         * [PlaybackState.STATE_BUFFERING] at rate 1 until the phone names a playback
         * status. The original app does this at CONNECTING, seconds before it asks for
         * focus: CarMediaService only lets a newly playing source displace a source that
         * is not already playing. Waiting until the music stream opens is too late.
         */
        fun announcePreparing() {
            if (wasidremin.gmccpa.AudioRoute.bluetooth) return
            wantPreparing = true
            noteArbitration("preparing")
            live?.get()?.publishPreparing()
        }

        /**
         * True while a stream-start should re-signal BUFFERING. False on the Bluetooth route,
         * and false once the phone has sent a playback status — calling [announcePreparing]
         * after that would restart the arbitration window.
         */
        fun needsPreparing(): Boolean =
            !wasidremin.gmccpa.AudioRoute.bluetooth && last?.playbackStatus == null && last?.hasContent != true

        /** Records the initial source-arbitration interval; transport filtering is pattern-only. */
        fun noteArbitration(why: String) {
            arbitrationUntil = SystemClock.elapsedRealtime() + ARBITRATION_MS
            ProbeLog.sub("mbs").i("arbitration window ${ARBITRATION_MS}ms — $why")
        }

        fun inArbitration(): Boolean = SystemClock.elapsedRealtime() < arbitrationUntil

        fun noteCaller(pkg: String, uid: Int) {
            if (pkg.isEmpty() || pkg == "?") return
            val first = synchronized(seenCallers) { seenCallers.add(pkg) }
            if (first) ProbeLog.sub("mbs").i("transport: first caller package=$pkg uid=$uid — candidate steering-wheel source")
        }

        /**
         * A CarPlay session came up. Playback state follows the phone once Now Playing arrives.
         * See [publishState].
         */
        fun onSessionUp() {
            sessionUp = true
            cardDropLogged = false
            warnedNoPlaybackStatus = false
            announcePreparing()
            live?.get()?.resetCardLog()
            replayCard()
            // Not a fault at this instant — AAOS binds Media Center on its own schedule, routinely
            // after RECORD — but a session that ends with this still DOWN never appeared in the
            // source switcher, and the board is the only place that is visible.
            if (live?.get() != null) SessionTrace.Board.up(BOARD, "bound by AAOS; session up")
            else SessionTrace.Board.down(BOARD, "not bound by AAOS at session up — not in the source switcher until Media Center binds")
        }

        /**
         * The session ended. This IS an inference we are entitled to make — the phone will never send
         * a final "stopped" record, it simply stops — so idle is asserted here. Clearing [sessionUp]
         * also drops any seam record still in flight from the dead session.
         */
        fun onSessionDown() {
            sessionUp = false; last = null; wantPreparing = false
            AacPlayer.clearPermanentLoss()
            live?.get()?.let { service -> service.tx.execute { service.resetTransport() } }
            cardDropLogged = false
            warnedNoPlaybackStatus = false
            live?.get()?.resetCardLog()
            live?.get()?.let {
                it.publishIdle()
                it.stopForegroundState()
                SessionTrace.Board.up(BOARD, "bound by AAOS; session idle (card cleared)")
            }
        }

        /** Settings changed the audio source, or the service just came up. */
        fun applyRoute() {
            live?.get()?.applySessionActive()
        }

        /** Ask the head unit to play this app. No-op when the driver chose Bluetooth.
         *  After the first "not switchable" result, returns without reflecting again.
         *  The lookup itself runs on the service executor, never on the caller. */
        fun claimCarSource() {
            if (sourceSwitchable == false) return
            live?.get()?.enqueueClaim()
        }

        /** Distinct from [CarPlaySessionService]'s notification. Two foreground services, two ids. */
        /** The picture to put on the card: the live snapshot when it has a title, else the last stored one. */
        private fun replayCard() {
            val snap = CarPlayActivity.nowPlaying.snapshot
            val s = if (snap.hasContent) snap else last?.takeIf { it.hasContent } ?: return
            last = s
            live?.get()?.publishNow(s)
        }

        private const val NOTIF_ID = 1002
        private const val CHANNEL_ID = "carplay_media"

        /**
         * Bring the service up ourselves, in the foreground, as a media-playback service.
         * AAOS binds it on its own schedule, which on the 2026-09-24 drive was never.
         * Always starts, even when [live] is already set: a bind-only instance has not
         * called [startForeground] yet. [onStartCommand] is idempotent.
         */
        fun ensureStarted(ctx: android.content.Context) {
            val app = ctx.applicationContext
            val intent = android.content.Intent(app, CarPlayMediaBrowserService::class.java)
            runCatching { app.startForegroundService(intent) }
                .onFailure {
                    ProbeLog.sub("mbs").w(
                        "startForegroundService refused: ${it.javaClass.simpleName}: ${it.message}"
                    )
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        return START_NOT_STICKY
    }

    /**
     * Media playback only. No microphone type: a Play install does not grant `RECORD_AUDIO`,
     * and declaring that type on `startForeground` force-closed versionCode 34 and 35.
     * [CarPlaySessionService] is the process pin that adds the microphone type when held.
     */
    private fun promote() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT < 29) {
            startForeground(NOTIF_ID, n)
            foreground = true
            log.i("foreground media service started")
            return
        }
        try {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foreground = true
            log.i("foreground media service started (mediaPlayback)")
        } catch (e: Exception) {
            log.e("startForeground(mediaPlayback) refused: $e")
        }
    }

    /** Session ended. The service may stay bound; the foreground notification does not. */
    private fun stopForegroundState() {
        if (!foreground) return
        foreground = false
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            .onFailure { log.w("stopForeground refused: ${it.javaClass.simpleName}: ${it.message}") }
        log.i("foreground media service stopped — session down")
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "CarPlay media", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            },
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("CarPlay")
            .setContentText("Connecting media")
            .setSmallIcon(android.R.drawable.stat_sys_headset)
            .setOngoing(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        val s = MediaSession(this, "CarPlayRx")
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        s.setCallback(callback)
        session = s
        sessionToken = s.sessionToken
        live = WeakReference(this)
        // The session stays ACTIVE for the service's whole life rather than being deactivated when
        // there is no content: the source switcher must be able to select CarPlay BEFORE any audio
        // flows, and a source that only appears once it is already playing cannot be switched *to*.
        // Idle is therefore expressed as STATE_STOPPED plus placeholder metadata, not as an inactive
        // session.
        wasidremin.gmccpa.AudioRoute.load(this)
        publishIdle()
        applySessionActive()
        if (wantPreparing || sessionUp) publishPreparing()
        if (sessionUp) replayCard()
        log.i("registered as an AAOS media source")
        SessionTrace.Board.up(BOARD, "bound by AAOS; session ${if (sessionUp) "up" else "idle"}")
    }

    /**
     * Adapter keeps the session active and asks the car to select us, which is what puts
     * CarPlay audio on the cabin speakers. Bluetooth marks the session inactive so the
     * stereo stays on the phone, matching the earlier app's `setInactive()`.
     */
    private fun applySessionActive() {
        val s = session ?: return
        val bt = wasidremin.gmccpa.AudioRoute.bluetooth
        s.isActive = !bt
        if (bt) log.i("audio source Bluetooth — media session inactive")
        else {
            log.i("audio source Adapter — media session active")
            enqueueClaim()
        }
    }

    /**
     * `CarMediaManager` is not in the API 35 car stub this app compiles against, and some
     * head units still have it. Reflection keeps a missing class from being a build break.
     * A failed claim leaves the session active, which is the path AAOS already uses.
     */
    /** Posts the reflection onto [tx]. A consume-thread caller returns immediately. */
    private fun enqueueClaim() {
        if (sourceSwitchable == false) return
        if (wasidremin.gmccpa.AudioRoute.bluetooth) return
        tx.execute {
            if (sourceSwitchable == false) return@execute
            if (wasidremin.gmccpa.AudioRoute.bluetooth) return@execute
            claimCarSource()
        }
    }

    private fun claimCarSource() {
        if (sourceSwitchable == false) return
        if (wasidremin.gmccpa.AudioRoute.bluetooth) return
        val cn = ComponentName(this, CarPlayMediaBrowserService::class.java)
        runCatching {
            val carClass = Class.forName("android.car.Car")
            val car = carClass.getMethod("createCar", android.content.Context::class.java)
                .invoke(null, applicationContext)
            if (car == null) {
                sourceSwitchable = false
                log.w("car media source not switchable — Car.createCar returned null")
                return@runCatching
            }
            val service = try {
                carClass.getField("CAR_MEDIA_SERVICE").get(null) as String
            } catch (_: Throwable) {
                "car_media"
            }
            val mgr = carClass.getMethod("getCarManager", String::class.java).invoke(car, service)
            val set = mgr?.javaClass?.methods?.firstOrNull {
                it.name == "setMediaSource" && it.parameterTypes.size == 1
            }
            if (set == null) {
                sourceSwitchable = false
                log.w("car media source not switchable from here (${mgr?.javaClass?.name ?: "no manager"}) — session stays active")
            } else {
                sourceSwitchable = true
                set.invoke(mgr, cn)
                log.i("car media source set to ${cn.flattenToShortString()}")
            }
            runCatching { carClass.getMethod("disconnect").invoke(car) }
        }.onFailure {
            sourceSwitchable = false
            log.w("could not select this app as the car media source (${it.javaClass.simpleName}: ${it.message})")
        }
    }

    override fun onDestroy() {
        if (live?.get() === this) live = null
        session?.let { it.isActive = false; it.release() }
        session = null
        artBitmap = null; artFor = null
        tx.shutdownNow()
        SessionTrace.Board.down(BOARD, "unbound by AAOS — not in the source switcher until it re-binds")
        super.onDestroy()
    }

    /**
     * Every caller is allowed. This is a projection surface with no library to protect, and rejecting
     * unknown callers (the usual package-allowlist pattern) is how an app silently disappears from the
     * source list on an OEM build whose Media Center package you did not anticipate.
     */
    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        // One line per bind. The music card can only be wrong in one of two ways: the phone never sent
        // metadata, or nobody in the car bound us to show it. `np: sync` answers the first; this
        // answers the second, and neither is visible from the other.
        val n = synchronized(bindLock) { ++browserBinds }
        log.i(
            "browser: bind #$n package=$clientPackageName uid=$clientUid " +
                "(sessionUp=$sessionUp sourceSwitchable=$sourceSwitchable)"
        )
        return BrowserRoot(ROOT_ID, null)
    }

    /** Empty, not null — see the class docs. Null reads as a failed connection. */
    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        result.sendResult(mutableListOf())
    }

    // ---- publishing ------------------------------------------------------------------------------

    /**
     * Carlink publishes `STATE_BUFFERING` + `playWhenReady=true` at CONNECTING, before any
     * media frame. That is the arbitration weight CarMediaService uses to select the app.
     * A real Now Playing status replaces it. Bluetooth leaves the card stopped.
     */
    private fun publishPreparing() {
        val sess = session ?: return
        if (wasidremin.gmccpa.AudioRoute.bluetooth) return
        if (last?.playbackStatus != null || last?.hasContent == true || seenContent) return
        sess.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, "CarPlay")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Waiting for media...")
                .build()
        )
        sess.setPlaybackState(
            PlaybackState.Builder().setActions(ACTIONS)
                .setState(PlaybackState.STATE_BUFFERING, 0L, 1f, SystemClock.elapsedRealtime())
                .build()
        )
        log.i("playback STATE_BUFFERING rate=1 t=${SystemClock.elapsedRealtime()} — selecting this source before another one locks")
    }

    private fun publishIdle() {
        val sess = session ?: return
        artBitmap = null; artFor = null
        sess.setMetadata(
            MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "CarPlay").build()
        )
        sess.setPlaybackState(
            PlaybackState.Builder().setActions(ACTIONS)
                .setState(PlaybackState.STATE_STOPPED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f,
                    SystemClock.elapsedRealtime())
                .build()
        )
    }

    fun resetCardLog() { cardLogLine = null }

    private fun publishNow(s: NowPlayingState.Snapshot) {
        val sess = session ?: return
        // Metadata waits for a title. Playback state does not: a status-only update (paused
        // before any title) must leave BUFFERING, or the card keeps claiming the source.
        if (s.hasContent) {
        val b = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, s.title.orEmpty())
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, s.title.orEmpty())
            .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist.orEmpty())
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, s.artist.orEmpty())
            .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album.orEmpty())
        s.genre?.let { b.putString(MediaMetadata.METADATA_KEY_GENRE, it) }
        s.composer?.let { b.putString(MediaMetadata.METADATA_KEY_COMPOSER, it) }
        if (s.durationMs > 0) b.putLong(MediaMetadata.METADATA_KEY_DURATION, s.durationMs)
        if (s.trackNumber > 0) b.putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, s.trackNumber.toLong())
        if (s.trackCount > 0) b.putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, s.trackCount.toLong())
        val art = bitmapFor(s.artwork)
        art?.let {
            // BOTH keys: AAOS consumers disagree on which they read, and a card with no image where
            // one exists is the usual symptom of picking only one.
            b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it)
            b.putBitmap(MediaMetadata.METADATA_KEY_ART, it)
        }
        val queueId = if (s.artworkId >= 0) s.artworkId.toLong() else 0L
        sess.setQueue(listOf(MediaSession.QueueItem(
            MediaDescription.Builder()
                .setMediaId("carplay:" + (s.title.orEmpty().hashCode().toString(16)))
                .setTitle(s.title.orEmpty())
                .setSubtitle(s.artist.orEmpty())
                .setDescription(s.album.orEmpty())
                .build(), queueId)))
        val artNote = if (art != null) "${art.width}x${art.height}" else "none"
        val cardLine = "card: metadata \"${s.title.orEmpty()}\" art=$artNote " +
            "dur=${s.durationMs} status=${s.playbackStatus} queueId=$queueId (sessionUp=$sessionUp)"
        if (cardLine != cardLogLine) {
            cardLogLine = cardLine
            log.i(cardLine)
        }
        runCatching { sess.setMetadata(b.build()) }.onFailure { log.e("setMetadata failed: ${it.message}") }
        }
        publishState(s, "status")
    }

    /**
     * Separate from [publishNow] on purpose: position moves ~2 Hz and the metadata does not. Rebuilding
     * a `MediaMetadata` at that rate would churn every subscribed controller for a value that belongs
     * in the playback state.
     *
     * Retain the last PlaybackAttributes across null updates. BUFFERING is preparing-only:
     * once content is seen, unknown state must not reclaim another source. Focus pauses
     * override stale phone ticks at a frozen position until explicit play or transient resume.
     */
    @Synchronized
    private fun publishState(s: NowPlayingState.Snapshot, source: String = "replay") {
        val sess = session ?: return
        if (s.playbackStatus == null && s.hasContent && !warnedNoPlaybackStatus) {
            warnedNoPlaybackStatus = true
            log.w("track with no iAP2 playbackStatus — check NowPlayingUpdate PlaybackAttributes are subscribed")
        }
        seenContent = seenContent || s.hasContent
        s.playbackStatus?.let { knownStatus = it }
        val status = if (focusPaused) NowPlayingState.PAUSE else knownStatus
        val state = when (status) {
            NowPlayingState.STOP -> PlaybackState.STATE_STOPPED
            NowPlayingState.PAUSE -> PlaybackState.STATE_PAUSED
            NowPlayingState.PLAY, NowPlayingState.SEEK_FWD, NowPlayingState.SEEK_BACK -> PlaybackState.STATE_PLAYING
            else -> if (seenContent) PlaybackState.STATE_PAUSED else PlaybackState.STATE_BUFFERING
        }
        // Rate follows the published STATE, not the raw status: SEEK_FWD/BACK publish PLAYING and
        // must interpolate, or the card freezes mid-seek. (Was `status == PLAY`, so seeks stalled.)
        val rate = if (state == PlaybackState.STATE_PLAYING) 1f else 0f
        // Unknown rather than 0 when no record has stated elapsed time: a track-change frame carries
        // no PlaybackAttributes, and publishing the blank base's 0 at rate 1 made the card report
        // "0:00 of this track" (remaining = full length) until the next tick snapped it back.
        val pos = publishedPosition(s.elapsedKnown, s.elapsedMs, focusPaused, frozenPosition)
        val now = SystemClock.elapsedRealtime()
        // AAOS interpolates position from (pos, rate, updateTime) between pushes — that is the
        // designed path (the sibling Media3 app pushes only on change/seek/duration for the same
        // reason). Re-baselining at the phone's ~2 Hz tick rate on jittered timestamps makes the
        // card's seconds stutter or freeze, and spams six controllers per tick. Push ticks only on
        // a state/rate change or a seek (>2 s off the extrapolated line); explicit paths (status,
        // focus, replay after bind) always go through.
        if (source == "tick") {
            val expected = lastPushedPos + ((now - lastPushedAt) * lastPushedRate).toLong()
            // A push that left the position UNKNOWN owes AAOS the first real one, or the card would
            // sit with no progress until something else changed: extrapolating from -1 makes every
            // real tick look like "within 2 s of the line" and get suppressed.
            val seek = lastPushedAt == 0L || lastPushedPos == POSITION_UNKNOWN ||
                (pos != POSITION_UNKNOWN && kotlin.math.abs(pos - expected) > SEEK_DEDUP_MS)
            if (!seek && state == lastPushedState && rate == lastPushedRate) return
        }
        val ps = PlaybackState.Builder().setActions(ACTIONS)
            .setState(state, pos, rate, now)
            .build()
        runCatching { sess.setPlaybackState(ps) }.onFailure { log.e("setPlaybackState failed: ${it.message}") }
        lastPushedState = state; lastPushedPos = pos; lastPushedAt = now; lastPushedRate = rate
        val line = "card: state=$state pos=${if (pos == POSITION_UNKNOWN) "?" else pos.toString()} rate=$rate"
        if (line != stateLogLine) {
            stateLogLine = line
            log.i("$line source=$source")
        }
    }

    private fun bitmapFor(jpeg: ByteArray?): Bitmap? {
        if (jpeg == null) { artBitmap = null; artFor = null; return null }
        if (artFor === jpeg) return artBitmap   // decode once per blob, including a failed decode
        val bm = runCatching { decodeBounded(jpeg) }.getOrNull()
        if (bm == null) log.w("artwork (${jpeg.size} B) failed to decode — publishing without an image")
        else log.i("card: art decode ${bm.width}x${bm.height}")
        artBitmap = bm; artFor = jpeg
        return bm
    }

    /**
     * Bounds-probe then power-of-two subsample, so a 1400 px JPEG never decodes at full size. The box
     * deliberately forwards over-long artwork buffers (it has no link-layer duplicate suppression), so
     * a corrupt image is an expected input, not an exceptional one — hence the null return rather than
     * a throw.
     */
    private fun decodeBounded(jpeg: ByteArray): Bitmap? {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null
        var sample = 1
        while (probe.outWidth / sample > ART_MAX_PX || probe.outHeight / sample > ART_MAX_PX) sample *= 2
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---- transport controls ----------------------------------------------------------------------

    /**
     * Transport controls travel to the phone as taps on the uid-2 Consumer-Control HID device.
     *
     * This replaces an earlier claim in this file that "the existing OCBM INPUT_MEDIA_BTN path already
     * carries wheel buttons to the phone". That was inherited from the WIRED design and is false here:
     * `INPUT_MEDIA_BTN` is consumed by the BOX's `carplayd`, which in this app's bridge role never
     * spawns its A/V layer and therefore holds no event channel — the report would go nowhere. Nothing
     * in this app ever emitted the opcode either. Meanwhile wheel keys arrive as `KeyEvent`s routed to
     * the active `MediaSession` — this one — where the previously empty callback swallowed them. The
     * buttons were dead twice over.
     *
     * iOS owns playback. Only source-change callback probes stay on the car; all explicit
     * media keys and real transport commands use HID, independent of arbitration timing.
     * Focus pause is reflected immediately on the card, ahead of the phone's status response.
     */
    private val callback = object : MediaSession.Callback() {
        override fun onPlay() {
            val caller = callerLabel()
            tx.execute { forward(NativeCore.MediaBtn.PLAY, caller) }
        }
        override fun onPause() { transport("pause", callerLabel()) }
        override fun onStop() { transport("stop", callerLabel()) }
        override fun onSkipToNext() {
            val caller = callerLabel()
            tx.execute { forward(NativeCore.MediaBtn.NEXT, caller) }
        }
        override fun onSkipToPrevious() {
            val caller = callerLabel()
            tx.execute { forward(NativeCore.MediaBtn.PREV, caller) }
        }

        /**
         * Handled explicitly so PLAY_PAUSE reaches iOS as the dedicated toggle rather than being
         * guessed into play-or-pause from a state we do not own.
         *
         * Explicit key events are always user controls, even during source arbitration.
         */
        override fun onMediaButtonEvent(intent: Intent): Boolean {
            @Suppress("DEPRECATION")
            val ev = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                ?: return super.onMediaButtonEvent(intent)
            if (ev.action != KeyEvent.ACTION_DOWN) return true
            val caller = callerLabel()
            val idx = when (ev.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> NativeCore.MediaBtn.PLAY
                KeyEvent.KEYCODE_MEDIA_PAUSE -> NativeCore.MediaBtn.PAUSE
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> NativeCore.MediaBtn.PLAY_PAUSE
                KeyEvent.KEYCODE_MEDIA_NEXT -> NativeCore.MediaBtn.NEXT
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> NativeCore.MediaBtn.PREV
                KeyEvent.KEYCODE_MEDIA_STOP -> NativeCore.MediaBtn.PAUSE
                else -> return super.onMediaButtonEvent(intent)
            }
            tx.execute { forward(idx, caller) }
            return true
        }
    }

    /** `package=` and `uid=` of the controller that delivered this callback. */
    private fun callerLabel(): String {
        if (Build.VERSION.SDK_INT < 29) return "caller=package=? uid=?"
        val info = runCatching { session?.currentControllerInfo }.getOrNull()
            ?: return "caller=package=? uid=?"
        val pkg = info.packageName ?: "?"
        noteCaller(pkg, info.uid)
        return "caller=package=$pkg uid=${info.uid}"
    }

    /** Hold callback pauses/stops briefly so neither half of a probe reaches the phone. Keys bypass. */
    private fun transport(cmd: String, caller: String) {
        val arrived = SystemClock.elapsedRealtime()
        tx.execute {
            if (caller.contains("package=com.android.car ")) {
                log.i("transport: $cmd $caller dropped — source-change probe")
                return@execute
            }
            if (pendingCommand != null && pendingCommand != cmd && arrived - pendingAt <= 100L) {
                pendingTransport?.cancel(false)
                log.i("transport: $pendingCommand $pendingCaller dropped — source-change probe")
                log.i("transport: $cmd $caller dropped — source-change probe")
                pendingCommand = null
                return@execute
            }
            pendingTransport?.cancel(false)
            pendingCommand?.let { forward(NativeCore.MediaBtn.PAUSE, pendingCaller ?: "caller=package=?", it) }
            pendingCommand = cmd; pendingCaller = caller; pendingAt = arrived
            pendingTransport = tx.schedule({
                pendingCommand = null
                forward(NativeCore.MediaBtn.PAUSE, caller, cmd)
            }, 100L, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    private fun forward(index: Int, caller: String, name: String = when (index) {
        NativeCore.MediaBtn.PLAY -> "play"
        NativeCore.MediaBtn.PAUSE -> "pause"
        NativeCore.MediaBtn.NEXT -> "next"
        NativeCore.MediaBtn.PREV -> "previous"
        else -> "play-pause"
    }) {
        log.i("transport: $name $caller forwarded")
        val changesPlayback = index == NativeCore.MediaBtn.PLAY || index == NativeCore.MediaBtn.PAUSE ||
            index == NativeCore.MediaBtn.PLAY_PAUSE
        if (changesPlayback) { commandGeneration++; focusGeneration++ }
        val pause = index == NativeCore.MediaBtn.PAUSE ||
            (index == NativeCore.MediaBtn.PLAY_PAUSE && last?.playbackStatus == NowPlayingState.PLAY)
        val play = index == NativeCore.MediaBtn.PLAY ||
            (index == NativeCore.MediaBtn.PLAY_PAUSE && last?.playbackStatus != NowPlayingState.PLAY)
        if (pause) { userPaused = true; pausedByTransientLoss = false }
        if (play) {
            userPaused = false
            pausedByFocusLoss = false; pausedByTransientLoss = false
            AacPlayer.clearPermanentLoss()
            if (!AacPlayer.requestUserFocus()) {
                log.w("focus: GAIN → phone PLAY sent=false reason=user-play (focus not granted)")
                return
            }
        }
        val status = last?.playbackStatus
        val duplicate = (index == NativeCore.MediaBtn.PLAY && status == NowPlayingState.PLAY) ||
            (index == NativeCore.MediaBtn.PAUSE && status in listOf(NowPlayingState.PAUSE, NowPlayingState.STOP))
        if (play) synchronized(this) { focusPaused = false }
        val ok = !duplicate && hid(index)
        if (ok && play) last?.let { publishState(it.copy(playbackStatus = NowPlayingState.PLAY), "status") }
        if (play) log.i("focus: GAIN → phone PLAY sent=$ok reason=user-play")
        log.i("transport: $name $caller -> HID media $index sent=$ok")
        if (!ok && !duplicate && pause && sessionUp) {
            val generation = commandGeneration
            val deadline = SystemClock.elapsedRealtime() + 3_000L
            log.i("transport: $name $caller queued for 3000ms — no HID channel yet")
            retryPause(index, caller, generation, deadline)
        }
    }

    private fun retryPause(index: Int, caller: String, generation: Int, deadline: Long) {
        tx.schedule({
            if (!sessionUp || generation != commandGeneration) return@schedule
            if (SystemClock.elapsedRealtime() >= deadline) {
                log.w("transport: pause $caller expired — no HID channel within 3000ms")
            } else if (hid(index)) log.i("transport: pause $caller -> HID media $index sent=true (queued)")
            else retryPause(index, caller, generation, deadline)
        }, 100L, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun hid(index: Int): Boolean = hidSender(index)

    /** All intent/command decisions run on tx; card publication has its own short monitor. */
    private fun handleFocus(change: Int, voice: Boolean) {
        val permanent = change == android.media.AudioManager.AUDIOFOCUS_LOSS
        val transient = change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
        if (permanent || transient) {
            focusGeneration++
            if (voice) {
                log.i("focus: LOSS(${if (permanent) "permanent" else "transient"}) → phone PAUSE sent=false (status was ${last?.playbackStatus}; voice sink active)")
                return
            }
            if (permanent) { pausedByFocusLoss = true; pausedByTransientLoss = false }
            else if (!pausedByFocusLoss && !userPaused &&
                last?.playbackStatus != NowPlayingState.PAUSE && last?.playbackStatus != NowPlayingState.STOP)
                pausedByTransientLoss = true
            focusLossAt = SystemClock.elapsedRealtime()
            focusCommand(NativeCore.MediaBtn.PAUSE, focusGeneration,
                "focus: LOSS(${if (permanent) "permanent" else "transient"}) → phone PAUSE")
        } else if (change == android.media.AudioManager.AUDIOFOCUS_GAIN &&
            pausedByTransientLoss && !userPaused && !pausedByFocusLoss) {
            focusGeneration++ // Cancel a rate-limited PAUSE if focus returned before it ran.
            focusCommand(NativeCore.MediaBtn.PLAY, focusGeneration,
                "focus: GAIN → phone PLAY", "transient-resume")
        }
    }

    private fun focusCommand(index: Int, generation: Int, prefix: String, reason: String? = null) {
        if (!sessionUp || generation != focusGeneration) return
        val status = last?.playbackStatus
        val duplicate = if (index == NativeCore.MediaBtn.PAUSE)
            status == NowPlayingState.PAUSE || status == NowPlayingState.STOP
        else status == NowPlayingState.PLAY
        val now = SystemClock.elapsedRealtime()
        val wait = (2_000L - (now - lastFocusCommandAt)).coerceAtLeast(0L)
        if (!duplicate && wait > 0L) {
            tx.schedule({ focusCommand(index, generation, prefix, reason) }, wait, java.util.concurrent.TimeUnit.MILLISECONDS)
            return
        }
        val ok = !duplicate && hid(index)
        if (index == NativeCore.MediaBtn.PLAY && duplicate) {
            pausedByTransientLoss = false
            synchronized(this) { focusPaused = false; last?.let { publishState(it, "focus") } }
        }
        if (ok) {
            lastFocusCommandAt = now
            synchronized(this) {
                if (index == NativeCore.MediaBtn.PAUSE) {
                    frozenPosition = session?.controller?.playbackState?.let { ps ->
                        ps.position + ((now - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
                    } ?: last?.elapsedMs ?: 0L
                    focusPaused = true
                } else {
                    focusPaused = false; pausedByTransientLoss = false
                }
                last?.let { publishState(if (index == NativeCore.MediaBtn.PLAY)
                    it.copy(playbackStatus = NowPlayingState.PLAY) else it, "focus") }
            }
        }
        log.i("$prefix sent=$ok" + if (reason != null) " reason=$reason" else " (status was $status)")
    }

    private fun resetTransport() {
        commandGeneration++; focusGeneration++
        pendingTransport?.cancel(false); pendingCommand = null
        pausedByFocusLoss = false; pausedByTransientLoss = false; userPaused = false
        synchronized(this) {
            focusPaused = false; seenContent = false; knownStatus = null; stateLogLine = null
            lastPushedState = -1; lastPushedPos = 0L; lastPushedAt = 0L; lastPushedRate = 0f
        }
    }
}
