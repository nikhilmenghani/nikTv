package com.nikhil.niktv.discovery

import android.provider.Settings
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.NikTvState
import kotlinx.coroutines.delay
import java.io.File

/** Reuse Home's artwork/gesture presentation without resolving provider availability. */
@Composable
internal fun DiscoveryFavoritesHero(state: NikTvState, scrolling: Boolean,
    open: (FavoriteItem) -> Unit, toggleFavorite: (FavoriteItem) -> Unit, presentation: (DiscoveryCard?) -> Unit) {
    val context = LocalContext.current
    val profile = state.session?.profile?.cacheKey() ?: state.savedProfile?.cacheKey().orEmpty()
    val favorites = remember(state.favorites, profile) { libraryFavoriteHeroes(state.favorites, profile) }
    var removal by remember(profile) { mutableStateOf<FavoriteItem?>(null) }
    var selectedKey by rememberSaveable(profile) { mutableStateOf<String?>(null) }
    val index = favorites.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
    val favorite = favorites.getOrNull(index)
    val cache = remember(context) { DiscoveryCache(File(context.cacheDir, "discovery_preview")) }
    val entry = favorite?.let { LibraryEntry(it.media, it.kind, favorite = it) }
    val identity = "$profile:${favorite?.key}"
    val cached by produceState<Pair<String, DiscoveryCard?>?>(null, identity) {
        value = null
        if (entry != null) value = identity to libraryCachedPresentation(cache, entry)
    }
    val card = cached?.takeIf { it.first == identity }?.second ?: entry?.let { providerDetailFallback(it.media, it.type) }
    val publish by rememberUpdatedState(presentation)
    LaunchedEffect(identity, card) { publish(card) }
    DisposableEffect(Unit) { onDispose { publish(null) } }
    if (favorite == null) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var focused by remember { mutableStateOf(false) }
    var touching by remember { mutableStateOf(false) }
    val view = LocalView.current
    val details = LocalOpenProviderDiscovery.current
    val reduced = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    fun move(direction: Int) { selectedKey = favorites[Math.floorMod(index + direction, favorites.size)].key }
    LaunchedEffect(identity, favorites, scrolling, foreground, focused, touching, reduced, removal) {
        if (favorites.size > 1 && !scrolling && foreground && !focused && !touching && !reduced && removal == null) {
            while (true) {
                delay(8_000)
                if (view.hasWindowFocus()) { move(1); break }
            }
        }
    }
    val play = remember { FocusRequester() }
    val info = remember { FocusRequester() }
    Box(Modifier.wrapContentWidth(unbounded = true).requiredWidth(LocalConfiguration.current.screenWidthDp.dp).onFocusChanged { focused = it.hasFocus }.focusGroup().pointerInput(identity) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touching = true
            try { waitForUpOrCancellation(pass = PointerEventPass.Initial) } finally { touching = false }
        }
    }) {
        DiscoveryPhoneHero(card?.let { if (!it.poster.isNullOrBlank()) it.copy(backdrop = null) else it }, null, phoneDiscoveryHeroHeight(), false, false, true, play, info,
            play = { open(favorite) }, add = { removal = favorite }, info = { if (favorite.source == FavoriteSource.TMDB) open(favorite) else details(requireNotNull(entry).type, favorite.media, listOf(favorite.media)) },
            previous = { move(-1) }, next = { move(1) }, carousel = favorites.size > 1,
            index = index, count = favorites.size, playLabel = "Open", playEnabled = true,
            reduceMotion = reduced, onFocus = {}, onDown = {},
            header = { Spacer(Modifier.height(64.dp)) },
            artworkAlignment = Alignment.TopCenter, shadeArtworkBounds = true, supportingTitle = true, fitArtwork = true)
    }
    removal?.let { pending -> LibraryRemoveDialog(pending, { removal = null }, {
        removal = null
        toggleFavorite(pending)
    }) }
}
