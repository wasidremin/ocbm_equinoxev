package wasidremin.gmccpa.av

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import wasidremin.gmccpa.DisplayPrefs
import wasidremin.gmccpa.EqTheme
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.SessionHolder
import wasidremin.gmccpa.VideoFrame
import wasidremin.gmccpa.ocbm.AdapterSession
import wasidremin.gmccpa.ocbm.Ocbm

/** Main-display output telemetry, published by the decoder (not a configured-FPS claim). */
object VideoPresentation {
    @Volatile var width = 0
    @Volatile var height = 0
    val frames = java.util.concurrent.atomic.AtomicLong()
    @Volatile var onSizeChanged: (() -> Unit)? = null
    fun size(w: Int, h: Int) {
        if (width == w && height == h) return
        width = w; height = h
        onSizeChanged?.invoke()
    }
}

class ProjectionStatsPill(ctx: Context, private val preview: Boolean) : TextView(ctx) {
    private val log = ProbeLog.sub("display")
    private var fraction = DisplayPrefs.statsPosition(ctx)
    private var grabX = 0f
    private var grabY = 0f
    private var previousFrames = VideoPresentation.frames.get()
    private var previousTime = SystemClock.elapsedRealtime()
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val frames = VideoPresentation.frames.get()
            val fps = (frames - previousFrames) * 1000.0 / (now - previousTime).coerceAtLeast(1)
            previousTime = now; previousFrames = frames
            val client = SessionHolder.ocbmProbe?.client
            val health = client?.lastBoxHealth ?: -1
            val wifi = if (health < 0) "unknown" else if (health and Ocbm.BH_WLAN_AP != 0) "AP up" else "AP down"
            val state = if (preview) "Preview" else if (AdapterSession.sessionUp || SessionHolder.cpRx?.sessionLive == true) "Connected" else "Waiting"
            val bt = client?.let { Ocbm.btpName(it.lastBtPhase) } ?: "unknown"
            text = "$state · ${VideoPresentation.width.takeIf { it > 0 } ?: VideoFrame.width}×${VideoPresentation.height.takeIf { it > 0 } ?: VideoFrame.height} · %.1f fps\nBT: %s · Wi-Fi: %s".format(fps, bt, wifi)
            postDelayed(this, 1000)
        }
    }

    init {
        setTextColor(EqTheme.TEXT)
        textSize = 14f
        setPadding(16, 10, 16, 10)
        background = EqTheme.rounded(0xE60A0F18.toInt(), 24f, EqTheme.ACCENT)
        elevation = 24f
        contentDescription = "Projection stats; drag to move"
        isClickable = true
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
    }

    private fun place() {
        val p = parent as? View ?: return
        x = fraction.first * (p.width - width).coerceAtLeast(0)
        y = fraction.second * (p.height - height).coerceAtLeast(0)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post { place(); log.i("display: stats restored=${fraction.first},${fraction.second}") }
        post(tick)
    }

    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val p = parent as? View ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                grabX = event.rawX - x; grabY = event.rawY - y
                parent.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                x = (event.rawX - grabX).coerceIn(0f, (p.width - width).coerceAtLeast(0).toFloat())
                y = (event.rawY - grabY).coerceIn(0f, (p.height - height).coerceAtLeast(0).toFloat())
                fraction = x / (p.width - width).coerceAtLeast(1) to y / (p.height - height).coerceAtLeast(1)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                DisplayPrefs.setStatsPosition(context, fraction.first, fraction.second)
                log.i("display: stats saved=${fraction.first},${fraction.second} position=$x,$y")
                parent.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }
}
