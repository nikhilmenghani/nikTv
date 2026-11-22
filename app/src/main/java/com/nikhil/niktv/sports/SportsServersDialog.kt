package com.nikhil.niktv.sports

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.nikhil.niktv.ui.NikTvSecondaryActionButton
import com.nikhil.niktv.ui.initialFocusEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun SportsServersDialog(state: SportsServersState, check: () -> Unit, choose: (String?) -> Unit, close: () -> Unit,
    title: String = "Sports servers") {
    val entry = remember { FocusRequester() }
    val initialChoice = remember { state.preferred }
    val initialIndex = remember { state.servers.indexOfFirst { it.base == initialChoice }.let { if (it < 0) 0 else it + 1 } }
    val scroll = rememberLazyListState(initialIndex)
    val window = LocalConfiguration.current
    val shortWide = window.screenHeightDp < 480 && window.screenWidthDp > window.screenHeightDp
    @Composable fun checkButton(modifier: Modifier) {
        NikTvSecondaryActionButton({ if (!state.checking) check() }, modifier) {
            Text(if (state.checking) "Checking servers…" else "Check servers")
        }
    }
    AlertDialog(onDismissRequest = close, containerColor = SportsSurface, shape = SportsShape,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(.92f).initialFocusEntry(entry),
        title = { Text(title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (shortWide) "Choose a catalogue server. Other servers are tried if it fails."
                    else "Choose a preferred catalogue server. If it stops working, another known server will be tried.", color = SportsMuted)
                Text("Checks catalogues, not individual video streams.", color = SportsMuted, style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 300.dp).weight(1f, fill = false), state = scroll, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item(key = "automatic") {
                        ServerChoice("Automatic", "Follows service updates and uses a working server", state.preferred == null,
                            Modifier.then(if (initialIndex == 0) Modifier.focusRequester(entry) else Modifier)) { choose(null) }
                    }
                    items(state.servers, key = { it.base }) { server ->
                        val detail = listOfNotNull(server.health.title,
                            "In use".takeIf { state.active == server.base },
                            "Preferred".takeIf { state.preferred == server.base }).joinToString(" · ")
                        ServerChoice(server.label, detail, state.preferred == server.base,
                            Modifier.then(if (initialIndex > 0 && server.base == initialChoice) Modifier.focusRequester(entry) else Modifier)) { choose(server.base) }
                    }
                }
            }
        }, confirmButton = {
            if (shortWide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                checkButton(Modifier.weight(1f))
                NikTvSecondaryActionButton(close, Modifier.weight(1f)) { Text("Close") }
            } else NikTvSecondaryActionButton(close, Modifier.fillMaxWidth()) { Text("Close") }
        }, dismissButton = {
            if (!shortWide) checkButton(Modifier.fillMaxWidth())
        })
}

@Composable
private fun ServerChoice(title: String, detail: String, selected: Boolean, modifier: Modifier, choose: () -> Unit) {
    val reveal = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var revealJob by remember { mutableStateOf<Job?>(null) }
    NikTvSecondaryActionButton(choose, modifier.fillMaxWidth().semantics { this.selected = selected }
        .bringIntoViewRequester(reveal).onFocusChanged {
            revealJob?.cancel()
            if (it.isFocused) revealJob = scope.launch { reveal.bringIntoView() }
        }, shape = SportsShape) {
        Box(Modifier.size(18.dp)) { if (selected) Icon(Icons.Default.Check, null, Modifier.fillMaxSize()) }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, color = SportsMuted, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
