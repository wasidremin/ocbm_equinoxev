package wasidremin.gmccpa.nav

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.ocbm.AdapterSession
import java.lang.ref.WeakReference

/**
 * The window on the driver display that shows CarPlay's cluster map ([DriverDisplay]). It takes no
 * touch and no focus: the driver display has no touchscreen, and focus there must not pull it away
 * from the CarPlay screen. Its Surface is the decoder's: created, it attaches the cluster stream;
 * destroyed (GM hides the window when another page is picked), it lets the stream go.
 */
class DriverDisplayActivity : Activity() {
    private val log = ProbeLog.sub("cluster")
    private var outline: Outline? = null

    companion object {
        @Volatile private var open: WeakReference<DriverDisplayActivity>? = null

        fun isOpen(): Boolean = open?.get()?.let { !it.isFinishing && !it.isDestroyed } == true

        /** Settings changed the directions area: outline it now, for [DriverDisplay.PREVIEW_MS]. */
        fun refreshPreview() {
            val a = open?.get() ?: return
            a.runOnUiThread { a.outline?.invalidate() }
        }

        fun closeOpen(why: String) {
            val a = open?.get() ?: return
            a.runOnUiThread { if (!a.isFinishing) { a.log.i("closing the map window — $why"); a.finish() } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        open = WeakReference(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val v = SurfaceView(this)
        v.setBackgroundColor(Color.TRANSPARENT)
        val root = FrameLayout(this)
        root.addView(v, FrameLayout.LayoutParams(-1, -1))
        // Above the SurfaceView's hole: a SurfaceView draws below its window by default.
        outline = Outline(this).also { root.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(root)
        DriverDisplay.size?.let { v.holder.setFixedSize(it.width, it.height) }
        v.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) = AdapterSession.attachCluster(h.surface)
            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, ht: Int) {
                log.i("map window ${v.width}x${v.height} on display ${display?.displayId}, buffer ${w}x$ht")
            }
            override fun surfaceDestroyed(h: SurfaceHolder) = AdapterSession.detachCluster("map window hidden")
        })
        log.i("map window open on display ${display?.displayId}")
    }

    /**
     * Hidden means GM took the display back (its own map, another page). Close rather than linger:
     * on 2026-11-08 a window left stopped behind GM's map was followed by the owner being unable to
     * get the driver display's map page back at all until the head unit restarted. A long-press on
     * the strip right of CarPlay opens a fresh one ([DriverDisplay.reopen]).
     */
    override fun onStop() {
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) {
            log.i("map window hidden on display ${display?.displayId} — closing it, the display is GM's again")
            finish()
            DriverDisplay.onHidden()
        }
    }

    /**
     * The directions area being set in Settings, drawn over the map for [DriverDisplay.PREVIEW_MS]
     * after each change. What it outlines is what the next connect declares; until then CarPlay
     * still lays out for the previous one.
     */
    private class Outline(ctx: Context) : View(ctx) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.rgb(0x4C, 0xC2, 0xFF)
        }
        private val shade = Paint().apply { color = Color.argb(110, 0, 0, 0) }

        override fun onDraw(canvas: Canvas) {
            val left = DriverDisplay.previewUntil - SystemClock.elapsedRealtime()
            if (left <= 0 || width == 0 || height == 0) return
            val area = DriverDisplay.safeArea(wasidremin.gmccpa.ocbm.PixelSize(width and 1.inv(), height and 1.inv()), DriverDisplay.margins(context))
                ?: wasidremin.gmccpa.ocbm.PixelRect(0, 0, width, height)
            val r = android.graphics.RectF(area.x.toFloat(), area.y.toFloat(), area.right.toFloat(), (area.y + area.height).toFloat())
            canvas.drawRect(0f, 0f, width.toFloat(), r.top, shade)
            canvas.drawRect(0f, r.bottom, width.toFloat(), height.toFloat(), shade)
            canvas.drawRect(0f, r.top, r.left, r.bottom, shade)
            canvas.drawRect(r.right, r.top, width.toFloat(), r.bottom, shade)
            r.inset(3f, 3f)
            canvas.drawRect(r, line)
            postInvalidateDelayed(left + 50)
        }
    }

    override fun onDestroy() {
        outline = null
        if (open?.get() === this) open = null
        AdapterSession.detachCluster("map window closed")
        log.i("map window closed")
        super.onDestroy()
    }
}
