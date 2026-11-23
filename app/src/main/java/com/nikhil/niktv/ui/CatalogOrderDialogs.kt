package com.nikhil.niktv.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.nikhil.niktv.data.UserSyncStore
import com.nikhil.niktv.model.*

@Composable
private fun orderOwner() = LocalViewingOwner.current ?: UserSyncStore.get(LocalContext.current).selectedScope.collectAsState().value

@Composable
internal fun CategoryOrderDialog(profile: String, type: CatalogType, categories: List<Category>, close: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val owner = orderOwner()
    val pins = rememberIptvPins(profile)
    val choices = orderedForDisplay(categories, pins.categoryOrder(type), ::categoryDisplayOrderId)
        .map { OrderChoice(categoryDisplayOrderId(it), it.title) }
    key(owner, profile, type) { ReorderItemsDialog("Arrange ${type.title} categories", choices, close) {
        IptvPinPreferences.saveCategoryOrder(context, owner, profile, type, it)
    } }
}

@Composable
internal fun TmdbOrderDialog(profile: String, surface: DashboardSurface, sections: List<TmdbHomeSection>, close: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val owner = orderOwner()
    val pins = rememberIptvPins(profile)
    val choices = orderedForDisplay(sections, pins.tmdbOrder(surface)) { it.name }.map { OrderChoice(it.name, it.title) }
    key(owner, profile, surface) { ReorderItemsDialog("Arrange TMDB categories", choices, close) {
        IptvPinPreferences.saveTmdbOrder(context, owner, profile, surface, it)
    } }
}

@Composable
internal fun PinnedChannelOrderDialog(profile: String, category: Category, channels: List<MediaItem>, close: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val owner = orderOwner()
    val pins = rememberIptvPins(profile).pinnedChannelOrder(category.id)
    val byId = channels.associateBy { it.id }
    // Keep unloaded pins visible and movable without fetching an entire provider catalogue.
    val choices = pins.map { OrderChoice(it, byId[it]?.title ?: "Channel $it (not loaded)") }
    key(owner, profile, category.id) { ReorderItemsDialog("Arrange pinned channels", choices, close) {
        IptvPinPreferences.reorderChannels(context, owner, profile, category.id, it)
    } }
}

@Composable
internal fun PinnedOverviewOrderDialog(profile: String, channels: List<MediaItem>, close: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val owner = orderOwner()
    val pins = rememberIptvPins(profile)
    val choices = orderedForDisplay(channels.distinctBy { it.id }, pins.pinnedOverviewOrder()) { it.id }
        .map { OrderChoice(it.id, it.title) }
    key(owner, profile) { ReorderItemsDialog("Arrange pinned channels", choices, close) {
        IptvPinPreferences.savePinnedOverviewOrder(context, owner, profile, it)
    } }
}
