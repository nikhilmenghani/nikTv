package com.nikhil.niktv.data

import android.content.Context
import android.os.Build
import com.nikhil.niktv.BuildConfig
import java.util.Locale

internal const val DEFAULT_SYNC_COMMIT_MESSAGE = "Sync NikTV account data"
internal const val MAX_SYNC_COMMIT_MESSAGE_LENGTH = 220

/** Public device/software labels and an installation-local random ID only. */
internal data class SyncDeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val appVersion: String,
    val installationId: String,
)

private val unsafeCommitField = Regex(
    "(?i)(?:[a-z][a-z0-9+.-]*://|www\\.|https?:|(?:token|password|authorization|serial(?:number)?|android[ _-]?id|mac)\\s*[:=]|\\b(?:github_pat_|gh[pousr]_)[a-z0-9_]+|\\bbearer\\s+\\S+|(?:[a-f0-9]{2}[:-]){5}[a-f0-9]{2})"
)
private val commitControls = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]")
private val commitWhitespace = Regex("\\s+")
private fun unsafeField(value: String): Boolean = unsafeCommitField.containsMatchIn(value) ||
    unsafeCommitField.containsMatchIn(value.replace(commitControls, ""))

private fun devicePart(value: String, limit: Int, fallback: String): String {
    if (unsafeField(value)) return fallback
    return value.replace(commitControls, " ")
        .filter { it.isLetterOrDigit() || it in " ._-/()+" }
        .replace(commitWhitespace, " ").trim().take(limit).trim().ifBlank { fallback }
}

internal fun syncCommitMessage(device: SyncDeviceInfo): String {
    val manufacturer = devicePart(device.manufacturer, 28, "")
    val model = devicePart(device.model, 52, "Android device")
    val label = if (manufacturer.isBlank() || model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model"
    val android = devicePart(device.androidVersion, 20, "unknown")
    val app = devicePart(device.appVersion, 32, "unknown")
    val installId = device.installationId.trim().takeIf { it.matches(Regex("[a-fA-F0-9]{8}")) }
        ?.lowercase(Locale.ROOT) ?: "unknown"
    return sanitizeSyncCommitMessage("$DEFAULT_SYNC_COMMIT_MESSAGE · $label · Android $android · NikTV v$app · device $installId")
}

/** Defense in depth for transport callers: no URL, secret-shaped field or multiline metadata. */
internal fun sanitizeSyncCommitMessage(value: String): String {
    if (unsafeField(value)) return DEFAULT_SYNC_COMMIT_MESSAGE
    return value.replace(commitControls, " ").replace(commitWhitespace, " ").trim()
        .take(MAX_SYNC_COMMIT_MESSAGE_LENGTH).trim().ifBlank { DEFAULT_SYNC_COMMIT_MESSAGE }
}

/** Called from the account manager's IO operations. Custom names can contain a room/location,
 * so commits use the manufacturer's model label rather than user-entered device labels. */
internal fun currentDeviceSyncCommitMessage(context: Context): String = syncCommitMessage(SyncDeviceInfo(
    manufacturer = Build.MANUFACTURER.orEmpty(),
    model = Build.MODEL.orEmpty(),
    androidVersion = Build.VERSION.RELEASE.orEmpty(),
    appVersion = BuildConfig.VERSION_NAME,
    installationId = DeviceBackupIdentity.id(context),
))
