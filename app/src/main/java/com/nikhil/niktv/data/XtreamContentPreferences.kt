package com.nikhil.niktv.data

import android.content.Context
import android.content.SharedPreferences
import com.nikhil.niktv.model.XtreamContentPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal object XtreamContentPreferences {
    private const val DOMAIN = "xtream_content_policy_v1"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun read(context: Context, owner: UserScope?): XtreamContentPolicy {
        if (owner == null) return XtreamContentPolicy()
        val value = UserProfilePreferences.get(context, DOMAIN, owner = owner).getString("policy", null)
            ?: return XtreamContentPolicy()
        // Malformed existing restrictions fail closed; absence alone means opt out.
        return runCatching { json.decodeFromString<XtreamContentPolicy>(value) }.getOrElse {
            XtreamContentPolicy(enabled = true, languages = emptySet(), interests = emptySet(), sports = emptySet())
        }
    }
    fun observe(context: Context, owner: UserScope?) = callbackFlow {
        if (owner == null) { trySend(XtreamContentPolicy()); close(); return@callbackFlow }
        UserProfilePreferences.warm(context, owner)
        val preferences = UserProfilePreferences.get(context, DOMAIN, owner = owner)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read(context, owner)) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(read(context, owner))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()
    suspend fun save(context: Context, owner: UserScope, policy: XtreamContentPolicy) = withContext(Dispatchers.IO) {
        UserProfilePreferences.warm(context, owner)
        check(UserProfilePreferences.get(context, DOMAIN, owner = owner).edit()
            .putString("policy", json.encodeToString(policy)).commit())
    }
}
