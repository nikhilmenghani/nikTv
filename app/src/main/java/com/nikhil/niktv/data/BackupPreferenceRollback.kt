package com.nikhil.niktv.data

import android.content.Context

/** Only stores written by setup import are eligible for compensation. */
internal class BackupPreferenceRollback(private val context: Context, account: String?) {
    private val remoteAccount = context.getSharedPreferences("github_configuration_accounts", 0).getString("active", null)
    private val names = DevicePreferencesBackup.stores + listOf(
        remoteAccount?.let { "github_remote_credentials_account_$it" } ?: "github_remote_credentials",
        account?.let { "github_backup_config_account_$it" } ?: "github_backup_config",
        "niktv_legacy_assignment")
    private val snapshots = names.associateWith { context.getSharedPreferences(it, 0).all.toMap() }
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
