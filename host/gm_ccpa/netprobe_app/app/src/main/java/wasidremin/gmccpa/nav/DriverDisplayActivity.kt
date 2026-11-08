package wasidremin.gmccpa.nav

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
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

    companion object {
        @Volatile private var open: WeakReference<DriverDisplayActivity>? = null

        fun isOpen(): Boolean = open?.get()?.let { !it.isFinishing && !it.isDestroyed } == true

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
        setContentView(v)
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
        }
    }

    override fun onDestroy() {
        if (open?.get() === this) open = null
        AdapterSession.detachCluster("map window closed")
        log.i("map window closed")
        super.onDestroy()
    }
}
