package com.nikhil.niktv.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.ui.graphics.vector.ImageVector

internal enum class SettingsDestination(
    val title: String,
    val subtitle: String
) {
    PLAYBACK("Playback", "Player and series preferences"),
    GENERAL(
        "Display & controls",
        "Brightness, orientation and screen awake"
    ),
    PROFILES(
        "Profiles & access",
        "Accounts, portals and session access"
    ),
    CATALOG(
        "Library & backup",
        "Catalog, scan progress, backup and restore"
    ),
    SYSTEM(
        "App & updates",
        "Updates, advanced settings and app data"
    )
}

internal fun SettingsDestination.icon(): ImageVector = when (this) {
    SettingsDestination.PLAYBACK -> Icons.Default.PlayCircle
    SettingsDestination.GENERAL -> Icons.Default.Tune
    SettingsDestination.PROFILES -> Icons.Default.ManageAccounts
    SettingsDestination.CATALOG -> Icons.Default.Storage
    SettingsDestination.SYSTEM -> Icons.Default.Settings
}

internal fun settingsDestinationFor(sectionTitle: String): SettingsDestination =
    when (sectionTitle) {
        "Device & display" -> SettingsDestination.GENERAL
        "Playback", "Compatibility" -> SettingsDestination.PLAYBACK

        "Profiles",
        "Connection",
        "Parental controls",
        "Private configuration",
        "Pair devices" -> SettingsDestination.PROFILES

        "Backup and restore",
        "Storage & refresh",
        "Catalog backup",
        "Device backup",
        "Backup destination",
        "Data and sync" -> SettingsDestination.CATALOG

        "App updates",
        "Update preferences",
        "Advanced",
        "Data & reset" -> SettingsDestination.SYSTEM

        else -> SettingsDestination.SYSTEM
    }

/** Stable route IDs are independent of visible section headings. */
internal enum class SettingsPage(val destination: SettingsDestination, val title: String, val parent: SettingsPage? = null) {
    PLAYBACK(SettingsDestination.PLAYBACK, "Playback"),
    DISPLAY(SettingsDestination.GENERAL, "Display & controls"),
    PROFILES(SettingsDestination.PROFILES, "Profiles & access"),
    LIBRARY(SettingsDestination.CATALOG, "Library & backup"),
    APP(SettingsDestination.SYSTEM, "App & updates"),
    COMPATIBILITY(SettingsDestination.PLAYBACK, "Compatibility", PLAYBACK),
    MANAGE_PROFILES(SettingsDestination.PROFILES, "Manage profiles", PROFILES),
    PROFILE_DETAILS(SettingsDestination.PROFILES, "Profile details", MANAGE_PROFILES),
    CONNECTION(SettingsDestination.PROFILES, "Connection", PROFILES),
    PARENTAL(SettingsDestination.PROFILES, "Parental controls", PROFILES),
    PRIVATE_CONFIGURATION(SettingsDestination.PROFILES, "Private configuration", PROFILES),
    PAIRING(SettingsDestination.PROFILES, "Pair a device", PROFILES),
    CATALOG_UPDATES(SettingsDestination.CATALOG, "Catalog updates", LIBRARY),
    CATALOG_MEDIA_TYPES(SettingsDestination.CATALOG, "Update by media type", CATALOG_UPDATES),
    CATALOG_TOOLS(SettingsDestination.CATALOG, "Catalog tools", CATALOG_UPDATES),
    BROWSE_REFRESH(SettingsDestination.CATALOG, "Browse refresh", LIBRARY),
    BACKUPS(SettingsDestination.CATALOG, "Back up & restore", LIBRARY),
    CATALOG_BACKUP(SettingsDestination.CATALOG, "Catalog backup", BACKUPS),
    CATALOG_TRANSFER_PROGRESS(SettingsDestination.CATALOG, "Transfer progress", CATALOG_BACKUP),
    DEVICE_BACKUP(SettingsDestination.CATALOG, "Device backup", BACKUPS),
    BACKUP_DESTINATION(SettingsDestination.CATALOG, "Backup destination", BACKUPS),
    UPDATE_PREFERENCES(SettingsDestination.SYSTEM, "Update preferences", APP),
    RESET(SettingsDestination.SYSTEM, "Reset app", APP)
}

internal fun SettingsDestination.rootPage(): SettingsPage = when (this) {
    SettingsDestination.PLAYBACK -> SettingsPage.PLAYBACK
    SettingsDestination.GENERAL -> SettingsPage.DISPLAY
    SettingsDestination.PROFILES -> SettingsPage.PROFILES
    SettingsDestination.CATALOG -> SettingsPage.LIBRARY
    SettingsDestination.SYSTEM -> SettingsPage.APP
}

internal fun compactSettingsPageFor(title: String): SettingsPage = when (title) {
    "Device & display" -> SettingsPage.DISPLAY
    "Playback" -> SettingsPage.PLAYBACK
    "Compatibility" -> SettingsPage.COMPATIBILITY
    "Storage & refresh" -> SettingsPage.BROWSE_REFRESH
    "Profiles" -> SettingsPage.MANAGE_PROFILES
    "Connection" -> SettingsPage.CONNECTION
    "Parental controls" -> SettingsPage.PARENTAL
    "Private configuration" -> SettingsPage.PRIVATE_CONFIGURATION
    "Pair devices" -> SettingsPage.PAIRING
    "Data and sync" -> SettingsPage.CATALOG_UPDATES
    "Catalog backup" -> SettingsPage.CATALOG_BACKUP
    "Device backup" -> SettingsPage.DEVICE_BACKUP
    "Backup destination" -> SettingsPage.BACKUP_DESTINATION
    "Update preferences" -> SettingsPage.UPDATE_PREFERENCES
    "Data & reset" -> SettingsPage.RESET
    else -> SettingsPage.APP
}

internal data class SettingsReturnSurface(
    val home: Boolean, val favorites: Boolean, val search: Boolean, val downloads: Boolean
) {
    fun restore(state: NikTvState) = state.copy(settingsOpen = false,
        homeOpen = home, favoritesOpen = favorites, searchOpen = search, offlineDownloadsOpen = downloads)

    companion object {
        fun capture(state: NikTvState) = SettingsReturnSurface(state.homeOpen, state.favoritesOpen,
            state.searchOpen, state.offlineDownloadsOpen)
    }
}
