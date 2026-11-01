package com.nikhil.niktv.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class DevicePreferenceValue(val type: String, val value: String)

/** Portable user configuration only: no cache, download IDs, device identity or unlock session. */
internal object DevicePreferencesBackup {
    val stores = listOf("discovery_trial_v1", "discovery_experience", "browse_collections", "iptv_pin_preferences",
        "niktv_ui_preferences", "mobile_ui_preferences", "video_appearance_profiles", "player_video_sizing",
        "niktv_playback_ui", "player_media_browser", "app_display_preferences", "device_audio_preferences",
        "player_intro_timings", "parental_controls",
        "app_update_download", "catalog_device", "catalog_scans", "on_screen_dpad", "niktv_pairing_relay")

    private fun included(file: String, key: String) = when (file) {
        "app_update_download" -> key in setOf("enforce_updates", "startup_check_enabled", "update_package")
        "catalog_device" -> key == "prefer_local"
        "catalog_scans" -> key.startsWith("hours:")
        "parental_controls" -> !key.startsWith("unlock_")
        else -> true
    }

    fun capture(context: Context): Map<String, Map<String, DevicePreferenceValue>> = stores.associateWith { file ->
        context.getSharedPreferences(file, Context.MODE_PRIVATE).all.toSortedMap().filterKeys { included(file, it) }
            .mapValues { (_, value) -> when (value) {
                is String -> DevicePreferenceValue("string", value)
                is Int -> DevicePreferenceValue("int", value.toString())
                is Long -> DevicePreferenceValue("long", value.toString())
                is Float -> DevicePreferenceValue("float", value.toString())
                is Boolean -> DevicePreferenceValue("boolean", value.toString())
                is Set<*> -> DevicePreferenceValue("strings", Json.encodeToString(value.filterIsInstance<String>().sorted()))
                else -> error("Unsupported preference value")
            } }
    }

    fun validate(snapshot: Map<String, Map<String, DevicePreferenceValue>>) {
        snapshot.filterKeys { it in stores }.forEach { (file, values) ->
            values.filterKeys { included(file, it) }.values.forEach { value ->
                when (value.type) {
                    "string" -> Unit
                    "int" -> value.value.toInt()
                    "long" -> value.value.toLong()
                    "float" -> require(value.value.toFloat().isFinite())
                    "boolean" -> require(value.value in setOf("true", "false"))
                    "strings" -> Json.decodeFromString<List<String>>(value.value)
                    else -> error("Unsupported preference type ${value.type}")
                }
            }
        }
    }

    fun restore(context: Context, snapshot: Map<String, Map<String, DevicePreferenceValue>>) {
        validate(snapshot)
        stores.forEach { file ->
            val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            prefs.all.keys.filter { included(file, it) }.forEach(editor::remove)
            snapshot[file].orEmpty().filterKeys { included(file, it) }.forEach { (key, value) ->
                when (value.type) {
                    "string" -> editor.putString(key, value.value)
                    "int" -> editor.putInt(key, value.value.toInt())
                    "long" -> editor.putLong(key, value.value.toLong())
                    "float" -> editor.putFloat(key, value.value.toFloat())
                    "boolean" -> editor.putBoolean(key, value.value.toBooleanStrict())
                    "strings" -> editor.putStringSet(key, Json.decodeFromString<List<String>>(value.value).toSet())
                }
            }
            if (file == "parental_controls") editor.remove("unlock_started").remove("unlock_until").remove("unlock_boot")
            check(editor.commit()) { "Could not restore $file preferences" }
        }
    }
}

@Serializable
internal data class RemoteConfigurationBackup(val token: String, val owner: String, val repository: String,
    val path: String, val values: Map<String, String>, val administrator: Boolean)

@Serializable
internal data class BackupConnectionConfiguration(val username: String, val repository: String, val token: String,
    val password: String, val mode: String, val intervalHours: Int)
