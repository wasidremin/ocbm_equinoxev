package wasidremin.gmccpa.ocbm

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.os.UserManager
import wasidremin.gmccpa.bridgeapi.BridgeProtocol

/**
 * GM CCPA's side of the sideloaded USB bridge ([BridgeProtocol], `netprobe_app/bridge`).
 *
 * The bridge is installed as `android.car.usb.handler`, the package GM's fixed USB handler config
 * names, so the platform grants it the adapter at attach with no dialog. [UsbBulkTransport] asks it
 * first: when it holds the grant the adapter is claimed through it; when it is absent or holds none
 * (the adapter attached while another Android user was in front), the transport claims the adapter
 * itself and raises its own dialog, as before the bridge.
 *
 * The binding is made once and kept: it is what keeps the bridge process alive at our priority.
 * Calls that may have to bind wait up to [BIND_TIMEOUT_MS]; on the main thread they never wait, they
 * start the binding and answer as if the bridge were absent. The connection callbacks run on their
 * own thread: on the main thread they waited behind the app's startup, and the first bind on the
 * emulator timed out at 3 s with the bridge already running.
 */
object UsbBridge {
    private const val BIND_TIMEOUT_MS = 5_000L
    private const val LOOKUP_MS = 5_000L

    private val lock = Object()
    private val callbacks = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "usb-bridge-bind") }
    @Volatile private var binder: IBinder? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var lookedUpAt = 0L
    @Volatile private var lookedUp: String? = null

    /** Why the last call that needed the bridge got nothing. */
    @Volatile var lastFailure = ""
        private set

    /** How the current link was claimed: "bridge", "app", or null before the first claim. */
    @Volatile var lastClaim: String? = null

    /**
     * The installed bridge's package, or null. A debuggable GM CCPA also takes the bridge's debug
     * build, which installs beside the emulator's own `android.car.usb.handler`. Cached for
     * [LOOKUP_MS]: the claim loop asks every 250 ms.
     */
    fun installedPackage(ctx: Context): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - lookedUpAt < LOOKUP_MS) return lookedUp
        val app = ctx.applicationContext
        val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val names = if (debuggable) listOf(BridgeProtocol.PACKAGE, BridgeProtocol.DEBUG_PACKAGE) else listOf(BridgeProtocol.PACKAGE)
        lookedUp = names.firstOrNull { pkg ->
            runCatching { app.packageManager.resolveService(serviceIntent(pkg), 0) != null }.getOrDefault(false)
        }
        lookedUpAt = now
        return lookedUp
    }

    fun versionName(ctx: Context): String? = installedPackage(ctx)?.let { pkg ->
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull()
    }

    /**
     * Whether this Android user may install an app from a file at all. GM can set either
     * restriction for the driver; when it has, Files cannot install the bridge and saying so up
     * front saves the trip to the car.
     */
    fun sideloadBlocked(ctx: Context): String? {
        val um = ctx.getSystemService(Context.USER_SERVICE) as UserManager
        return listOf(
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_INSTALL_APPS,
        ).firstOrNull { runCatching { um.hasUserRestriction(it) }.getOrDefault(false) }
    }

    /**
     * The screen that answers "allow this app to install apps", as `package/activity`. Stock AAOS
     * answers with `com.android.car.frameworkpackagestubs`, a stub that only toasts "not supported"
     * (AAOS 14 emulator, 2026-11-06): there is then no way to let Files install the bridge, unless the
     * car already allows it.
     */
    fun unknownSourcesScreen(ctx: Context): String? = runCatching {
        ctx.packageManager.resolveActivity(
            Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES),
            android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.let { "${it.packageName}/${it.name}" }
    }.getOrNull()

    fun unknownSourcesStubbed(ctx: Context): Boolean =
        unknownSourcesScreen(ctx)?.startsWith("com.android.car.frameworkpackagestubs/") == true

    fun hasPermission(ctx: Context, deviceName: String): Boolean =
        call(ctx, BridgeProtocol.TX_HAS_PERMISSION, { it.writeString(deviceName) }) { it.readInt() == 1 } == true

    /** The bridge's one-line status; null when it is not installed or did not answer. */
    fun status(ctx: Context): String? = call(ctx, BridgeProtocol.TX_STATUS, {}) { it.readString() }

    /** The bulk IN stream for [deviceName]; null with the reason in [lastFailure]. */
    fun open(ctx: Context, deviceName: String): ParcelFileDescriptor? =
        call(ctx, BridgeProtocol.TX_OPEN, { it.writeString(deviceName) }) { reply ->
            if (reply.readInt() == 1) {
                ParcelFileDescriptor.CREATOR.createFromParcel(reply)
            } else {
                lastFailure = "the bridge could not open $deviceName: ${reply.readString()}"
                null
            }
        }

    /** Write [data] to bulk OUT through the bridge, split under the binder limit. False on any failure. */
    fun write(ctx: Context, data: ByteArray): Boolean {
        var off = 0
        while (off < data.size) {
            val len = minOf(BridgeProtocol.MAX_WRITE, data.size - off)
            val chunk = if (off == 0 && len == data.size) data else data.copyOfRange(off, off + len)
            val n = call(ctx, BridgeProtocol.TX_WRITE, { it.writeByteArray(chunk) }) { it.readInt() } ?: return false
            if (n != len) {
                lastFailure = "bridge write returned $n of $len bytes"
                return false
            }
            off += len
        }
        return true
    }

    fun close(ctx: Context) {
        call(ctx, BridgeProtocol.TX_CLOSE, {}) { }
    }

    /** One line for the log: absent, or installed with its version and status. */
    fun describe(ctx: Context): String {
        val pkg = installedPackage(ctx) ?: return "not installed" + (sideloadBlocked(ctx)?.let { ", this user has $it" } ?: "") +
            ", unknown-sources screen ${unknownSourcesScreen(ctx) ?: "none"}"
        val st = status(ctx) ?: "no answer: $lastFailure"
        return "$pkg ${versionName(ctx)}: $st"
    }

    private fun serviceIntent(pkg: String) = Intent().setComponent(ComponentName(pkg, BridgeProtocol.SERVICE))

    private fun <T> call(ctx: Context, code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T? {
        val b = connect(ctx) ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(BridgeProtocol.DESCRIPTOR)
            write(data)
            b.transact(code, data, reply, 0)
            reply.readException()
            read(reply)
        } catch (e: SecurityException) {
            lastFailure = "the bridge refused GM CCPA: ${e.message}"
            null
        } catch (t: Throwable) {
            // DeadObjectException included: the bridge died; the system restarts it and rebinds.
            lastFailure = "bridge call $code failed: ${t.javaClass.simpleName}: ${t.message}"
            if (!b.isBinderAlive) binder = null
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun connect(ctx: Context): IBinder? {
        binder?.takeIf { it.isBinderAlive }?.let { return it }
        val pkg = installedPackage(ctx) ?: run {
            lastFailure = "not installed"
            return null
        }
        val app = ctx.applicationContext
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        synchronized(lock) {
            if (connection == null) {
                val c = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, service: IBinder) {
                        synchronized(lock) {
                            if (connection !== this) return
                            binder = service
                            lock.notifyAll()
                        }
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        if (connection === this) binder = null
                    }

                    override fun onBindingDied(name: ComponentName) {
                        // Reinstalled or disabled: this binding never comes back. The next call binds afresh.
                        synchronized(lock) {
                            if (connection === this) {
                                connection = null
                                binder = null
                                lastFailure = "binding died (bridge reinstalled?)"
                            }
                            lock.notifyAll()
                        }
                        runCatching { app.unbindService(this) }
                    }

                    override fun onNullBinding(name: ComponentName) {
                        synchronized(lock) {
                            lastFailure = "the bridge returned no binder"
                            lock.notifyAll()
                        }
                    }
                }
                connection = c
                val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
                val ok = runCatching {
                    if (android.os.Build.VERSION.SDK_INT >= 29) app.bindService(serviceIntent(pkg), flags, callbacks, c)
                    else app.bindService(serviceIntent(pkg), c, flags)
                }.getOrElse { false }
                if (!ok) {
                    connection = null
                    runCatching { app.unbindService(c) }
                    lastFailure = "bindService refused for $pkg"
                    return null
                }
            }
            if (onMain) {
                lastFailure = "not bound yet"
                return binder
            }
            val end = SystemClock.elapsedRealtime() + BIND_TIMEOUT_MS
            while (binder == null && connection != null) {
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0) break
                lock.wait(left)
            }
            binder?.let { return it }
            if (connection != null) {
                lastFailure = "timed out binding the bridge"
                // A binding that never connected is not waited on again (enigy/carlink [211]).
                connection?.let { runCatching { app.unbindService(it) } }
                connection = null
            }
            return null
        }
    }
}
