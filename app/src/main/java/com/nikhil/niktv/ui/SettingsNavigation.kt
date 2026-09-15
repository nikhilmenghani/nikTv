package com.nikhil.niktv.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector

internal enum class SettingsDestination(
    val title: String,
    val subtitle: String
) {
    APPEARANCE(
        "Appearance",
        "Display, screen and device presentation"
    ),
    PLAYBACK(
        "Playback",
        "Player engine, controls and series defaults"
    ),
    CONTENT(
        "Content",
        "Category visibility and catalog refresh behavior"
    ),
    PROFILES(
        "Profiles",
        "Accounts, portals and session access"
    ),
    SYSTEM(
        "System",
        "Backup, updates, diagnostics and app data"
    )
}

internal fun SettingsDestination.icon(): ImageVector = when (this) {
    SettingsDestination.APPEARANCE -> Icons.Default.Palette
    SettingsDestination.PLAYBACK -> Icons.Default.PlayCircle
    SettingsDestination.CONTENT -> Icons.Default.VideoLibrary
    SettingsDestination.PROFILES -> Icons.Default.ManageAccounts
    SettingsDestination.SYSTEM -> Icons.Default.Settings
}

internal fun settingsDestinationFor(sectionTitle: String): SettingsDestination =
    when (sectionTitle) {
        "Mobile controls",
        "Display and screen" -> SettingsDestination.APPEARANCE

        "Default media player",
        "Player controls",
        "Series" -> SettingsDestination.PLAYBACK

        "Category Filters",
        "Catalog cache" -> SettingsDestination.CONTENT

        "Profiles",
        "Connection",
        "Connection actions" -> SettingsDestination.PROFILES

        "Metadata and subtitle diagnostics",
        "Backup and restore",
        "Danger zone",
        "App updates" -> SettingsDestination.SYSTEM

        else -> SettingsDestination.SYSTEM
    }
