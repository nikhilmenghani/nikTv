package com.nikhil.niktv.ui

import com.nikhil.niktv.sports.openSports
import com.nikhil.niktv.sports.openPlayfy
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nikhil.niktv.update.AppUpdates
import com.nikhil.niktv.update.automaticUpdateChecks
import com.nikhil.niktv.data.AccountSyncManager
import com.nikhil.niktv.data.AccountSyncState
import com.nikhil.niktv.data.UserProfile
import com.nikhil.niktv.data.UserProfileType
import com.nikhil.niktv.data.UserSyncStore
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.data.ProfilePin
import com.nikhil.niktv.data.ProfilePortalAccess
import com.nikhil.niktv.data.ProfilePortalChoice
import com.nikhil.niktv.data.ProfileSetupStore
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.model.PortalProfile
import com.nikhil.niktv.R
import com.nikhil.niktv.discovery.PortalNavigationRequests
import com.nikhil.niktv.discovery.LocalPortalFocusReturn
import com.nikhil.niktv.discovery.PortalFocusReturn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import com.nikhil.niktv.data.ViewerDestinationKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject

internal val LocalOpenAccountScreen = staticCompositionLocalOf<() -> Unit> { {}
 }
internal val LocalOpenUserProfiles = staticCompositionLocalOf<() -> Unit> { {} }
internal val LocalCloseAccountSettings = staticCompositionLocalOf<() -> Unit> { {} }
internal val LocalSelectedUserProfile = staticCompositionLocalOf<UserProfile?> { null }
internal val LocalCloseProfileDownloads = staticCompositionLocalOf<(() -> Unit)?> { null }
internal val LocalOpenConfiguredViewer = staticCompositionLocalOf<((UserProfile) -> Unit)?> { null }

/** Account authentication precedes both the people chooser and every content destination. */
@Composable
internal fun AccountProfileGate(vm: NikTvViewModel, content: @Composable (Boolean) -> Unit) {
    val context = LocalContext.current
    val manager = remember(context) { AccountSyncManager.get(context) }
    val store = remember(context) { UserSyncStore.get(context) }
    val account by manager.state.collectAsStateWithLifecycle()
    val profiles by store.profiles.collectAsStateWithLifecycle(emptyList())
    val enabledIds by store.enabledProfiles.collectAsStateWithLifecycle(emptySet())
    val enabledProfiles = profiles.filter { it.id in enabledIds }
    // Startup restoration can finish before STARTED. Keep this lightweight gate
    // observable whenever composed, so a validated viewer never falls back to the chooser.
    val selectedScope by store.selectedScope.collectAsState()
    val pendingCount by store.pendingChanges.collectAsStateWithLifecycle(0)
    val appState by vm.state.collectAsStateWithLifecycle()
    val pendingUpdate by AppUpdates.pendingUpdate.collectAsStateWithLifecycle()
    val updateDownload by AppUpdates.downloadState.collectAsStateWithLifecycle()
    val enforceUpdates by AppUpdates.updateEnforcementEnabled.collectAsStateWithLifecycle()
    val autoCheckEnabled by AppUpdates.startupUpdateCheckEnabled.collectAsStateWithLifecycle()
    val availableUpdate = pendingUpdate ?: updateDownload.updateInfoOrNull()
    val updateKnown = availableUpdate != null
    val playing = appState.nowPlaying != null
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var checkingUpdate by remember { mutableStateOf(enforceUpdates && autoCheckEnabled && !updateKnown) }
    LaunchedEffect(lifecycle, autoCheckEnabled, playing, updateKnown) {
        if (!autoCheckEnabled || playing || updateKnown) {
            checkingUpdate = false
            return@LaunchedEffect
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            automaticUpdateChecks(
                check = { AppUpdates.check() },
                available = { AppUpdates.notifyAvailable(context, it) },
                attemptFinished = { checkingUpdate = false },
                failed = { android.util.Log.w("AppUpdates", "Foreground update check failed; retrying in one minute", it) },
            )
        }
    }
    val requestedPortal by PortalNavigationRequests.pending.collectAsStateWithLifecycle()
    var portalEntryFocus by remember { mutableStateOf<Pair<UserScope, PortalFocusReturn>?>(null) }
    fun preparePortalFocus(owner: UserScope, request: PortalNavigationRequests.Request) {
        portalEntryFocus = if (request.restoreFocus) owner to PortalFocusReturn().apply { clicked(request.key) } else null
    }
    val scope = rememberCoroutineScope()
    val launchBranding = LocalLaunchBrandingProgress.current
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
    var pendingProfilePin by remember { mutableStateOf<ProfilePinRequest?>(null) }
    var settingProfilePin by remember { mutableStateOf<UserProfile?>(null) }
    var viewerSetup by remember { mutableStateOf<UserProfile?>(null) }
    var openingConfiguredViewer by remember { mutableStateOf<UserProfile?>(null) }
    var configuredViewerError by remember { mutableStateOf(false) }
    var configuredViewerJob by remember { mutableStateOf<Job?>(null) }
    suspend fun authorizeProfile(profile: UserProfile, deleting: Boolean = false, changingPin: Boolean = false): Boolean {
        val accountId = store.selectedAccount.value ?: return false
        if (!deleting && !changingPin && profile.pinVerifier != null && com.nikhil.niktv.data.ProfilePin.remembered(context, accountId, profile)) return true
        val request = ProfilePinRequest(profile, deleting, allowOffline = !deleting && !changingPin) {
            manager.profileForPasswordCheck(accountId, profile.id)
        }
        pendingProfilePin?.result?.complete(false)
        pendingProfilePin = request
        return try {
            val accepted = request.result.await()
            val current = store.profiles.first().firstOrNull { it.id == profile.id }
            val valid = accepted && store.selectedAccount.value == accountId && current?.pinVerifier == request.authorizedVerifier && current != null
            if (valid && !deleting) {
                val authorized = request.newPinVerifier?.let { store.setProfilePin(profile.id, it, onlyIfUnset = true) } ?: requireNotNull(current)
                if (authorized.pinVerifier != null) com.nikhil.niktv.data.ProfilePin.remember(context, accountId, authorized)
                if (request.newPinVerifier != null) manager.requestProfileSync(accountId)
                if (changingPin && request.newPinVerifier == null) settingProfilePin = authorized
            }
            valid
        } finally { if (pendingProfilePin === request) pendingProfilePin = null }
    }
    LaunchedEffect(pendingProfilePin, profiles) {
        pendingProfilePin?.let { request ->
            profiles.firstOrNull { it.id == request.profile.id }?.let(request::refresh)
                ?: request.result.complete(false)
        }
    }
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
    suspend fun activateViewerPortal(owner: UserScope, portal: PortalProfile, waitForHome: Boolean = false) {
        if (store.selectedScope.value != owner) throw CancellationException("Viewer changed")
        val current = vm.state.value
        val portalKey = portal.cacheKey()
        if (!current.loading && current.session?.profile?.cacheKey() == portalKey &&
            current.savedProfile?.cacheKey() == portalKey) {
            store.rememberDestination(owner, com.nikhil.niktv.data.ViewerDestination(ViewerDestinationKind.PORTAL, portal.cacheKey()))
            return
        }
        vm.switchProfile(portal)
        // Let the activation clear a previous error before observing its result.
        yield()
        kotlinx.coroutines.withTimeout(95_000L) {
            vm.state.first { store.selectedScope.value != owner ||
                it.isViewerPortalReady(portalKey, waitForHome) || (!it.loading && it.error != null) }
        }
        if (store.selectedScope.value != owner) throw CancellationException("Viewer changed")
        if (!vm.state.value.isViewerPortalReady(portalKey, waitForHome))
            throw IllegalStateException(vm.state.value.error ?: "Could not open ${portal.displayName}.")
    }
    suspend fun openViewer(profileId: String, offlineOnly: Boolean = false, atLaunch: Boolean = false,
        afterSetup: Boolean = false) {
        require(profileId in store.enabledProfileIds(requireNotNull(store.selectedAccount.value))) { "Enable this profile in Settings first." }
        val targetProfile = store.profiles.first().first { it.id == profileId }
        if (!afterSetup && !authorizeProfile(targetProfile)) { choosingProfile = true; return }
        val owner = UserScope(requireNotNull(store.selectedAccount.value), profileId)
        com.nikhil.niktv.data.UserProfilePreferences.warm(context, owner)
        val current = vm.state.value
        val previousOwner = store.selectedScope.value
        store.selectProfile(profileId)
        // Let the ViewModel dispose the previous viewer before starting portal work.
        yield()
        profileDownloads = offlineOnly
        if (offlineOnly) {
            vm.openProfileDownloads()
            selectedUser = profileId
            choosingProfile = false
            return
        }
        val destination = store.rememberedDestination(owner)
        val portals = legacyStore.scoped(owner).profiles.first().filter {
            ProfilePortalChoice(it, saved = true).ready && ProfilePortalAccess.allowed(context, it, owner)
        }
        if (store.selectedScope.value != owner) return
        suspend fun openPortal(portal: PortalProfile) {
            activateViewerPortal(owner, portal, waitForHome = atLaunch)
            if (atLaunch && launchBranding != null) snapshotFlow { launchBranding.value }.first { it >= 1f }
        }
        val sportsDestination = when (destination?.kind) {
            ViewerDestinationKind.SPORTS -> ProfilePortalAccess.SPORTS
            ViewerDestinationKind.PLAYFY -> ProfilePortalAccess.PLAYFY
            else -> null
        }?.let { it !in ProfilePortalAccess.hidden(context, owner) } == true
        if (portals.isEmpty() && !sportsDestination) {
            check(!afterSetup) { "This profile has no available starting portal." }
            vm.resetPortalSession()
            viewerSetup = store.profiles.first().first { it.id == profileId }
            return
        }
        val staysOnPortal = afterSetup && previousOwner == owner && !current.loading && destination?.kind == ViewerDestinationKind.PORTAL &&
            destination.portalKey == current.session?.profile?.cacheKey() && portals.any { it.cacheKey() == destination.portalKey }
        if (staysOnPortal) {
            vm.closeSettings()
            vm.openHome()
        } else {
            vm.resetPortalSession()
        }
        // An explicit Sports -> IPTV handoff takes priority over the saved Sports destination.
        val requested = PortalNavigationRequests.pending.value?.takeUnless { afterSetup || it.key == PortalNavigationRequests.PROFILE_CHOOSER }
        if (requested != null) {
            try {
                val portal = portals.firstOrNull { it.cacheKey() == requested.key }
                    ?: throw IllegalStateException("This portal is unavailable for this profile.")
                openPortal(portal)
                if (PortalNavigationRequests.pending.value === requested && store.selectedScope.value == owner) {
                    preparePortalFocus(owner, requested)
                }
            } finally { PortalNavigationRequests.pending.compareAndSet(requested, null) }
        } else when (destination?.kind) {
            ViewerDestinationKind.OFFLINE -> {
                // Older versions remembered Downloads as a portal destination. Resume
                // the retained content portal now that Downloads lives on the people screen.
                val previous = store.rememberedPortalKey(owner)
                    ?: legacyStore.scoped(owner).activeProfile.first()?.cacheKey()
                preferredViewerPortal(portals, previous)?.let { openPortal(it) }
            }
            ViewerDestinationKind.SPORTS -> if (sportsDestination)
                context.openSports()
                else portals.firstOrNull()?.let { openPortal(it) }
            ViewerDestinationKind.PLAYFY -> if (sportsDestination)
                context.openPlayfy()
                else portals.firstOrNull()?.let { openPortal(it) }
            ViewerDestinationKind.PORTAL -> preferredViewerPortal(portals, destination.portalKey)?.let { openPortal(it) }
            null -> {
                // Upgrade an existing device's last portal only when it belongs to this viewer.
                val active = legacyStore.scoped(owner).activeProfile.first()
                preferredViewerPortal(portals, active?.cacheKey())?.let { openPortal(it) }
            }
        }
        if (store.selectedScope.value != owner) return
        selectedUser = profileId
        choosingProfile = false
    }
    fun openConfiguredViewer(profile: UserProfile) {
        if (configuredViewerJob?.isActive == true) return
        openingConfiguredViewer = profile
        configuredViewerError = false
        viewerSetup = null
        // This job belongs to the gate, so removing the editor or Settings leaves
        // a single visible route until the dashboard or retry screen is ready.
        configuredViewerJob = scope.launch {
            try {
                openViewer(profile.id, afterSetup = true)
                showSettings = false
                openingConfiguredViewer = null
            } catch (_: TimeoutCancellationException) {
                configuredViewerError = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                configuredViewerError = true
            }
        }
    }
    fun leaveConfiguredViewer(edit: Boolean) {
        val profile = openingConfiguredViewer
        configuredViewerJob?.cancel()
        vm.cancelPortalSwitch()
        openingConfiguredViewer = null
        openProfiles()
        viewerSetup = profile.takeIf { edit }
    }
    suspend fun restoreViewer(cached: AccountSyncState, atLaunch: Boolean) {
        val id = cached.accountId ?: return
        if (!cached.initialized || cached.reconnectRequired || restoredAccount == id) return
        restoringViewer = true
        try {
            val request = PortalNavigationRequests.pending.value
            if (request?.key == PortalNavigationRequests.PROFILE_CHOOSER && PortalNavigationRequests.pending.compareAndSet(request, null)) {
                openProfiles()
            } else {
                store.rememberedProfileId(id)?.let { profileId ->
                    if (store.selectedAccount.value == id && profileId in store.enabledProfileIds(id)) {
                        profileAction("Could not restore this profile. Select a profile to continue.") {
                            openViewer(profileId, atLaunch = atLaunch)
                        }
                    }
                }
            }
            restoredAccount = id
        } finally { restoringViewer = false }
    }
    LaunchedEffect(manager) {
        val cached = manager.restoreCachedAccount()
        manager.requestLaunchSync()
        try { restoreViewer(cached, atLaunch = true) }
        finally {
            launchComplete = true
            restoringViewer = false
        }
        hasLegacy = legacyStore.hasLegacyViewingData()
    }
    LaunchedEffect(account.accountId) {
        if (launchComplete && restoredAccount != account.accountId) {
            selectedUser = null
            choosingProfile = false
            profileDownloads = false
            useCachedProfiles = false
            profileError = null
            mappingProfile = null
            pendingProfilePin?.result?.complete(false)
            settingProfilePin = null
            viewerSetup = null
            configuredViewerJob?.cancel()
            openingConfiguredViewer = null
        }
    }
    LaunchedEffect(launchComplete, account.accountId, account.initialized, account.reconnectRequired) {
        if (launchComplete) restoreViewer(account, atLaunch = false)
    }
    // MainActivity can resume behind Who's watching after Sports, so the gate
    // owns external navigation rather than waiting for its hidden content to run.
    LaunchedEffect(requestedPortal, launchComplete, restoringViewer, account.accountId, account.initialized,
        selectedUser, selectedScope, enabledIds, showAccount, account.reconnectRequired, useCachedProfiles,
        openingConfiguredViewer, viewerSetup) {
        val requested = requestedPortal ?: return@LaunchedEffect
        if (!launchComplete || restoringViewer || !account.initialized || openingConfiguredViewer != null || viewerSetup != null) return@LaunchedEffect
        if (requested.key == PortalNavigationRequests.PROFILE_CHOOSER) {
            PortalNavigationRequests.pending.compareAndSet(requested, null)
            openProfiles()
            return@LaunchedEffect
        }
        if (showAccount || account.reconnectRequired && !useCachedProfiles) return@LaunchedEffect
        val viewer = selectedUser ?: return@LaunchedEffect
        val owner = selectedScope ?: return@LaunchedEffect
        if (owner.accountId != account.accountId || owner.profileId != viewer || viewer !in enabledIds) return@LaunchedEffect
        restoringViewer = true
        // Clearing a consumed request must not cancel the portal activation itself.
        scope.launch {
            try {
                profileAction("Could not open this portal. Select your profile to retry.") {
                    val portal = legacyStore.scoped(owner).profiles.first().firstOrNull {
                        it.cacheKey() == requested.key && com.nikhil.niktv.data.ProfilePortalAccess.allowed(context, it, owner)
                    } ?: throw IllegalStateException("This portal is unavailable for this profile.")
                    // A loaded viewer keeps their context; switching a portal does not select the person again.
                    activateViewerPortal(owner, portal)
                    if (store.selectedScope.value == owner) {
                        if (PortalNavigationRequests.pending.value === requested) preparePortalFocus(owner, requested)
                        choosingProfile = false
                        showAccount = false
                        showSettings = false
                        profileDownloads = false
                    }
                }
            } finally {
                PortalNavigationRequests.pending.compareAndSet(requested, null)
                restoringViewer = false
            }
        }
    }
    LaunchedEffect(selectedScope) {
        if (selectedUser != null && store.selectedScope.value?.profileId != selectedUser) selectedUser = null
        if (portalEntryFocus?.first != store.selectedScope.value) portalEntryFocus = null
    }
    LaunchedEffect(appState.settingsOpen, appState.profileEditorOpen) {
        if (showSettings && appState.settingsOpen) settingsEntered = true
        if (showSettings && settingsEntered && !appState.settingsOpen && !appState.profileEditorOpen && openingConfiguredViewer == null) {
            showSettings = false
            vm.resetPortalSession()
        }
    }
    // Profile names and enabled tiles load independently from selection. The live
    // selection has already been validated and is cleared when disabled/deleted.
    val activeOwner = selectedScope
    val defaultPortalFocus = remember(activeOwner) { PortalFocusReturn() }
    val portalFocus = portalEntryFocus?.takeIf { it.first == activeOwner }?.second ?: defaultPortalFocus
    val selectedViewerAvailable = activeOwner != null && activeOwner.profileId == selectedUser &&
        activeOwner.accountId == account.accountId
    CompositionLocalProvider(LocalOpenAccountScreen provides ::openAccount, LocalOpenUserProfiles provides ::openProfiles,
        LocalCloseProfileDownloads provides if (profileDownloads) ({ vm.closeOfflineDownloads(); openProfiles() }) else null,
        LocalCloseAccountSettings provides { showSettings = false },
        LocalOpenConfiguredViewer provides ::openConfiguredViewer, LocalPortalFocusReturn provides portalFocus,
        LocalViewingOwner provides activeOwner,
        LocalSelectedUserProfile provides profiles.firstOrNull { it.id == selectedUser }) {
        MaterialTheme(colorScheme = NikColors) {
            Surface(Modifier.fillMaxSize()) {
                when {
                    !launchComplete -> NikTvLaunchBranding()
                    restoringViewer -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    openingConfiguredViewer != null -> {
                        val profile = requireNotNull(openingConfiguredViewer)
                        BackHandler { leaveConfiguredViewer(edit = false) }
                        if (configuredViewerError) ProfileOpenRecovery(profile.name,
                            retry = { openConfiguredViewer(profile) },
                            edit = { leaveConfiguredViewer(edit = true) },
                            back = { leaveConfiguredViewer(edit = false) })
                        else ProfileLoadingScreen(profile.name, if (appState.profileLoadProgress != null) appState.profileLoadMessage else "Opening dashboard…",
                            appState.profileLoadProgress ?: 0f)
                    }
                    viewerSetup != null -> {
                        val profile = requireNotNull(viewerSetup)
                        ProfileSetupEditor(profile, onDismiss = { viewerSetup = null; openProfiles() }, onSave = { draft ->
                            val saved = ProfileSetupStore.save(context, draft)
                            if (draft.canOpen) openConfiguredViewer(saved)
                            else { viewerSetup = null; openProfiles() }
                        }, onDelete = null, requirePortal = true, openAfterSave = true,
                            load = { ProfileSetupStore.load(context, profile) })
                    }
                    showSettings -> content(checkingUpdate)
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
                            vm.resetPortalSession()
                            vm.openAccountSettings()
                            showSettings = true
                        },
                        onDisconnect = { scope.launch { vm.resetPortalSession(); manager.disconnect(); selectedUser = null } }
                    )
                    choosingProfile || !selectedViewerAvailable -> {
                        var showUpdate by remember { mutableStateOf(false) }
                        AccountGateBackHandler(peopleVisible = true, choosingProfile = choosingProfile,
                            selectedViewerAvailable = selectedViewerAvailable, state = appState,
                            openPeople = ::openProfiles, resumeContent = { choosingProfile = false })
                        UserProfilesScreen(
                        profiles = enabledProfiles,
                        allProfiles = profiles,
                        state = if (!account.syncing && pendingCount > 0 && account.error == null)
                            account.copy(message = "Changes saved on this device") else account,
                        error = profileError,
                        onSelect = { profile -> scope.launch {
                            profileAction("Could not select this profile. Retry after reconnecting.") {
                                openViewer(profile.id)
                            }
                        } },
                        onSaveSetup = { draft ->
                            val saved = ProfileSetupStore.save(context, draft)
                            if (!draft.existing) {
                                if (saved.pinVerifier != null) ProfilePin.remember(context, requireNotNull(draft.accountId), saved)
                                if (draft.canOpen) openConfiguredViewer(saved)
                            }
                        },
                        onDelete = { profile -> scope.launch { profileAction("Could not delete this profile. Retry from this screen.") { if (authorizeProfile(profile, deleting = true)) store.deleteProfile(profile.id) } } },
                        onChangePin = { profile -> scope.launch {
                            profileAction("Could not change this profile's password. Retry from this screen.") {
                                authorizeProfile(profile, changingPin = true)
                            }
                        } },
                        onManagePortals = { mappingProfile = it },
                        onImportLegacy = if (hasLegacy) { profile -> scope.launch {
                            profileAction("Could not import device viewing data. Your existing data is preserved; retry from this screen.") {
                                val accountId = requireNotNull(account.accountId)
                                legacyStore.importLegacyToProfile(UserScope(accountId, profile.id))
                                hasLegacy = legacyStore.hasLegacyViewingData()
                            }
                        } } else null,
                        onAccount = ::openAccount,
                        onSettings = {
                            settingsEntered = false
                            vm.resetPortalSession()
                            vm.openAccountSettings()
                            showSettings = true
                        },
                        onSync = manager::requestManualSync,
                        selectedProfileId = selectedUser,
                        updateVersion = availableUpdate?.version,
                        onUpdate = { showUpdate = true },
                        onOfflineDownloads = { profile -> scope.launch {
                            profileAction("Could not open local downloads. Your files are preserved.") {
                                openViewer(profile.id, offlineOnly = true)
                            }
                        } }
                    )
                        if (showUpdate && availableUpdate != null) {
                            Dialog(onDismissRequest = { showUpdate = false },
                                properties = DialogProperties(usePlatformDefaultWidth = false)) {
                                MandatoryNikTvUpdateScreen(false, availableUpdate, updateDownload, enforceUpdates,
                                    AppUpdates::setUpdateEnforcementEnabled, onLater = { showUpdate = false })
                            }
                        }
                    }
                    else -> {
                        AccountGateBackHandler(peopleVisible = false, choosingProfile = choosingProfile,
                            selectedViewerAvailable = true, state = appState,
                            openPeople = ::openProfiles, resumeContent = { choosingProfile = false })
                        content(checkingUpdate)
                    }
                }
            }
            mappingProfile?.let { profile ->
                account.accountId?.let { accountId -> PortalMappingsDialog(UserScope(accountId, profile.id), profile.name, store, legacyStore) { mappingProfile = null } }
            }
            pendingProfilePin?.let { ProfilePinPrompt(it) }
            settingProfilePin?.let { profile -> SetProfilePinPrompt(profile, { settingProfilePin = null }) { verifier ->
                val updated = store.setProfilePin(profile.id, verifier, onlyIfUnset = profile.pinVerifier == null)
                store.selectedAccount.value?.let {
                    com.nikhil.niktv.data.ProfilePin.remember(context, it, updated)
                    manager.requestProfileSync(it)
                }
            } }
        }
    }
}

@Composable
private fun ProfileOpenRecovery(name: String, retry: () -> Unit, edit: () -> Unit, back: () -> Unit) {
    val retryFocus = remember { FocusRequester() }
    val remote = LocalContext.current.usesRemoteNavigation(LocalConfiguration.current) ||
        androidx.compose.ui.platform.LocalInputModeManager.current.inputMode == androidx.compose.ui.input.InputMode.Keyboard
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 380.dp).fillMaxWidth().initialFocusEntry(retryFocus, remote),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Could not open $name", style = MaterialTheme.typography.titleLarge)
            Text("Check the portal connection or choose another starting portal.", textAlign = TextAlign.Center)
            NikTvPrimaryActionButton(retry, Modifier.focusRequester(retryFocus)) { Text("Retry") }
            NikTvSecondaryActionButton(edit) { Text("Edit profile setup") }
            NikTvTextActionButton(back) { Text("Back to profiles") }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
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
    var tokenEditKeyDown by remember { mutableStateOf(false) }
    var tokenEditing by remember { mutableStateOf(false) }
    var tokenSessionActive by remember { mutableStateOf(false) }
    var tokenWindowLost by remember { mutableStateOf(false) }
    LaunchedEffect(state.initialized, state.syncing, state.error) {
        if (state.initialized && !state.syncing && state.error == null) token = ""
    }
    val first = remember { FocusRequester() }
    val remote = LocalContext.current.usesRemoteNavigation(LocalConfiguration.current)
    val keyboard = LocalSoftwareKeyboardController.current
    val editScope = rememberCoroutineScope()
    fun activateTokenEditing() {
        tokenEditing = true
        tokenSessionActive = true
        editScope.launch { withFrameNanos { }; first.requestFocus(); keyboard?.show() }
    }
    var tokenImeSeen by remember { mutableStateOf(false) }
    val tokenImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    fun finishTokenEditing() {
        tokenSessionActive = false
        tokenWindowLost = false
        tokenEditing = false
        tokenEditKeyDown = false
        keyboard?.hide()
        editScope.launch { withFrameNanos { }; runCatching { first.requestFocus() } }
    }
    val tokenWindowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(tokenWindowFocused, tokenSessionActive) {
        if (tokenSessionActive && !tokenWindowFocused) tokenWindowLost = true
        else if (tokenSessionActive && tokenWindowLost) finishTokenEditing()
    }
    LaunchedEffect(tokenImeVisible, tokenEditing) {
        if (tokenImeVisible && tokenEditing) tokenImeSeen = true
        else if (!tokenImeVisible && tokenImeSeen) {
            tokenImeSeen = false
            finishTokenEditing()
        }
    }
    if (state.initialized && !state.reconnectRequired) BackHandler(onBack = onProfiles)
    Column(
        Modifier.fillMaxSize().initialFocusEntry(first, remote).background(Color(0xFF090909)).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("NikTV", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("GitHub account", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileChooserAction("Remote control", Icons.Default.SettingsRemote, LocalOpenLanPlayback.current, Modifier.weight(1f))
                ProfileChooserAction("Settings", Icons.Default.Settings, onSettings, Modifier.weight(1f))
            }
            Text(state.login?.let { "Connected as $it" } ?: "Connect your GitHub account to sync the people watching and their viewing data.")
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text(if (state.accountId == null) "GitHub token" else "Replacement GitHub token") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true, readOnly = !tokenEditing,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.White,
                    unfocusedBorderColor = Color(0xFF454B58),
                    focusedLabelColor = Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, showKeyboardOnFocus = false),
                keyboardActions = KeyboardActions(onDone = { finishTokenEditing() }),
                enabled = !state.syncing, modifier = Modifier.fillMaxWidth().focusRequester(first).focusProperties { canFocus = true }
                    .onFocusChanged { if (!it.isFocused) { tokenEditing = false; tokenEditKeyDown = false } }
                    .onPreInterceptKeyBeforeSoftKeyboard { event ->
                        if (tokenSessionActive && event.key == Key.Back) {
                            if (event.type == KeyEventType.KeyUp) finishTokenEditing()
                            true
                        } else false
                    }
                    .pointerInput(Unit) { awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) activateTokenEditing()
                    } }
                    .onPreviewKeyEvent { event ->
                        if (event.key in menuActivationKeys) {
                            if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0)
                                tokenEditKeyDown = true
                            if (event.type == KeyEventType.KeyUp) {
                                if (tokenEditKeyDown) activateTokenEditing()
                                tokenEditKeyDown = false
                            }
                            true
                        } else false
                    }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NikTvPrimaryActionButton(onClick = { onConnect(token.trim()) },
                    enabled = token.isNotBlank() && !state.syncing, modifier = Modifier.weight(1f)) {
                    Text(if (state.accountId == null) "Connect" else "Replace token")
                }
                if (state.error != null || state.accountId != null) NikTvSecondaryActionButton(
                    onClick = { if (token.isNotBlank()) onConnect(token.trim()) else onRetry() },
                    enabled = !state.syncing, modifier = Modifier.weight(1f)) { Text("Retry sync") }
            }
            if (state.syncing) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(state.message.ifBlank { "Connecting and syncing…" })
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.initialized) NikTvPrimaryActionButton(onClick = onProfiles, modifier = Modifier.weight(1f)) {
                    Text(if (state.error == null && !state.reconnectRequired) "User profiles" else "Cached profiles")
                }
                if (state.accountId != null) NikTvSecondaryActionButton(onClick = { token = ""; onDisconnect() },
                    enabled = !state.syncing, modifier = Modifier.weight(1f)) { Text("Disconnect") }
            }
        }
    }
}

@Composable
internal fun UserProfilesScreen(
    profiles: List<UserProfile>, state: AccountSyncState, error: String?,
    onSelect: (UserProfile) -> Unit,
    onSave: ((UserProfile?, String, UserProfileType, Long, String) -> Unit)? = null,
    onDelete: (UserProfile) -> Unit, onAccount: () -> Unit, onSync: () -> Unit,
    onImportLegacy: ((UserProfile) -> Unit)? = null,
    onManagePortals: ((UserProfile) -> Unit)? = null,
    onChangePin: ((UserProfile) -> Unit)? = null,
    selectedProfileId: String? = null,
    onOfflineDownloads: ((UserProfile) -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    updateVersion: String? = null,
    onUpdate: (() -> Unit)? = null,
    onSaveSetup: (suspend (com.nikhil.niktv.data.ProfileSetupDraft) -> Unit)? = null,
    allProfiles: List<UserProfile> = profiles,
) {
    var editing by remember { mutableStateOf<UserProfile?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf<UserProfile?>(null) }
    var managementKeyTime by remember { mutableStateOf<Long?>(null) }
    var managementPicker by remember { mutableStateOf(false) }
    var offlinePicker by remember { mutableStateOf(false) }
    var tools by remember { mutableStateOf<UserProfile?>(null) }
    var deleting by remember { mutableStateOf<UserProfile?>(null) }
    var importing by remember { mutableStateOf<UserProfile?>(null) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val remote = context.usesRemoteNavigation(configuration) ||
        androidx.compose.ui.platform.LocalInputModeManager.current.inputMode == androidx.compose.ui.input.InputMode.Keyboard
    // Entry focus belongs to this visit, not to selection/sync updates while a portal opens.
    val pullOnly by remember(context) { AccountSyncManager.get(context).manualPullOnly }.collectAsStateWithLifecycle()
    val first = remember { FocusRequester() }
    val entryProfileId = remember { selectedProfileId }
    val offlineEntry = remember { FocusRequester() }
    val managementEntry = remember { FocusRequester() }
    val managementPickerEntry = remember { FocusRequester() }
    val deleteCancel = remember { FocusRequester() }
    val preferred = entryProfileId?.takeIf { id -> profiles.any { it.id == id } } ?: profiles.firstOrNull()?.id
    fun edit(profile: UserProfile?) { editing = profile; editorOpen = true; managing = null; managementPicker = false }

    if (editorOpen) ProfileSetupEditor(editing, onDismiss = { editorOpen = false }, onSave = { draft ->
        if (onSaveSetup != null) onSaveSetup(draft)
        else onSave?.invoke(editing, draft.profile.name, draft.profile.type, draft.profile.color, draft.profile.avatar)
        editorOpen = false
    }, onDelete = editing?.let { profile -> { deleting = profile } },
        load = { com.nikhil.niktv.data.ProfileSetupStore.load(context, editing) })
    else BoxWithConstraints(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
        listOf(Color(0xFF111318), Color(0xFF090A0C), Color(0xFF060606)))).safeDrawingPadding()) {
        val shortWide = maxWidth >= 600.dp && maxHeight < 600.dp
        val actionColumns = if (maxWidth >= 600.dp) 3 else 2
        val tileWidth = if (shortWide) 96.dp else if (maxWidth < 600.dp) 108.dp else 128.dp
        val gap = if (maxWidth < 600.dp) 20.dp else 28.dp
        val columns = ((maxWidth - 32.dp + gap) / (tileWidth + gap)).toInt().coerceIn(1, 5)
        val entries = profiles.map<UserProfile, UserProfile?> { it } + listOf(null)
        Column(Modifier.fillMaxSize().initialFocusEntry(first, remote).verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight).padding(horizontal = 16.dp, vertical = if (shortWide) 12.dp else 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Image(painterResource(R.drawable.niktv_logo_foreground), contentDescription = "NikTV",
                modifier = Modifier.size(if (shortWide) 40.dp else 64.dp).graphicsLayer { scaleX = 1.8f; scaleY = 1.8f },
                contentScale = ContentScale.Fit)
            Spacer(Modifier.height(if (shortWide) 10.dp else 14.dp))
            Text("Who's watching?", style = if (shortWide) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (profiles.isEmpty()) Text(
                if (allProfiles.isEmpty()) "Add a profile to get started." else "Enable a profile in Settings to show it here.",
                style = MaterialTheme.typography.bodySmall, color = Color(0xFF9699A2), textAlign = TextAlign.Center)
            if (updateVersion != null && onUpdate != null) {
                Spacer(Modifier.height(8.dp))
                NikTvPrimaryActionButton(onUpdate, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Update available · $updateVersion", style = MaterialTheme.typography.labelLarge)
                }
            }
            Spacer(Modifier.height(if (shortWide) 12.dp else 20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                entries.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.Top) {
                        row.forEach { profile ->
                            key(profile?.id ?: "add-person") {
                                UserProfileTile(profile, tileWidth, selected = profile != null && profile.id == selectedProfileId,
                                    modifier = if (profile?.id == preferred || profile == null && profiles.isEmpty()) Modifier.focusRequester(first) else Modifier,
                                    select = { if (profile == null) edit(null) else onSelect(profile) },
                                    manage = { keyTime -> profile?.let { managementKeyTime = keyTime; managing = it } })
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(if (shortWide) 10.dp else 14.dp))
            if (profiles.isNotEmpty()) Text(if (remote) "Hold OK or press Menu to manage a profile" else "Touch and hold a profile to manage it",
                color = Color(0xFF9699A2), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            val actions = buildList<Triple<String, ImageVector, () -> Unit>> {
                if (allProfiles.isNotEmpty()) add(Triple("Manage profiles", Icons.Default.People) { managementPicker = true })
                if (profiles.isNotEmpty()) {
                    onOfflineDownloads?.let { open -> add(Triple("Offline downloads", Icons.Default.Download) {
                        if (profiles.size == 1) open(profiles.single()) else offlinePicker = true
                    }) }
                }
                add(Triple("GitHub account", Icons.Default.AccountCircle, onAccount))
                add(Triple(if (pullOnly) "Pull profiles" else "Sync now", Icons.Default.Sync, onSync))
                add(Triple("Remote control", Icons.Default.SettingsRemote, LocalOpenLanPlayback.current))
                onSettings?.let { add(Triple("Settings", Icons.Default.Settings, it)) }
            }
            Column(Modifier.widthIn(max = if (actionColumns == 3) 720.dp else 520.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.chunked(actionColumns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (label, icon, action) ->
                            ProfileChooserAction(label, icon, action, Modifier.weight(1f),
                                enabled = label !in setOf("Sync now", "Pull profiles") || !state.syncing,
                                compact = shortWide)
                        }
                        repeat(actionColumns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            (error ?: state.error)?.let { Text(it, modifier = Modifier.widthIn(max = 640.dp),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center) }
            Row(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                if (state.syncing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(if (state.syncing) state.message.ifBlank { "Syncing…" } else state.message.ifBlank { "Changes are saved on this device" },
                    color = Color(0xFF9699A2), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            }
        }
    }
    if (managementPicker) AlertDialog(modifier = Modifier.initialFocusEntry(managementPickerEntry, remote),
        onDismissRequest = { managementPicker = false },
        title = { Text("Manage profiles", style = MaterialTheme.typography.titleLarge) },
        text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            allProfiles.forEach { profile -> NikTvSecondaryActionButton(onClick = { edit(profile) }, modifier = Modifier.fillMaxWidth()
                .then(if (profile.id == (preferred ?: allProfiles.firstOrNull()?.id)) Modifier.focusRequester(managementPickerEntry) else Modifier)) {
                Column {
                    Text(profile.name)
                    if (profiles.none { it.id == profile.id }) Text("Not shown on this device", style = MaterialTheme.typography.labelSmall)
                }
            } }
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
    managing?.let { profile -> AlertDialog(
        // The dialog receives the rest of the key gesture that opened it.
        // Swallow its repeats and release, but accept the next deliberate OK.
        modifier = Modifier.initialFocusEntry(managementEntry, remote).onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            val openingGesture = managementKeyTime == native.downTime && native.keyCode in setOf(
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER, android.view.KeyEvent.KEYCODE_MENU)
            if (openingGesture && event.type == KeyEventType.KeyUp) managementKeyTime = null
            openingGesture
        }, onDismissRequest = { managing = null },
        title = { Text(profile.name, style = MaterialTheme.typography.titleLarge) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NikTvSecondaryActionButton(onClick = { edit(profile) }, modifier = Modifier.fillMaxWidth().focusRequester(managementEntry)) { Text("Manage profile") }
            onChangePin?.let { change -> NikTvSecondaryActionButton(onClick = { managing = null; change(profile) }, modifier = Modifier.fillMaxWidth()) { Text("Profile PIN") } }
            if (onImportLegacy != null || onManagePortals != null) NikTvTextActionButton(onClick = { managing = null; tools = profile }, modifier = Modifier.fillMaxWidth()) { Text("Profile tools") }
        } }, confirmButton = { NikTvTextActionButton(onClick = { managing = null }) { Text("Cancel") } }) }
    tools?.let { profile -> AlertDialog(onDismissRequest = { tools = null },
        title = { Text("Tools for ${profile.name}", style = MaterialTheme.typography.titleLarge) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            onImportLegacy?.let { NikTvSecondaryActionButton(onClick = { tools = null; importing = profile }, modifier = Modifier.fillMaxWidth()) { Text("Import existing device viewing data") } }
            onManagePortals?.let { NikTvSecondaryActionButton(onClick = { tools = null; it(profile) }, modifier = Modifier.fillMaxWidth()) { Text("Map portals on this device") } }
        } }, confirmButton = { NikTvTextActionButton(onClick = { tools = null }) { Text("Done") } }) }
    deleting?.let { profile -> AlertDialog(modifier = Modifier.initialFocusEntry(deleteCancel, remote),
        onDismissRequest = { deleting = null }, title = { Text("Delete ${profile.name} across devices?") },
        text = { Text("This removes this user profile and its viewing data from the account after the next sync. Local downloaded media files remain on their device.") },
        confirmButton = { NikTvPrimaryActionButton(onClick = { onDelete(profile); deleting = null; editorOpen = false }) { Text("Delete across devices") } },
        dismissButton = { NikTvTextActionButton(onClick = { deleting = null }, modifier = Modifier.focusRequester(deleteCancel)) { Text("Cancel") } }) }
    importing?.let { profile -> AlertDialog(onDismissRequest = { importing = null },
        title = { Text("Import into ${profile.name}?") },
        text = { Text("Assign this device's viewing data from the previous NikTV version to this user profile. This import is performed once and preserves existing account data.") },
        confirmButton = { NikTvPrimaryActionButton(onClick = { onImportLegacy?.invoke(profile); importing = null }) { Text("Import into ${profile.name}") } },
        dismissButton = { NikTvTextActionButton(onClick = { importing = null }) { Text("Cancel") } }) }
}

@Composable
private fun ProfileChooserAction(label: String, icon: ImageVector, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, compact: Boolean = false) {
    NikTvSecondaryActionButton(onClick, modifier.height(if (compact) 48.dp else 52.dp), enabled = enabled,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserProfileTile(profile: UserProfile?, width: Dp, selected: Boolean, modifier: Modifier,
    select: () -> Unit, manage: (Long?) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var activationDownTime by remember { mutableStateOf<Long?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val keyScope = rememberCoroutineScope()
    var holdJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var held by remember { mutableStateOf(false) }
    var keyPress by remember { mutableStateOf<androidx.compose.foundation.interaction.PressInteraction.Press?>(null) }
    fun finishKey() {
        holdJob?.cancel(); holdJob = null
        keyPress?.let { interaction.tryEmit(androidx.compose.foundation.interaction.PressInteraction.Release(it)) }
        keyPress = null; activationDownTime = null; held = false
    }
    val pressed by interaction.collectIsPressedAsState()
    val label = profile?.let { "Watch as ${it.name}" } ?: "Add user profile"
    val shape = RoundedCornerShape(12.dp)
    val scale by animateFloatAsState(
        if (focused) com.nikhil.niktv.discovery.DiscoveryPosterFocusScale else 1f,
        tween(if (focused) 380 else 320, easing = CubicBezierEasing(.2f, 0f, 0f, 1f)),
        label = "profileFocusScale")
    Column(modifier.width(width).onFocusChanged { focused = it.isFocused; if (!it.isFocused) finishKey() }
        .onPreviewKeyEvent { event ->
            val native = event.nativeKeyEvent
            val activation = native.keyCode in setOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
            when {
                profile != null && native.keyCode == android.view.KeyEvent.KEYCODE_MENU -> {
                    if (event.type == KeyEventType.KeyDown) manage(native.downTime)
                    true
                }
                activation && event.type == KeyEventType.KeyDown -> {
                    if (activationDownTime == null && native.repeatCount == 0) {
                        activationDownTime = native.downTime; held = false
                        keyPress = androidx.compose.foundation.interaction.PressInteraction.Press(androidx.compose.ui.geometry.Offset.Zero)
                            .also { interaction.tryEmit(it) }
                        if (profile != null) holdJob = keyScope.launch {
                            kotlinx.coroutines.delay(android.view.ViewConfiguration.getLongPressTimeout().toLong())
                            held = true; manage(native.downTime)
                        }
                    }
                    // Consume every remote activation; combinedClickable handles touch only.
                    // Its internal keyboard press must never remain down behind the popup.
                    true
                }
                activation && event.type == KeyEventType.KeyUp -> {
                    val matches = activationDownTime == native.downTime && !native.isCanceled
                    val long = held || native.eventTime - native.downTime >= android.view.ViewConfiguration.getLongPressTimeout()
                    val opened = held
                    finishKey()
                    if (matches) {
                        if (profile != null && long) { if (!opened) manage(native.downTime) }
                        else select()
                    }
                    true
                }
                else -> false
            }
        }.semantics(mergeDescendants = true) {
            contentDescription = label
            this.selected = selected
            if (selected) stateDescription = "Current profile"
            if (profile != null) customActions = listOf(CustomAccessibilityAction("Manage ${profile.name}") { manage(null); true })
        }.combinedClickable(interactionSource = interaction, indication = null,
            role = Role.Button, onClickLabel = label, onClick = select,
            onLongClickLabel = profile?.let { "Manage ${it.name}" }, onLongClick = profile?.let { { manage(activationDownTime) } }),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(width).graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (pressed) Modifier.shadow(8.dp, shape, clip = false) else Modifier)
            .border(if (focused || selected) 2.dp else 1.dp,
            if (focused || selected) Color.White else Color(0xFF343842), shape)
            .background(profile?.let { Color(it.color) } ?: Color(0xFF1B1E25), shape), contentAlignment = Alignment.Center) {
            if (pressed) Box(Modifier.matchParentSize().background(Color.White.copy(alpha = .08f), shape))
            Text(profile?.avatar?.ifBlank { profile.name.take(1).uppercase() } ?: "+", color = Color.White,
                fontSize = (width.value * if (profile == null) .5f else .42f).sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (selected) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp)
                .background(Color(0xFFE50925), CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        Text(profile?.name ?: "Add profile", color = if (focused || selected) Color.White else Color(0xFFB7B9C0),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // Reserve the line so changing selection never moves neighbouring tiles.
        Text(if (selected) "Current profile" else " ", color = Color(0xFFB7B9C0),
            style = MaterialTheme.typography.labelSmall, maxLines = 1, textAlign = TextAlign.Center)
        if (profile?.type == UserProfileType.KIDS) Text("Kids profile", color = Color(0xFF9699A2), style = MaterialTheme.typography.labelSmall)
    }
}

private data class SyncedPortalChoice(val id: String, val name: String, val type: String) {
    val displayName: String get() = if (type.equals("XTREAM", true)) "Xtream" else "Stalker"
}

@Composable
private fun PortalMappingsDialog(owner: UserScope, name: String, store: UserSyncStore, legacy: ProfileStore, onDismiss: () -> Unit) {
    var remote by remember(owner) { mutableStateOf<List<SyncedPortalChoice>>(emptyList()) }
    var local by remember(owner) { mutableStateOf<List<PortalProfile>>(emptyList()) }
    var message by remember(owner) { mutableStateOf("Loading portal associations…") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(owner) {
        runCatching {
            local = legacy.connections.first()
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
                Text("${portal.displayName}", style = MaterialTheme.typography.titleMedium)
                val candidates = local.filter { it.portalType.name == portal.type }
                if (candidates.isEmpty()) Text("Configure a ${portal.displayName} connection in Settings, then return here.")
                candidates.forEachIndexed { index, candidate ->
                    NikTvSecondaryActionButton(onClick = { scope.launch {
                        runCatching { store.mapPortal(owner, portal.id, candidate) }
                            .onSuccess { message = "Mapped ${portal.displayName} to device connection ${index + 1}." }
                            .onFailure { message = "Could not map this portal. Sync and retry." }
                    } }, modifier = Modifier.fillMaxWidth()) { Text("Use ${candidate.portalType.name.lowercase().replaceFirstChar { it.uppercase() }} connection ${index + 1}") }
                }
            }
        } },
        confirmButton = { NikTvTextActionButton(onClick = onDismiss) { Text("Done") } })
}
