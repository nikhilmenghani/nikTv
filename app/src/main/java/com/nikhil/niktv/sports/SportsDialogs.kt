package com.nikhil.niktv.sports

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.ui.*

@Composable internal fun SportsItemDialog(channel: SportsChannel, favourite: Boolean, toggle: () -> Unit, close: () -> Unit,
    flag: SportsChannelFlag? = null, toggleFlag: () -> Unit = {}) {
    val first = remember { FocusRequester() }
    AlertDialog(onDismissRequest = close, containerColor = SportsSurface, shape = SportsShape, modifier = Modifier.initialFocusEntry(first),
        title = { Text(channel.title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (flag != null) Text(sportsFlagDescription(flag), color = SportsMuted)
                NikTvSecondaryActionButton(toggle, Modifier.fillMaxWidth().focusRequester(first)) {
                    Icon(if (favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp)); Text(if (favourite) "Remove from Favourites" else "Add to Favourites")
                }
                NikTvSecondaryActionButton(toggleFlag, Modifier.fillMaxWidth()) {
                    Icon(if (flag == null) Icons.Default.Flag else Icons.Default.Clear, null, Modifier.size(20.dp), tint = SportsWarning)
                    Spacer(Modifier.width(10.dp)); Text(if (flag == null) "Flag as unavailable" else "Remove flag")
                }
            }
        }, confirmButton = { NikTvSecondaryActionButton(close, Modifier.fillMaxWidth()) { Text("Close") } })
}

@Composable internal fun SportsAvailabilityFilterDialog(selected: SportsAvailabilityFilter, choose: (SportsAvailabilityFilter) -> Unit, close: () -> Unit) {
    val first = remember { FocusRequester() }
    AlertDialog(onDismissRequest = close, containerColor = SportsSurface, shape = SportsShape, modifier = Modifier.initialFocusEntry(first),
        title = { Text("Channel availability") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Flags remember a previous playback failure or your own report. Unflagged channels have not necessarily been tested.", color = SportsMuted)
                SportsAvailabilityFilter.entries.forEach { filter ->
                    NikTvSecondaryActionButton({ choose(filter) }, Modifier.fillMaxWidth().then(if (filter == selected) Modifier.focusRequester(first) else Modifier)) {
                        if (filter == selected) { Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                        Text(filter.title)
                    }
                }
            }
        }, confirmButton = { NikTvSecondaryActionButton(close, Modifier.fillMaxWidth()) { Text("Close") } })
}

@Composable internal fun SportsGroupDialog(groups: List<String>, selected: String, choose: (String) -> Unit, close: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val rows = remember(groups, query) { groups.filter { it.contains(query.trim(), true) } }
    AlertDialog(onDismissRequest = close, containerColor = SportsSurface, shape = SportsShape, modifier = Modifier.initialFocusEntry(first),
        title = { Text("Playlist groups") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(query, { query = it }, label = { Text("Find a group") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(rows) { index, group -> NikTvSecondaryActionButton({ choose(group) }, Modifier.fillMaxWidth()
                        .then(if (index == 0) Modifier.focusRequester(first) else Modifier)) {
                        if (group == selected) { Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                        Text(group)
                    } }
                }
            }
        }, confirmButton = { NikTvSecondaryActionButton(close, Modifier.fillMaxWidth()) { Text("Close") } })
}

@Composable internal fun SportsAddPlaylistDialog(error: String?, importing: Boolean, add: (String, String) -> Unit, file: (String) -> Unit, close: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }; var url by rememberSaveable { mutableStateOf("") }
    val first = remember { FocusRequester() }
    AlertDialog(onDismissRequest = { if (!importing) close() }, containerColor = SportsSurface, shape = SportsShape, modifier = Modifier.initialFocusEntry(first),
        title = { Text("Add playlist") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Playlist name") }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(first), enabled = !importing)
                OutlinedTextField(url, { url = it }, label = { Text("Playlist URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !importing)
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                if (importing) SportsLoading("Importing playlist")
                NikTvSecondaryActionButton({ file(name) }, Modifier.fillMaxWidth(), enabled = !importing) { Icon(Icons.Default.FolderOpen, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Select file") }
            }
        }, confirmButton = { NikTvPrimaryActionButton({ add(name, url) }, Modifier.fillMaxWidth(), enabled = !importing && url.isNotBlank()) { Text("Add playlist") } },
        dismissButton = { NikTvSecondaryActionButton(close, Modifier.fillMaxWidth(), enabled = !importing) { Text("Cancel") } })
}

@Composable internal fun SportsRemovePlaylistDialog(category: SportsCategory, remove: () -> Unit, close: () -> Unit) {
    val first = remember { FocusRequester() }
    AlertDialog(onDismissRequest = close, containerColor = SportsSurface, shape = SportsShape, modifier = Modifier.initialFocusEntry(first),
        title = { Text(category.title) }, text = { Text("Remove this playlist from your Sports library?") },
        confirmButton = { NikTvPrimaryActionButton(remove, Modifier.fillMaxWidth()) { Text("Remove playlist") } },
        dismissButton = { NikTvSecondaryActionButton(close, Modifier.fillMaxWidth().focusRequester(first)) { Text("Cancel") } })
}

/** Shared fillMaxSize buttons must not be placed unconstrained inside AlertDialog actions. */
@Composable internal fun SportsPlaybackErrorDialog(message: String, exhausted: Boolean, retry: () -> Unit, choose: () -> Unit, close: () -> Unit) {
    val first = remember { FocusRequester() }
    AlertDialog(onDismissRequest = if (exhausted) choose else close, containerColor = SportsSurface, shape = SportsShape,
        modifier = Modifier.widthIn(max = 560.dp).initialFocusEntry(first),
        title = { Text(if (exhausted) "Channel unavailable" else "Unable to switch stream") },
        text = { Text(message, color = SportsMuted) },
        confirmButton = { NikTvPrimaryActionButton(retry, Modifier.fillMaxWidth().focusRequester(first)) { Text("Refresh and retry") } },
        dismissButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NikTvSecondaryActionButton(choose, Modifier.fillMaxWidth()) { Text("Choose another source") }
                if (!exhausted) NikTvSecondaryActionButton(close, Modifier.fillMaxWidth()) { Text("Keep current stream") }
            }
        })
}
