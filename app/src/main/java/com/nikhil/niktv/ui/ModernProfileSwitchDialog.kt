package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.People
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.model.PortalProfile

/** Switching is explicit; opening or dismissing this modal leaves the active session intact. */
@Composable
internal fun ModernProfileSwitchDialog(profiles: List<PortalProfile>, current: PortalProfile?,
    close: () -> Unit, select: (PortalProfile) -> Unit, openChooser: () -> Unit = {}) {
    val entry = remember { FocusRequester() }
    val selected = profiles.indexOfFirst { it.cacheKey() == current?.cacheKey() }.coerceAtLeast(0)
    val closeFocus = remember { FocusRequester() }
    AlertDialog(modifier = Modifier.initialFocusEntry(if (profiles.isEmpty()) closeFocus else entry), onDismissRequest = close, containerColor = Color(0xFF181B22),
        title = { Text("Switch profile") }, text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEachIndexed { index, profile ->
                    NikTvSecondaryActionButton(onClick = {
                        close()
                        if (profile.cacheKey() != current?.cacheKey()) select(profile)
                    }, modifier = Modifier.fillMaxWidth().then(if (index == selected) Modifier.focusRequester(entry) else Modifier)) {
                        Text(profile.name.trim().firstOrNull()?.uppercase() ?: "N")
                        Spacer(Modifier.width(12.dp))
                        Text(profile.name, Modifier.weight(1f))
                        if (profile.cacheKey() == current?.cacheKey()) Text("Current")
                    }
                }
            }
        }, confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NikTvSecondaryActionButton(openChooser, Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Default.People, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Who's watching?")
                }
                NikTvSecondaryActionButton(close, Modifier.size(48.dp).focusRequester(closeFocus), contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Default.Close, "Close", Modifier.size(22.dp))
                }
            }
        })
}
