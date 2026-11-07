package wasidremin.gmccpa.calltest

import android.app.Activity
import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import wasidremin.gmccpa.EqButtonStyle
import wasidremin.gmccpa.EqCard
import wasidremin.gmccpa.EqRow
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.eqButton
import java.util.concurrent.Executors

/**
 * Settings ▸ Calls: the parked echo and voice tests ([EchoTest], [VoiceTest]). Each runs on its own
 * thread, refuses while a CarPlay call is up or the car is out of Park, and writes its lines to the
 * log under `calltest` as well as to the card, so a run also reaches the log server.
 */
class CallTestsCard(private val act: Activity) {
    private val log = ProbeLog.sub("calltest")
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "cp-calltest").apply { isDaemon = true } }
    private val assistantHeld = { wasidremin.gmccpa.voice.AssistantRole.held(act.applicationContext) }

    private lateinit var echoRow: EqRow
    private lateinit var voiceRow: EqRow
    private lateinit var resultRow: EqRow
    private val takesCard = EqCard(act)
    private var takes: List<VoiceTest.Take> = emptyList()
    @Volatile private var running = false

    private val voiceTest by lazy { VoiceTest(act.applicationContext, ::blockedReason, { log.i(it) }, assistantHeld) }

    /** The card, plus the take rows once a voice test has run. */
    fun build(): View {
        echoRow = EqRow(
            act, "Echo test",
            "Parked. Plays tones on the call channel and measures how much each microphone hears back, and how late (about 25 s).",
            trailing = eqButton(act, "Run", EqButtonStyle.SECONDARY) { runEcho() },
        )
        voiceRow = EqRow(
            act, "Voice test",
            "Parked. Count aloud when asked; then each microphone's take plays back, so you hear what a caller would (about 45 s).",
            trailing = eqButton(act, "Run", EqButtonStyle.SECONDARY) { runVoice() },
        )
        resultRow = EqRow(act, "Last result", "No test run yet.")
        takesCard.visibility = View.GONE
        return LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(EqCard(act).addRow(echoRow).addRow(voiceRow).addRow(resultRow))
            addView(takesCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = wasidremin.gmccpa.EqTheme.px(16f) })
        }
    }

    private fun runEcho() {
        if (running) return
        running = true
        show("Starting the echo test…")
        exec.execute {
            val lines = runCatching { EchoTest(act.applicationContext, ::blockedReason, { log.i(it) }, assistantHeld).run { show(it) } }
                .getOrElse { listOf("Echo test failed: ${it.javaClass.simpleName}: ${it.message}") }
            running = false
            show(lines.joinToString("\n"))
        }
    }

    private fun runVoice() {
        if (running) return
        running = true
        show("Starting the voice test…")
        exec.execute {
            val out = runCatching { voiceTest.run { show(it) } }
                .getOrElse { VoiceTest.Outcome(emptyList(), listOf("Voice test failed: ${it.javaClass.simpleName}: ${it.message}")) }
            running = false
            takes = out.takes
            show(out.notes.joinToString("\n"))
            act.runOnUiThread { fillTakes() }
        }
    }

    private fun fillTakes() {
        takesCard.removeAllViews()
        for (t in takes.filter { it.pcm.isNotEmpty() }) {
            val level = t.voiceDbfs?.let { "voice %.0f dBFS".format(it) } ?: "no voice level"
            takesCard.addRow(EqRow(act, t.label, level, trailing = eqButton(act, "Play", EqButtonStyle.SECONDARY) {
                if (running) return@eqButton
                exec.execute { voiceTest.play(t)?.let { show(it) } }
            }))
        }
        takesCard.visibility = if (takesCard.childCount > 0) View.VISIBLE else View.GONE
    }

    private fun show(text: String) = act.runOnUiThread {
        if (!act.isFinishing && !act.isDestroyed) resultRow.setSubtitle(text)
    }

    /** Why a test must not run now, or null. Called off the main thread. */
    private fun blockedReason(): String? = when {
        wasidremin.gmccpa.av.CarPlayActivity.nowPlaying.phoneAction() != null -> "a CarPlay call is on"
        !parked(act.applicationContext) -> "the car is out of Park"
        else -> null
    }

    private companion object {
        /** GEAR_SELECTION; an unreadable gear does not block (the emulator, a refused permission). */
        fun parked(ctx: Context): Boolean = runCatching {
            val car = Car.createCar(ctx) ?: return@runCatching true
            try {
                val pm = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return@runCatching true
                pm.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0) == VehicleGear.GEAR_PARK
            } finally {
                car.disconnect()
            }
        }.getOrDefault(true)
    }
}
