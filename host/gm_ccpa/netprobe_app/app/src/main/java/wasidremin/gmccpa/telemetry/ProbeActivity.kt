package wasidremin.gmccpa.telemetry

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.storage.StorageManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import wasidremin.gmccpa.logging.LogExport
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Not distraction optimized: AAOS blocks this detailed Activity in motion. */
class ProbeActivity : Activity() {
    private var service: ProbeService? = null
    private var bound = false
    private lateinit var detail: TextView
    private lateinit var rawLocation: CheckBox
    private lateinit var autoStream: CheckBox
    private val controls = mutableListOf<Button>()
    private val captureControls = mutableListOf<Button>()
    private lateinit var stopButton: Button
    private val io = Executors.newSingleThreadExecutor()
    private var pendingZip: File? = null
    private var busy = false
    private var requested = emptySet<String>()
    private var note = ""
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as ProbeService.LocalBinder).service
            service?.observer = { refresh() }
            refresh()
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; refresh() }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        page.addView(TextView(this).apply { text = "Vehicle Telemetry Probe — temporary, read-only diagnostics"; textSize = 24f })
        page.addView(TextView(this).apply { text = "Park first. Unknown/restricted UX disables detailed controls. Drive capture may continue with this screen hidden. No property writes. GPS is off by default." })
        fun button(label: String, capture: Boolean = false, action: () -> Unit): Button {
            val b = Button(this).apply { text = label; setOnClickListener { if (allowed()) action() } }
            page.addView(b); controls.add(b)
            if (capture) captureControls.add(b)
            return b
        }
        button("Request eligible runtime permissions once", true) { requestProbePermissions() }
        button("Refresh read-only census", true) { service?.probe?.census() }
        rawLocation = CheckBox(this).apply {
            text = "Enable raw GPS coordinates in JSON/CSV (privacy sensitive; export reveals route)"
            isChecked = ProbeUpload.isAutoRawLocationEnabled(this@ProbeActivity)
            setOnCheckedChangeListener { _, checked -> ProbeUpload.setAutoRawLocationEnabled(this@ProbeActivity, checked) }
        }
        page.addView(rawLocation)
        autoStream = CheckBox(this).apply {
            text = "Continuous EV Insights auto-upload (streams 60s chunks & drains offline queue automatically)"
            isChecked = ProbeUpload.isAutoUploadEnabled(this@ProbeActivity)
            setOnCheckedChangeListener { _, checked ->
                ProbeUpload.setAutoUploadEnabled(this@ProbeActivity, checked)
                if (checked) ProbeService.ensureAutoStarted(this@ProbeActivity) else ProbeService.stopAuto(this@ProbeActivity)
                note = if (checked) "Continuous EV Insights auto-upload enabled" else "Continuous EV Insights auto-upload paused"
                refresh()
            }
        }
        page.addView(autoStream)
        button("Start 60-second capture", true) { start(60) }
        button("Start Drive Capture — until stopped", true) { start(0) }
        stopButton = button("Stop capture") { service?.probe?.stop() }
        button("Export latest report ZIP", true) { export() }
        button("Upload latest report to log server", true) { uploadReport() }
        button("Copy exact local report path", true) {
            val path = latest()?.absolutePath ?: "No report saved"
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Vehicle probe path", path))
            note = "Copied: $path"; refresh()
        }
        button("Clear probe data", true) {
            AlertDialog.Builder(this).setTitle("Delete all vehicle probe reports?")
                .setMessage("Only temporary telemetry reports are deleted. Export first. Projection logs are untouched.")
                .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                    if (allowed() && service?.probe?.capturing != true) {
                        service?.probe?.clearData()
                        note = "Probe data clear requested; refresh census when ready"
                    }
                }.show()
        }
        detail = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }
        page.addView(detail)
        setContentView(ScrollView(this).apply { addView(page) })
        bindProbe()
        refresh()
    }
    private fun bindProbe() {
        bound = bindService(Intent(this, ProbeService::class.java), connection, Context.BIND_AUTO_CREATE)
    }
    private fun unbindProbe() {
        service?.observer = null
        if (bound) unbindService(connection)
        bound = false; service = null
    }
    private fun allowed(): Boolean = service?.probe?.parked == true && !busy
    private fun refresh() {
        if (isDestroyed || isFinishing) return
        val p = service?.probe
        val safe = p?.parked == true
        controls.forEach { it.isEnabled = safe && !busy }
        captureControls.forEach { it.isEnabled = safe && !busy && p?.capturing != true }
        stopButton.isEnabled = safe && !busy && p?.capturing == true
        rawLocation.isEnabled = safe && !busy && p?.capturing != true
        // Redact details in-app while restricted; reports remain on disk for parked review.
        detail.text = if (!safe) "Details unavailable: vehicle UX restricted or unknown. ${service?.unavailable ?: "Connecting…"}\n$note"
            else "${p?.summary ?: service?.unavailable}\n$note"
    }
    private fun requestProbePermissions() {
        val prefs = getSharedPreferences("vehicle_probe_permissions", Context.MODE_PRIVATE)
        val declared = packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet() ?: emptySet()
        requested = ProbeModel.permissions.filter { name ->
            name in declared && !prefs.getBoolean(name, false) && checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED &&
                try { packageManager.getPermissionInfo(name, 0).protectionLevel and 15 == 1 } catch (_: PackageManager.NameNotFoundException) { false }
        }.toSet()
        // Android 12+ fine location must be requested together with coarse.
        if ("android.permission.ACCESS_FINE_LOCATION" in requested) requested = requested + "android.permission.ACCESS_COARSE_LOCATION"
        if (requested.isEmpty()) { note = "No unrequested, defined dangerous permissions. Review denied/undefined results in report."; refresh(); return }
        prefs.edit().apply { requested.forEach { putBoolean(it, true) } }.apply()
        try { requestPermissions(requested.toTypedArray(), PERMISSIONS) }
        catch (t: Exception) { note = "${t.javaClass.name}: ${t.message}"; service?.probe?.refreshPermissions(requested); refresh() }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS) {
            note = "Runtime request completed. Denials are expected; no automatic retries. OS dialog presentation cannot be proven by the request API."
            service?.probe?.refreshPermissions(requested)
        }
    }
    private fun start(seconds: Int) {
        try {
            startForegroundService(Intent(this, ProbeService::class.java).setAction(ProbeService.START)
                .putExtra("seconds", seconds).putExtra("raw_location", rawLocation.isChecked))
            note = "Capture requested; return while parked to stop/review/export."
        } catch (t: Exception) { note = "${t.javaClass.name}: ${t.message}" }
        refresh()
    }
    private fun dataRoot() = File(getExternalFilesDir(null) ?: filesDir, "telemetry")
    private fun latest(): File? = service?.probe?.latestDirectory?.takeIf { File(it, "report.json").exists() }
        ?: dataRoot().listFiles()?.filter { it.isDirectory && File(it, "report.json").exists() }?.maxByOrNull { it.name }
    private fun work(action: () -> Unit) {
        busy = true; refresh()
        io.execute {
            try { action() } catch (t: Exception) { note = "${t.javaClass.name}: ${t.message}" }
            finally { runOnUiThread { busy = false; refresh() } }
        }
    }
    private fun export() {
        val source = latest() ?: run { note = "No completed local report"; refresh(); return }
        work {
            val zip = File(cacheDir, "vehicle-telemetry-${source.name}.zip")
            ZipOutputStream(zip.outputStream()).use { out ->
                source.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.forEach { file ->
                    out.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(out) }; out.closeEntry()
                }
            }
            pendingZip = zip
            runOnUiThread {
                try {
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/zip").putExtra(Intent.EXTRA_TITLE, zip.name), EXPORT)
                } catch (_: android.content.ActivityNotFoundException) { removableExport(zip) }
                catch (t: SecurityException) { note = "Picker denied: ${t.message}"; removableExport(zip) }
            }
        }
    }
    private fun uploadReport() {
        val source = latest() ?: run { note = "No completed local report"; refresh(); return }
        val report = File(source, "report.json")
        if (!report.isFile) { note = "No completed local report"; refresh(); return }
        busy = true
        val base = wasidremin.gmccpa.upload.UploadCenter.baseUrl(this)
        note = "Uploading ${source.name}/report.json (${report.length()} B) to $base/probe…"
        refresh()
        ProbeUpload.upload(
            report,
            source.name,
            base,
            onState = { st ->
                runOnUiThread {
                    when (st) {
                        is wasidremin.gmccpa.upload.UploadState.InProgress -> {
                            val total = st.bytesTotal
                            note = if (total != null && total > 0L) {
                                val pct = ((st.bytesSent * 100L) / total).coerceIn(0L, 100L)
                                "Uploading ${source.name}/report.json… $pct% (${st.bytesSent}/$total B)"
                            } else {
                                "Uploading ${source.name}/report.json… ${st.bytesSent} B sent"
                            }
                            refresh()
                        }
                        is wasidremin.gmccpa.upload.UploadState.Finalizing -> {
                            note = "Finishing upload of ${source.name}/report.json…"
                            refresh()
                        }
                        else -> Unit
                    }
                }
            },
        ) { result ->
            runOnUiThread {
                result
                    .onSuccess { r -> note = "Report uploaded: ${r.file} (${r.bytes} B) to ${r.endpoint}" }
                    .onFailure { t -> note = "${t.javaClass.simpleName}: ${t.message}. Local report retained; retry or ZIP-export." }
                busy = false
                refresh()
            }
        }
    }
    private fun removableExport(zip: File) {
        work {
            val sm = getSystemService(StorageManager::class.java)
            val target = getExternalFilesDirs(null).filterNotNull().firstOrNull {
                sm.getStorageVolume(it)?.let { v -> v.isRemovable && v.state == android.os.Environment.MEDIA_MOUNTED } == true
            }
            if (target != null) {
                val file = File(target, zip.name)
                zip.copyTo(file, overwrite = true)
                note = "Exported ZIP: ${file.absolutePath}. Remove stick and open Android/data/$packageName/files/${zip.name} on a computer."
            } else note = "No document picker or mounted removable volume. Insert a USB stick and retry. Local report: ${latest()?.absolutePath}; cached ZIP: ${zip.absolutePath}. Copy path is available; no network or ADB is required when a picker/stick is available."
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != EXPORT) return
        val zip = pendingZip ?: run { note = "Export interrupted; retry export from local reports"; refresh(); return }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { note = "Export cancelled; local data retained"; refresh(); return }
        work {
            contentResolver.openOutputStream(uri, "w")?.use { out -> zip.inputStream().use { it.copyTo(out) } }
                ?: throw java.io.IOException("Export destination could not be opened")
            note = "ZIP exported to $uri"
        }
    }
    override fun onDestroy() {
        unbindProbe()
        io.shutdown()
        super.onDestroy()
    }
    companion object { private const val PERMISSIONS = 761; private const val EXPORT = 762 }
}
