package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsNavigationTest {
    @Test
    fun destinationOrderMatchesRedesign() {
        assertEquals(
            listOf(
                "General",
                "Profiles",
                "Data & sync",
                "System"
            ),
            SettingsDestination.entries.map { it.title }
        )
    }

    @Test
    fun currentSectionsMapToExpectedDestinations() {
        val expected = mapOf(
            "Device & display" to SettingsDestination.GENERAL,
            "Playback" to SettingsDestination.GENERAL,
            "Storage & refresh" to SettingsDestination.GENERAL,
            "Profiles" to SettingsDestination.PROFILES,
            "Connection" to SettingsDestination.PROFILES,
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
}
