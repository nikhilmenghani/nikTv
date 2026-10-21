package com.nikhil.niktv.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

// Several independent preference flows can change together. Leave CPU capacity
// for input/rendering and avoid decoding every large library at once.
internal val profileMetadataDispatcher = Dispatchers.Default.limitedParallelism(2)

/** Unrelated preference writes must not reparse entire libraries on the collector's UI thread. */
internal fun <Raw, Value> Flow<Preferences>.decodedProfilePreference(
    select: (Preferences) -> Raw,
    decode: (Raw) -> Value,
): Flow<Value> = map(select).distinctUntilChanged().map(decode)
    .distinctUntilChanged().flowOn(profileMetadataDispatcher)

/** Enqueue in the caller's order; dispatch only the atomic JSON transform off its thread. */
internal suspend fun DataStore<Preferences>.editProfileMetadata(
    transform: suspend (MutablePreferences) -> Unit,
): Preferences = edit { preferences ->
    withContext(profileMetadataDispatcher) { transform(preferences) }
}
