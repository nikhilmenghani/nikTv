package com.nikhil.niktv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderSeriesTitleTest {
    @Test fun broadcasterLabelsAreRemoved() {
        assertEquals("Anupama", providerSeriesTmdbTitle("Star Plus Hindi : Anupama"))
        assertEquals("Anupama", providerSeriesTmdbTitle("HI | Star Plus Hindi : Anupama"))
        assertEquals("Taarak Mehta Ka Ooltah Chashmah", providerSeriesTmdbTitle("Sony SAB Hindi : Taarak Mehta Ka Ooltah Chashmah"))
    }
    @Test fun legitimateTitlesAndEpisodeNamesRemainIntact() {
        for (title in listOf("Star Trek: Discovery", "Friends", "Preranas Tears Over Pranshi", "1883", "Unknown Channel : Example")) {
            assertEquals(title, providerSeriesTmdbTitle(title))
        }
    }
}
