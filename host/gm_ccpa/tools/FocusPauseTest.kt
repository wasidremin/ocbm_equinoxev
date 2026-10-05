package wasidremin.gmccpa.test

import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import wasidremin.gmccpa.av.CarPlayMediaBrowserService
import wasidremin.gmccpa.av.NowPlayingState

/** Separate installed packages give the framework genuine caller identities, not guessed labels. */
class FocusHolder : Activity() {
    private var request: AudioFocusRequest? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val token = intent.getParcelableExtra<MediaSession.Token>("token")
        val controller = token?.let { MediaController(this, it) }
        when (intent.getStringExtra("command")) {
            "pause" -> { controller!!.transportControls.pause(); finish() }
            "probe" -> { controller!!.transportControls.pause(); controller.transportControls.stop(); finish() }
            "play" -> { controller!!.transportControls.play(); finish() }
            "permanent", "transient" -> {
                val am = getSystemService(AudioManager::class.java)
                request = AudioFocusRequest.Builder(if (intent.getStringExtra("command") == "permanent")
                    AudioManager.AUDIOFOCUS_GAIN else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setOnAudioFocusChangeListener { }.build()
                check(am.requestAudioFocus(request!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                Handler(Looper.getMainLooper()).postDelayed({ am.abandonAudioFocusRequest(request!!); finish() }, 2600L)
            }
        }
        sendBroadcast(Intent("wasidremin.gmccpa.test.READY").putExtra("command", intent.getStringExtra("command")))
    }
}

class FocusPauseTest : Instrumentation() {
    private val sent = java.util.concurrent.CopyOnWriteArrayList<Int>()
    private var checks = 0
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    private fun assertTest(name: String, ok: Boolean) {
        check(ok) { name }
        checks++
        android.util.Log.i("FOCUS_TEST", "PASS $name")
    }
    private fun field(name: String) = CarPlayMediaBrowserService::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun invoke(service: Any, name: String, vararg args: Any) {
        val method = service.javaClass.declaredMethods.first { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        method.invoke(service, *args)
    }
    private fun helper(command: String, token: MediaSession.Token, pkg: String = "com.gm.car.media.gmcarmediaservice") {
        val ready = java.util.concurrent.CountDownLatch(1)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                if (intent.getStringExtra("command") == command) ready.countDown()
            }
        }
        targetContext.registerReceiver(receiver, android.content.IntentFilter("wasidremin.gmccpa.test.READY"), 2 /* RECEIVER_EXPORTED, API 33 constant; flags overload exists since API 26 */)
        try {
            targetContext.startActivity(Intent().setClassName(pkg, FocusHolder::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("command", command).putExtra("token", token))
            check(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "helper $command did not run" }
        } finally { targetContext.unregisterReceiver(receiver) }
    }
    override fun onStart() {
        var player: Any? = null
        try {
            runOnMainSync { CarPlayMediaBrowserService.ensureStarted(targetContext) }
            var service: CarPlayMediaBrowserService? = null
            repeat(50) {
                if (service == null) {
                    service = (field("live").get(null) as? java.lang.ref.WeakReference<*>)?.get() as? CarPlayMediaBrowserService
                    SystemClock.sleep(100)
                }
            }
            val svc = checkNotNull(service)
            field("hidSender").set(svc, { index: Int -> sent.add(index); true })
            CarPlayMediaBrowserService.onSessionUp()
            val sessionField = svc.javaClass.getDeclaredField("session").apply { isAccessible = true }
            val session = sessionField.get(svc) as MediaSession
            val token = session.sessionToken
            val controller = MediaController(targetContext, token)
            fun snapshot(status: Int?, pos: Long = 1000L) = NowPlayingState.Snapshot(title = "Focus test", playbackStatus = status, elapsedMs = pos)
            CarPlayMediaBrowserService.publish(snapshot(NowPlayingState.PLAY))
            helper("pause", token)
            SystemClock.sleep(700)
            assertTest("GM pause forwarded inside first 5 seconds", sent.toList() == listOf(2))
            sent.clear()
            invoke(svc, "transport", "pause", "caller=package=com.android.car uid=1000")
            invoke(svc, "transport", "stop", "caller=package=com.android.car uid=1000")
            SystemClock.sleep(700)
            assertTest("com.android.car pause+stop dropped", sent.isEmpty())
            helper("probe", token)
            SystemClock.sleep(700)
            assertTest("GM pause+stop pair dropped", sent.isEmpty())

            val cls = Class.forName("wasidremin.gmccpa.av.AacPlayer")
            player = cls.constructors.first { it.parameterCount == 1 }.newInstance(targetContext.getSystemService(AudioManager::class.java))
            runOnMainSync { invoke(player!!, "start"); invoke(player!!, "onStreamStart", 102L) }
            SystemClock.sleep(400)
            helper("permanent", token)
            SystemClock.sleep(900)
            assertTest("permanent loss sends one PAUSE", sent.toList() == listOf(2))
            assertTest("permanent loss card immediately PAUSED", controller.playbackState!!.state == PlaybackState.STATE_PAUSED)
            SystemClock.sleep(3000)
            assertTest("permanent loss never auto-resumes on release", sent.toList() == listOf(2))
            invoke(player!!, "onStreamStart", 103L)
            SystemClock.sleep(200)
            assertTest("stream restart cannot reclaim permanent loss", sent.toList() == listOf(2))
            CarPlayMediaBrowserService.publish(snapshot(NowPlayingState.PAUSE))
            helper("play", token)
            SystemClock.sleep(700)
            assertTest("user play reclaims focus then PLAY", sent.toList() == listOf(2, 1))
            CarPlayMediaBrowserService.publish(snapshot(NowPlayingState.PLAY))
            sent.clear()
            helper("transient", token)
            SystemClock.sleep(900)
            assertTest("transient loss PAUSE", sent.toList() == listOf(2))
            CarPlayMediaBrowserService.publish(snapshot(NowPlayingState.PAUSE))
            SystemClock.sleep(3100)
            assertTest("transient gain PLAY", sent.toList() == listOf(2, 1))
            CarPlayMediaBrowserService.publish(snapshot(NowPlayingState.PLAY, 2000))
            val playing = controller.playbackState!!
            SystemClock.sleep(400)
            assertTest("card playing position extrapolates", playing.playbackSpeed == 1f &&
                playing.position + ((SystemClock.elapsedRealtime() - playing.lastPositionUpdateTime) * playing.playbackSpeed) > 2200)
            CarPlayMediaBrowserService.publishPlaybackState(snapshot(NowPlayingState.PAUSE, 2400))
            val paused = controller.playbackState!!
            SystemClock.sleep(400)
            assertTest("card paused position frozen", paused.playbackSpeed == 0f && paused.position == 2400L)
            CarPlayMediaBrowserService.publish(snapshot(null, 2400))
            assertTest("null status retains PAUSED after content", controller.playbackState!!.state == PlaybackState.STATE_PAUSED)
            CarPlayMediaBrowserService.publish(snapshot(NowPlayingState.PLAY))
            sent.clear()
            helper("transient", token)
            SystemClock.sleep(800)
            controller.transportControls.pause()
            SystemClock.sleep(3300)
            assertTest("user pause prevents transient auto-resume", sent.none { it == 1 })
            sent.clear()
            // The production voice owner latch is asserted BEFORE a sink's focus binder call.
            val voiceClass = Class.forName("wasidremin.gmccpa.av.VoiceRouter")
            val owners = voiceClass.getDeclaredField("voiceFocusOwners").apply { isAccessible = true }.get(null) as MutableSet<Any>
            val owner = Any()
            synchronized(owners) { owners.add(owner) }
            CarPlayMediaBrowserService.publish(snapshot(NowPlayingState.PLAY))
            helper("transient", token)
            SystemClock.sleep(3300)
            assertTest("voice sink focus never pauses phone", sent.isEmpty())
            synchronized(owners) { owners.remove(owner) }
            // Queued pause: channel absent at arrival, available before the 3-second deadline.
            field("hidSender").set(svc, { _: Int -> false })
            CarPlayMediaBrowserService.publish(snapshot(NowPlayingState.PLAY))
            controller.transportControls.pause()
            SystemClock.sleep(500)
            field("hidSender").set(svc, { index: Int -> sent.add(index); true })
            SystemClock.sleep(500)
            assertTest("pause queued until HID ready", sent.toList() == listOf(2))
            sent.clear()
            for ((key, index) in listOf("play" to 1, "pause" to 2, "play-pause" to 3, "next" to 4, "previous" to 5, "headsethook" to 3)) {
                if (key == "play") CarPlayMediaBrowserService.publish(snapshot(NowPlayingState.PAUSE))
                else CarPlayMediaBrowserService.publish(snapshot(NowPlayingState.PLAY))
                val pfd = uiAutomation.executeShellCommand("cmd media_session dispatch $key")
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
                // AAOS shell dispatch is user 0; projection runs as user 10. Target its token
                // explicitly as a real controller instead of claiming shell routed to this user.
                val code = when (key) {
                    "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
                    "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
                    "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
                    "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    "headsethook" -> android.view.KeyEvent.KEYCODE_HEADSETHOOK
                    else -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                }
                controller.dispatchMediaButtonEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
                controller.dispatchMediaButtonEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
                SystemClock.sleep(400)
                assertTest("targeted media key $key forwarded", sent.contains(index))
                sent.clear()
            }
            CarPlayMediaBrowserService.publish(snapshot(NowPlayingState.STOP))
            CarPlayMediaBrowserService.onMediaFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, false)
            SystemClock.sleep(300)
            CarPlayMediaBrowserService.onMediaFocus(AudioManager.AUDIOFOCUS_GAIN, false)
            SystemClock.sleep(2300)
            assertTest("already stopped phone never paused or resumed by focus", sent.isEmpty())
            CarPlayMediaBrowserService.publish(snapshot(NowPlayingState.PLAY))
            CarPlayMediaBrowserService.onMediaFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, false)
            SystemClock.sleep(300)
            assertTest("duck never sends PAUSE", sent.isEmpty())
            val result = Bundle().apply { putString("stream", "FOCUS_TEST: $checks passed, 0 failed\n") }
            finish(Activity.RESULT_OK, result)
        } catch (e: Throwable) {
            android.util.Log.e("FOCUS_TEST", "FAIL", e)
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: $e\n") })
        } finally {
            player?.let { runOnMainSync { invoke(it, "stop", "focus test complete") } }
            CarPlayMediaBrowserService.onSessionDown()
        }
    }
}
