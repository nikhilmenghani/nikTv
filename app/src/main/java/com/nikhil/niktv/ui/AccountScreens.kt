package com.nikhil.niktv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.yield
import com.nikhil.niktv.data.ViewerDestinationKind
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal val LocalOpenAccountScreen = staticCompositionLocalOf<() -> Unit> { {}
 }
internal val LocalOpenUserProfiles = staticCompositionLocalOf<() -> Unit> { {} }
internal val LocalCloseAccountSettings = staticCompositionLocalOf<() -> Unit> { {} }
internal val LocalSelectedUserProfile = staticCompositionLocalOf<UserProfile?> { null }
internal val LocalCloseProfileDownloads = staticCompositionLocalOf<(() -> Unit)?> { null }

/** Account authentication precedes both the people chooser and every content destination. */
@Composable
internal fun AccountProfileGate(vm: NikTvViewModel, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { AccountSyncManager.get(context) }
    val store = remember(context) { UserSyncStore.get(context) }
    val account by manager.state.collectAsStateWithLifecycle()
    val profiles by store.profiles.collectAsStateWithLifecycle(emptyList())
    val selectedScope by store.selectedScope.collectAsStateWithLifecycle()
    val pendingCount by store.pendingChanges.collectAsStateWithLifecycle(0)
    val appState by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val legacyStore = remember(context) { ProfileStore(context) }
    var hasLegacy by remember { mutableStateOf(false) }
    var launchComplete by remember { mutableStateOf(false) }
    var showAccount by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var settingsEntered by remember { mutableStateOf(false) }
    var selectedUser by remember { mutableStateOf<String?>(null) }
    var choosingProfile by remember { mutableStateOf(false) }
    var restoredAccount by remember { mutableStateOf<String?>(null) }
    var restoringViewer by remember { mutableStateOf(true) }
    var useCachedProfiles by remember { mutableStateOf(false) }
    var profileDownloads by remember { mutableStateOf(false) }
    var profileError by remember { mutableStateOf<String?>(null) }
    var mappingProfile by remember { mutableStateOf<UserProfile?>(null) }
    suspend fun profileAction(message: String, action: suspend () -> Unit) {
        profileError = null
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { profileError = message }
    }
    fun openAccount() { profileDownloads = false; showAccount = true; showSettings = false }
    fun openProfiles() {
        profileDownloads = false
        useCachedProfiles = account.initialized
        choosingProfile = true
        profileError = null
        showAccount = false
        showSettings = false
    }
    suspend fun openViewer(profileId: String, offlineOnly: Boolean = false) {
        val owner = UserScope(requireNotNull(store.selectedAccount.value), profileId)
        com.nikhil.niktv.data.UserProfilePreferences.warm(context, owner)
        store.selectProfile(profileId)
        // Let the ViewModel dispose the previous viewer before starting portal work.
        yield()
        if (offlineOnly) vm.openProfileDownloads() else vm.openProfileChooser()
        profileDownloads = offlineOnly
        selectedUser = profileId
        choosingProfile = false
        if (offlineOnly) return
        val destination = store.rememberedDestination(owner)
        val portals = legacyStore.scoped(owner).profiles.first()
        if (store.selectedScope.value != owner) return
        when (destination?.kind) {
            ViewerDestinationKind.OFFLINE -> {
                // Older versions remembered Downloads as a portal destination. Resume
                // the retained content portal now that Downloads lives on the people screen.
                val previous = store.rememberedPortalKey(owner)
                    ?: legacyStore.scoped(owner).activeProfile.first()?.cacheKey()
                portals.firstOrNull { it.cacheKey() == previous }?.let(vm::switchProfile)
            }
            ViewerDestinationKind.SPORTS -> context.startActivity(android.content.Intent(context, com.nikhil.niktv.sports.SportsActivity::class.java))
            ViewerDestinationKind.PORTAL -> portals.firstOrNull { it.cacheKey() == destination.portalKey }?.let(vm::switchProfile)
            null -> {
                // Upgrade an existing device's last portal only when it belongs to this viewer.
                val active = legacyStore.scoped(owner).activeProfile.first()
                portals.firstOrNull { it.cacheKey() == active?.cacheKey() }?.let(vm::switchProfile)
            }
        }
    }
    LaunchedEffect(manager) {
        manager.restoreCachedAccount()
        launchComplete = true
        manager.requestLaunchSync()
        hasLegacy = legacyStore.hasLegacyViewingData()
    }
    LaunchedEffect(account.accountId) { selectedUser = null; choosingProfile = false; profileDownloads = false; useCachedProfiles = false; profileError = null; mappingProfile = null }
    LaunchedEffect(launchComplete, account.accountId, account.initialized) {
        if (!launchComplete) return@LaunchedEffect
        val id = account.accountId
        try {
            if (account.initialized && !account.reconnectRequired && id != null && restoredAccount != id) {
                restoringViewer = true
                restoredAccount = id
                store.rememberedProfileId(id)?.let { profileId ->
                    profileAction("Could not restore this profile. Select a profile to continue.") { openViewer(profileId) }
                }
            }
        } finally { restoringViewer = false }
    }
    LaunchedEffect(selectedScope) { if (selectedUser != null && selectedScope?.profileId != selectedUser) selectedUser = null }
    LaunchedEffect(appState.settingsOpen, appState.profileEditorOpen) {
        if (showSettings && appState.settingsOpen) settingsEntered = true
        if (showSettings && settingsEntered && !appState.settingsOpen && !appState.profileEditorOpen) {
            showSettings = false
            vm.openProfileChooser()
        }
    }
    CompositionLocalProvider(LocalOpenAccountScreen provides ::openAccount, LocalOpenUserProfiles provides ::openProfiles,
        LocalCloseProfileDownloads provides if (profileDownloads) ({ vm.closeOfflineDownloads(); openProfiles() }) else null,
        LocalCloseAccountSettings provides { showSettings = false },
        LocalViewingOwner provides selectedScope,
        LocalSelectedUserProfile provides profiles.firstOrNull { it.id == selectedUser }) {
        MaterialTheme(colorScheme = NikColors) {
            Surface(Modifier.fillMaxSize()) {
                when {
                    !launchComplete || restoringViewer -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    showSettings -> content()
                    showAccount || !account.initialized || (account.reconnectRequired && !useCachedProfiles) -> GitHubAccountScreen(
                        state = account,
                        onConnect = { token -> scope.launch {
                            manager.connect(token)
                            launchComplete = true
                            if (manager.state.value.initialized && !manager.state.value.reconnectRequired) openProfiles()
                        } },
                        onRetry = { manager.requestManualSync(); launchComplete = true },
                        onProfiles = ::openProfiles,
                        onSettings = {
                            settingsEntered = false
                            vm.openProfileChooser()
                            vm.openSettingsFromProfileChooser()
                            showSettings = true
                        },
                        onDisconnect = { scope.launch { vm.openProfileChooser(); manager.disconnect(); selectedUser = null } }
                    )
                    choosingProfile || selectedUser == null || profiles.none { it.id == selectedUser } -> {
                        AccountGateBackHandler(peopleVisible = true, choosingProfile = choosingProfile,
                            selectedViewerAvailable = selectedUser != null && profiles.any { it.id == selectedUser }, state = appState,
                            openPeople = ::openProfiles, resumeContent = { choosingProfile = false })
                        UserProfilesScreen(
                        profiles = profiles,
                        state = if (!account.syncing && pendingCount > 0 && account.error == null)
                            account.copy(message = "Changes saved on this device") else account,
                        error = profileError,
                        onSelect = { profile -> scope.launch {
                            profileAction("Could not select this profile. Retry after reconnecting.") {
                                openViewer(profile.id)
                            }
                        } },
                        onSave = { existing, name, type, color, avatar -> scope.launch {
                            profileAction("Could not save this profile. Your local data is preserved.") {
                                if (existing == null) {
                                    store.clearSelection()
                                    selectedUser = null
                                    choosingProfile = true
                                    store.createProfile(name, type, color, avatar)
                                } else store.updateProfile(existing.copy(name = name, color = color, avatar = avatar))
                            }
                        } },
                        onDelete = { profile -> scope.launch { profileAction("Could not delete this profile. Retry from this screen.") { store.deleteProfile(profile.id) } } },
                        onManagePortals = { mappingProfile = it },
                        onImportLegacy = if (hasLegacy) { profile -> scope.launch {
                            profileAction("Could not import device viewing data. Your existing data is preserved; retry from this screen.") {
                                val accountId = requireNotNull(account.accountId)
                                legacyStore.importLegacyToProfile(UserScope(accountId, profile.id))
                                hasLegacy = legacyStore.hasLegacyViewingData()
                            }
                        } } else null,
                        onAccount = ::openAccount,
                        onSync = manager::requestManualSync,
                        selectedProfileId = selectedUser,
                        onOfflineDownloads = { profile -> scope.launch {
                            profileAction("Could not open local downloads. Your files are preserved.") {
                                openViewer(profile.id, offlineOnly = true)
                            }
                        } }
                    ) }
                    else -> {
                        AccountGateBackHandler(peopleVisible = false, choosingProfile = choosingProfile,
                            selectedViewerAvailable = true, state = appState,
                            openPeople = ::openProfiles, resumeContent = { choosingProfile = false })
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
            if (state.initialized) NikTvPrimaryActionButton(onClick = onProfiles, modifier = Modifier.fillMaxWidth()) { Text(if (state.error == null && !state.reconnectRequired) "User profiles" else "Use cached profiles") }
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
    onManagePortals: ((UserProfile) -> Unit)? = null,
    selectedProfileId: String? = null,
    onOfflineDownloads: ((UserProfile) -> Unit)? = null
) {
    var editing by remember { mutableStateOf<UserProfile?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf<UserProfile?>(null) }
    var managementPicker by remember { mutableStateOf(false) }
    var offlinePicker by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf<UserProfile?>(null) }
    var deleting by remember { mutableStateOf<UserProfile?>(null) }
    var importing by remember { mutableStateOf<UserProfile?>(null) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val remote = context.usesRemoteNavigation(configuration)
    val first = remember(selectedProfileId, profiles.map { it.id }) { FocusRequester() }
    val offlineEntry = remember { FocusRequester() }
    val preferred = selectedProfileId?.takeIf { id -> profiles.any { it.id == id } } ?: profiles.firstOrNull()?.id
    fun edit(profile: UserProfile?) { editing = profile; editorOpen = true; managing = null; managementPicker = false }

    if (editorOpen) UserProfileEditor(editing, onDismiss = { editorOpen = false }, onSave = { name, type, color, avatar ->
        onSave(editing, name, type, color, avatar); editorOpen = false
    }, onDelete = editing?.let { profile -> { deleting = profile } })
    else BoxWithConstraints(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
        listOf(Color(0xFF111318), Color(0xFF090A0C), Color(0xFF060606)))).safeDrawingPadding()) {
        val tileWidth = if (maxWidth < 600.dp) 108.dp else 128.dp
        val gap = if (maxWidth < 600.dp) 20.dp else 28.dp
        val columns = ((maxWidth - 32.dp + gap) / (tileWidth + gap)).toInt().coerceIn(1, 5)
        val entries = profiles.map<UserProfile, UserProfile?> { it } + listOf(null)
        Column(Modifier.fillMaxSize().initialFocusEntry(first, remote).verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight).padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("NIKTV", color = Color(0xFFE50914), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(14.dp))
            Text("Who's watching?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                entries.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
                        row.forEach { profile ->
                            key(profile?.id ?: "add-person") {
                                UserProfileTile(profile, tileWidth, selected = profile != null && profile.id == selectedProfileId,
                                    modifier = if (profile?.id == preferred || profile == null && profiles.isEmpty()) Modifier.focusRequester(first) else Modifier,
                                    select = { if (profile == null) edit(null) else onSelect(profile) },
                                    manage = { profile?.let { managing = it } })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(if (remote) "Hold OK or press Menu to manage a profile" else "Touch and hold a profile to manage it",
                color = Color(0xFF9699A2), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            if (profiles.isNotEmpty()) Row(Modifier.widthIn(max = 440.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                NikTvSecondaryActionButton(onClick = { managementPicker = true },
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                    Text("Manage profiles", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                }
                onOfflineDownloads?.let { open -> NikTvSecondaryActionButton(onClick = {
                    if (profiles.size == 1) open(profiles.single()) else offlinePicker = true
                }, modifier = Modifier.weight(1f).heightIn(min = 44.dp)) {
                    Text("Offline downloads", style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                } }
            }
            Spacer(Modifier.height(10.dp))
            (error ?: state.error)?.let { Text(it, modifier = Modifier.widthIn(max = 640.dp),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
            Row(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.syncing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(if (state.syncing) state.message.ifBlank { "Syncing…" } else state.message.ifBlank { "Changes are saved on this device" },
                    modifier = Modifier.weight(1f), color = Color(0xFF9699A2), style = MaterialTheme.typography.labelSmall)
                NikTvTextActionButton(onClick = onSync, enabled = !state.syncing) { Text("Sync now", style = MaterialTheme.typography.labelMedium) }
            }
            NikTvTextActionButton(onClick = onAccount) { Text("GitHub account & Settings", color = Color(0xFFB5B7BE), style = MaterialTheme.typography.labelMedium) }
        }
    }
    if (managementPicker) AlertDialog(onDismissRequest = { managementPicker = false },
        title = { Text("Manage profiles", style = MaterialTheme.typography.titleLarge) },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            profiles.forEach { profile -> NikTvSecondaryActionButton(onClick = { managing = profile; managementPicker = false }, modifier = Modifier.fillMaxWidth()) { Text(profile.name) } }
        } }, confirmButton = { NikTvTextActionButton(onClick = { managementPicker = false }) { Text("Done") } })
    if (offlinePicker && onOfflineDownloads != null) AlertDialog(
        modifier = Modifier.initialFocusEntry(offlineEntry, remote),
        onDismissRequest = { offlinePicker = false },
        title = { Text("Offline downloads", style = MaterialTheme.typography.titleLarge) },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Choose a profile to see downloads on this device.", style = MaterialTheme.typography.bodySmall)
            profiles.forEach { profile -> NikTvSecondaryActionButton(onClick = {
                offlinePicker = false
                onOfflineDownloads(profile)
            }, modifier = Modifier.fillMaxWidth()
                .then(if (profile.id == preferred) Modifier.focusRequester(offlineEntry) else Modifier)
                .semantics { contentDescription = "Offline downloads for ${profile.name}" }) { Text(profile.name) } }
        } }, confirmButton = { NikTvTextActionButton(onClick = { offlinePicker = false }) { Text("Cancel") } })
    managing?.let { profile -> AlertDialog(onDismissRequest = { managing = null },
        title = { Text(profile.name, style = MaterialTheme.typography.titleLarge) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NikTvSecondaryActionButton(onClick = { edit(profile) }, modifier = Modifier.fillMaxWidth()) { Text("Manage profile") }
            if (onImportLegacy != null || onManagePortals != null) NikTvTextActionButton(onClick = { managing = null; tools = profile }, modifier = Modifier.fillMaxWidth()) { Text("Profile tools") }
        } }, confirmButton = { NikTvTextActionButton(onClick = { managing = null }) { Text("Cancel") } }) }
    tools?.let { profile -> AlertDialog(onDismissRequest = { tools = null },
        title = { Text("Tools for ${profile.name}", style = MaterialTheme.typography.titleLarge) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            onImportLegacy?.let { NikTvSecondaryActionButton(onClick = { tools = null; importing = profile }, modifier = Modifier.fillMaxWidth()) { Text("Import existing device viewing data") } }
            onManagePortals?.let { NikTvSecondaryActionButton(onClick = { tools = null; it(profile) }, modifier = Modifier.fillMaxWidth()) { Text("Map portals on this device") } }
        } }, confirmButton = { NikTvTextActionButton(onClick = { tools = null }) { Text("Done") } }) }
    deleting?.let { profile -> AlertDialog(
        onDismissRequest = { deleting = null }, title = { Text("Delete ${profile.name} across devices?") },
        text = { Text("This removes this user profile and its viewing data from the account after the next sync. Local downloaded media files remain on their device.") },
        confirmButton = { NikTvPrimaryActionButton(onClick = { onDelete(profile); deleting = null; editorOpen = false }) { Text("Delete across devices") } },
        dismissButton = { NikTvTextActionButton(onClick = { deleting = null }) { Text("Cancel") } }) }
    importing?.let { profile -> AlertDialog(onDismissRequest = { importing = null },
        title = { Text("Import into ${profile.name}?") },
        text = { Text("Assign this device's viewing data from the previous NikTV version to this user profile. This import is performed once and preserves existing account data.") },
        confirmButton = { NikTvPrimaryActionButton(onClick = { onImportLegacy?.invoke(profile); importing = null }) { Text("Import into ${profile.name}") } },
        dismissButton = { NikTvTextActionButton(onClick = { importing = null }) { Text("Cancel") } }) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserProfileTile(profile: UserProfile?, width: Dp, selected: Boolean, modifier: Modifier,
    select: () -> Unit, manage: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val label = profile?.let { "Watch as ${it.name}" } ?: "Add user profile"
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.width(width).onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            val activation = native.keyCode in setOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
            when {
                profile != null && native.keyCode == android.view.KeyEvent.KEYCODE_MENU -> {
                    if (event.type == KeyEventType.KeyDown) manage()
                    true
                }
                profile != null && activation && event.type == KeyEventType.KeyDown && (native.repeatCount > 0 || native.isLongPress) -> { manage(); true }
                activation && event.type == KeyEventType.KeyUp && native.eventTime - native.downTime >= android.view.ViewConfiguration.getLongPressTimeout() -> true
                else -> false
            }
        }.semantics(mergeDescendants = true) {
            contentDescription = label
            this.selected = selected
            if (selected) stateDescription = "Current profile"
            if (profile != null) customActions = listOf(CustomAccessibilityAction("Manage ${profile.name}") { manage(); true })
        }.combinedClickable(interactionSource = interaction, indication = null,
            role = Role.Button, onClickLabel = label, onClick = select,
            onLongClickLabel = profile?.let { "Manage ${it.name}" }, onLongClick = profile?.let { manage }),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(width).then(if (pressed) Modifier.shadow(8.dp, shape, clip = false) else Modifier)
            .border(if (focused || selected) 2.dp else 1.dp,
            if (focused || selected) Color.White else Color(0xFF343842), shape)
            .background(profile?.let { Color(it.color) } ?: Color(0xFF1B1E25), shape), contentAlignment = Alignment.Center) {
            if (pressed) Box(Modifier.matchParentSize().background(Color.White.copy(alpha = .08f), shape))
            Text(profile?.avatar?.ifBlank { profile.name.take(1).uppercase() } ?: "+", color = Color.White,
                fontSize = (width.value * if (profile == null) .5f else .42f).sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(profile?.name ?: "Add profile", color = if (focused || selected) Color.White else Color(0xFFB7B9C0),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (profile?.type == UserProfileType.KIDS) Text("Kids profile", color = Color(0xFF9699A2), style = MaterialTheme.typography.labelSmall)
    }
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
    var name by remember(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var type by remember(profile?.id) { mutableStateOf(profile?.type ?: UserProfileType.GENERAL) }
    var color by remember(profile?.id) { mutableStateOf(profile?.color ?: 0xFFE50914L) }
    var avatar by remember(profile?.id) { mutableStateOf(profile?.avatar.orEmpty()) }
    val muted = Color(0xFF9A9DA7)
    val shape = RoundedCornerShape(12.dp)
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color(0xFFE50914),
        unfocusedBorderColor = Color(0xFF343842), focusedContainerColor = Color(0xFF171A20),
        unfocusedContainerColor = Color(0xFF171A20), focusedLabelColor = Color.White, unfocusedLabelColor = muted)
    BackHandler(onBack = onDismiss)
    Surface(Modifier.fillMaxSize(), color = Color(0xFF090B10), contentColor = Color.White) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("NIKTV", color = Color(0xFFE50914), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                    NikTvTextActionButton(onClick = onDismiss) { Text("Cancel") }
                }
                Text(if (profile == null) "Add user profile" else "Edit profile", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (profile == null) "Start with an empty viewing space. Configure portals after creating it." else "Your name and avatar, on every device.", color = muted, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(76.dp).background(Color(color), shape), contentAlignment = Alignment.Center) {
                        Text(avatar.ifBlank { name.take(1).uppercase().ifBlank { "N" } }, fontSize = 32.sp,
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedTextField(name, { name = it.take(60) }, label = { Text("Display name") }, singleLine = true,
                        shape = shape, colors = fieldColors, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(avatar, { avatar = it.take(8) }, label = { Text("Initials or emoji") }, singleLine = true,
                    placeholder = { Text("Use an initial or emoji") }, shape = shape, colors = fieldColors, modifier = Modifier.fillMaxWidth())
                Text("Avatar color", style = MaterialTheme.typography.labelLarge, color = muted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf("Red" to 0xFFE50914L, "Blue" to 0xFF1565C0L, "Green" to 0xFF2E7D32L, "Purple" to 0xFF7B1FA2L).forEach { (label, value) ->
                        val chosen = color == value
                        Box(Modifier.size(48.dp).border(if (chosen) 2.dp else 1.dp, if (chosen) Color.White else Color(0xFF343842), CircleShape)
                            .remoteFocusFrame(CircleShape).selectable(chosen, role = Role.RadioButton, onClick = { color = value })
                            .semantics { contentDescription = "$label avatar color" }.padding(5.dp).background(Color(value), CircleShape), contentAlignment = Alignment.Center) {
                            if (chosen) Text("✓", fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (profile == null) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(UserProfileType.GENERAL to "General", UserProfileType.KIDS to "Kids").forEach { (choice, label) ->
                        val chosen = type == choice
                        Surface(modifier = Modifier.weight(1f).remoteFocusFrame(shape)
                            .selectable(chosen, role = Role.RadioButton, onClick = { type = choice }), shape = shape,
                            color = if (chosen) Color(0xFF32191E) else Color(0xFF171A20),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (chosen) Color(0xFFE50914) else Color(0xFF343842))) {
                            Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                Text(if (chosen) "✓  $label" else label, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                } else Text(if (profile.type == UserProfileType.KIDS) "Kids profile" else "General profile", color = muted, style = MaterialTheme.typography.labelMedium)
                HorizontalDivider(color = Color(0xFF282B33))
                NikTvPrimaryActionButton(onClick = { onSave(name.trim(), type, color, avatar.trim()) }, enabled = name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (profile == null) "Create profile" else "Save changes")
                }
                onDelete?.let { NikTvTextActionButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("Delete profile", color = Color(0xFFFF727C)) } }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}
