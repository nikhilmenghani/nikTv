package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsNavigationTest {
    @Test
    fun destinationOrderMatchesRedesign() {
        assertEquals(
            listOf(
                "Playback",
                "Display & controls",
                "Profiles & access",
                "Library & backup",
                "App & updates"
            ),
            SettingsDestination.entries.map { it.title }
        )
    }

    @Test
    fun currentSectionsMapToExpectedDestinations() {
        val expected = mapOf(
            "Device & display" to SettingsDestination.GENERAL,
            "Playback" to SettingsDestination.PLAYBACK,
            "Compatibility" to SettingsDestination.PLAYBACK,
            "Storage & refresh" to SettingsDestination.CATALOG,
            "Profiles" to SettingsDestination.PROFILES,
            "Connection" to SettingsDestination.PROFILES,
            "Private configuration" to SettingsDestination.PROFILES,
            "Pair devices" to SettingsDestination.PROFILES,
            "Backup and restore" to SettingsDestination.CATALOG,
            "Data and sync" to SettingsDestination.CATALOG,
            "App updates" to SettingsDestination.SYSTEM,
            "Advanced" to SettingsDestination.SYSTEM,
            "Data & reset" to SettingsDestination.SYSTEM
        )

        expected.forEach { (title, destination) ->
            assertEquals(destination, settingsDestinationFor(title))
        }
    }

    @Test
    fun unknownSectionsFailSafeToSystem() {
        assertEquals(
            SettingsDestination.SYSTEM,
            settingsDestinationFor("Future advanced setting")
        )
    }

    @Test
    fun nestedPagesStayInTheirCategoryAndHaveAnAcyclicBackPath() {
        SettingsPage.entries.forEach { page ->
            val visited = mutableSetOf<SettingsPage>()
            var current: SettingsPage? = page
            while (current != null) {
                org.junit.Assert.assertTrue("Back path loops at $current", visited.add(current))
                assertEquals(page.destination, current.destination)
                if (current.parent == null) assertEquals(page.destination.rootPage(), current)
                current = current.parent
            }
        }
    }

    @Test
    fun catalogAndBackupOperationsHaveSeparateRoutes() {
        assertEquals(SettingsPage.CATALOG_UPDATES, compactSettingsPageFor("Data and sync"))
        assertEquals(SettingsPage.BACKUP_DESTINATION, compactSettingsPageFor("Backup destination"))
        assertEquals(SettingsPage.CATALOG_BACKUP, compactSettingsPageFor("Catalog backup"))
        assertEquals(SettingsPage.DEVICE_BACKUP, compactSettingsPageFor("Device backup"))
        assertEquals(SettingsPage.RESET, compactSettingsPageFor("Data & reset"))
    }

    @Test
    fun closingSettingsRestoresItsOriginAndRetainsChangedPreferences() {
        listOf(
            NikTvState(homeOpen = true),
            NikTvState(homeOpen = false, favoritesOpen = true),
            NikTvState(homeOpen = false, searchOpen = true),
            NikTvState(homeOpen = false, offlineDownloadsOpen = true)
        ).forEach { origin ->
            val saved = SettingsReturnSurface.capture(origin)
            val edited = origin.copy(settingsOpen = true, homeOpen = false, favoritesOpen = false,
                searchOpen = false, offlineDownloadsOpen = false, cacheIntervalMinutes = 360)
            val restored = saved.restore(edited)
            assertEquals(origin.homeOpen, restored.homeOpen)
            assertEquals(origin.favoritesOpen, restored.favoritesOpen)
            assertEquals(origin.searchOpen, restored.searchOpen)
            assertEquals(origin.offlineDownloadsOpen, restored.offlineDownloadsOpen)
            assertEquals(360, restored.cacheIntervalMinutes)
            org.junit.Assert.assertFalse(restored.settingsOpen)
        }
    }
}
