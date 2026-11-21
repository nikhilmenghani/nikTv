package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.model.PlayingMedia

data class PortalQualityPickerState(val playing: PlayingMedia, val positionMillis: Long,
    val loading: Boolean = false, val error: String? = null,
    val id: String = java.util.UUID.randomUUID().toString())

@Composable
internal fun PortalQualityDialog(state: PortalQualityPickerState, close: () -> Unit, choose: (String?) -> Unit) {
    val quality = state.playing.portalQuality ?: return
    val entry = remember { FocusRequester() }
    val current = quality.variants.firstOrNull { it.id == quality.selectedId }
    AlertDialog(onDismissRequest = close,
        containerColor = MaterialTheme.colorScheme.surface, title = { Text("Source and quality") },
        text = {
            Column(Modifier.initialFocusEntry(entry).widthIn(max = 440.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                current?.let { Text("Playing ${it.label}", style = MaterialTheme.typography.bodyMedium) }
                val best = quality.preferredHeight == 0
                NikTvSecondaryActionButton(onClick = { choose(null) }, modifier = Modifier.fillMaxWidth()
                    .then(if (best) Modifier.focusRequester(entry) else Modifier).semantics { selected = best }) {
                    Text(if (best) "✓ Best available" else "Best available")
                }
                quality.variants.forEach { variant ->
                    val active = !best && variant.id == quality.selectedId
                    key(variant.id) {
                        NikTvSecondaryActionButton(onClick = { choose(variant.id) }, modifier = Modifier.fillMaxWidth()
                            .then(if (active) Modifier.focusRequester(entry) else Modifier).semantics { selected = active }) {
                            Text(if (active) "✓ ${variant.label}" else variant.label)
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().heightIn(min = 24.dp)) {
                    if (state.loading) Text("Switching quality…", style = MaterialTheme.typography.bodySmall)
                    else state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }, confirmButton = { NikTvTextActionButton(onClick = close) { Text("Close") } })
}
