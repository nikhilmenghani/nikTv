package com.nikhil.niktv.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class CatalogOperationHeld(val state: String) : CancellationException(state)

@Serializable
internal data class CatalogPageEvent(
    val key: String, val time: Long = System.currentTimeMillis(),
    val location: String, val outcome: String, val detail: String
)

/** Durable device-only controls. A stop never discards a committed page or file. */
internal object CatalogOperations {
    const val BACKUP = "backup"
    private fun prefs(context: Context) = context.getSharedPreferences("catalog_operations", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    fun scan(id: String) = "scan:$id"
    fun mode(context: Context, key: String) = prefs(context).getString("mode:$key", "Ready")!!
    fun held(context: Context, key: String) = mode(context, key) in listOf("Paused", "Stopped")
    fun check(context: Context, key: String) {
        val mode = mode(context, key)
        if (mode == "Paused" || mode == "Stopped") throw CatalogOperationHeld(mode)
    }
    fun control(context: Context, key: String, mode: String) {
        require(mode in listOf("Ready", "Paused", "Stopped"))
        prefs(context).edit().putString("mode:$key", mode).commit()
        message(context, key, if (mode == "Ready") "Queued to continue from saved progress." else
            "$mode. Finishing any in-flight request; completed pages/files are kept. Resume explicitly to continue. Scheduled runs are held too.")
    }
    fun message(context: Context, key: String) = prefs(context).getString("message:$key", "No operation running.")!!
    fun message(context: Context, key: String, value: String) {
        prefs(context).edit().putString("message:$key", value).putLong("time:$key", System.currentTimeMillis()).apply()
    }
    fun updated(context: Context, key: String) = prefs(context).getLong("time:$key", 0)
    fun observe(context: Context, key: String) = callbackFlow {
        val p = prefs(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed?.endsWith(":$key") == true) trySend(System.nanoTime())
        }
        p.registerOnSharedPreferenceChangeListener(listener)
        trySend(System.nanoTime())
        awaitClose { p.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()
    fun events(context: Context, key: String, failures: Boolean = false): List<CatalogPageEvent> =
        runCatching { json.decodeFromString<List<CatalogPageEvent>>(prefs(context).getString("${if (failures) "failures" else "events"}:$key", "[]")!!) }.getOrDefault(emptyList())
    @Synchronized fun page(context: Context, key: String, event: CatalogPageEvent) {
        val failures = events(context, key, true).filterNot { it.key == event.key } +
            if (event.outcome == "Failed") listOf(event) else emptyList()
        prefs(context).edit()
            .putString("events:$key", json.encodeToString((listOf(event) + events(context, key)).take(100)))
            .putString("failures:$key", json.encodeToString(failures)).apply()
    }
}
