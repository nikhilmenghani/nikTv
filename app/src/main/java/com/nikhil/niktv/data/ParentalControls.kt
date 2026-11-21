package com.nikhil.niktv.data
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.Settings
import com.nikhil.niktv.model.*
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

private val adultLabelPattern = Regex(
    "(?i)(?:\\b(?:adult(?:s)?|xxx|porn(?:ography)?|erotic(?:a)?|hentai|playboy)\\b|(?:^|[^0-9])18\\s*(?:\\+|plus\\b))"
)
internal fun adultContentLabel(label: String): Boolean = adultLabelPattern.containsMatchIn(label)
internal fun Category.isSensitiveIptvCategory(): Boolean = isAdult || adultContentLabel(title)
internal fun validParentalPin(pin: String) = pin.length == 4 && pin.all { it in '0'..'9' }
internal fun parentalPinHash(pin: String): String = MessageDigest.getInstance("SHA-256")
    .digest(("NikTV parental PIN:" + pin).toByteArray()).joinToString("") { "%02x".format(it) }
internal fun acceptsParentalPin(pin: String, configuredHash: String?): Boolean = validParentalPin(pin) &&
    parentalPinHash(pin) == (configuredHash ?: parentalPinHash("0000"))

internal data class ProfileParentalPolicy(
    val protectionEnabled: Boolean = true,
    val showSensitiveIptv: Boolean = false,
    val relockMinutes: Int = 5
)

internal object ParentalControls {
    private fun prefs(context: Context) = context.getSharedPreferences("parental_controls", Context.MODE_PRIVATE)
    private fun profilePrefs(context: Context, owner: UserScope) =
        UserProfilePreferences.get(context, "profile_parental_v1", portable = false, owner = owner)
    private fun activeProfile(context: Context) =
        context.applicationContext?.let { UserSyncStore.get(it).selectedScope.value }

    fun policy(context: Context, owner: UserScope?): ProfileParentalPolicy {
        if (owner == null) return ProfileParentalPolicy(
            protectionEnabled = prefs(context).getBoolean("enabled", true),
            relockMinutes = prefs(context).getInt("relock_minutes", 5)
        )
        val saved = profilePrefs(context, owner)
        return ProfileParentalPolicy(
            protectionEnabled = saved.getBoolean("enabled", true),
            showSensitiveIptv = saved.getBoolean("show_sensitive_iptv", false),
            relockMinutes = saved.getInt("relock_minutes", prefs(context).getInt("relock_minutes", 5))
        )
    }

    fun observe(context: Context, owner: UserScope?) = callbackFlow {
        if (owner == null) {
            trySend(policy(context, null))
            close()
            return@callbackFlow
        }
        UserProfilePreferences.warm(context, owner)
        val saved = profilePrefs(context, owner)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(policy(context, owner)) }
        saved.registerOnSharedPreferenceChangeListener(listener)
        trySend(policy(context, owner))
        awaitClose { saved.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    private suspend fun update(context: Context, owner: UserScope, edit: SharedPreferences.Editor.() -> Unit) {
        withContext(Dispatchers.IO) {
            UserProfilePreferences.warm(context, owner)
            check(profilePrefs(context, owner).edit().apply(edit).commit())
        }
    }

    suspend fun setProtection(context: Context, owner: UserScope, enabled: Boolean) =
        update(context, owner) { putBoolean("enabled", enabled) }

    suspend fun setCategoryVisibility(context: Context, owner: UserScope, show: Boolean) =
        update(context, owner) { putBoolean("show_sensitive_iptv", show) }

    suspend fun setRelockMinutes(context: Context, owner: UserScope, minutes: Int) {
        require(relockOptions.any { it.first == minutes })
        update(context, owner) { putInt("relock_minutes", minutes) }
    }

    fun enabled(context: Context, owner: UserScope? = activeProfile(context)) = policy(context, owner).protectionEnabled
    val relockOptions = listOf(1 to "1 min", 5 to "5 min", 15 to "15 min", 30 to "30 min", 60 to "1 hour", 120 to "2 hours", 240 to "4 hours", 480 to "8 hours", 1440 to "24 hours")
    fun relockMinutes(context: Context) = policy(context, null).relockMinutes
    fun setRelockMinutes(context: Context, minutes: Int) {
        require(relockOptions.any { it.first == minutes })
        prefs(context).edit().putInt("relock_minutes", minutes).apply()
        if (unlocked(context, null)) unlock(context, null)
    }
    private fun bootCount(context: Context) = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    fun unlocked(context: Context, owner: UserScope? = activeProfile(context)): Boolean {
        val saved = prefs(context)
        val now = SystemClock.elapsedRealtime()
        return saved.getString("unlock_account", null) == owner?.accountId &&
            saved.getString("unlock_profile", null) == owner?.profileId &&
            saved.getInt("unlock_boot", -2) == bootCount(context) &&
            now >= saved.getLong("unlock_started", Long.MAX_VALUE) &&
            now < saved.getLong("unlock_until", 0L)
    }
    fun unlock(context: Context, owner: UserScope? = activeProfile(context)) {
        val now = SystemClock.elapsedRealtime()
        prefs(context).edit().putLong("unlock_started", now)
            .putLong("unlock_until", now + policy(context, owner).relockMinutes * 60_000L)
            .putString("unlock_account", owner?.accountId)
            .putString("unlock_profile", owner?.profileId)
            .putInt("unlock_boot", bootCount(context)).apply()
    }
    fun lock(context: Context) {
        prefs(context).edit().remove("unlock_started").remove("unlock_until").remove("unlock_boot")
            .remove("unlock_account").remove("unlock_profile").apply()
    }
    fun accepts(context: Context, pin: String) = acceptsParentalPin(pin, prefs(context).getString("pin_hash", null))
    fun setEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("enabled", enabled).apply(); lock(context) }
    fun setPin(context: Context, pin: String) {
        require(validParentalPin(pin))
        prefs(context).edit().putString("pin_hash", parentalPinHash(pin)).apply()
        lock(context)
    }
    fun rememberCategories(context: Context, profile: String, type: CatalogType, categories: List<Category>) {
        if (categories.isEmpty()) return
        val key = profile + ":" + type.name
        val adultIds = categories.filter(Category::isSensitiveIptvCategory).map { it.id }.toSet()
        if (prefs(context).getStringSet(key, emptySet()) != adultIds)
            prefs(context).edit().putStringSet(key, adultIds).apply()
    }
    fun adultCategory(context: Context, profile: String, type: CatalogType, categoryId: String?) =
        categoryId != null && categoryId in prefs(context).getStringSet(profile + ":" + type.name, emptySet()).orEmpty()
}
