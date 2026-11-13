package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Shared season UI; callers own loading, metadata and playback. */
@Composable
internal fun SeriesSeasonDropdown(seasons: List<Int>, selected: Int?, requester: FocusRequester,
    choose: (Int) -> Unit, refresh: (() -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    val selectedEntry = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val host = LocalView.current
    fun restore() { scope.launch { repeat(30) {
        withFrameNanos { }
        if (host.hasWindowFocus() && runCatching { requester.requestFocus() }.getOrDefault(false)) return@launch
    } } }
    Box {
        if (com.nikhil.niktv.discovery.LocalDetailActionGlass.current) {
            CompositionLocalProvider(com.nikhil.niktv.discovery.LocalCollapsingDiscoveryActions provides false) {
                com.nikhil.niktv.discovery.DiscoveryAction(selected?.let { "Season $it" } ?: "Choose season",
                    Icons.Default.ExpandMore, modifier = Modifier.focusRequester(requester)) { expanded = true }
            }
        } else {
        NikTvSecondaryActionButton({ expanded = true }, Modifier.focusRequester(requester)) {
            Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(selected?.let { "Season $it" } ?: "Choose season", style = MaterialTheme.typography.labelMedium)
        }
        }
        DropdownMenu(expanded, { expanded = false; restore() }, modifier = Modifier.width(220.dp),
            containerColor = Color.Transparent, tonalElevation = 0.dp, shadowElevation = 0.dp) {
            // The popup owns a different window. Requesting from the parent can
            // succeed before its initial focus pass, which then selects item one.
            val inputMode = LocalInputModeManager.current
            val revealSelected = remember { BringIntoViewRequester() }
            var selectedFocused by remember { mutableStateOf(false) }
            LaunchedEffect(selected, seasons) {
                inputMode.requestInputMode(InputMode.Keyboard)
                repeat(2) { withFrameNanos { } }
                revealSelected.bringIntoView()
                var stableFrames = 0
                repeat(30) {
                    withFrameNanos { }
                    if (selectedFocused) stableFrames++ else {
                        stableFrames = 0
                        runCatching { selectedEntry.requestFocus() }
                    }
                    if (stableFrames >= 4) return@LaunchedEffect
                }
            }
            if (seasons.isNotEmpty() || selected != null) EpisodeSortMenuGroup(scrollable = true) {
                (seasons + listOfNotNull(selected)).distinct().sorted().forEach { season ->
                    EpisodeSortMenuItem("Season $season", Icons.Default.Tv, selected = season == selected,
                        modifier = if (season == selected) Modifier.focusRequester(selectedEntry)
                            .bringIntoViewRequester(revealSelected).onFocusChanged { selectedFocused = it.hasFocus } else Modifier) {
                        expanded = false
                        runCatching { requester.requestFocus() }
                        choose(season)
                        restore()
                    }
                }
            }
            refresh?.let { action ->
                Spacer(Modifier.height(4.dp))
                EpisodeSortMenuGroup {
                    EpisodeSortMenuItem("Refresh seasons", Icons.Default.Refresh) { expanded = false; action(); restore() }
                }
            }
        }
    }
}

@Composable
internal fun SeriesActionsMenu(expanded: Boolean, dismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(expanded, dismiss, modifier = Modifier.width(220.dp), containerColor = Color.Transparent,
        tonalElevation = 0.dp, shadowElevation = 0.dp) {
        EpisodeSortMenuGroup(content = content)
    }
}
