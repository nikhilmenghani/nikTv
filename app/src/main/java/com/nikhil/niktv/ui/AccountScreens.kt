package com.nikhil.niktv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikhil.niktv.data.AccountSyncManager
import com.nikhil.niktv.data.AccountSyncState
import com.nikhil.niktv.data.UserProfile
import com.nikhil.niktv.data.UserProfileType
import com.nikhil.niktv.data.UserSyncStore
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.model.PortalProfile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal val LocalOpenAccountScreen = staticCompositionLocalOf<() -> Unit> { {}
 }
internal val LocalOpenUserProfiles = staticCompositionLocalOf<() -> Unit> { {} }
internal val LocalCloseAccountSettings = staticCompositionLocalOf<() -> Unit> { {} }
internal val LocalSelectedUserProfile = staticCompositionLocalOf<UserProfile?> { null }

/** Account authentication precedes both the people chooser and every content destination. */
@Composable
internal fun AccountProfileGate(vm: NikTvViewModel, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { AccountSyncManager.get(context) }
    val store = remember(context) { UserSyncStore.get(context) }
    val account by manager.state.collectAsStateWithLifecycle()
    val profiles by store.profiles.collectAsStateWithLifecycle(emptyList())
    val selectedScope by store.selectedScope.collectAsStateWithLifecycle()
    val appState by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val legacyStore = remember(context) { ProfileStore(context) }
    var hasLegacy by remember { mutableStateOf(false) }
    var launchComplete by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var settingsEntered by remember { mutableStateOf(false) }
    var selectedUser by remember { mutableStateOf<String?>(null) }
    var profileError by remember { mutableStateOf<String?>(null) }
    var mappingProfile by remember { mutableStateOf<UserProfile?>(null) }
    suspend fun profileAction(message: String, action: suspend () -> Unit) {
        profileError = null
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { profileError = message }
    }
    fun openAccount() { showAccount = true; showSettings = false }
    fun openProfiles() {
        selectedUser = null
        profileError = null
        store.clearSelection()
        showAccount = false
        showSettings = false
        vm.openProfileChooser()
    }
    LaunchedEffect(manager) { manager.launchSync(); hasLegacy = legacyStore.hasLegacyViewingData(); launchComplete = true }
    LaunchedEffect(account.accountId) { selectedUser = null; profileError = null; mappingProfile = null }
    LaunchedEffect(selectedScope) { if (selectedUser != null && selectedScope?.profileId != selectedUser) selectedUser = null }
    LaunchedEffect(appState.settingsOpen, appState.profileEditorOpen) {
        if (showSettings && appState.settingsOpen) settingsEntered = true
        if (showSettings && settingsEntered && !appState.settingsOpen && !appState.profileEditorOpen) {
            showSettings = false
            vm.openProfileChooser()
        }
    }
    CompositionLocalProvider(LocalOpenAccountScreen provides ::openAccount, LocalOpenUserProfiles provides ::openProfiles,
        LocalCloseAccountSettings provides { showSettings = false },
        LocalViewingOwner provides selectedScope,
        LocalSelectedUserProfile provides profiles.firstOrNull { it.id == selectedUser }) {
        MaterialTheme(colorScheme = NikColors) {
            Surface(Modifier.fillMaxSize()) {
                when {
                    showSettings -> content()
                    !launchComplete || showAccount || !account.initialized || account.reconnectRequired -> GitHubAccountScreen(
                        state = account,
                        onConnect = { token -> scope.launch {
                            manager.connect(token)
                            launchComplete = true
                            if (manager.state.value.initialized && !manager.state.value.reconnectRequired) openProfiles()
                        } },
                        onRetry = { scope.launch { manager.launchSync(); launchComplete = true } },
                        onProfiles = ::openProfiles,
                        onSettings = {
                            settingsEntered = false
                            vm.openProfileChooser()
                            vm.openSettingsFromProfileChooser()
                            showSettings = true
                        },
                        onDisconnect = { scope.launch { vm.openProfileChooser(); manager.disconnect(); selectedUser = null } }
                    )
                    selectedUser == null || profiles.none { it.id == selectedUser } -> UserProfilesScreen(
                        profiles = profiles,
                        state = account,
                        error = profileError,
                        onSelect = { profile -> scope.launch {
                            profileAction("Could not select this profile. Retry after reconnecting.") {
                                store.selectProfile(profile.id)
                                vm.openProfileChooser()
                                selectedUser = profile.id
                                manager.syncNow()
                            }
                        } },
                        onSave = { existing, name, type, color, avatar -> scope.launch {
                            profileAction("Could not save this profile. Your local data is preserved.") {
                                if (existing == null) {
                                    store.clearSelection()
                                    selectedUser = null
                                    store.createProfile(name, type, color, avatar)
                                } else store.updateProfile(existing.copy(name = name, color = color, avatar = avatar))
                                manager.syncNow()
                            }
                        } },
                        onDelete = { profile -> scope.launch { profileAction("Could not delete this profile. Retry from this screen.") { store.deleteProfile(profile.id); manager.syncNow() } } },
                        onManagePortals = { mappingProfile = it },
                        onImportLegacy = if (hasLegacy) { profile -> scope.launch {
                            profileAction("Could not import device viewing data. Your existing data is preserved; retry from this screen.") {
                                val accountId = requireNotNull(account.accountId)
                                legacyStore.importLegacyToProfile(UserScope(accountId, profile.id))
                                hasLegacy = legacyStore.hasLegacyViewingData()
                                manager.syncNow()
                            }
                        } } else null,
                        onAccount = ::openAccount,
                        onSync = { scope.launch { manager.syncNow() } }
                    )
                    else -> {
                        BackHandler(enabled = appState.session == null && !appState.profileEditorOpen && !appState.offlineDownloadsOpen) { openProfiles() }
                        content()
                    }
                }
            }
            mappingProfile?.let { profile ->
                account.accountId?.let { accountId -> PortalMappingsDialog(UserScope(accountId, profile.id), profile.name, store, legacyStore) { mappingProfile = null } }
            }
        }
    }
}

@Composable
internal fun GitHubAccountScreen(
    state: AccountSyncState,
    onConnect: (String) -> Unit,
    onRetry: () -> Unit,
    onProfiles: () -> Unit,
    onSettings: () -> Unit,
    onDisconnect: () -> Unit
) {
    // Deliberately blank: credentials are never prefilled or kept in saved UI state.
    var token by remember { mutableStateOf("") }
    LaunchedEffect(state.initialized, state.syncing, state.error) {
        if (state.initialized && !state.syncing && state.error == null) token = ""
    }
    val first = remember { FocusRequester() }
    val remote = LocalContext.current.usesRemoteNavigation(LocalConfiguration.current)
    if (state.initialized && !state.reconnectRequired) BackHandler(onBack = onProfiles)
    Column(
        Modifier.fillMaxSize().initialFocusEntry(first, remote).background(Color(0xFF090909)).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("NikTV", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("GitHub account", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(state.login?.let { "Connected as $it" } ?: "Connect your GitHub account to sync the people watching and their viewing data.")
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text(if (state.accountId == null) "GitHub token" else "Replacement GitHub token") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                enabled = !state.syncing, modifier = Modifier.fillMaxWidth().focusRequester(first).remoteFocusFrame(RoundedCornerShape(8.dp))
            )
            NikTvPrimaryActionButton(
                onClick = { onConnect(token.trim()) },
                enabled = token.isNotBlank() && !state.syncing, modifier = Modifier.fillMaxWidth()
            ) { Text(if (state.accountId == null) "Connect" else "Connect / replace token") }
            if (state.syncing) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(state.message.ifBlank { "Connecting and syncing…" })
            } else if (state.message.isNotBlank()) Text(state.message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.error != null || state.accountId != null) NikTvSecondaryActionButton(onClick = { if (token.isNotBlank()) onConnect(token.trim()) else onRetry() }, enabled = !state.syncing, modifier = Modifier.fillMaxWidth()) { Text("Retry sync") }
            if (state.initialized && !state.reconnectRequired) NikTvPrimaryActionButton(onClick = onProfiles, enabled = !state.syncing, modifier = Modifier.fillMaxWidth()) { Text(if (state.error == null) "User profiles" else "Use cached profiles") }
            NikTvSecondaryActionButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("Settings") }
            if (state.accountId != null) NikTvTextActionButton(onClick = { token = ""; onDisconnect() }, enabled = !state.syncing, modifier = Modifier.fillMaxWidth()) { Text("Disconnect this device") }
            if (state.accountId != null) Text("Disconnect removes this device's account authentication. Local viewing data and remote data are kept. Delete a user profile across devices from User profiles.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("The token stays encrypted on this device. NikTV uses a private niktv-data repository. Creating it requires repository creation permission; syncing requires Contents read and write access.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun UserProfilesScreen(
    profiles: List<UserProfile>, state: AccountSyncState, error: String?,
    onSelect: (UserProfile) -> Unit,
    onSave: (UserProfile?, String, UserProfileType, Long, String) -> Unit,
    onDelete: (UserProfile) -> Unit, onAccount: () -> Unit, onSync: () -> Unit,
    onImportLegacy: ((UserProfile) -> Unit)? = null,
    onManagePortals: ((UserProfile) -> Unit)? = null
) {
    var editing by remember { mutableStateOf<UserProfile?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<UserProfile?>(null) }
    var importing by remember { mutableStateOf<UserProfile?>(null) }
    var managing by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    LazyColumn(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(Color(0xFF111318), Color(0xFF090A0C), Color(0xFF060606))
        )).then(if (profiles.isNotEmpty()) Modifier.initialFocusEntry(first) else Modifier)
            .safeDrawingPadding().padding(horizontal = 24.dp),
        contentPadding = PaddingValues(vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("NIKTV", color = Color(0xFFE50914), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(12.dp))
                Text("Who's watching?", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Your own space to watch.", color = Color(0xFF9699A2), style = MaterialTheme.typography.bodyLarge)
                if (state.syncing) LinearProgressIndicator(Modifier.fillMaxWidth())
                (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(8.dp))
            }
        }
        items(profiles, key = { it.id }) { profile ->
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(shape = RoundedCornerShape(20.dp), color = Color(0xFF181A20)) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Surface(Modifier.size(64.dp), shape = RoundedCornerShape(18.dp), color = Color(profile.color)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(profile.avatar.ifBlank { profile.name.take(1).uppercase() }, color = Color.White,
                                    style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(profile.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text(if (profile.type == UserProfileType.KIDS) "Kids profile" else "Personal profile",
                                color = Color(0xFF9699A2), style = MaterialTheme.typography.bodySmall)
                        }
                        NikTvTextActionButton(onClick = { managing = if (managing == profile.id) null else profile.id },
                            modifier = Modifier.semantics { contentDescription = "Manage ${profile.name}" }) { Text("Manage") }
                    }
                }
                NikTvPrimaryActionButton(onClick = { onSelect(profile) },
                    modifier = Modifier.fillMaxWidth().then(if (profiles.firstOrNull()?.id == profile.id) Modifier.focusRequester(first) else Modifier)) {
                    Text("Watch as ${profile.name}")
                }
                if (managing == profile.id) {
                    NikTvSecondaryActionButton(onClick = { editing = profile; editorOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("Edit profile") }
                    NikTvTextActionButton(onClick = { deleting = profile }, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete profile", color = Color(0xFFFF727C))
                    }
                    if (onImportLegacy != null) NikTvTextActionButton(onClick = { importing = profile }, modifier = Modifier.fillMaxWidth()) { Text("Import existing device viewing data") }
                    if (onManagePortals != null) NikTvTextActionButton(onClick = { onManagePortals(profile) }, modifier = Modifier.fillMaxWidth()) { Text("Map portals on this device") }
                }
            }
        }
        item {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NikTvSecondaryActionButton(onClick = { editing = null; editorOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("+  Add user profile") }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFF282A30))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.syncing) "Syncing…" else if (state.error != null) "Saved on this device" else "Up to date",
                        modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = Color(0xFF9699A2))
                    NikTvTextActionButton(onClick = onSync, enabled = !state.syncing) { Text("Sync now") }
                }
                NikTvSecondaryActionButton(onClick = onAccount, modifier = Modifier.fillMaxWidth()) { Text("GitHub account & Settings") }
            }
        }
    }
    if (editorOpen) UserProfileEditor(editing, onDismiss = { editorOpen = false }, onSave = { name, type, color, avatar ->
        onSave(editing, name, type, color, avatar); editorOpen = false
    }, onDelete = editing?.let { profile -> { deleting = profile; editorOpen = false } })
    deleting?.let { profile -> AlertDialog(
        onDismissRequest = { deleting = null }, title = { Text("Delete ${profile.name} across devices?") },
        text = { Text("This removes this user profile and its viewing data from the account after sync. Local downloaded media files remain on their device.") },
        confirmButton = { NikTvPrimaryActionButton(onClick = { onDelete(profile); deleting = null }) { Text("Delete across devices") } },
        dismissButton = { NikTvTextActionButton(onClick = { deleting = null }) { Text("Cancel") } }
    ) }
    importing?.let { profile -> AlertDialog(onDismissRequest = { importing = null },
        title = { Text("Import into ${profile.name}?") },
        text = { Text("Assign this device's viewing data from the previous NikTV version to this user profile. This import is performed once and preserves existing account data.") },
        confirmButton = { NikTvPrimaryActionButton(onClick = { onImportLegacy?.invoke(profile); importing = null }) { Text("Import into ${profile.name}") } },
        dismissButton = { NikTvTextActionButton(onClick = { importing = null }) { Text("Cancel") } }) }
}


private data class SyncedPortalChoice(val id: String, val name: String, val type: String)

@Composable
private fun PortalMappingsDialog(owner: UserScope, name: String, store: UserSyncStore, legacy: ProfileStore, onDismiss: () -> Unit) {
    var remote by remember(owner) { mutableStateOf<List<SyncedPortalChoice>>(emptyList()) }
    var local by remember(owner) { mutableStateOf<List<PortalProfile>>(emptyList()) }
    var message by remember(owner) { mutableStateOf("Loading portal associations…") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(owner) {
        runCatching {
            local = legacy.profiles.first()
            remote = store.snapshot(owner.accountId).filter { it.profileId == owner.profileId && it.collection == "portals" && !it.deleted }.map {
                val payload = JSONObject(it.payload)
                SyncedPortalChoice(it.recordId, payload.optString("name", "Portal"), payload.optString("type"))
            }
            message = if (remote.isEmpty()) "No synced portal associations. Select this user profile and configure a portal first." else "Match each synced portal to the same provider connection on this device. Viewing data is kept until a portal is mapped."
        }.onFailure { message = "Could not load portals. Retry after syncing." }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Portals for $name") },
        text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message)
            remote.forEach { portal ->
                Text("${portal.name} · ${portal.id.take(8)}", style = MaterialTheme.typography.titleMedium)
                val candidates = local.filter { it.portalType.name == portal.type }
                if (candidates.isEmpty()) Text("Configure a ${portal.type.lowercase()} connection in Portals, then return here.")
                candidates.forEachIndexed { index, candidate ->
                    NikTvSecondaryActionButton(onClick = { scope.launch {
                        runCatching { store.mapPortal(owner, portal.id, candidate) }
                            .onSuccess { message = "Mapped ${portal.name} to device connection ${index + 1}." }
                            .onFailure { message = "Could not map this portal. Sync and retry." }
                    } }, modifier = Modifier.fillMaxWidth()) { Text("Use ${candidate.portalType.name.lowercase().replaceFirstChar { it.uppercase() }} connection ${index + 1}") }
                }
            }
        } },
        confirmButton = { NikTvTextActionButton(onClick = onDismiss) { Text("Done") } })
}

@Composable
private fun UserProfileEditor(profile: UserProfile?, onDismiss: () -> Unit, onSave: (String, UserProfileType, Long, String) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember(profile) { mutableStateOf(profile?.name.orEmpty()) }
    var type by remember(profile) { mutableStateOf(profile?.type ?: UserProfileType.GENERAL) }
    var color by remember(profile) { mutableStateOf(profile?.color ?: 0xFFE50914L) }
    var avatar by remember(profile) { mutableStateOf(profile?.avatar.orEmpty()) }
    val outline = Color(0xFF30323A)
    val muted = Color(0xFF9A9DA7)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Color(0xFFE50914), unfocusedBorderColor = outline,
        focusedContainerColor = Color(0xFF101115), unfocusedContainerColor = Color(0xFF101115),
        focusedLabelColor = Color(0xFFDDDEE3), unfocusedLabelColor = muted
    )
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp).widthIn(max = 480.dp)
                .fillMaxWidth().imePadding(),
            shape = RoundedCornerShape(24.dp), color = Color(0xFF17191E), contentColor = Color.White,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2A2C33))
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("NIKTV / PROFILES", color = Color(0xFFFF4D58), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text(if (profile == null) "Add user profile" else "Edit user profile",
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(if (profile == null) "Start with an empty viewing space. Configure portals after creating it."
                        else "Make this space yours.", color = muted, style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth().background(Color(0xFF101115), RoundedCornerShape(18.dp)).padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(Modifier.size(72.dp), shape = RoundedCornerShape(20.dp), color = Color(color)) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(avatar.ifBlank { name.trim().take(1).uppercase().ifBlank { "N" } },
                                style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(name.trim().ifBlank { "Your profile" }, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("Avatar preview", color = muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text("Display name") }, singleLine = true,
                        shape = RoundedCornerShape(12.dp), colors = fieldColors, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(avatar, { avatar = it.take(8) }, label = { Text("Initials or emoji") }, singleLine = true,
                        placeholder = { Text("Use your initial, or add an emoji") }, shape = RoundedCornerShape(12.dp),
                        colors = fieldColors, modifier = Modifier.fillMaxWidth())
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("AVATAR COLOR", style = MaterialTheme.typography.labelSmall, color = muted, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf("Red" to 0xFFE50914L, "Blue" to 0xFF1565C0L, "Green" to 0xFF2E7D32L, "Purple" to 0xFF7B1FA2L).forEach { (label, value) ->
                            val selected = color == value
                            Box(Modifier.size(48.dp).border(if (selected) 2.dp else 1.dp,
                                if (selected) Color.White else outline, CircleShape)
                                .remoteFocusFrame(CircleShape)
                                .selectable(selected = selected, role = Role.RadioButton, onClick = { color = value })
                                .semantics { contentDescription = label + " avatar color" }
                                .padding(5.dp).background(Color(value), CircleShape), contentAlignment = Alignment.Center) {
                                if (selected) Text("✓", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (profile == null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PROFILE TYPE", style = MaterialTheme.typography.labelSmall, color = muted, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(UserProfileType.GENERAL to "General", UserProfileType.KIDS to "Kids").forEach { (choice, label) ->
                            val selected = type == choice
                            Surface(modifier = Modifier.weight(1f).remoteFocusFrame(RoundedCornerShape(12.dp))
                                .selectable(selected, role = Role.RadioButton, onClick = { type = choice }),
                                shape = RoundedCornerShape(12.dp), color = if (selected) Color(0xFF32191E) else Color(0xFF101115),
                                border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Color(0xFFE50914) else outline)) {
                                Box(Modifier.padding(vertical = 14.dp), contentAlignment = Alignment.Center) {
                                    Text(if (selected) "✓  $label" else label, color = Color.White, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = outline)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    NikTvPrimaryActionButton(onClick = { onSave(name.trim(), type, color, avatar.trim()) },
                        enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(if (profile == null) "Create profile" else "Save changes")
                    }
                    NikTvTextActionButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                    onDelete?.let { NikTvTextActionButton(onClick = it, modifier = Modifier.fillMaxWidth()) {
                        Text("Delete across devices", color = Color(0xFFFF727C), style = MaterialTheme.typography.bodySmall)
                    } }
                }
            }
        }
    }
}
