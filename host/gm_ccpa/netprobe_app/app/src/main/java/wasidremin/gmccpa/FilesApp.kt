package wasidremin.gmccpa

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent

/**
 * Opens the head unit's own Files app (DocumentsUI). Car makers hide it from the app grid but keep
 * it installed, and it can still hand an APK on a USB drive to the package installer: that is how
 * the USB bridge gets installed with no adb (enigy/carlink's FilesLauncher, [210]).
 *
 * Tried in order: a launcher entry, the known browser activities, then ACTION_VIEW_DOWNLOADS. The
 * AAOS 14 emulator ships `com.android.car.documentsui` with no launcher entry and no FilesActivity;
 * VIEW_DOWNLOADS opens its full browser, with USB drives in the side list. The packages need the
 * `<queries>` entries in the manifest. Files is not distraction-optimised: parked only.
 */
object FilesApp {
    private val PACKAGES = listOf("com.android.documentsui", "com.google.android.documentsui", "com.android.car.documentsui")

    private val ACTIVITIES = listOf(
        "com.android.documentsui" to "com.android.documentsui.files.FilesActivity",
        "com.android.documentsui" to "com.android.documentsui.LauncherActivity",
        "com.google.android.documentsui" to "com.android.documentsui.LauncherActivity",
        "com.android.car.documentsui" to "com.android.documentsui.files.FilesActivity",
    )

    /** What was opened, or null when nothing could be. */
    fun open(ctx: Context): String? {
        val pm = ctx.packageManager
        val candidates = PACKAGES.mapNotNull { pm.getLaunchIntentForPackage(it) } +
            ACTIVITIES.map { (pkg, cls) -> Intent().setClassName(pkg, cls) } +
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        for (intent in candidates) {
            val target = intent.component?.flattenToShortString() ?: intent.action.orEmpty()
            try {
                ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ProbeLog.sub("usb").i("bridge setup: opened Files ($target)")
                return target
            } catch (_: ActivityNotFoundException) {
                // Not on this head unit; try the next.
            } catch (e: SecurityException) {
                ProbeLog.sub("usb").w("bridge setup: Files ($target) refused: ${e.message}")
            }
        }
        ProbeLog.sub("usb").w("bridge setup: no Files app could be opened (${candidates.size} tried)")
        return null
    }
}
