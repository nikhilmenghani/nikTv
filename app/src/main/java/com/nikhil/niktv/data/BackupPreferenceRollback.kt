package com.nikhil.niktv.data

import android.content.Context

/** Only stores written by setup import are eligible for compensation. */
internal class BackupPreferenceRollback private constructor(private val context: Context,
    private val snapshots: Map<String, Map<String, Any?>>) {
    constructor(context: Context, account: String?) : this(context, capture(context, account))
    private val names get() = snapshots.keys
    fun journalSnapshot() = snapshots.mapValues { (_, values) -> values.mapValues { (_, value) -> encodeRestorePreference(requireNotNull(value)) } }
    companion object {
        private fun capture(context: Context, account: String?): Map<String, Map<String, Any?>> {
            val remoteAccount = context.getSharedPreferences("github_configuration_accounts", 0).getString("active", null)
            val names = DevicePreferencesBackup.stores + listOf(
                remoteAccount?.let { "github_remote_credentials_account_$it" } ?: "github_remote_credentials",
                account?.let { "github_backup_config_account_$it" } ?: "github_backup_config",
                "niktv_legacy_assignment")
            return names.associateWith { context.getSharedPreferences(it, 0).all.toMap() }
        }
        fun fromJournal(context: Context, snapshot: Map<String, Map<String, DevicePreferenceValue>>): BackupPreferenceRollback {
            require(snapshot.keys.all { it in DevicePreferencesBackup.stores || it == "niktv_legacy_assignment" ||
                it.matches(Regex("github_(?:remote_credentials|backup_config)(?:_account_[0-9]+)?")) })
            return BackupPreferenceRollback(context, snapshot.mapValues { (_, values) -> values.mapValues { (_, value) -> decodeRestorePreference(value) } })
        }
    }
    fun restore() {
        names.forEach { name ->
            val prefs = context.getSharedPreferences(name, 0)
            val original = snapshots.getValue(name)
            val editor = prefs.edit()
            (prefs.all.keys - original.keys).forEach(editor::remove)
            original.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            } }
            check(editor.commit()) { "Could not roll back $name" }
        }
    }
}
