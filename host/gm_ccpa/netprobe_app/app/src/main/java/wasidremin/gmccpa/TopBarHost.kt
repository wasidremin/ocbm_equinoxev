package wasidremin.gmccpa

import android.app.Activity
import android.view.View
import android.view.WindowInsets

/**
 * Builds and positions the custom top bar for ANY activity that hosts it (the CarPlay screen and
 * the settings screen), so the two cannot disagree about slope, cutout or lifetime.
 */
internal object TopBarHost {

    /** Where visible glass ends: the live right-cutout inset and the top-edge wedge slope. */
    data class Glass(val rightInsetPx: Int, val slopeM: Float)

    /**
     * Reads the panel shape from the window's display cutout. The Equinox reports a top wedge
     * `Rect(0,0-2629,167)` and a right block; anything else (a notch, none) is a flat panel.
     */
    fun glass(insets: WindowInsets?): Glass {
        val cutout = insets?.displayCutout
        val wedge = cutout?.boundingRects?.filter { it.top == 0 && it.left == 0 }?.maxByOrNull { it.width() }
        val slope = DisplayGeometry.wedgeSlopeM(wedge?.right ?: 0, wedge?.bottom ?: 0)
        return Glass(maxOf(cutout?.safeInsetRight ?: 0, VideoFrame.rightPx), slope)
    }

    /** True when GM's own status bar is transiently visible: the freed strip is gone, hide ours. */
    fun statusBarVisible(insets: WindowInsets?): Boolean =
        android.os.Build.VERSION.SDK_INT >= 30 && insets?.isVisible(WindowInsets.Type.statusBars()) == true

    /**
     * Creates the bar with its vehicle-data feed. The feed is stopped when the view detaches, so a
     * recreated Activity never leaks the Car connection.
     */
    fun create(activity: Activity, onTarget: (BarTarget) -> Unit): GmTopBarView {
        val bar = GmTopBarView(activity)
        bar.onButton = onTarget
        bar.elevation = 10f * activity.resources.displayMetrics.density
        val data = TopBarVehicleData(bar).also { it.start() }
        bar.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) { data.stop() }
        })
        return bar
    }
}
