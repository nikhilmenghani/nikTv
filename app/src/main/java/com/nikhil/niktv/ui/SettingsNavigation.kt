package com.nikhil.niktv.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.vector.ImageVector

internal enum class SettingsDestination(
    val title: String,
    val subtitle: String
) {
    GENERAL(
        "General",
        "Device, playback and refresh preferences"
    ),
    PROFILES(
        "Profiles",
        "Accounts, portals and session access"
    ),
    CATALOG(
        "Catalog & backup",
        "Local database, scan progress, backup and restore"
    ),
    SYSTEM(
        "System",
        "Updates, advanced settings and app data"
    )
}

internal fun SettingsDestination.icon(): ImageVector = when (this) {
    SettingsDestination.GENERAL -> Icons.Default.Tune
    SettingsDestination.PROFILES -> Icons.Default.ManageAccounts
    SettingsDestination.CATALOG -> Icons.Default.Storage
    SettingsDestination.SYSTEM -> Icons.Default.Settings
}

internal fun settingsDestinationFor(sectionTitle: String): SettingsDestination =
    when (sectionTitle) {
        "Device & display",
        "Playback",
        "Storage & refresh" -> SettingsDestination.GENERAL

        "Profiles",
        "Connection" -> SettingsDestination.PROFILES

        "Backup and restore" -> SettingsDestination.CATALOG

        "App updates",
        "Advanced",
        "Data & reset" -> SettingsDestination.SYSTEM

        else -> SettingsDestination.SYSTEM
    }
