package wasidremin.gmccpa.video

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer

/**
 * Route A's screen (T34): the phone's video over CarPlay while [VideoPlayback.showing] (parked only), with a Close
 * button. CarPlay goes on underneath; this view takes every touch, so none reaches CarPlay while the video is up.
 * The picture keeps the video's own aspect ratio, black around it. Written here after enigy/carlink's Compose
 * `VideoOverlay` (a04f4c0); this app's screens are Views.
 *
 * The video's SurfaceView is a media overlay, so it sits above the CarPlay picture's SurfaceView: two plain
 * SurfaceViews in one window have no defined order.
 */
class VideoOverlayView(ctx: Context, onClose: () -> Unit) : FrameLayout(ctx) {
    private val surface = SurfaceView(ctx).apply { setZOrderMediaOverlay(true) }
    private var bound: ExoPlayer? = null
    private var aspect = 0f

    init {
        setBackgroundColor(Color.BLACK)
        isClickable = true // every touch stops here
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val dp = resources.displayMetrics.density
        val close = TextView(ctx).apply {
            text = "✕"
            textSize = 34f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Close video"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC333333.toInt())
            }
            setOnClickListener { onClose() }
        }
        addView(close, LayoutParams((88 * dp).toInt(), (88 * dp).toInt(), Gravity.TOP or Gravity.END).apply {
            val m = (24 * dp).toInt()
            setMargins(m, m, m, m)
        })
    }

    /** Main thread: draw [player] into the surface, sized to [videoAspect] (width over height; null when not known). */
    fun bind(player: ExoPlayer?, videoAspect: Float?) {
        if (bound !== player) {
            bound?.clearVideoSurfaceView(surface)
            player?.setVideoSurfaceView(surface)
            bound = player
        }
        val a = videoAspect ?: 0f
        if (a != aspect) {
            aspect = a
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val (sw, sh) = fit(w, h, aspect)
        (surface.layoutParams as LayoutParams).apply {
            width = sw
            height = sh
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    companion object {
        /**
         * The largest [aspect] (width over height) rectangle inside [boxW] x [boxH], or the whole box when the aspect
         * isn't known yet. Pure.
         */
        fun fit(boxW: Int, boxH: Int, aspect: Float): Pair<Int, Int> {
            if (aspect <= 0f || boxW <= 0 || boxH <= 0) return boxW to boxH
            return if (boxW.toFloat() / boxH > aspect) {
                (boxH * aspect).toInt() to boxH
            } else {
                boxW to (boxW / aspect).toInt()
            }
        }
    }
}
