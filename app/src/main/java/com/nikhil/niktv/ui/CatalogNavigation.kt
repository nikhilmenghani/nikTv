package com.nikhil.niktv.ui

import com.nikhil.niktv.model.CatalogType

/** A menu destination takes ownership even when its catalogue type is already active underneath an overlay. */
internal fun NikTvState.enterCatalogRoot(type: CatalogType): NikTvState = copy(
    favoritesOpen = false, homeOpen = false, settingsOpen = false, searchOpen = false,
    selectedSeries = null, selectedType = type, discoverySurface = null,
    offlineDownloadsOpen = false
)
