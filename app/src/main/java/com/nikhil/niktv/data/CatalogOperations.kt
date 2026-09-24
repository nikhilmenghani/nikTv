package com.nikhil.niktv.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Locale

internal class CatalogOperationHeld(val state: String) : CancellationException(state)

@Serializable
internal data class CatalogPageEvent(
    val key: String, val time: Long = System.currentTimeMillis(),
    val location: String, val outcome: String, val detail: String
)

@Serializable
internal data class CatalogOperationProgress(
    val phase: String,
    val mediaType: String = "",
    val category: String = "",
    val categoryPosition: Int = 0,
    val categoryCount: Int = 0,
    val mediaPosition: Int = 0,
    val mediaCount: Int = 0,
    val page: Int = 0,
    val totalPages: Int = 0,
    val recordsInPage: Int = 0,
    val recordsInCategory: Int = 0,
    val totalRecords: Int = 0,
    val part: Int = 0,
    val totalParts: Int = 0,
    val estimatedRemainingMillis: Long = 0
) {
    val fraction: Float?
        get() {
            if (phase.equals("Complete", ignoreCase = true)) return 1f
            val raw = when {
            totalParts > 0 -> part.toFloat() / totalParts
            mediaCount > 0 && categoryCount > 0 && totalPages > 0 -> {
                val categoryFraction = ((categoryPosition - 1).coerceAtLeast(0) +
                    page.toFloat() / totalPages.coerceAtLeast(page).coerceAtLeast(1)) /
                    categoryCount
                ((mediaPosition - 1).coerceAtLeast(0) + categoryFraction) / mediaCount
            }
            mediaCount > 0 && categoryCount > 0 -> {
                val completedCategories = if (phase.startsWith("Finalizing", ignoreCase = true)) {
                    categoryPosition
                } else {
                    (categoryPosition - 1).coerceAtLeast(0)
                }
                val categoryFraction = completedCategories.toFloat() / categoryCount
                ((mediaPosition - 1).coerceAtLeast(0) + categoryFraction) / mediaCount
            }
            categoryCount > 0 && totalPages > 0 ->
                ((categoryPosition - 1).coerceAtLeast(0) +
                    page.toFloat() / totalPages.coerceAtLeast(page).coerceAtLeast(1)) /
                    categoryCount
            totalPages > 0 -> page.toFloat() / totalPages
            categoryCount > 0 -> {
                val completedCategories = if (phase.startsWith("Finalizing", ignoreCase = true)) {
                    categoryPosition
                } else {
                    (categoryPosition - 1).coerceAtLeast(0)
                }
                completedCategories.toFloat() / categoryCount
            }
            mediaCount > 0 -> (mediaPosition - 1).coerceAtLeast(0).toFloat() / mediaCount
            else -> null
            }?.coerceIn(0f, 1f)
            return raw?.coerceAtMost(0.999f)
        }

    val percentText: String?
        get() = fraction?.let { String.format(Locale.US, "%.1f%%", it * 100f) }
}

/** Durable device-only controls. A stop never discards a committed page or file. */
internal object CatalogOperations {
    const val BACKUP = "backup"
    const val RESTORE = "restore"
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
    fun progress(context: Context, key: String): CatalogOperationProgress? =
        prefs(context).getString("progress:$key", null)?.let {
            runCatching { json.decodeFromString<CatalogOperationProgress>(it) }.getOrNull()
        }
    fun progress(context: Context, key: String, value: CatalogOperationProgress?) {
        prefs(context).edit().apply {
            if (value == null) remove("progress:$key")
            else putString("progress:$key", json.encodeToString(value))
            putLong("time:$key", System.currentTimeMillis())
        }.apply()
    }
    fun pageTotal(context: Context, key: String, category: String) =
        prefs(context).getInt("total:$key:$category", 0).takeIf { it > 0 }
    fun pageTotal(context: Context, key: String, category: String, total: Int?) {
        if (total == null || total <= 0) return
        prefs(context).edit().putInt("total:$key:$category", total).apply()
    }
    fun clearPageTotals(context: Context, key: String, mediaType: String) {
        val prefix = "total:$key:$mediaType:"
        val editor = prefs(context).edit()
        prefs(context).all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.commit()
    }
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

    fun estimatedRemainingMillis(
        context: Context,
        key: String,
        mediaType: String,
        category: String,
        currentPage: Int,
        totalPages: Int?
    ): Long {
        if (currentPage <= 0 || totalPages == null || totalPages <= currentPage) return 0
        val prefix = "$mediaType:$category:"
        val samples = events(context, key)
            .asSequence()
            .filter { it.outcome == "Stored" && it.key.startsWith(prefix) }
            .mapNotNull { event -> event.key.substringAfterLast(':').toIntOrNull()?.let { it to event.time } }
            .distinctBy { it.first }
            .sortedBy { it.first }
            .zipWithNext { previous, next ->
                if (next.first == previous.first + 1) next.second - previous.second else 0L
            }
            .filter { it in 250L..300_000L }
            .toList()
            .takeLast(20)
            .sorted()
        if (samples.size < 2) return 0
        val medianMillis = samples[samples.size / 2]
        return (totalPages - currentPage).toLong() * medianMillis
    }
}

internal fun formatRemainingTime(millis: Long): String? {
    if (millis <= 0) return null
    val totalMinutes = (millis + 59_999L) / 60_000L
    val days = totalMinutes / (24 * 60)
    val hours = totalMinutes % (24 * 60) / 60
    val minutes = totalMinutes % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
