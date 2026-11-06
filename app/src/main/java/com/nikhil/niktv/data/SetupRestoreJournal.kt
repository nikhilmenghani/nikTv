package com.nikhil.niktv.data

import android.content.Context
import android.util.AtomicFile
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class SetupRestoreSnapshot(val id: String, val keys: Set<String>,
    val preferences: Map<String, DevicePreferenceValue>,
    val shared: Map<String, Map<String, DevicePreferenceValue>>, val version: Int = 1)

/** App-private, excluded from Android backup, and fsynced before any import writes. */
internal class SetupRestoreJournal(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "setup-restore-journal.json"))
    fun read(): SetupRestoreSnapshot? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val bytes = file.openRead().use { input ->
            require(input.channel.size() <= MAX_BYTES) { "Restore recovery journal is too large." }
            input.readBytes()
        }
        return Json.decodeFromString<SetupRestoreSnapshot>(bytes.toString(Charsets.UTF_8)).also(::validate)
    }
    private fun validate(snapshot: SetupRestoreSnapshot) {
        require(snapshot.version == 1)
        java.util.UUID.fromString(snapshot.id)
        require(snapshot.preferences.keys.all { it in snapshot.keys })
        (snapshot.preferences.values + snapshot.shared.values.flatMap { it.values }).forEach(::decodeRestorePreference)
    }

    fun write(snapshot: SetupRestoreSnapshot) {
        validate(snapshot)
        check(read() == null) { "Finish the previous restore recovery before importing again." }
        val bytes = Json.encodeToString(snapshot).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Restore recovery snapshot exceeds 16 MiB. Nothing was changed." }
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (failure: Throwable) { file.failWrite(stream); throw failure }
    }
    fun clear() { file.delete() }
    companion object {
        private const val MAX_BYTES = 16 * 1024 * 1024
        const val MARKER_ACCOUNT = "__device_setup_restore__"
        const val MARKER_NAME = "committed"
    }
}

internal fun encodeRestorePreference(value: Any): DevicePreferenceValue = when (value) {
    is String -> DevicePreferenceValue("string", value)
    is Int -> DevicePreferenceValue("int", value.toString())
    is Long -> DevicePreferenceValue("long", value.toString())
    is Float -> DevicePreferenceValue("float", value.toString())
    is Boolean -> DevicePreferenceValue("boolean", value.toString())
    is Set<*> -> DevicePreferenceValue("strings", Json.encodeToString(value.filterIsInstance<String>()))
    else -> error("Unsupported restore preference")
}
internal fun decodeRestorePreference(value: DevicePreferenceValue): Any = when (value.type) {
    "string" -> value.value
    "int" -> value.value.toInt()
    "long" -> value.value.toLong()
    "float" -> value.value.toFloat().also { require(it.isFinite()) }
    "boolean" -> value.value.toBooleanStrict()
    "strings" -> Json.decodeFromString<List<String>>(value.value).toSet()
    else -> error("Unsupported restore preference")
}
internal fun MutablePreferences.putRestoreValue(name: String, entry: DevicePreferenceValue) {
    when (val value = decodeRestorePreference(entry)) {
        is String -> this[stringPreferencesKey(name)] = value
        is Int -> this[intPreferencesKey(name)] = value
        is Long -> this[longPreferencesKey(name)] = value
        is Float -> this[floatPreferencesKey(name)] = value
        is Boolean -> this[booleanPreferencesKey(name)] = value
        is Set<*> -> this[stringSetPreferencesKey(name)] = value.filterIsInstance<String>().toSet()
    }
}

internal suspend fun awaitSetupRecovery(context: Context) {
    (context.applicationContext as? com.nikhil.niktv.NikTvApplication)?.setupReady?.await()
}
/** Reading or updating profile preferences cannot race process-restart compensation. */
internal class RestoreReadyPreferences(private val context: Context, private val delegate: DataStore<Preferences>,
    private val awaitReady: suspend () -> Unit = { awaitSetupRecovery(context) }) : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { awaitReady(); emitAll(delegate.data) }
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        awaitReady()
        return delegate.updateData(transform)
    }
}
