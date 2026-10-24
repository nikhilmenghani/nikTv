package com.nikhil.niktv.data

import com.nikhil.niktv.model.CatalogType
import org.junit.Assert.*
import org.junit.Test

class ProviderTitlePresentationTest {
    @Test fun wioMovieAndSeriesDecorations() {
        for ((raw, wanted) in listOf(
            "Whalefall (English) (Cam)" to "Whalefall",
            "Friends (English-Comedy Central)" to "Friends",
            "Show (Turkish-Urdu-Dub)" to "Show",
            "Film (English-Subtitle) (4K)" to "Film",
            "HI | Film [FHD]" to "Film",
            "Taarak Mehta Ka Ooltah Chashmah - HINDI | TV SHOWS" to "Taarak Mehta Ka Ooltah Chashmah")) {
            assertEquals(wanted, providerTmdbTitle(raw))
            assertEquals(wanted, providerTmdbTitle(providerTmdbTitle(raw)))
        }
        assertEquals("Kaun Banega Crorepati", providerSeriesTmdbTitle("Sony Set Hindi : Kaun Banega Crorepati"))
        assertEquals("Anupama", providerSeriesTmdbTitle("Star Plus Hindi : Anupama (Hindi)"))
    }
    @Test fun realTitlesAreNotMetadata() {
        for (raw in listOf("1917", "2001: A Space Odyssey", "V", "English", "Cam", "It: Chapter Two",
            "Hindi Medium", "The Office (US)", "Show (Nikosh Chhaya)", "Spider-Man: Brand New Day", "Unknown : Show")) {
            assertEquals(raw, providerTmdbTitle(raw))
            assertEquals(raw, providerSeriesTmdbTitle(raw))
        }
    }
    @Test fun yearHintsArePreservedForMatchingButNotTheTile() {
        assertEquals("Dune (2021)", providerTmdbTitle("Dune (2021) (English)"))
        assertEquals("Dune", providerDisplayTitle("Dune (2021) (English)", CatalogType.MOVIES))
        assertEquals("BBC NEWS | UK", providerDisplayTitle("BBC NEWS | UK", CatalogType.LIVE_TV))
    }
    @Test fun liveNamesKeepTheirOwnIdentity() {
        assertEquals("English Vinglish", com.nikhil.niktv.ui.liveTvTilePresentation("English Vinglish", "ENGLISH | Movies", false).title)
        assertEquals("Tom Cruise", com.nikhil.niktv.ui.liveTvTilePresentation("ACTOR | Tom Cruise (4K)", "ENGLISH | MOVIES", false).title)
        assertEquals("NETFLIX", com.nikhil.niktv.ui.liveTvTilePresentation("ENGLISH NETFLIX MOVIES (4K)", "ENGLISH | Movies", false).title)
    }
    @Test fun cacheIsIndependentOfCallOrderAndMediaType() {
        val raw = "Sony SAB Hindi : Show (Hindi)"
        assertEquals("Sony SAB Hindi : Show", providerTmdbTitle(raw))
        assertEquals("Show", providerSeriesTmdbTitle(raw))
        repeat(2000) { providerTmdbTitle("Film $it (English)") }
        assertEquals("Show", providerSeriesTmdbTitle(raw))
    }
}
