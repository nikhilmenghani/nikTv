package com.nikhil.niktv.ui

import com.nikhil.niktv.sports.openSports
import com.nikhil.niktv.sports.openPlayfy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.SportsCricket
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.model.PortalProfile
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Switching is explicit; opening or dismissing this modal leaves the active session intact. */
@Composable
internal fun ModernProfileSwitchDialog(profiles: List<PortalProfile>, current: PortalProfile?,
    close: () -> Unit, select: (PortalProfile) -> Unit, openChooser: () -> Unit = {},
    openOffline: () -> Unit = {}) {
    val context = LocalContext.current
    val owner = LocalViewingOwner.current
    val hidden by remember(owner) { com.nikhil.niktv.data.ProfilePortalAccess.observe(context, owner) }
        .collectAsStateWithLifecycle(emptySet())
    val scope = rememberCoroutineScope()
    val entry = remember { FocusRequester() }
    val selected = profiles.indexOfFirst { it.cacheKey() == current?.cacheKey() }.coerceAtLeast(0)
    val closeFocus = remember { FocusRequester() }
    AlertDialog(modifier = Modifier.initialFocusEntry(if (profiles.isEmpty()) closeFocus else entry), onDismissRequest = close, containerColor = Color(0xFF181B22),
        title = { Text("Portals") }, text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEachIndexed { index, profile ->
                    NikTvSecondaryActionButton(onClick = {
                        close()
                        if (profile.cacheKey() != current?.cacheKey()) select(profile)
                    }, modifier = Modifier.fillMaxWidth().then(if (index == selected) Modifier.focusRequester(entry) else Modifier)) {
                        Icon(if (profile.portalType == com.nikhil.niktv.model.PortalType.STALKER) Icons.Default.Tv else Icons.Default.Key, null)
                        Spacer(Modifier.width(12.dp))
                        Text(profile.displayName, Modifier.weight(1f))
                        if (profile.cacheKey() == current?.cacheKey()) Text("Current")
                    }
                }
                if (com.nikhil.niktv.data.ProfilePortalAccess.SPORTS !in hidden) NikTvSecondaryActionButton(onClick = {
                    scope.launch {
                        owner?.let { captured -> com.nikhil.niktv.data.UserSyncStore.get(context).rememberDestination(captured,
                            com.nikhil.niktv.data.ViewerDestination(com.nikhil.niktv.data.ViewerDestinationKind.SPORTS))
                        }
                        close()
                        context.openSports()
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.SportsCricket, null)
                    Spacer(Modifier.width(12.dp))
                    Text("Sports")
                }
                if (com.nikhil.niktv.data.ProfilePortalAccess.PLAYFY !in hidden) NikTvSecondaryActionButton(onClick = {
                    scope.launch {
                        owner?.let { captured -> com.nikhil.niktv.data.UserSyncStore.get(context).rememberDestination(captured,
                            com.nikhil.niktv.data.ViewerDestination(com.nikhil.niktv.data.ViewerDestinationKind.PLAYFY))
                        }
                        close()
                        context.openPlayfy()
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.LiveTv, null)
                    Spacer(Modifier.width(12.dp))
                    Text("Playfy TV")
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
