package com.nikhil.niktv.data

import android.content.Context
import com.nikhil.niktv.model.PortalProfile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

/** Device-only schedule and durable scan cursor, keyed by anonymous provider identity. */
object CatalogScanPreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("catalog_scans", Context.MODE_PRIVATE)
    fun id(profile: PortalProfile) = SearchMetadataDocuments.anonymousProfileId(profile)
    fun selectedProfileId(context: Context) = prefs(context).getString("selected_profile", null)
    fun selectedProfileId(context: Context, id: String) {
        prefs(context).edit().putString("selected_profile", id).apply()
    }
    fun hours(context: Context, id: String) = prefs(context).getInt("hours:$id", 0)
    fun hours(context: Context, id: String, hours: Int) {
        require(hours in listOf(0, 6, 12, 24, 168))
        prefs(context).edit().putInt("hours:$id", hours).apply()
    }
    fun cursor(context: Context, id: String) = prefs(context).getInt("cursor:$id", -1)
    fun cursor(context: Context, id: String, cursor: Int) { prefs(context).edit().putInt("cursor:$id", cursor).commit() }
    fun restoredCursor(context: Context, id: String) = prefs(context).getInt("restored_cursor:$id", -1)
    fun restoredCursor(context: Context, id: String, cursor: Int) {
        prefs(context).edit().putInt("restored_cursor:$id", cursor)
            .putLong("restored_at:$id", System.currentTimeMillis()).commit()
    }
    fun restoredAt(context: Context, id: String) = prefs(context).getLong("restored_at:$id", 0L)
    fun completed(context: Context, id: String) = prefs(context).getLong("completed:$id", 0L)
    fun completed(context: Context, id: String, time: Long) { prefs(context).edit().putLong("completed:$id", time).commit() }
    fun status(context: Context, id: String) = prefs(context).getString("status:$id", "Not scanned on this device.").orEmpty()
    fun status(context: Context, id: String, message: String) {
        prefs(context).edit().putString("status:$id", message).apply()
    }
    fun observe(context: Context, id: String) = callbackFlow {
        val prefs = prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "status:$id") trySend(status(context, id))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(status(context, id))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()
}

internal fun needsProviderSearch(emptyResults: Boolean, scanCompletedAt: Long, scanCursor: Int): Boolean =
    emptyResults || scanCompletedAt == 0L || scanCursor >= 0
