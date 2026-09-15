package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsNavigationTest {
    @Test
    fun destinationOrderMatchesRedesign() {
        assertEquals(
            listOf(
                "Appearance",
                "Playback",
                "Content",
                "Profiles",
                "System"
            ),
            SettingsDestination.entries.map { it.title }
        )
    }

    @Test
    fun currentSectionsMapToExpectedDestinations() {
        val expected = mapOf(
            "Mobile controls" to SettingsDestination.APPEARANCE,
            "Picture and video appearance" to SettingsDestination.APPEARANCE,
            "Display and screen" to SettingsDestination.APPEARANCE,
            "Default media player" to SettingsDestination.PLAYBACK,
            "Player controls" to SettingsDestination.PLAYBACK,
            "Series" to SettingsDestination.PLAYBACK,
            "Category Filters" to SettingsDestination.CONTENT,
            "Catalog cache" to SettingsDestination.CONTENT,
            "Profiles" to SettingsDestination.PROFILES,
            "Connection" to SettingsDestination.PROFILES,
            "Connection actions" to SettingsDestination.PROFILES,
            "Metadata and subtitle diagnostics" to SettingsDestination.SYSTEM,
            "Backup and restore" to SettingsDestination.SYSTEM,
            "Danger zone" to SettingsDestination.SYSTEM,
            "App updates" to SettingsDestination.SYSTEM
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
