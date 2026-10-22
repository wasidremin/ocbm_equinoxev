package wasidremin.gmccpa.av

import org.json.JSONObject
import wasidremin.gmccpa.ProbeLog
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The merged now-playing picture assembled from the `:9004` metadata seam.
 *
 * ## Records are DELTAS — this is the whole reason the class exists
 * `metadata.rs:19` states the prerequisite outright: *the updates are DELTAS — merge non-empty fields
 * (an elapsed-only frame must not blank the title)*. iOS sends a full record on a track change and then
 * roughly 2 Hz frames carrying only `elapsedMs`; device-observed here on 2026-09-08 as 543 twelve-byte
 * `0x5001 NowPlayingUpdate` messages against a single 78-byte one. Publishing each record verbatim
 * would blank the title and artist twice a second — a merge bug that presents as a metadata bug.
 *
 * Every field is therefore MERGED, never replaced, and fields are cleared only on an inferred track
 * change, because iAP2 sends no "new track" marker.
 *
 * ## Wire keys
 * `metadata.rs:333-418`: title, artist, album, genre, composer, appName, durationMs, elapsedMs,
 * trackNumber, trackCount, discNumber, discCount, queueIndex, queueCount, shuffleMode, repeatMode,
 * playbackStatus, playbackSpeed, artworkId. Only the subset an AAOS media card renders is kept.
 */
class NowPlayingState {
    private val log = ProbeLog.sub("np")
    /** Posts the 2 s id-mismatch check off this monitor. Daemon: it must not pin the process. */
    private val artSched = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "np-art").apply { isDaemon = true }
    }
    private lateinit var art: ArtworkKeeper

    // ---- music-sync instrumentation -----------------------------------------------------------
    //
    // The 2026-10-20 drive showed the failure mode a per-line log cannot answer: the card was wrong
    // and the capture contained no evidence of whether the PHONE sent anything at all. A per-record
    // log would drown a 227 MB/h capture, so this is a counter plus a 30 s beat: one line that says
    // how many records, of which kind, arrived in the window — and, when nothing did, says so once
    // rather than not at all.
    //
    // All of it is guarded by this object's own monitor, the same one [onNowPlaying] / [onArtwork]
    // already hold, so a beat cannot observe a torn tally.
    private val tally = SyncTally()
    private var sessionRecords = 0L
    private var sessionArtwork = 0L
    private var firstRecordAtMs = 0L
    // This object is PROCESS-wide (CarPlayActivity.nowPlaying), so the clock has to be re-based by
    // [beginSession] — measured from construction, "first record N s after the seam opened" read
    // 7769 s on the 2026-10-22 drive (the app had been up since 18:59, the session began 21:09).
    private var sessionStartMs = System.currentTimeMillis()
    private var lastBeatAtMs = sessionStartMs
    private var silentLogged = false

    init {
        art = ArtworkKeeper(
            log = { log.i(it) },
            afterMs = { delay, fire ->
                artSched.schedule({
                    val next = synchronized(this@NowPlayingState) {
                        if (!fire()) null
                        else {
                            val n = snapshot.copy(artwork = art.artwork, artworkId = art.artworkId)
                            snapshot = n
                            n
                        }
                    }
                    if (next != null) emit(next)
                }, delay, TimeUnit.MILLISECONDS)
            },
        )
        artSched.scheduleWithFixedDelay({ beat() }, BEAT_MS, BEAT_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * One `np: sync` line per [BEAT_MS], plus a single "silent" line per gap.
     *
     * `scheduleWithFixedDelay` CANCELS all later runs if the task throws, so this must never
     * propagate — a diagnostic that dies silently is worse than none (the same trap [LogFiles]
     * documents for its flush executor).
     */
    private fun beat() = runCatching {
        val now = System.currentTimeMillis()
        var line: String? = null
        synchronized(this) {
            val windowSec = ((now - lastBeatAtMs) / 1000L).coerceAtLeast(1L)
            lastBeatAtMs = now
            if (tally.isEmpty()) {
                // Nothing at all — no records, no art, no ignored kinds. Report once per gap: a
                // silent seam repeated every 30 s for an hour is noise, but one line is the answer
                // to "did the phone ever send metadata on this session".
                if (!silentLogged) {
                    silentLogged = true
                    line = "np: sync SILENT ${windowSec}s — no nowPlaying JSON, no artwork, no other " +
                        "kinds on the :9004 seam since the last beat (first record after " +
                        "${if (firstRecordAtMs == 0L) "never" else "${(firstRecordAtMs - sessionStartMs) / 1000L}s"})"
                }
            } else {
                line = tally.line(windowSec)
                tally.clear()
                silentLogged = false
            }
        }
        line?.let { log.i(it) }
    }.onFailure { log.e("np: sync heartbeat failed: ${it.javaClass.simpleName}: ${it.message}") }

    data class Snapshot(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val genre: String? = null,
        val composer: String? = null,
        val appName: String? = null,
        val durationMs: Long = 0,
        val elapsedMs: Long = 0,
        /**
         * False when [elapsedMs] is only the field's default, not something the phone stated.
         *
         * iOS splits a track change across frames: the title frame usually carries NO
         * PlaybackAttributes, so `elapsedMs` falls through to the blank base and reads 0. Publishing
         * that as position 0 at rate 1 makes the card extrapolate "0:00 of this track" and its
         * remaining time jump to full length on every track boundary before snapping back — the
         * 2026-10-20 drive logged exactly that (`state=3 pos=0 rate=1.0` at 21:37:06.276 and
         * 22:21:37.538). A track change whose record omits the field now clears this flag, and
         * [CarPlayMediaBrowserService.publishState] publishes PLAYBACK_POSITION_UNKNOWN instead of a
         * fabricated 0 until a record actually carries an elapsed time.
         *
         * Excluded from [equals] together with [elapsedMs]: it describes the playback state, not the
         * displayed metadata, so it must not trigger a `MediaMetadata` rebuild.
         */
        val elapsedKnown: Boolean = true,
        val trackNumber: Int = 0,
        val trackCount: Int = 0,
        val artworkId: Int = -1,
        /** Raw JPEG for [artworkId]. Decoding belongs to the consumer, off the seam thread. */
        val artwork: ByteArray? = null,
        /**
         * iAP2 PlaybackAttributes id 0, verbatim: 0 stop, 1 play, 2 pause, 3 seek-forward,
         * 4 seek-back (`metadata.rs:374-378`). Null until iOS says. Deliberately never inferred from
         * the audio seam — bytes arriving on `:9002` mean the phone is sending, not that the user
         * considers it playing.
         */
        val playbackStatus: Int? = null,
    ) {
        val playing: Boolean
            get() = playbackStatus == PLAY || playbackStatus == SEEK_FWD || playbackStatus == SEEK_BACK

        /** Gates publishing: an empty card is worse than the previous one. */
        val hasContent: Boolean get() = !title.isNullOrEmpty() || !artist.isNullOrEmpty()

        /**
         * "Has the DISPLAYED metadata changed."
         *
         * [elapsedMs] is deliberately excluded. With it, every 2 Hz tick compares unequal and the
         * consumer rebuilds a `MediaMetadata` at that rate — which churns all six subscribed
         * controllers for a value that belongs in `PlaybackState` instead. Hand-written because the
         * generated `equals` would compare [artwork] by identity and defeat the check entirely.
         */
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Snapshot) return false
            return title == other.title && artist == other.artist && album == other.album &&
                genre == other.genre && composer == other.composer && appName == other.appName &&
                durationMs == other.durationMs && trackNumber == other.trackNumber &&
                trackCount == other.trackCount && artworkId == other.artworkId &&
                playbackStatus == other.playbackStatus &&
                artwork.contentEqualsOrBothNull(other.artwork)
        }

        override fun hashCode(): Int {   // must agree with equals(): no elapsedMs
            var r = title?.hashCode() ?: 0
            r = 31 * r + (artist?.hashCode() ?: 0)
            r = 31 * r + (album?.hashCode() ?: 0)
            r = 31 * r + durationMs.hashCode()
            r = 31 * r + artworkId
            r = 31 * r + (playbackStatus ?: -1)
            return r
        }
    }

    @Volatile var snapshot: Snapshot = Snapshot(); private set
    private val calls = mutableMapOf<String, Int>()
    @Volatile var onCallChanged: (() -> Unit)? = null

    @Synchronized fun phoneAction(): Byte? = when {
        calls.values.any { it == 2 } -> wasidremin.gmccpa.ocbm.Ocbm.TEL_ANSWER
        calls.values.any { it in 1..5 } -> wasidremin.gmccpa.ocbm.Ocbm.TEL_END
        else -> null
    }

    /**
     * Which overlay the call pill should show. Delegates to the pure [callPhaseOf] so the
     * mapping is unit-testable without constructing this class (its init spawns a thread).
     */
    @Synchronized fun callPhase(): CallPhase = callPhaseOf(calls)

    fun onCallState(json: JSONObject) {
        synchronized(this) {
            if (!json.has("status")) return
            val status = json.optInt("status", -1)
            if (status !in 0..6) return
            val id = json.optString("callUuid").ifEmpty { json.optString("remoteId", "unknown") }
            if (status == 0 && id == "unknown") calls.clear()
            else if (status == 0 || status == 6) calls.remove(id)
            else calls[id] = status
        }
        log.i("call-state: action=${phoneAction()}")
        onCallChanged?.invoke()
    }

    /** The displayed picture changed. Expensive path: a full `MediaMetadata` rebuild. */
    @Volatile var onMetadataChanged: ((Snapshot) -> Unit)? = null

    /**
     * A record arrived that the merge judged NOT worth a metadata publish — the ~2 Hz elapsed-only
     * deltas. Split from [onMetadataChanged] because the two costs differ by orders of magnitude: a
     * `PlaybackState` carries position and rate and is what a card interpolates from. Wiring only the
     * expensive half is how a position ends up refreshing once per track.
     */
    @Volatile var onPlaybackTick: ((Snapshot) -> Unit)? = null

    /**
     * Seam entry point. Runs on the `cp-meta` thread: JSON parse inline (cheap), callbacks must post
     * anything expensive elsewhere — see the blocking note on [MetadataSeam].
     */
    fun dispatch(marker: Int, payload: ByteArray) {
        when (marker) {
            MetadataSeam.META_JSON -> {
                val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return
                // The seam also carries routeGuidance, maneuver, callState, artworkReady and more.
                // Only the media picture is consumed — by scope decision there is no navigation or
                // telephony surface in this app, and half-handling a kind is worse than ignoring it.
                val kind = json.optString("kind")
                if (kind == "callState") { onCallState(json); noteIgnoredKind(kind); return }
                if (kind != "nowPlaying") { noteIgnoredKind(kind); return }
                val prevElapsed = snapshot.elapsedMs
                val changed = onNowPlaying(json)
                if (changed != null) {
                    noteMetadataPublished()
                    emit(changed)
                } else if (shouldFireTick(prevElapsed, snapshot.elapsedMs)) {
                    noteTick()
                    runCatching { onPlaybackTick?.invoke(snapshot) }
                        .onFailure { log.e("tick consumer threw: ${it.message}") }
                }
            }
            MetadataSeam.META_ARTWORK -> {
                if (payload.size < 2) return
                // Mask: Kotlin's Byte is signed, so an id >= 128 would read negative and never match
                // the JSON's int. The same compare cost the macOS host a bug once already.
                val id = payload[0].toInt() and 0xFF
                onArtwork(id, payload.copyOfRange(1, payload.size))?.let { emit(it) }
            }
            else -> Unit   // META_CMD, META_CORNERMASK: not consumed
        }
    }

    /**
     * Last artworkId for which a title-less record was logged. A record that names an artworkId
     * but no title is the phone announcing a queue it will not describe — the 2026-10-17 midday
     * sessions showed artworkId advancing with no titles, no ticks and no art, and the log gave
     * no evidence either way. Logged once per id (not per tick) via [titlelessRecordEvent].
     */
    private var lastTitlelessArtworkId = -2

    /** Apply one nowPlaying record. Returns the new snapshot if the DISPLAYED picture changed. */
    @Synchronized
    fun onNowPlaying(json: JSONObject): Snapshot? {
        val prev = snapshot
        // Track change is inferred from a different non-empty title. NOT from artworkId: that key is
        // absent from elapsed-only frames, so it would read as a change to -1 on every single tick.
        val incomingTitle = json.optStringOrNull("title")
        val recordArtworkId = json.optIntOrNull("artworkId")
        val trackChanged = incomingTitle != null && incomingTitle != prev.title
        tally.records++
        sessionRecords++
        if (trackChanged) tally.titles++
        if (firstRecordAtMs == 0L) {
            firstRecordAtMs = System.currentTimeMillis()
            log.i("np: sync first nowPlaying record ${(firstRecordAtMs - sessionStartMs) / 1000L}s after the seam opened")
        }
        if (trackChanged) {
            log.i(trackSummaryLine(
                incomingTitle,
                json.optStringOrNull("artist") != null,
                json.optIntOrNull("playbackStatus"),
                recordArtworkId,
            ))
        } else {
            titlelessRecordEvent(incomingTitle, recordArtworkId, lastTitlelessArtworkId)?.let {
                lastTitlelessArtworkId = it
                log.i("np: record without title (artworkId=$it status=${json.optIntOrNull("playbackStatus") ?: "?"})" +
                    " — phone announced no item to describe; expect no ticks and probe-only art")
            }
        }
        // On a track change, merge onto a BLANK record: a field the full record omits is genuinely
        // absent for the new track. Merging onto the previous snapshot instead kept the old artist,
        // album and duration alive beside the new title. playbackStatus carries across because it
        // describes the PLAYER, not the item. Artwork is the exception: iOS does not resend a JPEG
        // whose id did not change, so a chapter that keeps the cover must keep the bytes too.
        val base = if (trackChanged) Snapshot(playbackStatus = prev.playbackStatus) else prev
        art.onRecord(trackChanged, recordArtworkId)
        val next = Snapshot(
            title = incomingTitle ?: base.title,
            artist = json.optStringOrNull("artist") ?: base.artist,
            album = json.optStringOrNull("album") ?: base.album,
            genre = json.optStringOrNull("genre") ?: base.genre,
            composer = json.optStringOrNull("composer") ?: base.composer,
            appName = json.optStringOrNull("appName") ?: base.appName,
            // NOT `?: base.durationMs`: iOS states 0 for a duration it has not resolved yet, and
            // taking that literally erased a known duration mid-track (2026-10-20 22:21:37.536 ->
            // `dur=163854`, .539 -> `dur=0`, same title, same artwork id; and 21:59:16.277 the same
            // way). A 0 is "not stated", so it must fall through to what we already knew. On a
            // track change the base is blank, so a new item still starts with no duration rather
            // than inheriting the old one.
            durationMs = mergeDuration(base.durationMs, json.optLongOrNull("durationMs")),
            elapsedMs = json.optLongOrNull("elapsedMs") ?: base.elapsedMs,
            // Only a frame that states elapsed time makes the position trustworthy; a track-change
            // frame that omits it must not publish the 0 the blank base left behind.
            elapsedKnown = json.optLongOrNull("elapsedMs") != null || (!trackChanged && base.elapsedKnown),
            trackNumber = json.optIntOrNull("trackNumber") ?: base.trackNumber,
            trackCount = json.optIntOrNull("trackCount") ?: base.trackCount,
            artworkId = art.artworkId,
            artwork = art.artwork,
            playbackStatus = json.optIntOrNull("playbackStatus") ?: base.playbackStatus,
        )
        snapshot = next
        return if (next == prev) null else next
    }

    /**
     * Album art arrived on the iAP2 file-transfer session (`metadata.rs:1262-1272`), forwarded as
     * `META_ARTWORK`. It can land before OR after the record that references it, and iOS re-runs
     * transfers on reconnect, so all three cases are handled here rather than assumed away.
     */
    @Synchronized
    fun onArtwork(id: Int, jpeg: ByteArray): Snapshot? {
        val verdict = art.onTransfer(id, jpeg, !snapshot.title.isNullOrEmpty())
        tally.artwork++
        sessionArtwork++
        if (verdict == "accepted") tally.artAccepted++
        if (verdict != "accepted") return null
        val next = snapshot.copy(artwork = art.artwork, artworkId = art.artworkId)
        snapshot = next
        return next
    }

    /**
     * A CarPlay session is starting: re-base the sync clock so `firstRecordAfter` is measured from
     * HERE, and drop the previous session's tallies. Does not touch the merged picture.
     */
    fun beginSession() = synchronized(this) {
        val now = System.currentTimeMillis()
        sessionStartMs = now
        lastBeatAtMs = now
        firstRecordAtMs = 0L
        sessionRecords = 0L
        sessionArtwork = 0L
        tally.clear()
        silentLogged = false
    }

    /**
     * Session ended. Everything goes AND the cleared picture is published — a card still showing the
     * last track over a dead session is the metadata twin of the frozen-frame bug that
     * `CarPlayActivity.onSessionEnded` was written to fix. [emit] runs after the lock, the same
     * way [dispatch] does, so a consumer cannot re-enter under the monitor.
     */
    fun clear(why: String) {
        val cleared = synchronized(this) {
            art.clear()
            calls.clear()
            snapshot = Snapshot()
            log.i("np: cleared — $why")
            // Session totals, because the 30 s beats summed over a drive are harder to read than
            // the one number a reader actually wants: did this session ever have a track at all?
            log.i(syncSummaryLine(
                sessionRecords, sessionArtwork,
                if (firstRecordAtMs == 0L) null else (firstRecordAtMs - sessionStartMs) / 1000L,
            ))
            tally.clear()
            silentLogged = false
            firstRecordAtMs = 0L
            sessionRecords = 0L
            sessionArtwork = 0L
            snapshot
        }
        onCallChanged?.invoke()
        emit(cleared)
    }

    private fun emit(s: Snapshot) {
        runCatching { onMetadataChanged?.invoke(s) }
            .onFailure { log.e("metadata consumer threw: ${it.message}") }
    }

    @Synchronized private fun noteMetadataPublished() { tally.metadata++ }

    @Synchronized private fun noteTick() { tally.ticks++ }

    /** A JSON kind this app deliberately does not consume. Counted, never per-record logged. */
    @Synchronized private fun noteIgnoredKind(kind: String) {
        tally.ignored++
        if (kind.isNotEmpty()) tally.lastIgnoredKind = kind
    }

    companion object {
        /** Beat period for the `np: sync` line. Long enough not to matter for size at 227 MB/h. */
        const val BEAT_MS = 30_000L

        const val STOP = 0
        const val PLAY = 1
        const val PAUSE = 2
        const val SEEK_FWD = 3
        const val SEEK_BACK = 4
    }
}

// org.json returns "" from optString and 0 from optLong for a MISSING key — indistinguishable from a
// real value, and exactly wrong for a delta merge. These return null for absent so `?:` falls through
// to the previous value. An empty string counts as absent too: iOS sends "" for a field it has no
// value for, and letting that through would blank a good title.
internal fun JSONObject.optStringOrNull(k: String): String? =
    if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotEmpty() } else null

internal fun JSONObject.optLongOrNull(k: String): Long? = if (has(k) && !isNull(k)) optLong(k) else null

internal fun JSONObject.optIntOrNull(k: String): Int? = if (has(k) && !isNull(k)) optInt(k) else null

internal fun ByteArray?.contentEqualsOrBothNull(o: ByteArray?): Boolean =
    if (this == null || o == null) this == null && o == null else contentEquals(o)

/**
 * One beat's worth of music-sync counters. Mutable and package-internal, because the point is to
 * increment it from three synchronized call sites and render it from a fourth; a copy-on-write
 * structure would only add garbage at a rate nothing here is starved for.
 *
 * Field meanings, in the order the beat line prints them:
 * - [records] `nowPlaying` JSON records, whatever they contained.
 * - [titles] of those, how many named a different title (i.e. a track change).
 * - [metadata] how many produced a card rebuild — records whose DISPLAYED picture changed.
 * - [ticks] elapsed-only records that moved the position but rebuilt nothing.
 * - [artwork] JPEG transfers received on the seam; [artAccepted] those that reached the picture.
 * - [ignored] JSON kinds this app does not consume (`routeGuidance`, `maneuver`, `callState`, …),
 *   with [lastIgnoredKind] as the most recent. Non-zero `ignored` with zero `records` is the
 *   signature of a live seam carrying something other than music — a diagnosis no per-line log
 *   could have produced without drowning the capture.
 */
internal class SyncTally {
    var records = 0L
    var titles = 0L
    var metadata = 0L
    var ticks = 0L
    var artwork = 0L
    var artAccepted = 0L
    var ignored = 0L
    var lastIgnoredKind: String? = null

    /** True when nothing at all arrived — the case the beat reports once as SILENT. */
    fun isEmpty(): Boolean = records == 0L && artwork == 0L && ignored == 0L

    fun clear() {
        records = 0L; titles = 0L; metadata = 0L; ticks = 0L
        artwork = 0L; artAccepted = 0L; ignored = 0L; lastIgnoredKind = null
    }

    /**
     * `np: sync 30s records=12 (titleChanges=1 metadata=1 ticks=11) artwork=1 accepted=1 ignoredKinds=0`
     *
     * Titles and artists are deliberately absent: the capture is uploadable, and the counts are what
     * the diagnosis needs. The one place content appears is [trackSummaryLine].
     */
    fun line(windowSec: Long): String = buildString {
        append("np: sync ${windowSec}s records=$records")
        append(" (titleChanges=$titles metadata=$metadata ticks=$ticks)")
        append(" artwork=$artwork accepted=$artAccepted ignoredKinds=$ignored")
        lastIgnoredKind?.let { append(" lastIgnored=$it") }
    }
}

/**
 * One line for a finished session: did it ever have music, and how long did the phone take to say
 * so. [firstRecordAfterSec] is null when the seam never carried a single nowPlaying record, which is
 * a completely different bug from "the card showed the wrong title".
 */
internal fun syncSummaryLine(
    records: Long,
    artwork: Long,
    firstRecordAfterSec: Long?,
): String = "np: sync session records=$records artwork=$artwork firstRecordAfter=" +
    (firstRecordAfterSec?.let { "${it}s" } ?: "never")

/** Which call-overlay state the pill shows. NONE hides it. */
enum class CallPhase { NONE, RINGING, ACTIVE }

/**
 * One-line track-change summary for the log. Titles already surface in `mbs` card logs, so
 * naming the track here is consistent, not new exposure. Pure for unit tests.
 */
internal fun trackSummaryLine(title: String?, artistPresent: Boolean, status: Int?, artworkId: Int?): String =
    "np: track \"${title ?: "?"}\" artist=${if (artistPresent) "yes" else "no"}" +
        " status=${status ?: "?"} artworkId=${artworkId ?: "?"}"

/**
 * Returns [recordArtworkId] when this record should emit the title-less diagnostic: the record
 * names an artworkId but carries no title, and this id has not been reported yet. Otherwise null.
 * Pure for unit tests; the caller persists the returned id as the last-reported one.
 */
internal fun titlelessRecordEvent(
    incomingTitle: String?,
    recordArtworkId: Int?,
    lastLoggedId: Int,
): Int? = if (incomingTitle == null && recordArtworkId != null && recordArtworkId != lastLoggedId)
    recordArtworkId else null

/**
 * Merge one `durationMs` field. A non-positive value is "not stated" and leaves [prev] standing:
 * iOS sends 0 for a track whose duration it has not resolved, and letting that through erased a
 * known duration mid-track (2026-10-20 capture). Pure for unit tests.
 */
internal fun mergeDuration(prev: Long, incoming: Long?): Long =
    if (incoming != null && incoming > 0) incoming else prev

/** `PlaybackState.PLAYBACK_POSITION_UNKNOWN`, restated so the helpers below stay Android-free. */
internal const val POSITION_UNKNOWN = -1L

/**
 * The position to publish for one snapshot. Unknown when no record has stated elapsed time, so AAOS
 * shows no progress rather than interpolating a fabricated 0 (see [NowPlayingState.Snapshot.elapsedKnown]).
 * A focus pause still pins the frozen position — that value came from a real push.
 */
internal fun publishedPosition(
    elapsedKnown: Boolean,
    elapsedMs: Long,
    focusPaused: Boolean,
    frozenPosition: Long,
): Long = when {
    focusPaused -> frozenPosition
    elapsedKnown -> elapsedMs
    else -> POSITION_UNKNOWN
}

/**
 * True when an unchanged-metadata record actually moved [nextElapsed] relative to [prevElapsed].
 * Records that neither change displayed metadata nor advance elapsed time (e.g. duplicate ticks
 * or unsubscribed shuffle/repeat frames) do not fire the tick listener.
 */
internal fun shouldFireTick(prevElapsed: Long, nextElapsed: Long): Boolean =
    nextElapsed != prevElapsed

/**
 * Pure mapping from the `calls` table (callId -> `metadata.rs` TEL_ state code) to [CallPhase].
 * Code 2 is an unconnected incoming call (the only state [NowPlayingState.phoneAction] answers);
 * any other non-empty table means a call is up or on hold. Top-level and dependency-free so JVM
 * unit tests can cover it without touching Android or spawning the class executor.
 */
fun callPhaseOf(calls: Map<String, Int>): CallPhase = when {
    calls.isEmpty() -> CallPhase.NONE
    calls.values.any { it == 2 } -> CallPhase.RINGING
    else -> CallPhase.ACTIVE
}
