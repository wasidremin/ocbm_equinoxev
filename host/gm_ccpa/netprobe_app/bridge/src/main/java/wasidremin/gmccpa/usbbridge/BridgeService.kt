package wasidremin.gmccpa.usbbridge

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.Process
import android.os.SystemClock
import android.util.Log
import wasidremin.gmccpa.bridgeapi.BridgeProtocol

/**
 * What GM CCPA binds. The USB permission comes from the platform's fixed-handler grant at attach;
 * this service never asks for one. When the grant did not land (the adapter attached while another
 * Android user was in front), [BridgeProtocol.TX_HAS_PERMISSION] says so and GM CCPA claims the
 * adapter itself, with its own dialog.
 */
class BridgeService : Service() {
    private lateinit var usb: UsbManager
    private lateinit var link: UsbLink
    private lateinit var callers: CallerCheck

    private val detach = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            dev?.let { link.onDetached(it.deviceName) }
        }
    }

    private val binder = object : Binder(BridgeProtocol.DESCRIPTOR) {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code < BridgeProtocol.TX_API || code > BridgeProtocol.TX_CLOSE) {
                return super.onTransact(code, data, reply, flags)
            }
            data.enforceInterface(BridgeProtocol.DESCRIPTOR)
            val out = reply ?: return true
            if (code != BridgeProtocol.TX_API) {
                val why = callers.rejectReason(Binder.getCallingUid())
                if (why != null) {
                    Events.add("refused: $why")
                    Log.w(TAG, "refused: $why")
                    out.writeException(SecurityException(why))
                    return true
                }
            }
            out.writeNoException()
            when (code) {
                BridgeProtocol.TX_API -> out.writeInt(BridgeProtocol.API)
                BridgeProtocol.TX_STATUS -> out.writeString(status())
                BridgeProtocol.TX_HAS_PERMISSION -> out.writeInt(if (link.hasPermission(data.readString())) 1 else 0)
                BridgeProtocol.TX_OPEN -> {
                    val stream = link.open(data.readString())
                    if (stream == null) {
                        out.writeInt(0)
                        out.writeString(link.lastError)
                    } else {
                        out.writeInt(1)
                        // Closes our copy once written; the bridge keeps its own end of the pair.
                        stream.writeToParcel(out, Parcelable.PARCELABLE_WRITE_RETURN_VALUE)
                    }
                }
                BridgeProtocol.TX_WRITE -> out.writeInt(link.write(data.createByteArray()))
                BridgeProtocol.TX_CLOSE -> link.close("closed by GM CCPA")
            }
            return true
        }
    }

    override fun onCreate() {
        super.onCreate()
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        link = UsbLink(usb)
        callers = CallerCheck(this, BuildConfig.CALLER_CERTS)
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(detach, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(detach, filter)
        Events.add("service started")
        Log.i(TAG, "started: ${status()}")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        link.close("GM CCPA unbound")
        callers.forget()
        return false
    }

    override fun onDestroy() {
        link.close("bridge stopped")
        runCatching { unregisterReceiver(detach) }
        super.onDestroy()
    }

    /**
     * Everything a log needs to explain a missing grant: which Android user this copy runs as (the
     * grant goes to the user in front at attach), what the platform's handler config names, and
     * which devices this UID holds permission for.
     */
    private fun status(): String {
        val devices = usb.deviceList.values.sortedBy { it.deviceName }.joinToString(prefix = "[", postfix = "]") {
            "${it.deviceName} %04x:%04x perm=%b".format(it.vendorId, it.productId, usb.hasPermission(it))
        }
        return "bridge ${BuildConfig.VERSION_NAME} api=${BridgeProtocol.API} user=${Process.myUid() / 100_000} " +
            "up=${SystemClock.elapsedRealtime() / 1000}s handler=${handlerConfig()} devices=$devices " +
            "link=${link.describe()} events=${Events.recent()}"
    }

    @SuppressLint("DiscouragedApi")
    private fun handlerConfig(): String = runCatching {
        val res = Resources.getSystem()
        val id = res.getIdentifier("config_UsbDeviceConnectionHandling_component", "string", "android")
        if (id == 0) "<no such resource>" else res.getString(id).ifEmpty { "<empty>" }
    }.getOrElse { "<${it.javaClass.simpleName}>" }

    private companion object {
        const val TAG = "GmCcpaUsbBridge"
    }
}

/** The bridge's last few events, for [BridgeService]'s status line: the head unit has no adb. */
internal object Events {
    private const val KEEP = 12
    private val lines = ArrayDeque<String>()

    fun add(line: String) = synchronized(lines) {
        lines.addLast("+${SystemClock.elapsedRealtime() / 1000}s $line")
        while (lines.size > KEEP) lines.removeFirst()
    }

    fun recent(): String = synchronized(lines) { lines.joinToString("; ", prefix = "[", postfix = "]") }
}
