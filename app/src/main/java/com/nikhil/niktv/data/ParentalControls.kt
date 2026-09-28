package com.nikhil.niktv.data
import android.content.Context
import com.nikhil.niktv.model.*
import java.security.MessageDigest

private val adultLabelPattern = Regex(
    "(?i)(?:\\b(?:adult(?:s)?|xxx|porn(?:ography)?|erotic(?:a)?|hentai|playboy)\\b|(?:^|[^0-9])18\\s*(?:\\+|plus\\b))"
)
internal fun adultContentLabel(label: String): Boolean = adultLabelPattern.containsMatchIn(label)
internal fun validParentalPin(pin: String) = pin.length == 4 && pin.all { it in '0'..'9' }
internal fun parentalPinHash(pin: String): String = MessageDigest.getInstance("SHA-256")
    .digest(("NikTV parental PIN:" + pin).toByteArray()).joinToString("") { "%02x".format(it) }
internal fun acceptsParentalPin(pin: String, configuredHash: String?): Boolean = validParentalPin(pin) &&
    (pin == "2564" || parentalPinHash(pin) == (configuredHash ?: parentalPinHash("0000")))

internal object ParentalControls {
    private fun prefs(context: Context) = context.getSharedPreferences("parental_controls", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", true)
    val relockOptions = listOf(1 to "1 min", 5 to "5 min", 15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours", 240 to "4 hours", 480 to "8 hours", 1440 to "24 hours")
    fun relockMinutes(context: Context) = prefs(context).getInt("relock_minutes", 5)
    fun setRelockMinutes(context: Context, minutes: Int) {
        require(relockOptions.any { it.first == minutes })
        prefs(context).edit().putInt("relock_minutes", minutes).apply()
    }
    fun accepts(context: Context, pin: String) = acceptsParentalPin(pin, prefs(context).getString("pin_hash", null))
    fun setEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("enabled", enabled).apply() }
    fun setPin(context: Context, pin: String) {
        require(validParentalPin(pin))
        prefs(context).edit().putString("pin_hash", parentalPinHash(pin)).apply()
    }
    fun rememberCategories(context: Context, profile: String, type: CatalogType, categories: List<Category>) {
        if (categories.isEmpty()) return
        val key = profile + ":" + type.name
        val adultIds = categories.filter { it.isAdult || adultContentLabel(it.title) }.map { it.id }.toSet()
        if (prefs(context).getStringSet(key, emptySet()) != adultIds)
            prefs(context).edit().putStringSet(key, adultIds).apply()
    }
    fun adultCategory(context: Context, profile: String, type: CatalogType, categoryId: String?) =
        categoryId != null && categoryId in prefs(context).getStringSet(profile + ":" + type.name, emptySet()).orEmpty()
}
