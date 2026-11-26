package wasidremin.gmccpa.upload

import wasidremin.gmccpa.ProbeLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one way this app opens an internet connection, so Wi-Fi only ([DataMeter.wifiOnly]) covers every
 * upload and download, including ones added later (`NetTest` fails on any other `openConnection`).
 * The adapter link (USB networking, the A/V seams, mDNS) does not come through here: it never
 * reaches the car's modem.
 */
object Net {
    /** Refused because Wi-Fi only is on and the car is not on Wi-Fi. An [IOException], so every caller's
     *  failure path (retry later, keep the queue, say "not sent") already handles it. */
    class WifiOnly : IOException("Wi-Fi only is on and the car is not on Wi-Fi: not sent")

    private val log by lazy { ProbeLog.sub("data") }

    fun open(url: String): HttpURLConnection {
        if (!DataMeter.mayUseNetworkNow()) {
            log.i("request held: Wi-Fi only, and the car is not on Wi-Fi")
            throw WifiOnly()
        }
        return URL(url).openConnection() as HttpURLConnection
    }
}
