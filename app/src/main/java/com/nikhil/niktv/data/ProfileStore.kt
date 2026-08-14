package com.nikhil.niktv.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nikhil.niktv.model.PortalProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("nik_tv_profiles")

class ProfileStore(private val context: Context) {
    private val key = stringPreferencesKey("active_profile")
    val activeProfile: Flow<PortalProfile?> = context.dataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { Json.decodeFromString<PortalProfile>(it) }.getOrNull() }
    }
    suspend fun save(profile: PortalProfile) = context.dataStore.edit { it[key] = Json.encodeToString(profile) }
    suspend fun clear() = context.dataStore.edit { it.remove(key) }
}
