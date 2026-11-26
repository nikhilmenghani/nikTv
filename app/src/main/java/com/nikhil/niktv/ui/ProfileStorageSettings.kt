package com.nikhil.niktv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.data.*
import com.nikhil.niktv.discovery.PortalOptionsMenu
import com.nikhil.niktv.update.formatDownloadBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val SHARED_STORAGE = "shared-app-cache"
private data class StorageConfirmation(val title: String, val ids: Set<String> = emptySet(), val sharedKind: String? = null,
    val owner: UserScope? = null, val profileName: String = "")

/** Bounded settings viewport: selectors stay put while files scroll independently. */
@Composable
internal fun ProfileStorageSettings(state: NikTvState, initialProfileId: String? = null,
    snapshotOverride: ProfileStorageSnapshot? = null, profilesOverride: List<UserProfile>? = null) {
    val context = LocalContext.current
    val users = remember(context) { UserSyncStore.get(context) }
    val savedProfiles by users.profiles.collectAsState(initial = emptyList())
    val selectedOwner by users.selectedScope.collectAsState()
    val account by users.selectedAccount.collectAsState()
    val profiles = profilesOverride ?: savedProfiles
    var profileId by rememberSaveable { mutableStateOf(initialProfileId ?: selectedOwner?.profileId) }
    val profile = profiles.firstOrNull { it.id == profileId } ?: profiles.firstOrNull { it.id == selectedOwner?.profileId } ?: profiles.firstOrNull()
    val owner = profile?.let { person -> account?.let { UserScope(it, person.id) } }
    var portalId by rememberSaveable { mutableStateOf<String?>(null) }
    var snapshot by remember(owner) { mutableStateOf<ProfileStorageSnapshot?>(snapshotOverride) }
    var revision by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<StorageConfirmation?>(null) }
    val scope = rememberCoroutineScope()
    val refreshFocus = remember { FocusRequester() }
    val scroll = rememberLazyListState()
    LaunchedEffect(owner, revision, snapshotOverride) {
        loading = true
        try { snapshot = snapshotOverride ?: ProfileStorage.snapshot(context, owner) }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { message = failure.message ?: "Could not read storage. Try Refresh." }
        finally { loading = false }
    }
    LaunchedEffect(owner, portalId) { scroll.requestScrollToItem(0); message = null }
    val portals = (state.profiles + listOfNotNull(state.savedProfile)).distinctBy { it.cacheKey() }
    val knownIds = (portals.map { storagePortalId(it.cacheKey()) } + listOf("torrents", "sports", "playfy") +
        snapshot?.files.orEmpty().map { it.portal }).distinct()
    val choices = listOf(null to "All portals") + knownIds.map { it to storagePortalLabel(it, portals) } +
        listOf(SHARED_STORAGE to "Shared app caches")
    val visible = snapshot?.files.orEmpty().filter { portalId == null || it.portal == portalId }
    val shared = portalId == SHARED_STORAGE
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(snapshot?.let { "${formatDownloadBytes(it.availableBytes)} available" } ?: "Reading device storage…",
                    style = MaterialTheme.typography.titleSmall)
                snapshot?.let { Text("of ${formatDownloadBytes(it.totalBytes)} on this device", style = MaterialTheme.typography.bodySmall, color = SettingsMuted) }
            }
            if (loading || clearing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            NikTvTextActionButton(onClick = { if (!clearing) revision++ }, modifier = Modifier.focusRequester(refreshFocus)
                .settingsFocusEntry().semantics { contentDescription = "Refresh storage" }) {
                Icon(Icons.Default.Refresh, null, Modifier.size(20.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StorageSelector("Profile", profile?.id, profiles.map { it.id to it.name }, Modifier.weight(1f), enabled = !shared) {
                profileId = it
            }
            StorageSelector("Portal", portalId, choices, Modifier.weight(1f)) { portalId = it }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = SettingsMuted) }
        if (!shared) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${visible.size} ${if (visible.size == 1) "file" else "files"} · ${formatDownloadBytes(visible.sumOf { it.bytes })}", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge, color = SettingsMuted)
                if (visible.isNotEmpty()) NikTvTextActionButton(onClick = {
                    if (!clearing) pending = StorageConfirmation("Clear files for ${profile?.name.orEmpty()}?", visible.map { it.id }.toSet(),
                        owner = owner, profileName = profile?.name.orEmpty())
                }) { Text("Clear files") }
            }
        } else Text("Shared across all profiles on this device", style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shared) {
                val value = snapshot
                if (value != null) items(listOf(
                    Triple("artwork", "Artwork cache", value.artworkBytes),
                    Triple("subtitles", "Downloaded subtitles", value.subtitleBytes),
                    Triple("temporary", "Temporary cache", value.temporaryBytes),
                    Triple("legacy", "Legacy video cache", value.legacyMediaBytes)), key = { it.first }) { (key, name, bytes) ->
                    StorageFileRow(name, "Shared app cache", bytes, Icons.Default.CleaningServices, false,
                        actionLabel = "Clear $name", empty = bytes == 0L) {
                        if (!clearing && bytes > 0) pending = StorageConfirmation("Clear $name?", sharedKind = key)
                    }
                }
            } else {
                if (visible.isEmpty() && !loading) item("empty") {
                    Text(if (profile == null) "Choose a profile to view its files." else "No files stored for this selection.",
                        Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium, color = SettingsMuted)
                }
                items(visible, key = { it.id }) { file ->
                    val icon = when (file.kind) {
                        StorageFileKind.STREAM -> Icons.Default.VideoLibrary
                        StorageFileKind.RECORDING -> Icons.Default.FiberManualRecord
                        StorageFileKind.FILE -> Icons.Default.Description
                        else -> Icons.Default.DownloadDone
                    }
                    StorageFileRow(file.title, listOfNotNull(storagePortalLabel(file.portal, portals), file.kind.label,
                        file.status).joinToString(" · "), file.bytes, icon, file.inUse, "Clear ${file.title}") {
                        if (!clearing) pending = StorageConfirmation("Clear ${file.title}?", setOf(file.id), owner = owner, profileName = profile?.name.orEmpty())
                    }
                }
            }
        }
    }
    pending?.let { action ->
        val cancel = remember { FocusRequester() }
        AlertDialog(onDismissRequest = { pending = null }, modifier = Modifier.initialFocusEntry(cancel),
            containerColor = SettingsSurface, shape = RoundedCornerShape(12.dp),
            title = { Text(action.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text(if (action.sharedKind != null)
                "This clears shared cache for every profile. Cached artwork and subtitles can be fetched again. Clearing legacy video cache can remove older offline copies."
                else "Only the selected files for ${action.profileName} on this device will be removed. Active streams are kept. Your watch history and saved list remain available.") },
            dismissButton = { NikTvTextActionButton(onClick = { pending = null }, modifier = Modifier.focusRequester(cancel)) { Text("Cancel") } },
            confirmButton = { NikTvPrimaryActionButton(onClick = {
                val captured = action.owner
                pending = null
                clearing = true
                scope.launch {
                    try {
                        if (action.sharedKind != null) {
                            ProfileStorage.clearShared(context, action.sharedKind)
                            message = "Shared cache cleared."
                        } else if (captured != null) {
                            val result = ProfileStorage.clear(context, captured, action.ids)
                            message = "${result.removed} ${if (result.removed == 1) "file" else "files"} removed" + if (result.protected > 0) " · ${result.protected} in use and kept" else ""
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { message = failure.message ?: "Could not clear these files. Try again." }
                    finally {
                        // The dialog has closed while IO runs. Move to a surviving
                        // control before the refreshed list removes its old target.
                        runCatching { refreshFocus.requestFocus() }
                        clearing = false
                        revision++
                    }
                }
            }) { Text("Clear") } })
    }
}

@Composable
private fun <T> StorageSelector(label: String, selected: T, choices: List<Pair<T, String>>, modifier: Modifier,
    enabled: Boolean = true, choose: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        NikTvSecondaryActionButton(onClick = { expanded = true }, enabled = enabled && choices.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().settingsFocusEntry()) {
            Text("$label · ${choices.firstOrNull { it.first == selected }?.second ?: "None"}", Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp))
        }
        if (expanded) {
            val entry = remember { FocusRequester() }
            PortalOptionsMenu(true, { expanded = false }, Modifier.initialFocusEntry(entry)) {
                choices.forEach { (value, title) ->
                    NikDropdownMenuItem(text = { Text(title) }, modifier = if (value == selected) Modifier.focusRequester(entry) else Modifier,
                        trailingIcon = if (value == selected) ({ Icon(Icons.Default.Check, null, Modifier.size(18.dp)) }) else null,
                        onClick = { expanded = false; choose(value) })
                }
            }
        }
    }
}

@Composable
private fun StorageFileRow(title: String, subtitle: String, bytes: Long, icon: ImageVector, inUse: Boolean,
    actionLabel: String, empty: Boolean = false, clear: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = SettingsSurface,
        border = BorderStroke(1.dp, SettingsOutline)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = Color(0xFF90A9CC))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = SettingsMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(formatDownloadBytes(bytes), style = MaterialTheme.typography.labelMedium)
            }
            if (inUse) Text("In use", style = MaterialTheme.typography.labelMedium, color = SettingsMuted)
            else NikTvTextActionButton(onClick = clear, enabled = !empty, modifier = Modifier.semantics { contentDescription = actionLabel }) {
                Icon(Icons.Default.DeleteOutline, null, Modifier.size(20.dp))
            }
        }
    }
}
