package wasidremin.gmccpa

import android.content.Context
import org.json.JSONObject

/**
 * Names for the adapter's paired iPhones, for Settings ▸ Phones.
 *
 * The adapter stores only Bluetooth addresses (`MGMT_INFO` `devices`). A phone's name arrives
 * once per session in `CT_PHONE_IDENT`, whose `deviceID` is that same BR/EDR address, so the
 * app keeps the address → name join here. A phone that has paired but never finished a session
 * has no name yet and shows by its address.
 */
object PhoneBook {
    private const val PREFS = "phone_book"

    /** One row in the list: the adapter's bond, labelled. */
    data class Phone(val mac: String, val name: String?, val model: String?, val connected: Boolean) {
        val label: String get() = name ?: "iPhone (${mac.takeLast(5)})"
    }

    fun normalize(mac: String): String = mac.trim().uppercase()

    /** Learn the name of the phone the adapter just identified. Ignores an empty or address-less identity. */
    fun remember(ctx: Context, identJson: String) {
        val o = runCatching { JSONObject(identJson) }.getOrNull() ?: return
        val mac = normalize(o.optString("deviceID"))
        val name = o.optString("name").trim()
        if (mac.isEmpty() || name.isEmpty()) return
        val entry = JSONObject().put("name", name).put("model", o.optString("model").trim())
        prefs(ctx).edit().putString(mac, entry.toString()).apply()
    }

    fun forget(ctx: Context, mac: String) {
        prefs(ctx).edit().remove(normalize(mac)).apply()
    }

    /** The adapter's bonds in its own order, each with the name this app last saw for it. */
    fun list(ctx: Context, bonded: List<String>, connectedMac: String?): List<Phone> {
        val p = prefs(ctx)
        return rows(bonded, connectedMac) { mac -> p.getString(mac, null) }
    }

    /** Pure join, so ordering, de-duplication and the connected flag are unit-tested. */
    internal fun rows(bonded: List<String>, connectedMac: String?, stored: (String) -> String?): List<Phone> {
        val live = connectedMac?.let(::normalize)?.takeIf { it.isNotEmpty() }
        return bonded.map(::normalize).filter { it.isNotEmpty() }.distinct().map { mac ->
            val o = stored(mac)?.let { runCatching { JSONObject(it) }.getOrNull() }
            Phone(
                mac = mac,
                name = o?.optString("name")?.trim()?.takeIf { it.isNotEmpty() },
                model = o?.optString("model")?.trim()?.takeIf { it.isNotEmpty() },
                connected = mac == live,
            )
        }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
