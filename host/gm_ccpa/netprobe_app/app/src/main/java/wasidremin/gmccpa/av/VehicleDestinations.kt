package wasidremin.gmccpa.av

/**
 * Pure ranking for the custom top-bar's CAR / CONTROLS / ENERGY buttons.
 *
 * Android-free on purpose: the queryIntentActivities plumbing stays in CarPlayActivity,
 * but the ranking itself is unit-tested on the JVM, where android.content.pm.ResolveInfo
 * does not exist. CarPlayActivity converts each ResolveInfo to [VehicleCandidate] and
 * calls [selectVehicleDestination].
 *
 * Most-specific term first so generic "vehicle"/"energy" cannot outrank exact
 * "car.settings"/"charging"; within the same term, a human-readable label match beats a
 * package/class-only match (2026-10-17: `Settings_Launcher_Homepage label=Settings` lost
 * to `com.gm.vehicleinfo label=Vehicle Status` because "carsettings" never matched
 * "com.android.car.settings" and the generic "vehicle" term won).
 */
data class VehicleCandidate(
    val packageName: String,
    val className: String,
    val label: String,
)

enum class VehicleKind { CAR, CONTROLS, ENERGY }

fun selectVehicleDestination(
    kind: VehicleKind,
    candidates: List<VehicleCandidate>,
): VehicleCandidate? {
    val terms = when (kind) {
        VehicleKind.CAR -> listOf("carsettings", "car_settings", "car.settings", "vehicleinfo", "vehicle")
        VehicleKind.CONTROLS -> listOf("controls", "climate", "hvac")
        VehicleKind.ENERGY -> listOf("charging", "charge", "energy")
    }
    data class Scored(
        val candidate: VehicleCandidate,
        val term: Int,
        val labelHit: Boolean,
        val nonEmptyLabel: Boolean,
        val name: String,
    )
    return candidates.mapNotNull { c ->
        // An empty label is a fallback alias, not a user destination (2026-10-17:
        // com.android.car.settings.FallbackHome label=). Never prefer one over a real page.
        if (c.label.isBlank()) return@mapNotNull null
        val searchable = "${c.packageName} ${c.className} ${c.label}".lowercase()
        val labelLower = c.label.trim().lowercase()
        val idx = terms.indexOfFirst { searchable.contains(it) }
        if (idx < 0) return@mapNotNull null
        val term = terms[idx]
        val labelHit = labelLower.contains(term) ||
            (kind == VehicleKind.CAR && labelLower == "settings")
        Scored(c, idx, labelHit, labelLower.isNotEmpty(), "${c.packageName}/${c.className}")
    }.sortedWith(
        compareBy<Scored> { it.term }
            .thenByDescending { it.labelHit }
            .thenByDescending { it.nonEmptyLabel }
            .thenBy { it.name },
    ).firstOrNull()?.candidate
}
