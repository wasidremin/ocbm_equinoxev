package wasidremin.gmccpa.bridgeapi

/**
 * The binder contract between GM CCPA (`wasidremin.gmccpa`, from Play) and its sideloaded USB
 * bridge (`android.car.usb.handler`, `netprobe_app/bridge`). Both builds compile this one file, so
 * the two sides cannot drift.
 *
 * Why a second app: AAOS hands every USB host attach to a fixed handler package
 * (`config_UsbDeviceConnectionHandling_component`), granting that package's UID permission for the
 * device with no dialog. GM names `android.car.usb.handler` there and ships no app by that name. Play
 * refuses an `android.*` package, so the bridge is sideloaded; it holds the grant, claims the
 * adapter's OCBM interface and relays it. GM CCPA stays the Play app.
 *
 * Hand-written rather than AIDL because `tools/build_apk.sh` compiles the app with kotlinc alone.
 * Every call starts with [DESCRIPTOR] as the interface token and answers with the exception header
 * (`Parcel.writeNoException` / `readException`). Codes are only ever added at the end: the two apps
 * update separately.
 */
object BridgeProtocol {
    /** The fixed handler package GM's config names. */
    const val PACKAGE = "android.car.usb.handler"

    /** The bridge's debug build, which installs beside the emulator's own handler. Debuggable GM CCPA only. */
    const val DEBUG_PACKAGE = "android.car.usb.handler.debug"

    const val SERVICE = "wasidremin.gmccpa.usbbridge.BridgeService"

    /** The only app the bridge serves. */
    const val APP_PACKAGE = "wasidremin.gmccpa"

    const val DESCRIPTOR = "wasidremin.gmccpa.bridgeapi.UsbBridge"

    const val API = 1

    /** () -> int [API]. Answered for any caller. */
    const val TX_API = 1

    /** () -> String, one line: version, Android user, the handler config, devices and their grants, the link. */
    const val TX_STATUS = 2

    /** (String deviceName) -> int, 1 when the bridge's UID holds USB permission for that /dev/bus/usb path. */
    const val TX_HAS_PERMISSION = 3

    /**
     * (String deviceName) -> int ok, then either a ParcelFileDescriptor (ok = 1) or the reason
     * (String, ok = 0). The descriptor is a stream socket carrying the bulk IN bytes as read; EOF means
     * the link closed. A link already open is closed first.
     */
    const val TX_OPEN = 4

    /** (byte[] data) -> int, bytes written to bulk OUT in 16 KiB transfers, or -1. At most [MAX_WRITE] bytes. */
    const val TX_WRITE = 5

    /** () -> nothing. Close the link, if any. */
    const val TX_CLOSE = 6

    /** Binder transactions share 1 MB per process; the client splits anything longer. */
    const val MAX_WRITE = 128 * 1024
}
