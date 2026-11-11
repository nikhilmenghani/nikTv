package com.nikhil.niktv.data

import android.content.Context
import android.content.SharedPreferences
import com.nikhil.niktv.model.PortalProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Profile permissions sync as opaque connection IDs; connection credentials remain device-owned. */
internal object ProfilePortalAccess {
    private val edits = kotlinx.coroutines.sync.Mutex()
    private const val DOMAIN = "profile_portal_access_v1"
    const val SPORTS = "sports"
    fun key(portal: PortalProfile): String = MessageDigest.getInstance("SHA-256")
        .digest(portal.cacheKey().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun preferences(context: Context, owner: UserScope) = UserProfilePreferences.get(context, DOMAIN, owner = owner)
    fun hidden(context: Context, owner: UserScope?): Set<String> =
        owner?.let { preferences(context, it).getStringSet("hidden", emptySet()).orEmpty().toSet() }.orEmpty()
    fun allowed(context: Context, portal: PortalProfile, owner: UserScope? = UserSyncStore.get(context).selectedScope.value) =
        key(portal) !in hidden(context, owner)
    fun observe(context: Context, owner: UserScope?) = callbackFlow {
        if (owner == null) { trySend(emptySet<String>()); close(); return@callbackFlow }
        UserProfilePreferences.warm(context, owner)
        val prefs = preferences(context, owner)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(hidden(context, owner)) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(hidden(context, owner))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
    suspend fun setAllowed(context: Context, owner: UserScope, key: String, allowed: Boolean) = withContext(Dispatchers.IO) {
        UserProfilePreferences.warm(context, owner)
        edits.lock()
        try {
            val next = hidden(context, owner).toMutableSet()
            if (allowed) next.remove(key) else next.add(key)
            check(preferences(context, owner).edit().putStringSet("hidden", next).commit())
        } finally { edits.unlock() }
    }
}
