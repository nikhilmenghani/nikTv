package com.nikhil.niktv.sports

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

internal fun sportsCountry(value: String): String = value.trim().uppercase(Locale.ROOT).takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty()

internal fun sportsDeviceCountry(context: Context): String {
    val phone = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    val network = runCatching { phone?.networkCountryIso.orEmpty() }.getOrDefault("")
    val sim = runCatching { phone?.simCountryIso.orEmpty() }.getOrDefault("")
    return sequenceOf(network, sim, Locale.getDefault().country).map(::sportsCountry).firstOrNull(String::isNotEmpty).orEmpty()
}

internal fun sportsGeoAvailable(geo: String, country: String): Boolean {
    val regions = geo.trim().uppercase(Locale.ROOT).split(Regex("[,;|\\s]+"))
    // Device country is only a network/SIM/locale hint, not the service's
    // observed IP location. Unknown must retain choices rather than hide them.
    return geo.isBlank() || "ALL" in regions || "*" in regions || sportsCountry(country).let { it.isEmpty() || it in regions }
}
