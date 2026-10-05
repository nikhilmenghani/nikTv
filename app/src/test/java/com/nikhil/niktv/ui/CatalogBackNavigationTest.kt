package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogBackNavigationTest {
    @Test fun `movie match closes before the collection or other underlying panes`() {
        assertEquals(CatalogBackAction.CLOSE_MOVIE_MATCHES, catalogBackAction(
            movieMatchesOpen = true, seriesMatchesOpen = true, downloadsOpen = true,
            settingsOpen = true, searchOpen = true, favoritesOpen = true,
            seriesOpen = true, modernSectionOpen = true, homeOpen = true
        ))
    }

    @Test fun `series match closes before the underlying series or collection`() {
        assertEquals(CatalogBackAction.CLOSE_SERIES_MATCHES, catalogBackAction(
            seriesMatchesOpen = true, searchOpen = true, favoritesOpen = true,
            seriesOpen = true, modernSectionOpen = true
        ))
    }

    @Test fun `downloads opened over home close instead of exiting`() {
        assertEquals(CatalogBackAction.CLOSE_DOWNLOADS, catalogBackAction(
            downloadsOpen = true, homeOpen = true
        ))
    }

    @Test fun `downloads follow main pane precedence over retained destination flags`() {
        assertEquals(CatalogBackAction.CLOSE_DOWNLOADS, catalogBackAction(
            downloadsOpen = true, settingsOpen = true, searchOpen = true,
            favoritesOpen = true, seriesOpen = true, modernSectionOpen = true
        ))
    }

    @Test fun `settings opened from search close before search`() {
        assertEquals(CatalogBackAction.CLOSE_SETTINGS, catalogBackAction(
            settingsOpen = true, searchOpen = true, favoritesOpen = true,
            seriesOpen = true, modernSectionOpen = true
        ))
    }

    @Test fun `search closes before the retained series details`() {
        assertEquals(CatalogBackAction.CLOSE_SEARCH, catalogBackAction(
            searchOpen = true, favoritesOpen = true, seriesOpen = true,
            modernSectionOpen = true
        ))
    }

    @Test fun `favorites close before their underlying destination`() {
        assertEquals(CatalogBackAction.CLOSE_FAVORITES, catalogBackAction(
            favoritesOpen = true, seriesOpen = true, modernSectionOpen = true,
            homeOpen = true
        ))
    }

    @Test fun `series details return to collection before the collection closes`() {
        assertEquals(CatalogBackAction.CLOSE_SERIES, catalogBackAction(
            seriesOpen = true, modernSectionOpen = true, homeOpen = true
        ))
    }

    @Test fun `collection opened from home closes before exit confirmation`() {
        assertEquals(CatalogBackAction.CLOSE_MODERN_SECTION, catalogBackAction(
            modernSectionOpen = true, homeOpen = true
        ))
    }

    @Test fun `browse hub returns home and home then asks to exit`() {
        assertEquals(CatalogBackAction.OPEN_HOME, catalogBackAction())
        assertEquals(CatalogBackAction.SHOW_EXIT_CONFIRMATION, catalogBackAction(homeOpen = true))
    }
}
