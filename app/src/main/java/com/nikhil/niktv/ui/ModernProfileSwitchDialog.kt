package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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
    close: () -> Unit, select: (PortalProfile) -> Unit) {
    val entry = remember { FocusRequester() }
    val selected = profiles.indexOfFirst { it.cacheKey() == current?.cacheKey() }.coerceAtLeast(0)
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { entry.requestFocus() } }
    AlertDialog(onDismissRequest = close, containerColor = Color(0xFF181B22),
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
        }, confirmButton = { NikTvSecondaryActionButton(close, Modifier.fillMaxWidth()) { Text("Close") } })
}
