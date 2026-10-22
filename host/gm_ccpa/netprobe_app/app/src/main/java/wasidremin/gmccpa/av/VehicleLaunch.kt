package wasidremin.gmccpa.av

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import wasidremin.gmccpa.ProbeLog

/**
 * Launches the car's own (AAOS / GM) destinations for the top-bar tiles. Context-only, so the
 * CarPlay picture and the settings screen share one implementation and cannot drift.
 *
 * Destinations are RESOLVED from what the head unit actually exports, never hard-coded: the
 * 2026-10-17/18 vehicle candidate dump showed `com.android.car.settings/…Settings_Launcher_Homepage`
 * ("Settings"), `com.gm.hmi.energy/…MainLauncherActivity` ("Charging") and
 * `com.gm.homescreen` ("Home Screen"). Ranking is [selectVehicleDestination]. A tile whose
 * destination is missing falls back to the platform Home intent rather than doing nothing.
 */
object VehicleLaunch {
    private val log = ProbeLog.sub("cpui")
    private var candidatesLogged = false

    /** The car's home screen: the platform HOME category resolves to GM's launcher. */
    fun home(ctx: Context): Boolean =
        start(ctx, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), "car home")

    /** Car settings (AAOS Settings, not this app's). */
    fun settings(ctx: Context): Boolean = destination(ctx, VehicleKind.CAR, "car settings")

    /** GM's Charging app (`com.gm.hmi.energy`). */
    fun charging(ctx: Context): Boolean = destination(ctx, VehicleKind.ENERGY, "charging")

    /** Same ranking as [destination] without launching — for tests and the launch log. */
    fun resolve(ctx: Context, kind: VehicleKind): VehicleCandidate? =
        selectVehicleDestination(kind, candidates(ctx))

    private fun destination(ctx: Context, kind: VehicleKind, label: String): Boolean {
        val match = resolve(ctx, kind)
        if (match != null) {
            val target = Intent(Intent.ACTION_MAIN).setClassName(match.packageName, match.className)
            if (start(ctx, target, label)) return true
        }
        log.w("topbar: $label unavailable — falling back to car home")
        return home(ctx)
    }

    private fun candidates(ctx: Context): List<VehicleCandidate> {
        val pm = ctx.packageManager
        val probes = listOf(Intent(Intent.ACTION_MAIN), Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_DEFAULT))
        val hits = LinkedHashMap<String, ResolveInfo>()
        for (probe in probes) {
            for (ri in runCatching { pm.queryIntentActivities(probe, 0) }.getOrDefault(emptyList())) {
                val pkg = ri.activityInfo?.packageName ?: continue
                if (pkg.startsWith("com.gm.") || pkg.startsWith("com.android.car."))
                    hits["$pkg/${ri.activityInfo.name}"] = ri
            }
        }
        if (!candidatesLogged) {
            candidatesLogged = true
            hits.forEach { (name, ri) -> log.i("topbar: vehicle candidate $name label=${ri.loadLabel(pm)}") }
            if (hits.isEmpty()) log.i("topbar: no com.gm./com.android.car. activity candidates")
        }
        return hits.values.mapNotNull { ri ->
            val info = ri.activityInfo ?: return@mapNotNull null
            val label = runCatching { ri.loadLabel(pm)?.toString() ?: "" }.getOrDefault("")
            VehicleCandidate(info.packageName, info.name, label)
        }
    }

    internal fun start(ctx: Context, intent: Intent, label: String): Boolean = runCatching {
        if (intent.resolveActivity(ctx.packageManager) == null) return false
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        log.i("topbar: launched $label ${intent.component ?: intent.action}")
        true
    }.getOrElse { log.w("topbar: launch $label failed: ${it.message}"); false }
}
