package wasidremin.gmccpa.nav

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Parcelable
import wasidremin.gmccpa.ProbeLog

/**
 * GM's driver display reporting a page change. The idea is openautolink's (0.1.575, "GM page-return
 * handling"): a service in GM's own cluster category (`com.gm.intent.category.COREAPP`, meta-data
 * `SupportedDisplay=IPC`) is started by GM with `gm.cluster.action.APP_SELECTED` /
 * `FULL_MAP_SELECTED` when the driver picks a page, carrying `app_selected_state` (1 selected, 0 left),
 * `full_map_type` (GM's `NavigationAppType`, `NATIVE` or `PROJECTION`) and `display_info`. GM finds
 * the service at boot, so it needs one head-unit restart after install. Written from their
 * description; none of their GPL-3.0 code is used.
 *
 * Every event is logged (what GM sends on the Equinox is not known yet), and a full-map page selected
 * again brings CarPlay's map back ([DriverDisplay.onGmPage]). The GM parcelables need GM's
 * `info3_cluster` library (optional in the manifest); without it they are logged as unreadable and
 * the plain int still decides.
 */
class GmClusterPageService : Service() {
    private val log = ProbeLog.sub("cluster")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (intent != null) onPage(intent)
        } catch (t: Throwable) {
            log.w("GM page event unreadable: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun onPage(intent: Intent) {
        intent.setExtrasClassLoader(javaClass.classLoader)
        val state = runCatching { intent.getIntExtra("app_selected_state", -1) }.getOrDefault(-1)
        @Suppress("DEPRECATION")
        val type = runCatching { (intent.getParcelableExtra<Parcelable>("full_map_type") as? Enum<*>)?.name }.getOrNull()
        @Suppress("DEPRECATION")
        val display = runCatching { intent.getParcelableExtra<Parcelable>("display_info") }.getOrNull()
        val shown = display?.let { d ->
            listOf("mDisplayId", "mDisplayType", "mDisplayWidth", "mDisplayHeight").joinToString(" ") { f ->
                "$f=" + runCatching { d.javaClass.getField(f).get(d) }.getOrDefault("?")
            }
        } ?: "none"
        val keys = runCatching { intent.extras?.keySet()?.sorted()?.joinToString(",") }.getOrNull() ?: "unreadable"
        log.i("GM page event ${intent.action} state=$state type=${type ?: "?"} display[$shown] extras=[$keys]")
        DriverDisplay.onGmPage(applicationContext, intent.action, state.takeIf { it >= 0 }, type)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
