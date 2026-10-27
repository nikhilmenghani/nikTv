package com.nikhil.niktv.data

import android.content.Context
import android.os.Build
import com.nikhil.niktv.BuildConfig
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Serializable
internal data class DeviceBackupDevice(val displayName: String, val id: String)
@Serializable
internal data class DeviceBackupMetadata(val device: DeviceBackupDevice, val backupNumber: Long,
    val backupId: String, val exportedAt: String, val appVersion: String = BuildConfig.VERSION_NAME,
    val settingsVersion: Int = 2)

/** This identity belongs to the physical install and is never restored from another device. */
internal object DeviceBackupIdentity {
    private fun prefs(context: Context) = context.getSharedPreferences("device_backup_identity", Context.MODE_PRIVATE)
    @Synchronized fun id(context: Context): String = prefs(context).getString("id", null) ?: UUID.randomUUID().toString()
        .replace("-", "").take(8).also { check(prefs(context).edit().putString("id", it).commit()) }
    fun name(context: Context) = prefs(context).getString("name", null)?.takeIf { it.isNotBlank() }
        ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    fun rename(context: Context, name: String) { prefs(context).edit().putString("name", name.trim().take(80)).apply() }
    fun nextNumber(context: Context) = prefs(context).getLong("sequence", 0L) + 1
    @Synchronized fun create(context: Context, now: Long): DeviceBackupMetadata {
        val number = nextNumber(context)
        check(prefs(context).edit().putLong("sequence", number).commit())
        return DeviceBackupMetadata(DeviceBackupDevice(name(context), id(context)), number, UUID.randomUUID().toString(),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date(now)))
    }
}
