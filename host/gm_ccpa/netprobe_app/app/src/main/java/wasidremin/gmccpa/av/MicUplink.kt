package wasidremin.gmccpa.av

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.logging.SessionTrace
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Microphone uplink: vehicle mic → the Rust receiver → the iPhone.
 *
 * The receiver opens `127.0.0.1:9112` at library load. We connect to it and the socket carries BOTH
 * directions:
 *  - IN  (receiver → us): `uplink on <rate> <ch>\n` when iOS SETUPs type 100 with `input=true`, and
 *    `uplink off\n` at teardown. **This is the capture gate.**
 *  - OUT (us → receiver): `mic <len>\n` followed by `<len>` bytes of S16LE PCM.
 *
 * The Rust side does RTP framing, encryption with the stream INPUT key, and the byte-order swap.
 *
 * **Gate on the control line, never on downlink activity.** Siri wants the mic BEFORE any downlink
 * audio arrives, so an activity-based gate clips the onset of every request.
 *
 * **Connect eagerly, not on first data.** The same socket carries the gate, so a data-triggered
 * connect deadlocks: no connection → no gate → no capture → no data.
 *
 * ## This class IS live — both uplink encoders are compiled
 *
 * `carplay-jni/Cargo.toml` enables receiver `mic-uplink` (wired big-endian PCM) and defaults
 * `mic-uplink-eld` ON, so the truck x86_64 `.so` built by `tools/build_apk.sh` carries libfdk-aac
 * and the AAC-ELD entries in `/info` are real; the mic uplink is owner-confirmed on the truck
 * (`docs/13_AUDIO_ROUTING.md` §4). The ELD bail exists only for an aarch64/Pi build made with
 * `--no-default-features`. Do not assume this class is dormant.
 */
class MicUplink(
    /** Only for [micDiag]: who else is recording when ours fails. Null skips the diagnosis. */
    private val audio: AudioManager? = null,
    /** True while this app holds the car's assistant role. It picks the capture source; see [sourceFor]. */
    private val assistantHeld: () -> Boolean = { false },
) {

    private val log = ProbeLog.sub("mic")
    private val running = AtomicBoolean(false)
    private val capturing = AtomicBoolean(false)
    val framesSent = AtomicLong(0)

    /**
     * When set, captured PCM is handed here instead of the `:9112` socket, and [onGate] is the
     * capture gate (`CT_UPLINK` from the adapter). The Silverado path leaves this null.
     */
    var directSink: ((ByteArray, Int) -> Boolean)? = null

    @Volatile private var sock: Socket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var activeRate = 0
    @Volatile private var activeChannels = 0
    private var gateThread: Thread? = null
    /** Written on `mic-gate`, read by [stopCapture] from the UI thread too — hence volatile. */
    @Volatile private var captureThread: Thread? = null
    /** Capture-thread latch for [EXPECT_FIRST_CHUNK]; [framesSent] is per session, this is per gate. */
    @Volatile private var firstChunkThisCapture = false
    /** Captures started this session, so each capture's first-chunk line is attributable. */
    private var captures = 0
    /** Consecutive failed connects to `:9112`, gate thread only — see [gateLoop] for the severity rule. */
    private var connectFailures = 0

    private companion object {
        const val PORT = 9112
        /** 20 ms at the negotiated rate. Chunk size and period must stay in lockstep or the uplink
         *  underruns — the receiver packetises on the same cadence. */
        const val CHUNK_MS = 20
        /** Reconnect cadence to the gate seam while the receiver's listener is absent. */
        const val RECONNECT_MS = 2_000L
        /** [SessionTrace] name for "the capture the gate just opened has sent a chunk". */
        const val EXPECT_FIRST_CHUNK = "av/mic-first-chunk"
        /**
         * Budget from `uplink on` to the first chunk on the wire. Measured 2026-09-09: `GATE ON`
         * 22:55:39.482 → `capturing VOICE_COMMUNICATION` .694 (212 ms of AudioRecord bring-up on a
         * cold HAL) → `FIRST MIC CHUNK SENT` .744 — **262 ms**. Every failure on that path already
         * logs an `E` or `W` except one: `AudioRecord.read` blocking forever with no data, which is
         * exactly the silent shape a mic route that was never granted takes. 2 s is ~8× measured
         * and still inside a Siri turn, so the miss lands while the driver is waiting on Siri.
         */
        const val FIRST_CHUNK_MS = 2_000L
        /** [SessionTrace.Board] entry. */
        const val BOARD = "mic-uplink"
        /**
         * Waits before each re-open of a refused recorder. ~1.5 s in all: inside a Siri turn (the
         * phone keeps listening for several seconds), short enough that a mic that recovers costs
         * the driver one repeated word, not the whole request.
         */
        val OPEN_RETRY_MS = longArrayOf(150L, 400L, 1_000L)
        /** Re-opens after a mid-capture read failure, per gate, before giving up until the next one. */
        const val MAX_REOPENS = 2
    }

    /** Adapter-Wi-Fi gate. `on` opens capture at [rate] Hz / [channels]; off closes it. */
    fun onGate(on: Boolean, rate: Int, channels: Int) {
        if (!running.get()) return
        if (on) startCapture(rate, channels) else stopCapture("CT_UPLINK off")
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (directSink != null) {
            SessionTrace.Board.up(BOARD, "mic gate is CT_UPLINK — waiting")
            log.i("direct mic — waiting for CT_UPLINK")
            return
        }
        SessionTrace.Board.up(BOARD, "connecting to the gate seam :$PORT")
        gateThread = Thread({ gateLoop() }, "mic-gate").apply { isDaemon = true; start() }
    }

    /** [why] is the caller's reason, so the summary and the board both say whether this was expected. */
    fun stop(why: String) {
        running.set(false)
        stopCapture("uplink stopped — $why")
        runCatching { sock?.close() }   // deliberate: teardown of a socket we are discarding
        sock = null; out = null
        log.i("stopped ($why) — $framesSent chunks sent over $captures capture(s)")
        SessionTrace.Board.down(BOARD, "stopped — $why")
    }

    /**
     * Connect and follow the gate. Reconnects while running: the receiver may restart the listener.
     *
     * Severity of a failed connect is by COUNT, not by kind. The first miss is normal — the
     * receiver's `:9112` listener comes up at native-library load, which on the 2026-09-09 truck
     * session was 208 ms AFTER this connect was attempted (attempt at 22:51:14.467, `control-in
     * listening` at .675, connected on the retry at 22:51:16.471). Logging that at `W` on every app
     * start is the false alarm that gets a mechanism ignored. The SECOND consecutive miss means the
     * listener has been absent for over [RECONNECT_MS] + the 2 s connect timeout, which no ordering
     * of our own explains — that one is a `W`, and the board shows the uplink as FAILED until it lands.
     */
    private fun gateLoop() {
        while (running.get()) {
            try {
                val s = Socket().apply { connect(InetSocketAddress("127.0.0.1", PORT), 2000) }
                sock = s; out = s.getOutputStream()
                connectFailures = 0
                log.i("connected to the uplink control seam :$PORT — waiting for the gate")
                SessionTrace.Board.up(BOARD, "gate seam :$PORT connected — gate CLOSED")
                val r = BufferedReader(InputStreamReader(s.getInputStream()))
                while (running.get()) {
                    val line = r.readLine() ?: break
                    handleGate(line.trim())
                }
                if (running.get()) log.w("gate seam :$PORT closed by the receiver — reconnecting in ${RECONNECT_MS}ms")
            } catch (t: Throwable) {
                if (running.get()) {
                    connectFailures++
                    val what = "uplink seam :$PORT: ${t.javaClass.simpleName}: ${t.message}"
                    if (connectFailures == 1) {
                        log.i("$what — the receiver core may not be loaded yet; retrying in ${RECONNECT_MS}ms")
                        SessionTrace.Board.down(BOARD, "gate seam :$PORT not up yet — retrying every ${RECONNECT_MS}ms")
                    } else {
                        log.w("$what — attempt $connectFailures; no mic for Siri until this connects")
                        SessionTrace.Board.note(BOARD, "FAILED (gate seam :$PORT unreachable, $connectFailures attempts — no mic for Siri)")
                    }
                }
            } finally {
                stopCapture("gate seam connection ended")
                runCatching { sock?.close() }; sock = null; out = null   // deliberate: teardown
            }
            if (running.get()) try { Thread.sleep(RECONNECT_MS) } catch (_: InterruptedException) { return }
        }
    }

    private fun handleGate(line: String) {
        when {
            line.startsWith("uplink on") -> {
                // `uplink on <rate> <ch>`
                val p = line.split(" ")
                val rate = p.getOrNull(2)?.toIntOrNull() ?: 16000
                val ch = p.getOrNull(3)?.toIntOrNull() ?: 1
                log.i("GATE ON — ${rate}Hz ${ch}ch")
                startCapture(rate, ch)
            }
            line.startsWith("uplink off") -> {
                log.i("GATE OFF")
                stopCapture("gate closed by the receiver (`uplink off`)")
                SessionTrace.Board.up(BOARD, "gate seam :$PORT connected — gate CLOSED")
            }
            line.isNotEmpty() -> log.i("uplink seam says: $line")
        }
    }

    private fun startCapture(rate: Int, channels: Int) {
        // A re-SETUP can change the format, and the peer broadcasts a fresh `uplink on` for each.
        // Without this the CAS below swallows it and we keep capturing at the old rate while the
        // receiver frames and RTP-clocks at the new one.
        if (capturing.get() && (rate != activeRate || channels != activeChannels)) {
            log.i("format changed ${activeRate}Hz${activeChannels}ch -> ${rate}Hz${channels}ch — restarting")
            stopCapture("format changed ${activeRate}Hz${activeChannels}ch -> ${rate}Hz${channels}ch")
        }
        if (!capturing.compareAndSet(false, true)) return
        activeRate = rate; activeChannels = channels
        val mask = if (channels >= 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        // Each failure below already logs its own E; the board gets a note(), not a second E.
        if (minBuf <= 0) {
            log.e("getMinBufferSize failed ($rate/$channels) — no mic for this gate"); capturing.set(false)
            SessionTrace.Board.note(BOARD, "FAILED (getMinBufferSize($rate/$channels) refused — no mic this gate)"); return
        }
        val bytesPerChunk = rate / 1000 * CHUNK_MS * 2 * channels
        firstChunkThisCapture = false
        captures++
        val capture = captures
        // The recorder is opened ON the capture thread, not here. This runs on whichever thread
        // delivered the gate — in adapter mode the OCBM reader, which also carries video — and an
        // open can now wait out [OPEN_RETRY_MS] between attempts.
        captureThread = Thread({ captureLoop(rate, channels, mask, minBuf, bytesPerChunk, capture) }, "mic-capture").apply {
            isDaemon = true; start()
        }
    }

    /**
     * Open and START one recorder, retrying a refusal. Null when every attempt failed or the gate
     * closed meanwhile; each failure has its own line.
     *
     * Why retry, and why check [AudioRecord.getRecordingState]: on 2026-10-14 22:08:09 the car
     * refused the input (`E AudioRecord: start(89): status -61`, Siri just opened). Java's
     * `startRecording()` does NOT throw for that — it only logs and leaves the recorder STOPPED — so
     * the old code logged "capturing", then read 0 at once and gave up until the next `uplink on`.
     * Siri heard silence for that turn. A refusal like that can be momentary (the car's own audio
     * path is switching for the same voice event), so a fresh recorder a moment later is worth trying.
     */
    @SuppressLint("MissingPermission")   // RECORD_AUDIO is declared and asked for at first launch
    private fun openRecorder(source: Int, rate: Int, mask: Int, minBuf: Int, capture: Int): AudioRecord? {
        for (attempt in 0..OPEN_RETRY_MS.size) {
            if (attempt > 0 && !pauseWhileCapturing(OPEN_RETRY_MS[attempt - 1])) return null
            if (!capturing.get()) return null
            val tag = "capture #$capture attempt ${attempt + 1}/${OPEN_RETRY_MS.size + 1}"
            val rec = try {
                AudioRecord(source, rate, mask, AudioFormat.ENCODING_PCM_16BIT, minBuf * 3)
            } catch (t: Throwable) {
                log.e("AudioRecord: ${t.javaClass.simpleName}: ${t.message} ($tag) — ${micDiag()}"); continue
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                log.e("AudioRecord uninitialised ($tag) — ${micDiag()}"); runCatching { rec.release() }   // deliberate: discard
                continue
            }
            val threw = runCatching { rec.startRecording() }.exceptionOrNull()
            val recording = rec.recordingState == AudioRecord.RECORDSTATE_RECORDING
            if (threw != null || !recording) {
                log.e("startRecording ${threw?.let { "threw ${it.javaClass.simpleName}: ${it.message}" } ?: "left the recorder STOPPED (car refused the input)"}" +
                    " ($tag) — ${micDiag()}")
                runCatching { rec.release() }   // deliberate: discard
                continue
            }
            if (attempt > 0) log.w("mic opened on retry ($tag)")
            return rec
        }
        return null
    }

    /**
     * VOICE_RECOGNITION while this app is the car's assistant, VOICE_COMMUNICATION otherwise.
     *
     * Android silences the assistant's own mic unless it records with VOICE_RECOGNITION or HOTWORD.
     * `AudioPolicyService::updateUidStates_l` (android14-release, AudioPolicyService.cpp:1024-1058)
     * starts every capture at `allowCapture = !isAssistantOnTop && …`, and the assistant-UID branch
     * allows only those two sources again. Holding the role (Settings ▸ Voice, the wheel-button
     * path) makes our UID the assistant, and CarPlay on screen makes it "on top". After that, a
     * VOICE_COMMUNICATION recorder starts, reports RECORDING, and delivers full-rate chunks of
     * zeros with no error anywhere. On the 2026-10-27 drive there were four wheel-button Siri turns.
     * Every chunk was sent, and Siri heard nothing each time. The mic itself was owner-confirmed on
     * 2026-09-04, before the role existed.
     *
     * When we do not hold the role, keep the proven VOICE_COMMUNICATION and its call processing.
     * When we do hold it, CarPlay calls also go out through VOICE_RECOGNITION, because the other
     * source is silenced. Whether the car still cancels echo on calls is unverified.
     */
    private fun sourceFor(): Pair<Int, String> =
        if (runCatching { assistantHeld() }.getOrDefault(false))
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION"
        else MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION"

    private fun captureLoop(rate: Int, channels: Int, mask: Int, minBuf: Int, chunk: Int, capture: Int) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(chunk)
        var rec: AudioRecord? = null
        var reopens = 0
        val (source, sourceName) = sourceFor()
        val level = MicLevel()
        try {
            while (capturing.get() && running.get()) {
                val r = openRecorder(source, rate, mask, minBuf, capture)
                if (r == null) {
                    if (capturing.get()) {
                        log.e("no mic for this gate — the car refused the input ${OPEN_RETRY_MS.size + 1} times (capture #$capture)")
                        SessionTrace.Board.note(BOARD, "FAILED (car refused the mic ${OPEN_RETRY_MS.size + 1} times — no mic this gate)")
                    }
                    if (!firstChunkThisCapture) SessionTrace.cancel(EXPECT_FIRST_CHUNK, "the recorder never opened — already reported")
                    capturing.set(false)
                    return
                }
                rec = r
                if (!firstChunkThisCapture) {
                    // Armed once the recorder is RECORDING: what remains silent is a read() that
                    // never returns data. Met in [send] on this capture's first chunk.
                    SessionTrace.expect(EXPECT_FIRST_CHUNK, FIRST_CHUNK_MS,
                        "the receiver said `uplink on ${rate} ${channels}` and AudioRecord is recording, so PCM " +
                        "should reach the seam within one bring-up (262ms on 2026-09-09)")
                }
                if (reopens == 0) {
                    val why = if (source == MediaRecorder.AudioSource.VOICE_RECOGNITION) " (we hold the assistant role)" else ""
                    log.i("capturing $sourceName$why ${rate}Hz ${channels}ch, ${chunk}B/chunk (capture #$capture)")
                    SessionTrace.Board.up(BOARD, "gate OPEN — capturing $sourceName ${rate}Hz ${channels}ch, ${chunk}B/${CHUNK_MS}ms")
                }
                val n = pump(r, buf, chunk, level) ?: return   // null: the gate closed normally
                runCatching { r.stop() }; runCatching { r.release() }   // deliberate: replaced or given up
                rec = null
                if (!capturing.get()) return
                // Any non-positive read (0, ERROR, ERROR_BAD_VALUE, ERROR_INVALID_OPERATION,
                // ERROR_DEAD_OBJECT) is the mic dying under Siri. Reopen a bounded number of times;
                // past that, give up until the next `uplink on`. The flag MUST be cleared when giving
                // up: the peer only sends `uplink off` on full control teardown, not per-SETUP, so the
                // gate edge that would have reset it may never arrive — and iOS re-SETUPs MainAudio
                // several times per Siri turn. Leaving it set made every later `uplink on` fail its
                // CAS silently and killed the mic for the rest of the session.
                if (reopens >= MAX_REOPENS) {
                    log.e("AudioRecord.read -> $n mid-capture after $reopens reopen(s); ending this capture — no mic until the next `uplink on` — ${micDiag()}")
                    SessionTrace.Board.note(BOARD, "FAILED (AudioRecord.read -> $n mid-capture — no mic until the next gate)")
                    if (!firstChunkThisCapture) SessionTrace.cancel(EXPECT_FIRST_CHUNK, "AudioRecord.read failed ($n) first — already reported")
                    capturing.set(false)
                    return
                }
                reopens++
                log.w("AudioRecord.read -> $n mid-capture — reopening the mic ($reopens/$MAX_REOPENS, capture #$capture) — ${micDiag()}")
            }
        } finally {
            reportLevel(capture, sourceName, level, rec)   // before stop(): the silenced flag needs a live recorder
            rec?.let { runCatching { it.stop() }; runCatching { it.release() } }   // deliberate: teardown
        }
    }

    /** Read whole chunks until the gate closes (null) or a read fails (that read's return code). */
    private fun pump(rec: AudioRecord, buf: ByteArray, chunk: Int, level: MicLevel): Int? {
        while (capturing.get() && running.get()) {
            var off = 0
            while (off < chunk && capturing.get()) {
                val n = rec.read(buf, off, chunk - off)
                if (n <= 0) return if (capturing.get()) n else null
                off += n
            }
            if (off == chunk) {
                level.add(buf, chunk)
                send(buf, chunk)
            }
        }
        return null
    }

    /**
     * One line per capture: how loud it was, and Android's own silenced flag. A silenced recorder
     * raises no error at all (see [sourceFor]), so this line is the only evidence that Siri was fed zeros.
     */
    private fun reportLevel(capture: Int, source: String, level: MicLevel, rec: AudioRecord?) {
        if (level.chunks == 0) return
        val silenced = if (rec != null && Build.VERSION.SDK_INT >= 29)
            runCatching { rec.activeRecordingConfiguration?.isClientSilenced?.toString() }.getOrNull() ?: "?" else "?"
        val line = "capture #$capture heard: $source peak ${level.peak} (${level.dbfs()} dBFS), " +
            "${level.zeroChunks}/${level.chunks} chunks all-zero, silenced=$silenced"
        if (level.allZero) {
            log.e("$line — pure digital silence, so the phone heard nothing this turn — ${micDiag()}")
            SessionTrace.Board.note(BOARD, "SILENT (capture #$capture: every chunk zero, silenced=$silenced)")
        } else {
            log.i(line)
        }
    }

    /** Sleep [ms] in slices so [stopCapture]'s 500 ms join is not outlasted. False once the gate closed. */
    private fun pauseWhileCapturing(ms: Long): Boolean {
        var left = ms
        try {
            while (left > 0 && capturing.get()) {
                val step = minOf(left, 50L)
                Thread.sleep(step)
                left -= step
            }
        } catch (_: InterruptedException) {
            return false
        }
        return capturing.get()
    }

    /**
     * Who else holds the mic, at the moment ours failed. Other apps' entries are anonymised by the
     * platform, but the source and the silenced flag still say whether the car's own voice or call
     * path had it.
     */
    private fun micDiag(): String {
        val am = audio ?: return "no AudioManager"
        return runCatching {
            val cfgs = am.activeRecordingConfigurations
            val list = cfgs.joinToString(",") { c ->
                "src=${c.clientAudioSource}" + if (Build.VERSION.SDK_INT >= 29) "/silenced=${c.isClientSilenced}" else ""
            }
            "mode=${am.mode} micMute=${am.isMicrophoneMute} recording=${cfgs.size}[$list]"
        }.getOrElse { "mic diag failed: ${it.javaClass.simpleName}" }
    }

    private fun send(pcm: ByteArray, len: Int) {
        val sink = directSink
        if (sink != null) {
            val ok = sink(pcm, len)
            if (!ok) {
                log.w("mic write refused — no subscribed adapter")
                return
            }
            noteSent()
            return
        }
        val o = out ?: return
        try {
            synchronized(o) {
                o.write("mic $len\n".toByteArray(Charsets.US_ASCII))
                o.write(pcm, 0, len)
                o.flush()
            }
            noteSent()
        } catch (t: Throwable) {
            log.w("mic write failed: ${t.message}")
            capturing.set(false)
        }
    }

    private fun noteSent() {
        val n = framesSent.incrementAndGet()
        if (!firstChunkThisCapture) {
            firstChunkThisCapture = true
            log.i("FIRST MIC CHUNK SENT (capture #$captures, session chunk $n)")
            SessionTrace.met(EXPECT_FIRST_CHUNK)
        }
        if (n % 250 == 0L) log.i("$n mic chunks sent")
    }

    /** [why] is what the board and the cancelled expectation (if any) record. */
    private fun stopCapture(why: String) {
        if (!capturing.compareAndSet(true, false)) return
        if (!firstChunkThisCapture) SessionTrace.cancel(EXPECT_FIRST_CHUNK, why)
        captureThread?.let { t ->
            runCatching { t.join(500) }   // deliberate: an interrupt here is the caller's, not a fault
            // A join that TIMES OUT is a fault, and it was invisible: the thread is still inside
            // AudioRecord.read while `captureThread` is nulled below, so the next `uplink on` builds a
            // second recorder beside the stuck one.
            if (t.isAlive) log.w("capture thread still alive 500ms after stop ($why) — AudioRecord.read is stuck; the next gate starts a second recorder")
        }
        captureThread = null   // the capture thread owns AudioRecord stop()/release() in its finally
        log.i("capture stopped — $why")
    }
}

/**
 * Loudness of one capture, from S16LE chunks. A live car mic never delivers an exact zero for a
 * whole chunk, because there is always a noise floor. Zero chunks are what Android hands a silenced recorder.
 */
internal class MicLevel {
    var chunks = 0; private set
    var zeroChunks = 0; private set
    /** Largest |sample| seen, 0..32768. */
    var peak = 0; private set
    val allZero get() = chunks > 0 && zeroChunks == chunks

    fun add(pcm: ByteArray, len: Int) {
        var p = 0
        var i = 0
        while (i + 1 < len) {
            val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val a = if (s < 0) -s else s
            if (a > p) p = a
            i += 2
        }
        chunks++
        if (p == 0) zeroChunks++
        if (p > peak) peak = p
    }

    fun dbfs(): String = if (peak == 0) "-inf" else "%.0f".format(20 * kotlin.math.log10(peak / 32768.0))
}
