package wasidremin.gmccpa.nav

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import wasidremin.gmccpa.ProbeLog

/**
 * The CarAppService the Templates Host binds once [ClusterNav.bootstrap] has launched the hidden
 * CarAppActivity. Headless: it owns no UI, no USB and no A/V — CarPlay stays in CarPlayActivity.
 * Ported from lvalen91/carlink `cluster/CarlinkClusterService.kt` (public domain).
 *
 * Ships `android:enabled="false"`; [ClusterNav] enables it only while the feature is on.
 */
class ClusterNavService : CarAppService() {
    /**
     * NOT carlink's ALLOW_ALL_HOSTS_VALIDATOR. The service is exported and its session hands out the
     * driver's route, so any app that could bind it could read it. The library accepts a host that
     * either matches its published Templates Host / Android Auto signing certificates, or holds
     * `android.car.permission.TEMPLATE_RENDERER` — signature|privileged, which GM's unmodified
     * Google Templates Host has (gminfo_resources `projection/cluster_navigation.md` §Templates Host
     * Permissions). A rejected host is logged by the library under `CarApp.Val`.
     */
    override fun createHostValidator(): HostValidator =
        HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()

    override fun onCreateSession(sessionInfo: SessionInfo): Session {
        ProbeLog.sub("nav").i("nav: host created a session displayType=${sessionInfo.displayType}")
        return ClusterNavSession()
    }

    // Pre-SessionInfo hosts call this overload.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onCreateSession(): Session {
        ProbeLog.sub("nav").i("nav: host created a session (no SessionInfo)")
        return ClusterNavSession()
    }
}
