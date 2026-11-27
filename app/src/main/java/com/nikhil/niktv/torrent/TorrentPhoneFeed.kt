package com.nikhil.niktv.torrent

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.discovery.*
import kotlinx.coroutines.delay

/** The same recycled feed, artwork shell and contained native cards as IPTV Home. */
@Composable
internal fun TorrentPhoneFeed(rows: List<NativeDiscoveryRow>, card: DiscoveryCard?,
    inList: Boolean, bodyFocus: FocusRequester,
    playFocus: FocusRequester, open: () -> Unit, save: () -> Unit,
    previous: () -> Unit, next: () -> Unit, index: Int, count: Int,
    popup: Boolean, reduceMotion: Boolean, visible: (String) -> Unit = {},
    rotate: () -> Unit = next, focusRestorer: NativeDiscoveryFocusRestorer? = null) {
    var mayRotate by remember { mutableStateOf(false) }
    val currentRotate by rememberUpdatedState(rotate)
    LaunchedEffect(mayRotate, count, popup, reduceMotion) {
        if (mayRotate && count > 1 && !popup && !reduceMotion) {
            while (true) { delay(8_000); currentRotate() }
        }
    }
    DiscoveryArtworkShell(overlay = true, phone = true, publishToParent = true,
        header = { Spacer(Modifier.height(64.dp)) }, content = {
        RecycledDiscoveryRows(rows, Modifier.fillMaxSize(), bodyFocus,
            focusRestorer = focusRestorer,
            up = { runCatching { playFocus.requestFocus() } },
            visible = visible, animateFocus = !reduceMotion, containedCaptions = true,
            cardShadows = true, homeTileProportions = false, restoreSelection = true,
            showSkeletons = true, reconcileSkeletons = true,
            restoreViewportOnEntry = true, restoreTouchSelection = true,
            heroActivity = { mayRotate = it.mayRotate },
            heroFocusEntry = { runCatching { playFocus.requestFocus() }.getOrDefault(false) },
            hero = { _, down, up ->
                if (card == null) Spacer(Modifier.height(LocalHeroNavigationInset.current))
                else DiscoveryHero(card, rank = null,
                    height = phoneDiscoveryHeroHeight() - LocalHeroNavigationInset.current,
                    landscape = false, compact = true, detail = false, loading = false,
                    inList = inList, playRequester = playFocus, infoRequester = remember { FocusRequester() },
                    play = open, add = save, info = open, onFocus = {}, onDown = down,
                    onUp = { up(); true }, previous = previous, next = next,
                    carousel = count > 1, index = index, count = count,
                    playLabel = "Open", portraitPhone = true, openDetails = true)
            })
    })
}
