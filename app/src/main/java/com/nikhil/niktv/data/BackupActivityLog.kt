package com.nikhil.niktv.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class BackupActivityEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val operation: String,
    val status: String,
    val detail: String = ""
)

/** Device-local event history. Never include passwords, URLs, tokens or backup contents. */
object BackupActivityLog {
    private const val KEY = "events"
    private const val LIMIT = 100
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("backup_activity", Context.MODE_PRIVATE)

    fun read(context: Context): List<BackupActivityEntry> = read(prefs(context))
    private fun read(preferences: SharedPreferences): List<BackupActivityEntry> =
        runCatching { json.decodeFromString<List<BackupActivityEntry>>(preferences.getString(KEY, "[]")!!) }.getOrDefault(emptyList())

    @Synchronized
    fun record(context: Context, operation: String, status: String, detail: String = "") {
        val preferences = prefs(context)
        val entries = (listOf(BackupActivityEntry(operation = operation, status = status, detail = detail)) + read(preferences)).take(LIMIT)
        preferences.edit().putString(KEY, json.encodeToString(entries)).apply()
    }

    fun observe(context: Context) = callbackFlow {
        val preferences = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY) trySend(read(preferences))
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(read(preferences))
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().flowOn(Dispatchers.IO)

    suspend fun <T> track(
        context: Context, operation: String,
        success: (T) -> String = { "Operation completed." },
        block: suspend () -> T
    ): T {
        record(context, operation, "Started")
        try {
            return block().also { record(context, operation, "Completed", success(it)) }
        } catch (cancelled: CancellationException) {
            record(context, operation, "Cancelled", "Stopped before completion; any completed catalog imports are kept.")
            throw cancelled
        } catch (error: Exception) {
            record(context, operation, "Failed", failureSummary(error))
            throw error
        }
    }

    internal fun failureSummary(error: Exception): String {
        // Error messages from providers/HTTP clients can contain account URLs. Log only safe categories.
        val githubCode = Regex("GitHub (\\d{3})").find(error.message.orEmpty())?.groupValues?.get(1)
        return when {
            githubCode != null -> "GitHub returned HTTP $githubCode. Check access and connection settings."
            error is IllegalArgumentException -> "Backup validation failed. Check the password, file and backup settings."
            error is java.io.IOException -> "Could not read, write or transfer the backup. Check storage and network access."
            else -> "Operation could not finish. Check backup settings and try again."
        }
    }
}
