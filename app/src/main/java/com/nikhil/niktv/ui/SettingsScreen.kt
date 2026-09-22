package com.nikhil.niktv.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.nikhil.niktv.BuildConfig
import com.nikhil.niktv.R
import com.nikhil.niktv.data.TrendingMovie
import com.nikhil.niktv.data.TrendingSeries
import com.nikhil.niktv.data.TmdbMovie
import com.nikhil.niktv.data.artworkRequest
import com.nikhil.niktv.data.cast4kLegacyDeviceIdentity
import com.nikhil.niktv.model.*
import com.nikhil.niktv.update.AppUpdates
import com.nikhil.niktv.update.UpdateDownloadState
import com.nikhil.niktv.update.UpdateInfo
import com.nikhil.niktv.update.UpdatePackage
import com.nikhil.niktv.update.DownloadedApkCleanup
import com.nikhil.niktv.update.formatDownloadBytes
import com.nikhil.niktv.data.OfflineMediaDownloads
import com.nikhil.niktv.data.OfflineDownloadStatus
import com.nikhil.niktv.data.RemoteCredentials
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
private fun TvSafeSettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
    requester: FocusRequester? = null,
    upRequester: FocusRequester? = null,
    downRequester: FocusRequester? = null,
    singleLine: Boolean = true
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTv = context.isTvLikeDevice(configuration)
    var editing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val fallbackRequester = remember { FocusRequester() }
    val fieldRequester = requester ?: fallbackRequester
    val iconBoxSize = if (isTv) 32.dp else 28.dp
    val iconSize = if (isTv) 24.dp else 22.dp
    val contentInset = if (isTv) 44.dp else 40.dp
    val fieldShape = RoundedCornerShape(12.dp)

    fun beginEditing() {
        if (editing) return
        editing = true
        scope.launch {
            withFrameNanos { }
            runCatching { fieldRequester.requestFocus() }
            delay(50L)
            keyboard?.show()
        }
    }

    /* GITHUB_BACKUP_FIELDS_DPAD_EDIT_V1
     * Focus and edit mode are deliberately separate on every device:
     * D-pad traversal may focus the field without opening the IME, while
     * OK/Enter or a real pointer tap explicitly enters editing. Once editing
     * begins, normal TextField touch/cursor behavior is restored.
     */
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = if (isTv) 4.dp else 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement =
                Arrangement.spacedBy(if (isTv) 14.dp else 12.dp)
        ) {
            Box(
                modifier = Modifier.size(iconBoxSize),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(iconSize),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    title,
                    style =
                        if (isTv) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.bodyLarge
                        },
                    fontWeight =
                        if (isTv) FontWeight.SemiBold
                        else FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder?.let { text -> ({ Text(text) }) },
            visualTransformation =
                if (password) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
            readOnly = isTv && !editing,
            singleLine = singleLine,
            shape = fieldShape,
            textStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = contentInset)
                .heightIn(min = if (isTv) 60.dp else 56.dp)
                .then(
                    if (isTv && !editing) {
                        Modifier.pointerInput(fieldRequester) {
                            detectTapGestures(
                                onTap = {
                                    beginEditing()
                                }
                            )
                        }
                    } else {
                        Modifier
                    }
                )
                .focusRequester(fieldRequester)
                .focusProperties {
                    if (upRequester != null) {
                        up = upRequester
                    }
                    if (downRequester != null) {
                        down = downRequester
                    }
                }
                .onFocusChanged {
                    if (!it.isFocused) {
                        editing = false
                        keyboard?.hide()
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (
                        !editing &&
                        event.type == KeyEventType.KeyDown &&
                        event.key in setOf(
                            Key.DirectionCenter,
                            Key.Enter,
                            Key.NumPadEnter
                        )
                    ) {
                        beginEditing()
                        true
                    } else {
                        false
                    }
                }
                .remoteFocusFrame(fieldShape)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ModernSettingsScreen(
    state: NikTvState,
    closeSettings: () -> Unit,
    reauthenticate: () -> Unit,
    editProfile: () -> Unit,
    addProfile: () -> Unit,
    exportBackup: (android.net.Uri) -> Unit,
    importBackup: (android.net.Uri) -> Unit,
    importBackupContent: (String) -> Unit,
    switchProfile: (PortalProfile) -> Unit,
    removeProfile: (PortalProfile) -> Unit,
    setPreconfiguredProfileEnabled: (PortalProfile, Boolean) -> Unit,
    logout: () -> Unit,
    setCacheIntervalMinutes: (Int) -> Unit,
    setInitialCatalogItems: (Int) -> Unit,
    setPlayerControlsTimeoutSeconds: (Int) -> Unit,
    setKeepAwakeOnlyDuringPlayback: (Boolean) -> Unit,
    setAutomaticReauthentication: (Boolean) -> Unit,
    setModernUiEnabled: (Boolean) -> Unit,
    setPlaybackEngine: (PlaybackEngine) -> Unit,
    setSeriesStartSeason: (SeriesStartSeason) -> Unit,
    setBrowseLayout: (BrowseLayout) -> Unit,
    openCategoryManager: (CatalogType) -> Unit
) {
    val profile = state.savedProfile
    BackHandler(onBack = closeSettings)
    val context = LocalContext.current
    var appBrightness by remember(context) {
        mutableFloatStateOf(AppBrightnessPreferences.get(context))
    }
    var followSystemBrightness by remember(context) {
        mutableStateOf(AppBrightnessPreferences.followsSystem(context))
    }
    val generatedIdentity = remember(context) { cast4kLegacyDeviceIdentity(context) }
    val remoteValues = RemoteCredentials.values
    val preconfiguredProfiles = remember(generatedIdentity, remoteValues) {
        listOf(
            PortalProfile(
                RemoteCredentials.get("NIKTV_DEFAULT_PROFILE_NAME").ifBlank { "WIO" },
                RemoteCredentials.get("NIKTV_DEFAULT_PORTAL_URL"),
                RemoteCredentials.get("NIKTV_DEFAULT_MAC_ADDRESS").ifBlank { generatedIdentity.macAddress },
                RemoteCredentials.get("NIKTV_DEFAULT_SERIAL_NUMBER").ifBlank { generatedIdentity.serialNumber },
                PortalType.STALKER
            ),
            PortalProfile(
                RemoteCredentials.get("NIKTV_XTREAM_PROFILE_NAME").ifBlank { "Xtream" },
                RemoteCredentials.get("NIKTV_XTREAM_PORTAL_URL"),
                macAddress = "",
                portalType = PortalType.XTREAM,
                username = RemoteCredentials.get("NIKTV_XTREAM_USERNAME"),
                password = RemoteCredentials.get("NIKTV_XTREAM_PASSWORD")
            )
        )
    }
    val scope = rememberCoroutineScope()

    // GITHUB_BACKUP_V2
    val githubBackupManager = remember(context) {
        com.nikhil.niktv.data.GitHubBackupManager(context)
    }
    var githubBackupConfig by remember {
        mutableStateOf(githubBackupManager.loadConfig())
    }
    var githubBackupDialogOpen by remember { mutableStateOf(false) }
    var githubBackupUploading by remember { mutableStateOf(false) }
    var githubBackupMessage by remember { mutableStateOf<String?>(null) }
    var githubBackupSucceeded by remember { mutableStateOf<Boolean?>(null) }

    var githubRestoreDialogOpen by remember { mutableStateOf(false) }
    var githubRestoreLoading by remember { mutableStateOf(false) }
    var githubRestoreMessage by remember { mutableStateOf<String?>(null) }
    var githubBackupFiles by remember {
        mutableStateOf<List<com.nikhil.niktv.data.GitHubBackupFile>>(emptyList())
    }
    var githubPendingRestore by remember {
        mutableStateOf<com.nikhil.niktv.data.GitHubBackupDecoded?>(null)
    }
    var githubSelectedBackupName by remember { mutableStateOf<String?>(null) }

    fun normalizedGitHubBackupConfig(): com.nikhil.niktv.data.GitHubBackupConfig =
        githubBackupConfig.copy(
            username = githubBackupConfig.username.trim(),
            repository = githubBackupConfig.repository
                .trim()
                .removeSuffix(".git"),
            token = githubBackupConfig.token.trim()
        )

    fun openGitHubRestoreBrowser() {
        val config = normalizedGitHubBackupConfig()
        githubBackupConfig = config
        githubBackupManager.saveConfig(config)
        githubBackupDialogOpen = false
        githubRestoreDialogOpen = true
        githubRestoreLoading = true
        githubRestoreMessage = "Loading encrypted backups…"
        githubPendingRestore = null
        githubSelectedBackupName = null

        scope.launch {
            runCatching {
                githubBackupManager.listBackups(config)
            }.onSuccess { backups ->
                githubBackupFiles = backups
                githubRestoreMessage =
                    if (backups.isEmpty()) {
                        "No encrypted NikTV backups found in /backups."
                    } else {
                        null
                    }
            }.onFailure { error ->
                githubBackupFiles = emptyList()
                githubRestoreMessage =
                    error.message ?: "Could not load GitHub backups"
            }
            githubRestoreLoading = false
        }
    }


    fun performGitHubExport() {
        val config = normalizedGitHubBackupConfig()
        githubBackupConfig = config
        githubBackupManager.saveConfig(config)
        githubBackupUploading = true
        githubBackupSucceeded = null
        githubBackupMessage =
            if (config.passphrase.isBlank()) {
                "Uploading backup to GitHub…"
            } else {
                "Encrypting backup locally…"
            }

        scope.launch {
            runCatching {
                val store =
                    com.nikhil.niktv.data.ProfileStore(
                        context.applicationContext
                    )
                val content = store.exportBackup()
                val fingerprint = store.backupFingerprint()
                val upload =
                    githubBackupManager.uploadBackup(
                        content,
                        config
                    )
                githubBackupManager.recordSuccessfulBackupFingerprint(
                    fingerprint,
                    config
                )
                upload
            }.onSuccess { upload ->
                githubBackupSucceeded = true
                githubBackupMessage =
                    if (config.passphrase.isBlank()) {
                        "Backup uploaded: ${upload.path}"
                    } else {
                        "Encrypted backup uploaded: ${upload.path}"
                    }
            }.onFailure { error ->
                githubBackupSucceeded = false
                githubBackupMessage =
                    error.message ?: "Could not upload GitHub backup"
            }
            githubBackupUploading = false
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(exportBackup)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(importBackup)
    }
    var pendingRemoval by remember { mutableStateOf<PortalProfile?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var downloadActionMessage by remember { mutableStateOf<String?>(null) }
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    val updateDownloadRequester = remember { FocusRequester() }
    val versionRequester = remember { FocusRequester() }
    val oneClickUpdateRequester = remember { FocusRequester() }
    val settingsEntryRequester = remember { FocusRequester() }
    val settingsSummaryEntryRequester = remember { FocusRequester() }
    var updateDialogNavigationEnabled by remember { mutableStateOf(false) }
    var restoreVersionFocus by remember { mutableStateOf(false) }
    var pendingPermissionUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var pendingPermissionInstallAfterDownload by remember { mutableStateOf(false) }
    var oneClickUpdating by remember { mutableStateOf(false) }
    val downloadState by AppUpdates.downloadState.collectAsStateWithLifecycle()
    val pendingUpdate by AppUpdates.pendingUpdate.collectAsStateWithLifecycle()
    val updateEnforcementEnabled by AppUpdates.updateEnforcementEnabled.collectAsStateWithLifecycle()
    val startupUpdateCheckEnabled by AppUpdates.startupUpdateCheckEnabled.collectAsStateWithLifecycle()
    val updatePackagePreference by AppUpdates.updatePackage.collectAsStateWithLifecycle()
    var obsoleteApks by remember { mutableStateOf<DownloadedApkCleanup?>(null) }
    var cleaningObsoleteApks by remember { mutableStateOf(false) }
    var apkCleanupMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(downloadState) {
        obsoleteApks = runCatching { AppUpdates.obsoleteDownloadedApks(context) }.getOrNull()
    }
    val performDownload: (UpdateInfo) -> Unit = { update ->
        downloadActionMessage = null
        runCatching { AppUpdates.download(context, update) }
            .onSuccess {
                updateMessage = "Downloading ${update.version}…"
                restoreVersionFocus = true
                availableUpdate = null
            }
            .onFailure {
                downloadActionMessage = it.message ?: "Could not start the update download"
                updateMessage = "Could not start download: ${it.message}"
            }
    }
    val performDownloadAndInstall: (UpdateInfo) -> Unit = { update ->
        downloadActionMessage = null
        runCatching { AppUpdates.downloadAndInstall(context, update) }
            .onSuccess {
                updateMessage = "Updating to ${update.version} · installer will open automatically"
                availableUpdate = null
            }
            .onFailure {
                downloadActionMessage = it.message ?: "Could not start the one-click update"
                updateMessage = "Could not start update: ${it.message}"
            }
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val update = pendingPermissionUpdate
        val installAfterDownload = pendingPermissionInstallAfterDownload
        if ((granted || AppUpdates.canWritePublicDownloads(context)) && update != null) {
            pendingPermissionUpdate = null
            pendingPermissionInstallAfterDownload = false
            if (installAfterDownload) {
                performDownloadAndInstall(update)
            } else {
                performDownload(update)
            }
        } else {
            downloadActionMessage =
                "${AppUpdates.PUBLIC_DOWNLOADS_PERMISSION_MESSAGE} Select Allow & download to request it again."
            updateMessage = AppUpdates.PUBLIC_DOWNLOADS_PERMISSION_MESSAGE
        }
    }
    fun requestUpdateDownload(
        update: UpdateInfo,
        installAfterDownload: Boolean = false
    ) {
        if (AppUpdates.canWritePublicDownloads(context)) {
            if (installAfterDownload) {
                performDownloadAndInstall(update)
            } else {
                performDownload(update)
            }
        } else {
            pendingPermissionUpdate = update
            pendingPermissionInstallAfterDownload = installAfterDownload
            downloadActionMessage = AppUpdates.PUBLIC_DOWNLOADS_PERMISSION_MESSAGE
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    fun runOneClickUpdate() {
        if (oneClickUpdating) return
        oneClickUpdating = true
        downloadActionMessage = null
        updateMessage = "Checking for updates…"
        scope.launch {
            runCatching { AppUpdates.check() }
                .onSuccess { update ->
                    if (update == null) {
                        updateMessage = "You're up to date"
                    } else {
                        updateMessage = "Version ${update.version} is available · starting update…"
                        requestUpdateDownload(update, installAfterDownload = true)
                    }
                }
                .onFailure {
                    val message = updateCheckFailureMessage(it)
                    updateMessage = message
                    downloadActionMessage = message
                }
            oneClickUpdating = false
            delay(80L)
            runCatching { oneClickUpdateRequester.requestFocus() }
        }
    }
    LaunchedEffect(pendingUpdate) {
        pendingUpdate?.let { update ->
            availableUpdate = update
            updateMessage = "Version ${update.version} needs storage permission to download"
            downloadActionMessage = AppUpdates.PUBLIC_DOWNLOADS_PERMISSION_MESSAGE
        }
    }
    LaunchedEffect(availableUpdate) {
        if (availableUpdate != null) {
            updateDialogNavigationEnabled = false
            withFrameNanos { }
            runCatching { updateDownloadRequester.requestFocus() }
            withFrameNanos { }
            updateDialogNavigationEnabled = true
        } else if (restoreVersionFocus) {
            delay(80L)
            runCatching { versionRequester.requestFocus() }
            restoreVersionFocus = false
        }
    }
    val deviceMacAddress = remember(context) { cast4kStyleDeviceMacAddress(context) }
    val downloadStatus = when (val download = downloadState) {
        UpdateDownloadState.Idle ->
            updateMessage ?: if (startupUpdateCheckEnabled) {
                "Updates are checked on startup and every 24 hours"
            } else {
                "Startup update checks are off · background checks still run every 24 hours"
            }
        is UpdateDownloadState.Queued ->
            "NikTV ${download.version} is queued in Android Download Manager"
        is UpdateDownloadState.Downloading ->
            "Downloading NikTV ${download.version}" +
                (download.percent?.let { " · $it%" } ?: "")
        is UpdateDownloadState.Paused -> download.reason
        is UpdateDownloadState.Ready -> if (download.awaitingUnknownSourcesPermission) {
            "Allow NikTV to install unknown apps, then return here and select Install again"
        } else {
            "NikTV ${download.version} is ready to install"
        }
        is UpdateDownloadState.Installing -> "Opening Android's package installer…"
        is UpdateDownloadState.InstallerLaunched ->
            "Android's installer was opened. Complete installation there, or select Install again."
        is UpdateDownloadState.Failed -> download.message
    }
    val downloadedBytes = when (val download = downloadState) {
        is UpdateDownloadState.Downloading -> download.bytesDownloaded to download.totalBytes
        is UpdateDownloadState.Paused -> download.bytesDownloaded to download.totalBytes
        is UpdateDownloadState.Queued -> 0L to null
        else -> null
    }
    val settingsConfiguration = LocalConfiguration.current
    val settingsIsTv = context.isTvLikeDevice(settingsConfiguration)
    val compactSettingsHeader = settingsConfiguration.screenWidthDp < 600
    /* TABLET_SETTINGS_MODERN_V1
     * Touch tablets reuse the mobile preference hierarchy while keeping the
     * split rail/detail navigation. TVs retain their couch-distance layout.
     */
    val tabletSettingsLayout =
        !settingsIsTv &&
            settingsConfiguration.smallestScreenWidthDp >= 600
    val modernSettingsRows =
        compactSettingsHeader || tabletSettingsLayout
    val settingsDestinations = SettingsDestination.entries
    var selectedSettingsDestination by remember {
        mutableStateOf(SettingsDestination.GENERAL)
    }
    val settingsRailRequesters = remember {
        SettingsDestination.entries.associateWith { FocusRequester() }
    }
    val settingsPagerState = rememberPagerState(
        pageCount = { settingsDestinations.size }
    )
    val mobileSettingsTopBarBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(compactSettingsHeader) {
        withFrameNanos { }
        if (!compactSettingsHeader) {
            runCatching {
                settingsRailRequesters
                    .getValue(selectedSettingsDestination)
                    .requestFocus()
            }
        }
    }

    LaunchedEffect(settingsPagerState.currentPage, compactSettingsHeader) {
        if (compactSettingsHeader) {
            selectedSettingsDestination =
                settingsDestinations[settingsPagerState.currentPage]
        }
    }
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (compactSettingsHeader) {
                    Modifier.nestedScroll(mobileSettingsTopBarBehavior.nestedScrollConnection)
                } else Modifier
            ),
        containerColor = Color(0xFF07080A),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            if (compactSettingsHeader) {
                LargeTopAppBar(
                    title = { Text("Settings", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = closeSettings) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF090A0C),
                        scrolledContainerColor = Color(0xFF101216)
                    ),
                    scrollBehavior = mobileSettingsTopBarBehavior
                )
            } else {
                ModernScreenTopBar("Settings", closeSettings)
            }
        },
        bottomBar = {
            if (compactSettingsHeader) {
                SettingsBottomNavigation(
                    currentDestination =
                        settingsDestinations[settingsPagerState.currentPage],
                    selectDestination = { destination ->
                        scope.launch {
                            settingsPagerState.animateScrollToPage(
                                settingsDestinations.indexOf(destination)
                            )
                        }
                    }
                )
            }
        }
    ) { padding ->
        val settingsPageContent: @Composable () -> Unit = { Column(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF111318),
                        Color(0xFF090A0C),
                        Color(0xFF060606)
                    )
                )
            )
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal =
                    when {
                        compactSettingsHeader -> 16.dp
                        tabletSettingsLayout -> 24.dp
                        else -> 18.dp
                    },
                vertical =
                    when {
                        compactSettingsHeader -> 20.dp
                        tabletSettingsLayout -> 20.dp
                        else -> 18.dp
                    }
            ),
        verticalArrangement = Arrangement.spacedBy(
            when {
                compactSettingsHeader -> 20.dp
                tabletSettingsLayout -> 18.dp
                else -> 12.dp
            }
        )
    ) {
        val activeDestination =
            LocalSettingsDestination.current
                ?: selectedSettingsDestination

        SettingsDestinationHeader(
            destination = activeDestination,
            compact = compactSettingsHeader || tabletSettingsLayout
        )

        if (!compactSettingsHeader) {
            SettingsSummaryRow(
                activeProfile = profile?.name ?: "No active profile",
                defaultPlayer = when (state.playbackEngine) {
                    PlaybackEngine.AUTO -> "Auto"
                    PlaybackEngine.MEDIA3,
                    PlaybackEngine.EXOPLAYER -> "ExoPlayer"
                    PlaybackEngine.VLC -> "VLC"
                },
                backup = when (githubBackupConfig.backupMode) {
                    com.nikhil.niktv.data.BackupMode.GITHUB -> "GitHub"
                    com.nikhil.niktv.data.BackupMode.DEVICE -> "Device"
                },
                updates = "v${BuildConfig.VERSION_NAME}",
                currentDestination = activeDestination,
                railRequester =
                    settingsRailRequesters.getValue(activeDestination),
                entryRequester = settingsSummaryEntryRequester,
                onSelect = { selectedSettingsDestination = it }
            )
        }

        val showMobileAppearance =
            settingsConfiguration.smallestScreenWidthDp < 600

        val mobileDpadRequester = remember { FocusRequester() }
        val followSystemBrightnessRequester = remember { FocusRequester() }
        val appBrightnessRequester = remember { FocusRequester() }
        val keepAwakeRequester = remember { FocusRequester() }
        val orientationRequester = remember { FocusRequester() }
        val playbackEngineRequester = remember { FocusRequester() }
        val audioFallbackRequester = remember { FocusRequester() }
        val seriesSeasonRequester = remember { FocusRequester() }
        val catalogRefreshRequester = remember { FocusRequester() }
        val initialCatalogItemsRequester = remember { FocusRequester() }
        val appBrightnessStep =
            (AppBrightnessPreferences.MAX - AppBrightnessPreferences.MIN) /
                17f

        SettingsSection("Device & display") {
            if (showMobileAppearance) {
                val onScreenDpad by rememberOnScreenDpadEnabled()
                CompactSettingsOptionRow(
                    icon = Icons.Default.Tune,
                    title = "On-screen D-pad",
                    subtitle =
                        "Show a movable remote control overlay for testing focus navigation on this phone.",
                    trailingContent = {
SettingsSwitch(
                            checked = onScreenDpad,
                            onCheckedChange = {
                                OnScreenDpadPreferences.setEnabled(context, it)
                            },
                            modifier = Modifier
                                .focusRequester(mobileDpadRequester)
                                .focusProperties {
                                    down = followSystemBrightnessRequester
                                }
                        )
                    }
                )

                HorizontalDivider()
            }
            if (modernSettingsRows) {
                CompactSettingsOptionRow(
                    icon = Icons.Default.BrightnessAuto,
                    title = "Follow system brightness",
                    subtitle =
                        "Use the brightness configured by this device or TV.",
                    trailingContent = {
SettingsSwitch(
                            checked = followSystemBrightness,
                            onCheckedChange = {
                                followSystemBrightness = it
                                AppBrightnessPreferences
                                    .setFollowsSystem(context, it)
                            },
                            modifier = Modifier
                                .focusRequester(
                                    followSystemBrightnessRequester
                                )
                                .focusProperties {
                                    up =
                                        when {
                                            showMobileAppearance -> mobileDpadRequester
                                            tabletSettingsLayout -> settingsSummaryEntryRequester
                                            else -> FocusRequester.Default
                                        }
                                    if (tabletSettingsLayout) {
                                        left = settingsRailRequesters.getValue(activeDestination)
                                    }
                                    down =
                                        if (followSystemBrightness) {
                                            keepAwakeRequester
                                        } else {
                                            appBrightnessRequester
                                        }
                                }
                        )
                    }
                )
                HorizontalDivider()

                CompactSettingsOptionRow(
                    icon = Icons.Default.Brightness6,
                    title = "App brightness",
                    subtitle =
                        if (followSystemBrightness) {
                            "System controlled."
                        } else {
                            "${(appBrightness * 100).toInt()}% · Applies only while NikTV is open."
                        },
                    belowContent = {
                        Spacer(Modifier.height(6.dp))
                        Slider(
                            value = appBrightness,
                            enabled = !followSystemBrightness,
                            onValueChange = {
                                appBrightness = it
                                AppBrightnessPreferences.set(context, it)
                            },
                            valueRange =
                                AppBrightnessPreferences.MIN..
                                    AppBrightnessPreferences.MAX,
                            steps = 16,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 40.dp, end = 2.dp)
                                .focusRequester(appBrightnessRequester)
                                .onPreviewKeyEvent { event ->
                                    if (
                                        event.type != KeyEventType.KeyDown
                                    ) {
                                        return@onPreviewKeyEvent false
                                    }

                                    when (event.key) {
                                        Key.DirectionLeft -> {
                                            val next =
                                                (appBrightness -
                                                    appBrightnessStep)
                                                    .coerceIn(
                                                        AppBrightnessPreferences.MIN,
                                                        AppBrightnessPreferences.MAX
                                                    )
                                            appBrightness = next
                                            AppBrightnessPreferences.set(
                                                context,
                                                next
                                            )
                                            true
                                        }

                                        Key.DirectionRight -> {
                                            val next =
                                                (appBrightness +
                                                    appBrightnessStep)
                                                    .coerceIn(
                                                        AppBrightnessPreferences.MIN,
                                                        AppBrightnessPreferences.MAX
                                                    )
                                            appBrightness = next
                                            AppBrightnessPreferences.set(
                                                context,
                                                next
                                            )
                                            true
                                        }

                                        Key.DirectionUp -> {
                                            followSystemBrightnessRequester
                                                .requestFocus()
                                            true
                                        }

                                        Key.DirectionDown -> {
                                            keepAwakeRequester.requestFocus()
                                            true
                                        }

                                        else -> false
                                    }
                                }
                                .remoteFocusFrame(
                                    RoundedCornerShape(12.dp)
                                )
                        )
                    }
                )
                HorizontalDivider()

                CompactSettingsOptionRow(
                    icon = Icons.Default.LightMode,
                    title = "Keep screen awake during playback",
                    subtitle =
                        if (state.keepAwakeOnlyDuringPlayback) {
                            "Browsing may sleep normally; playback always stays awake."
                        } else {
                            "Keep the screen awake while NikTV is open."
                        },
                    trailingContent = {
SettingsSwitch(
                            checked =
                                state.keepAwakeOnlyDuringPlayback,
                            onCheckedChange =
                                setKeepAwakeOnlyDuringPlayback,
                            modifier = Modifier
                                .focusRequester(keepAwakeRequester)
                                .focusProperties {
                                    up =
                                        if (followSystemBrightness) {
                                            followSystemBrightnessRequester
                                        } else {
                                            appBrightnessRequester
                                        }
                                    down = orientationRequester
                                }
                        )
                    }
                )
                HorizontalDivider()

                CompactOrientationSetting(
                    entryRequester = orientationRequester,
                    upRequester = keepAwakeRequester,
                    downRequester = audioFallbackRequester
                )
            } else {
                ListItem(
                    headlineContent = {
                        Text("Follow system brightness")
                    },
                    supportingContent = {
                        Text(
                            "Use the brightness configured by this device or TV"
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Default.BrightnessAuto, null)
                    },
                    trailingContent = {
SettingsSwitch(
                            checked = followSystemBrightness,
                            onCheckedChange = {
                                followSystemBrightness = it
                                AppBrightnessPreferences
                                    .setFollowsSystem(context, it)
                            },
                            modifier = Modifier
                                .focusRequester(followSystemBrightnessRequester)
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    )
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("App brightness") },
                    supportingContent = {
                        Text(
                            if (followSystemBrightness) {
                                "System controlled"
                            } else {
                                "${(appBrightness * 100).toInt()}% · Applies only while NikTV is open"
                            }
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Default.Brightness6, null)
                    },
                    trailingContent = {
                        Slider(
                            value = appBrightness,
                            enabled = !followSystemBrightness,
                            onValueChange = {
                                appBrightness = it
                                AppBrightnessPreferences.set(
                                    context,
                                    it
                                )
                            },
                            valueRange =
                                AppBrightnessPreferences.MIN..
                                    AppBrightnessPreferences.MAX,
                            steps = 16,
                            modifier = Modifier
                                .width(220.dp)
                                .remoteFocusFrame(
                                    RoundedCornerShape(12.dp)
                                )
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    )
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = {
                        Text(
                            "Only keep screen awake during playback"
                        )
                    },
                    supportingContent = {
                        Text(
                            if (
                                state.keepAwakeOnlyDuringPlayback
                            ) {
                                "NikTV may let the screen sleep while browsing; playback always stays awake."
                            } else {
                                "NikTV keeps the screen awake for as long as the app is open."
                            }
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Default.LightMode, null)
                    },
                    trailingContent = {
SettingsSwitch(
                            checked =
                                state.keepAwakeOnlyDuringPlayback,
                            onCheckedChange =
                                setKeepAwakeOnlyDuringPlayback,
                            modifier = Modifier
                        )
                    },
                    colors = ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    )
                )
            }

        }
        SettingsSection("Playback") {
            val audioFallbackContext = LocalContext.current
            var audioFallbackEnabled by remember {
                mutableStateOf(AudioFailurePreferences.enabled(audioFallbackContext))
            }
            CompactSettingsOptionRow(
                icon = Icons.Default.VolumeOff,
                title = "Continue video if audio fails",
                subtitle = "This device only · ExoPlayer. Disable failed audio output and keep video playing. Retry audio when earphones connect.",
                trailingContent = {
                    SettingsSwitch(
                        checked = audioFallbackEnabled,
                        modifier = Modifier.focusRequester(audioFallbackRequester).focusProperties {
                            up = orientationRequester
                            down = playbackEngineRequester
                        },
                        onCheckedChange = {
                            audioFallbackEnabled = it
                            AudioFailurePreferences.setEnabled(audioFallbackContext, it)
                        }
                    )
                }
            )
            HorizontalDivider()
            PlaybackEngineSettingsContent(
                selectedEngine = state.playbackEngine,
                setPlaybackEngine = setPlaybackEngine,
                compact = modernSettingsRows,
                entryRequester = playbackEngineRequester,
                upRequester = audioFallbackRequester,
                downRequester = seriesSeasonRequester
            )
            HorizontalDivider()
            if (modernSettingsRows) {
                CompactSettingsOptionRow(
                    icon = Icons.Default.VideoLibrary,
                    title = "Default season",
                    subtitle =
                        "Used when a series has no remembered season. NikTV loads one season at a time.",
                    belowContent = {
                        Spacer(Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 40.dp, end = 2.dp)
                        ) {
                            SeriesStartSeason.entries
                                .forEachIndexed { index, option ->
                                    val shape =
                                        uniformSegmentShape(
                                            index,
                                            SeriesStartSeason.entries.size
                                        )
                                    val selected =
                                        state.seriesStartSeason == option
                                    SegmentedButton(
                                        selected = selected,
                                        onClick = {
                                            setSeriesStartSeason(option)
                                        },
                                        modifier = Modifier
                                            .then(
                                                if (selected) {
                                                    Modifier.focusRequester(
                                                        seriesSeasonRequester
                                                    )
                                                } else {
                                                    Modifier
                                                }
                                            )
                                            .focusProperties {
                                                up = playbackEngineRequester
                                                down = catalogRefreshRequester
                                                }
                                            .remoteFocusFrame(shape),
                                        shape = shape
                                    ) {
                                        Text(
                                            if (
                                                option ==
                                                SeriesStartSeason.FIRST
                                            ) {
                                                "First season"
                                            } else {
                                                "Latest season"
                                            },
                                            style =
                                                MaterialTheme.typography
                                                    .labelLarge
                                        )
                                    }
                                }
                        }
                    }
                )
            } else {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Default season",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Used only when a series has no remembered season. NikTV loads one season at a time.",
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SingleChoiceSegmentedButtonRow(
                        Modifier.fillMaxWidth()
                    ) {
                        SeriesStartSeason.entries
                            .forEachIndexed { index, option ->
                                val shape =
                                    uniformSegmentShape(
                                        index,
                                        SeriesStartSeason.entries.size
                                    )
                                SegmentedButton(
                                    selected =
                                        state.seriesStartSeason ==
                                            option,
                                    onClick = {
                                        setSeriesStartSeason(option)
                                    },
                                    modifier =
                                        Modifier.remoteFocusFrame(shape),
                                    shape = shape
                                ) {
                                    Text(
                                        if (
                                            option ==
                                            SeriesStartSeason.FIRST
                                        ) {
                                            "First season"
                                        } else {
                                            "Latest season"
                                        }
                                    )
                                }
                            }
                    }
                }
            }

        }
        SettingsSection("Storage & refresh") {
            if (modernSettingsRows) {
                val refreshOptions =
                    listOf(
                        30 to "30m",
                        60 to "1h",
                        360 to "6h",
                        1440 to "24h"
                    )

                CompactSettingsOptionRow(
                    icon = Icons.Default.Refresh,
                    title = "Refresh interval",
                    subtitle =
                        "Choose how long categories and media lists stay cached on this device.",
                    belowContent = {
                        Spacer(Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 40.dp, end = 2.dp)
                        ) {
                            refreshOptions.forEachIndexed {
                                    index,
                                    (minutes, label) ->
                                val shape =
                                    uniformSegmentShape(
                                        index,
                                        refreshOptions.size
                                    )
                                val selected =
                                    state.cacheIntervalMinutes == minutes
                                SegmentedButton(
                                    selected = selected,
                                    onClick = {
                                        setCacheIntervalMinutes(minutes)
                                    },
                                    modifier = Modifier
                                        .then(
                                            if (selected) {
                                                Modifier.focusRequester(
                                                    catalogRefreshRequester
                                                )
                                            } else {
                                                Modifier
                                            }
                                        )
                                        .focusProperties {
                                            up = seriesSeasonRequester
                                            down = initialCatalogItemsRequester
                                        }
                                        .remoteFocusFrame(shape),
                                    shape = shape
                                ) {
                                    Text(
                                        label,
                                        style =
                                            MaterialTheme.typography
                                                .labelLarge
                                    )
                                }
                            }
                        }
                    }
                )
            } else {
                Column(
                    Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Refresh interval",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Categories and media lists are stored on this device and refreshed after this interval.",
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SingleChoiceSegmentedButtonRow(
                        Modifier.fillMaxWidth()
                    ) {
                        listOf(
                            30 to "30m",
                            60 to "1h",
                            360 to "6h",
                            1440 to "24h"
                        ).forEachIndexed { index, (minutes, label) ->
                            val intervalShape =
                                uniformSegmentShape(index, 4)
                            SegmentedButton(
                                selected =
                                    state.cacheIntervalMinutes == minutes,
                                onClick = {
                                    setCacheIntervalMinutes(minutes)
                                },
                                modifier =
                                    Modifier.remoteFocusFrame(
                                        intervalShape
                                    ),
                                shape = intervalShape
                            ) {
                                Text(label)
                            }
                        }
                    }
                }
            }
            if (state.savedProfile != null) {
                val itemOptions = listOf(14, 28, 42, 56)
                if (modernSettingsRows) {
                    CompactSettingsOptionRow(
                        icon = Icons.Default.GridView,
                        title = "Initial media load",
                        subtitle = "Choose how many IPTV items load when a category opens.",
                        belowContent = {
                            Spacer(Modifier.height(8.dp))
                            SingleChoiceSegmentedButtonRow(
                                Modifier.fillMaxWidth().padding(start = 40.dp, end = 2.dp)
                            ) {
                                itemOptions.forEachIndexed { index, count ->
                                    val shape = uniformSegmentShape(index, itemOptions.size)
                                    SegmentedButton(
                                        selected = state.initialCatalogItems == count,
                                        onClick = { setInitialCatalogItems(count) },
                                        modifier = Modifier
                                            .then(
                                                if (state.initialCatalogItems == count) {
                                                    Modifier.focusRequester(
                                                        initialCatalogItemsRequester
                                                    )
                                                } else {
                                                    Modifier
                                                }
                                            )
                                            .focusProperties {
                                                up = catalogRefreshRequester
                                            }
                                            .remoteFocusFrame(shape),
                                        shape = shape
                                    ) { Text(count.toString()) }
                                }
                            }
                        }
                    )
                } else {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Initial media load", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Choose how many IPTV items load when a category opens.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            itemOptions.forEachIndexed { index, count ->
                                val shape = uniformSegmentShape(index, itemOptions.size)
                                SegmentedButton(
                                    selected = state.initialCatalogItems == count,
                                    onClick = { setInitialCatalogItems(count) },
                                    modifier = Modifier.remoteFocusFrame(shape),
                                    shape = shape
                                ) { Text(count.toString()) }
                            }
                        }
                    }
                }
            }

        }
        SettingsSection("Profiles") {
            if (!modernSettingsRows) {
                Text(
                    "Preconfigured profiles",
                    modifier = Modifier.padding(
                        horizontal = 16.dp,
                        vertical = 10.dp
                    ),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            preconfiguredProfiles.forEachIndexed { index, builtIn ->
                val enabled = state.profiles.any {
                    it.cacheKey() == builtIn.cacheKey()
                }
                ResponsiveSettingsOptionRow(
                    icon =
                        if (builtIn.portalType == PortalType.STALKER) {
                            Icons.Default.Tv
                        } else {
                            Icons.Default.Key
                        },
                    title = builtIn.name,
                    subtitle =
                        if (enabled) {
                            "Available on the profile screen"
                        } else {
                            "Hidden from the profile screen"
                        },
                    trailingContent = {
SettingsSwitch(
                            checked = enabled,
                            onCheckedChange = {
                                setPreconfiguredProfileEnabled(
                                    builtIn,
                                    it
                                )
                            },
                            enabled =
                                builtIn.portalUrl.isNotBlank() &&
                                    (
                                        builtIn.portalType ==
                                            PortalType.STALKER ||
                                            (
                                                builtIn.username
                                                    .isNotBlank() &&
                                                    builtIn.password
                                                        .isNotBlank()
                                                )
                                        ),
                            modifier = Modifier
                        )
                    }
                )
                if (
                    index != preconfiguredProfiles.lastIndex ||
                    state.profiles.isNotEmpty()
                ) {
                    HorizontalDivider()
                }
            }

            state.profiles.forEachIndexed { index, saved ->
                val isPreconfigured = preconfiguredProfiles.any {
                    it.cacheKey() == saved.cacheKey()
                }
                ResponsiveSettingsOptionRow(
                    icon =
                        if (saved.portalType == PortalType.STALKER) {
                            Icons.Default.Tv
                        } else {
                            Icons.Default.Key
                        },
                    title = saved.name,
                    subtitle =
                        if (isPreconfigured) {
                            "Preconfigured ${saved.portalType.displayName()} profile"
                        } else {
                            "${saved.portalType.displayName()} · ${saved.portalUrl}"
                        },
                    trailingContent = {
                        if (saved == profile) {
                            Icon(
                                Icons.Default.CheckCircle,
                                "Active",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            NikTvTextActionButton(
                                onClick = { switchProfile(saved) },
                                modifier = Modifier
                                    .height(48.dp)
                                    .then(
                                        if (index == 0) {
                                            Modifier.focusRequester(
                                                settingsEntryRequester
                                            )
                                        } else {
                                            Modifier
                                        }
                                    )
                            ) {
                                Text("Open")
                            }
                        }
                        IconButton(
                            onClick = { pendingRemoval = saved },
                            modifier = Modifier
                                .size(48.dp)
                                .then(
                                    if (
                                        index == 0 &&
                                        saved == profile
                                    ) {
                                        Modifier.focusRequester(
                                            settingsEntryRequester
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .remoteFocusFrame(CircleShape)
                        ) {
                            Icon(
                                Icons.Default.DeleteOutline,
                                "Remove ${saved.name}"
                            )
                        }
                    }
                )
                if (index != state.profiles.lastIndex) {
                    HorizontalDivider()
                }
            }

            HorizontalDivider()
            ResponsiveSettingsOptionRow(
                icon = Icons.Default.AddCircleOutline,
                title = "Add profile",
                subtitle = "Connect another Stalker or Xtream service",
                modifier = Modifier
                    .remoteFocusFrame(RoundedCornerShape(14.dp))
                    .clickable(onClick = addProfile),
                trailingContent = {
                    Icon(Icons.Default.ChevronRight, "Add profile")
                }
            )
        }
        val activeSettingsDestination = LocalSettingsDestination.current
        if (
            !modernSettingsRows &&
            (
                activeSettingsDestination == null ||
                    activeSettingsDestination == SettingsDestination.GENERAL
                )
        ) {
            OrientationSettingsSection(Modifier.focusGroup())
        }

        if (
            profile != null &&
            (
                activeSettingsDestination == null ||
                    activeSettingsDestination == SettingsDestination.GENERAL
                )
        ) {
            PlaybackDesignSettingsSection(
                profile.cacheKey(),
                Modifier.focusGroup()
            )
        }

        if (profile != null) SettingsSection("Connection") {
            SettingsValueRow(
                Icons.Default.AccountCircle,
                "Profile",
                profile.name
            )
            HorizontalDivider()
            SettingsValueRow(
                Icons.Default.Language,
                "Portal",
                profile.portalUrl
            )
            HorizontalDivider()
            SettingsValueRow(
                Icons.Default.Security,
                "Session",
                if (state.session != null) {
                    "Authenticated"
                } else {
                    "Authentication required"
                }
            )
            HorizontalDivider()
            ResponsiveSettingsOptionRow(
                icon = Icons.Default.Security,
                title = "Automatically re-authenticate expired sessions",
                subtitle =
                    if (state.automaticReauthentication) {
                        "Retry an interrupted page load or playback once with a fresh session."
                    } else {
                        "Show the Session expired prompt and wait for confirmation."
                    },
                trailingContent = {
SettingsSwitch(
                        checked = state.automaticReauthentication,
                        onCheckedChange = setAutomaticReauthentication,
                        modifier = Modifier
                    )
                }
            )
            HorizontalDivider()
            SettingsValueRow(
                Icons.Default.Wifi,
                "Device MAC Address",
                deviceMacAddress
            )

            HorizontalDivider()
            ResponsiveSettingsOptionRow(
                icon = Icons.Default.Refresh,
                title = "Re-authenticate",
                subtitle =
                    "Request a fresh session token using the saved profile",
                modifier = Modifier
                    .remoteFocusFrame(RoundedCornerShape(14.dp))
                    .clickable(onClick = reauthenticate),
                trailingContent = {
                    Icon(Icons.Default.ChevronRight, "Re-authenticate")
                }
            )
            HorizontalDivider()
            ResponsiveSettingsOptionRow(
                icon = Icons.Default.Edit,
                title = "Edit connection",
                subtitle = "Change portal address or credentials",
                modifier = Modifier
                    .remoteFocusFrame(RoundedCornerShape(14.dp))
                    .clickable(onClick = editProfile),
                trailingContent = {
                    Icon(Icons.Default.ChevronRight, "Edit connection")
                }
            )

        }
SettingsSection("Data and sync") {
            var backupActivityOpen by remember { mutableStateOf(false) }
            val backupEvents by remember(context) { com.nikhil.niktv.data.BackupActivityLog.observe(context) }
                .collectAsState(initial = com.nikhil.niktv.data.BackupActivityLog.read(context))
            if (backupActivityOpen) BackupActivityDialog { backupActivityOpen = false }
            var catalogBackupEnabled by remember { mutableStateOf(com.nikhil.niktv.data.CatalogPreferences.backupEnabled(context)) }
            var preferLocalCatalog by remember { mutableStateOf(com.nikhil.niktv.data.CatalogPreferences.preferLocal(context)) }
            var catalogStatus by remember { mutableStateOf("") }
            var catalogRestoreBusy by remember { mutableStateOf(false) }
            var confirmCatalogBackup by remember { mutableStateOf(false) }
            var confirmCatalogRestore by remember { mutableStateOf(false) }
            var catalogTransferType by remember { mutableStateOf<CatalogType?>(null) }
            val catalogScope = rememberCoroutineScope()
            var catalogProfileId by remember(state.profiles) {
                mutableStateOf(
                    com.nikhil.niktv.data.CatalogScanPreferences.selectedProfileId(context)
                        ?.takeIf { saved -> state.profiles.any { com.nikhil.niktv.data.CatalogScanPreferences.id(it) == saved } }
                        ?: state.savedProfile?.let(com.nikhil.niktv.data.CatalogScanPreferences::id)
                )
            }
            val catalogProfile = state.profiles.firstOrNull {
                com.nikhil.niktv.data.CatalogScanPreferences.id(it) == catalogProfileId
            } ?: state.savedProfile ?: state.profiles.firstOrNull()
            if (confirmCatalogBackup && catalogProfile != null) {
                val scopeLabel = catalogTransferType?.title ?: "full catalog"
                ProjectCardConfirmationDialog(
                    title = "Upload ${catalogProfile.name} $scopeLabel?",
                    message = "This uploads the local ${catalogTransferType?.title ?: "Live TV, Movies and Series"} Room data for ${catalogProfile.name} to GitHub in device-specific parts. The current snapshot for this device is updated; snapshots from other devices are kept. Restores merge records instead of replacing personal data. Dated checkpoints are created only by a full-catalog upload when the catalog is small enough. The upload continues in the background and can be paused or resumed.",
                    confirmLabel = "Upload catalog",
                    close = { confirmCatalogBackup = false },
                    confirm = {
                        confirmCatalogBackup = false
                        com.nikhil.niktv.data.SearchMetadataSyncScheduler.requestNow(context, resume = true, profile = catalogProfile, type = catalogTransferType)
                    }
                )
            }
            if (confirmCatalogRestore && catalogProfile != null) {
                val scopeLabel = catalogTransferType?.title ?: "full catalog"
                ProjectCardConfirmationDialog(
                    title = "Restore ${catalogProfile.name} $scopeLabel?",
                    message = "This downloads the latest ${catalogTransferType?.title ?: "Live TV, Movies and Series"} device snapshots for ${catalogProfile.name} and merges them into this device's Room database. Existing newer records, favorites and watch history are retained. A separate restored resume point is saved so you can choose Resume restored instead of continuing this device's local cursor.",
                    confirmLabel = "Restore and merge",
                    close = { confirmCatalogRestore = false },
                    confirm = {
                        confirmCatalogRestore = false
                        com.nikhil.niktv.data.SearchMetadataSyncScheduler.requestRestore(context, catalogProfile, resume = true, type = catalogTransferType)
                    }
                )
            }
            CatalogProfileSettings(state.profiles, catalogProfile) { selected ->
                catalogProfileId = com.nikhil.niktv.data.CatalogScanPreferences.id(selected)
            }
            HorizontalDivider()
            ResponsiveSettingsOptionRow(
                icon = Icons.Default.Storage,
                title = "Prefer local catalog",
                subtitle = "Load saved channels, movies and series first. Missing data uses the provider. Turn off to request fresh provider catalogs.",
                trailingContent = {
                    SettingsSwitch(checked = preferLocalCatalog, onCheckedChange = {
                        preferLocalCatalog = it
                        com.nikhil.niktv.data.CatalogPreferences.setPreferLocal(context, it)
                    })
                }
            )
            ResponsiveSettingsOptionRow(
                icon = Icons.Default.CloudUpload,
                title = "Back up catalog from this device",
                subtitle = "Off by default. Upload a catalog every 12 hours. Each device has its own snapshot; imports merge records. Requires GitHub; a backup password is optional.",
                trailingContent = {
                    SettingsSwitch(checked = catalogBackupEnabled, onCheckedChange = {
                        val saved = githubBackupManager.loadConfig()
                        if (it && (saved.token.isBlank() || (saved.passphrase.isNotBlank() && saved.passphrase.length < 12) || saved.backupMode != com.nikhil.niktv.data.BackupMode.GITHUB)) {
                            catalogStatus = "Save GitHub settings below, then enable catalog backup. Leave the password blank, or use at least 12 characters."
                        } else {
                            catalogBackupEnabled = it
                            com.nikhil.niktv.data.CatalogPreferences.setBackupEnabled(context, it)
                            com.nikhil.niktv.data.SearchMetadataSyncScheduler.initialize(context)
                            com.nikhil.niktv.data.BackupActivityLog.record(context, "Automatic IPTV catalog backup",
                                if (it) "Enabled" else "Disabled", if (it) "Runs approximately every 12 hours on this device." else "Automatic catalog uploads are off on this device.")
                        }
                    })
                }
            )
            HorizontalDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Backup and restore scope", style = MaterialTheme.typography.titleSmall)
                Text("Choose the whole catalog or operate on one media type.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf<CatalogType?>(null, CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)) { type ->
                        val label = type?.title ?: "Full catalog"
                        FilterChip(selected = catalogTransferType == type, onClick = { catalogTransferType = type },
                            label = { Text(label) }, modifier = Modifier.remoteFocusFrame(RoundedCornerShape(8.dp)))
                    }
                }
            }
            CatalogOperationPanel(com.nikhil.niktv.data.CatalogOperations.BACKUP, "GitHub catalog upload", catalogBackupEnabled) {
                com.nikhil.niktv.data.SearchMetadataSyncScheduler.requestNow(context, resume = true, profile = catalogProfile, type = catalogTransferType)
            }
            CatalogOperationPanel(com.nikhil.niktv.data.CatalogOperations.RESTORE, "GitHub catalog restore") {
                catalogProfile?.let { com.nikhil.niktv.data.SearchMetadataSyncScheduler.requestRestore(context, it, resume = true, type = catalogTransferType) }
            }
            BackupSettingsActionRow(
                icon = Icons.Default.CloudUpload,
                title = "Back up ${catalogTransferType?.title ?: "full IPTV catalog"} now",
                subtitle = if (catalogBackupEnabled) "Upload changed ${catalogProfile?.name ?: "profile"} snapshots to GitHub." else "Enable catalog backup on this device first.",
                enabled = catalogBackupEnabled,
                onClick = {
                    confirmCatalogBackup = true
                    catalogStatus = ""
                }
            )
            BackupSettingsActionRow(
                icon = Icons.Default.CloudDownload,
                title = if (catalogRestoreBusy) "Restoring IPTV catalog…" else "Restore ${catalogTransferType?.title ?: "full IPTV catalog"}",
                subtitle = "Merge ${catalogProfile?.name ?: "the selected profile"} snapshots from any device into this device.",
                enabled = !catalogRestoreBusy && catalogProfile != null,
                onClick = {
                    confirmCatalogRestore = true
                }
            )
            if (catalogStatus.isNotBlank()) Text(catalogStatus, modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            BackupSettingsActionRow(
                icon = Icons.Default.History,
                title = "Backup and restore activity",
                subtitle = backupEvents.firstOrNull()?.let {
                    "${it.operation} · ${it.status} · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it.timestamp))}"
                } ?: "View timestamped activity on this device. No events yet.",
                onClick = { backupActivityOpen = true }
            )
            HorizontalDivider()
            val backupModes =
                listOf(
                    com.nikhil.niktv.data.BackupMode.GITHUB to "GitHub",
                    com.nikhil.niktv.data.BackupMode.DEVICE to "Device"
                )

            if (modernSettingsRows) {
                CompactSettingsOptionRow(
                    icon = Icons.Default.CloudUpload,
                    title = "Backup mode",
                    subtitle =
                        "Choose where NikTV stores backups. Backups can contain portal credentials.",
                    belowContent = {
                        Spacer(Modifier.height(8.dp))
                        SingleChoiceSegmentedButtonRow(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 40.dp, end = 2.dp)
                        ) {
                            backupModes.forEachIndexed {
                                    index,
                                    (mode, label) ->
                                val shape =
                                    uniformSegmentShape(
                                        index,
                                        backupModes.size
                                    )
                                SegmentedButton(
                                    selected =
                                        githubBackupConfig.backupMode ==
                                            mode,
                                    onClick = {
                                        val updated =
                                            githubBackupConfig.copy(
                                                backupMode = mode
                                            )
                                        githubBackupConfig = updated
                                        githubBackupManager.saveConfig(
                                            updated
                                        )
                                    },
                                    modifier =
                                        Modifier.remoteFocusFrame(shape),
                                    shape = shape
                                ) {
                                    Text(
                                        label,
                                        style =
                                            MaterialTheme.typography
                                                .labelLarge
                                    )
                                }
                            }
                        }
                    }
                )
            } else {
                Text(
                    "Backup files contain portal addresses and credentials. " +
                        "Use a backup password when storing sensitive backups in a public repository.",
                    modifier = Modifier.padding(
                        horizontal = 16.dp,
                        vertical = 10.dp
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(
                    Modifier.padding(
                        horizontal = 12.dp,
                        vertical = 8.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Backup mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    SingleChoiceSegmentedButtonRow(
                        Modifier.fillMaxWidth()
                    ) {
                        backupModes.forEachIndexed {
                                index,
                                (mode, label) ->
                            val shape =
                                uniformSegmentShape(
                                    index,
                                    backupModes.size
                                )
                            SegmentedButton(
                                selected =
                                    githubBackupConfig.backupMode == mode,
                                onClick = {
                                    val updated =
                                        githubBackupConfig.copy(
                                            backupMode = mode
                                        )
                                    githubBackupConfig = updated
                                    githubBackupManager.saveConfig(updated)
                                },
                                modifier =
                                    Modifier.remoteFocusFrame(shape),
                                shape = shape
                            ) {
                                Text(label)
                            }
                        }
                    }
                }
            }

            if (
                githubBackupConfig.backupMode ==
                com.nikhil.niktv.data.BackupMode.GITHUB
            ) {
                HorizontalDivider()
                Column(
                    Modifier.padding(
                        horizontal =
                            if (modernSettingsRows) 16.dp else 12.dp,
                        vertical = 10.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val githubUsernameRequester =
                        remember { FocusRequester() }
                    val githubRepositoryRequester =
                        remember { FocusRequester() }
                    val githubTokenRequester =
                        remember { FocusRequester() }
                    val githubPasswordRequester =
                        remember { FocusRequester() }

                    TvSafeSettingsTextField(
                        value = githubBackupConfig.username,
                        onValueChange = {
                            githubBackupConfig =
                                githubBackupConfig.copy(username = it)
                        },
                        icon = Icons.Default.AccountCircle,
                        title = "GitHub username",
                        subtitle =
                            "Account that owns the backup repository.",
                        placeholder = "GitHub username",
                        requester = githubUsernameRequester,
                        downRequester = githubRepositoryRequester
                    )
                    TvSafeSettingsTextField(
                        value = githubBackupConfig.repository,
                        onValueChange = {
                            githubBackupConfig =
                                githubBackupConfig.copy(repository = it)
                        },
                        icon = Icons.Default.Folder,
                        title = "Repository",
                        subtitle =
                            "Repository where NikTV stores backup files.",
                        placeholder = "Repository name",
                        requester = githubRepositoryRequester,
                        upRequester = githubUsernameRequester,
                        downRequester = githubTokenRequester
                    )
                    TvSafeSettingsTextField(
                        value = githubBackupConfig.token,
                        onValueChange = {
                            githubBackupConfig =
                                githubBackupConfig.copy(token = it)
                        },
                        icon = Icons.Default.Key,
                        title = "Personal access token (PAT)",
                        subtitle =
                            "Uses the cached private G_TOKEN when blank. " +
                                "A changed token is stored encrypted on this device.",
                        placeholder = "Personal access token",
                        password = true,
                        requester = githubTokenRequester,
                        upRequester = githubRepositoryRequester,
                        downRequester = githubPasswordRequester
                    )
                    TvSafeSettingsTextField(
                        value = githubBackupConfig.passphrase,
                        onValueChange = {
                            githubBackupConfig =
                                githubBackupConfig.copy(
                                    passphrase = it,
                                    rememberPassphrase = it.isNotBlank()
                                )
                        },
                        icon = Icons.Default.Lock,
                        title = "Backup password",
                        subtitle =
                            "Optional · Leave blank for readable JSON. " +
                                "Use 12+ characters to encrypt profile and catalog GitHub backups.",
                        placeholder = "Optional backup password",
                        password = true,
                        requester = githubPasswordRequester,
                        upRequester = githubTokenRequester
                    )

                    if (githubBackupConfig.passphrase.isBlank()) {
                        Text(
                            "No password: the backup is uploaded as readable JSON. " +
                                "Anyone who can read the repository can read the backed-up credentials.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else if (githubBackupConfig.passphrase.length < 12) {
                        Text(
                            "Finish the password to at least 12 characters, or clear it " +
                                "to use plain JSON. Automatic backup is paused while it is incomplete.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    val scheduleOptions =
                        listOf(
                            0 to "Off",
                            6 to "6h",
                            12 to "12h",
                            24 to "24h"
                        )
                    val scheduleSummary =
                        if (
                            githubBackupConfig.autoBackupIntervalHours == 0
                        ) {
                            "Automatic backup is off."
                        } else {
                            "Check every ${githubBackupConfig.autoBackupIntervalHours} hours and upload only when data changes."
                        }

                    if (modernSettingsRows) {
                        CompactSettingsOptionRow(
                            icon = Icons.Default.Refresh,
                            title = "Automatic GitHub backup",
                            subtitle = scheduleSummary,
                            horizontalPadding = 0.dp,
                            verticalPadding = 4.dp,
                            belowContent = {
                                Spacer(Modifier.height(8.dp))
                                SingleChoiceSegmentedButtonRow(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = 40.dp,
                                            end = 2.dp
                                        )
                                ) {
                                    scheduleOptions.forEachIndexed {
                                            index,
                                            (hours, label) ->
                                        val shape =
                                            uniformSegmentShape(
                                                index,
                                                scheduleOptions.size
                                            )
                                        SegmentedButton(
                                            selected =
                                                githubBackupConfig
                                                    .autoBackupIntervalHours ==
                                                    hours,
                                            onClick = {
                                                val updated =
                                                    githubBackupConfig.copy(
                                                        autoBackupIntervalHours =
                                                            hours
                                                    )
                                                githubBackupConfig = updated
                                                githubBackupManager
                                                    .saveConfig(updated)
                                            },
                                            modifier =
                                                Modifier.remoteFocusFrame(
                                                    shape
                                                ),
                                            shape = shape
                                        ) {
                                            Text(
                                                label,
                                                style =
                                                    MaterialTheme.typography
                                                        .labelLarge
                                            )
                                        }
                                    }
                                }
                            }
                        )
                    } else {
                        Text(
                            "Automatic GitHub backup",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        SingleChoiceSegmentedButtonRow(
                            Modifier.fillMaxWidth()
                        ) {
                            scheduleOptions.forEachIndexed {
                                    index,
                                    (hours, label) ->
                                val shape =
                                    uniformSegmentShape(
                                        index,
                                        scheduleOptions.size
                                    )
                                SegmentedButton(
                                    selected =
                                        githubBackupConfig
                                            .autoBackupIntervalHours == hours,
                                    onClick = {
                                        val updated =
                                            githubBackupConfig.copy(
                                                autoBackupIntervalHours = hours
                                            )
                                        githubBackupConfig = updated
                                        githubBackupManager.saveConfig(updated)
                                    },
                                    modifier =
                                        Modifier.remoteFocusFrame(shape),
                                    shape = shape
                                ) {
                                    Text(label)
                                }
                            }
                        }
                        Text(
                            scheduleSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    NikTvSecondaryActionButton(
                        onClick = {
                            val config = normalizedGitHubBackupConfig()
                            githubBackupManager.saveConfig(config)
                            githubBackupConfig =
                                githubBackupManager.loadConfig()
                            githubBackupSucceeded = true
                            githubBackupMessage =
                                "GitHub backup settings saved"
                        },
                        enabled =
                            githubBackupConfig.username.isNotBlank() &&
                                githubBackupConfig.repository.isNotBlank() &&
                                (
                                    githubBackupConfig.token.isNotBlank() ||
                                        RemoteCredentials.get("G_TOKEN").isNotBlank()
                                    ) &&
                                (
                                    githubBackupConfig.passphrase.isBlank() ||
                                        githubBackupConfig.passphrase.length >=
                                        12
                                    ),
                        modifier = Modifier.remoteFocusFrame(
                            RoundedCornerShape(12.dp)
                        )
                    ) {
                        Icon(Icons.Default.Save, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Save GitHub settings")
                    }

                    githubBackupMessage?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                when (githubBackupSucceeded) {
                                    true ->
                                        MaterialTheme.colorScheme.primary
                                    false ->
                                        MaterialTheme.colorScheme.error
                                    null ->
                                        MaterialTheme.colorScheme
                                            .onSurfaceVariant
                                }
                        )
                    }
                }
            } else {
                Text(
                    "Export opens Android's document picker. Import lets you choose a local NikTV JSON backup.",
                    modifier = Modifier.padding(
                        horizontal = 16.dp,
                        vertical = 12.dp
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()
            ResponsiveSettingsOptionRow(
                icon =
                    if (
                        githubBackupConfig.backupMode ==
                        com.nikhil.niktv.data.BackupMode.GITHUB
                    ) {
                        Icons.Default.CloudUpload
                    } else {
                        Icons.Default.FileUpload
                    },
                title = "Export backup",
                subtitle =
                    if (
                        githubBackupConfig.backupMode ==
                        com.nikhil.niktv.data.BackupMode.GITHUB
                    ) {
                        if (githubBackupConfig.passphrase.isBlank()) {
                            "Upload the current NikTV setup to GitHub as JSON"
                        } else {
                            "Encrypt and upload the current NikTV setup to GitHub"
                        }
                    } else {
                        "Save the current NikTV setup to this device"
                    },
                modifier = Modifier
                    .remoteFocusFrame(RoundedCornerShape(14.dp))
                    .clickable(enabled = !githubBackupUploading) {
                        if (
                            githubBackupConfig.backupMode ==
                            com.nikhil.niktv.data.BackupMode.GITHUB
                        ) {
                            performGitHubExport()
                        } else {
                            exportLauncher.launch(
                                githubBackupManager.suggestedBackupFileName(
                                    encrypted = false
                                )
                            )
                        }
                    },
                trailingContent = {
                    if (githubBackupUploading) {
                        CircularProgressIndicator(
                            Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.ChevronRight, "Export backup")
                    }
                }
            )

            HorizontalDivider()
            ResponsiveSettingsOptionRow(
                icon =
                    if (
                        githubBackupConfig.backupMode ==
                        com.nikhil.niktv.data.BackupMode.GITHUB
                    ) {
                        Icons.Default.CloudDownload
                    } else {
                        Icons.Default.FileDownload
                    },
                title = "Import backup",
                subtitle =
                    if (
                        githubBackupConfig.backupMode ==
                        com.nikhil.niktv.data.BackupMode.GITHUB
                    ) {
                        "Choose one of the backups stored in GitHub"
                    } else {
                        "Choose a NikTV JSON backup from this device"
                    },
                modifier = Modifier
                    .remoteFocusFrame(RoundedCornerShape(14.dp))
                    .clickable {
                        if (
                            githubBackupConfig.backupMode ==
                            com.nikhil.niktv.data.BackupMode.GITHUB
                        ) {
                            openGitHubRestoreBrowser()
                        } else {
                            importLauncher.launch(
                                arrayOf(
                                    "application/json",
                                    "text/json",
                                    "text/plain"
                                )
                            )
                        }
                    },
                trailingContent = {
                    Icon(Icons.Default.ChevronRight, "Import backup")
                }
            )
        }


        if (githubRestoreDialogOpen) {
            AlertDialog(
                onDismissRequest = {
                    if (!githubRestoreLoading) {
                        githubRestoreDialogOpen = false
                        githubPendingRestore = null
                    }
                },
                icon = { Icon(Icons.Default.Restore, null) },
                title = { Text("Restore GitHub backup") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when {
                            githubRestoreLoading -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    CircularProgressIndicator(
                                        Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        githubRestoreMessage
                                            ?: "Loading backup…"
                                    )
                                }
                            }

                            githubPendingRestore != null -> {
                                val decoded = githubPendingRestore!!
                                val preview = decoded.preview
                                Text(
                                    "Decryption successful",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text("Device: ${preview.deviceName}")
                                Text("Backup date: ${preview.exportedAt}")
                                Text("NikTV version: ${preview.appVersion}")
                                Text("Settings version: ${preview.settingsVersion}")
                                Text("Profiles: ${preview.profileCount}")
                                githubSelectedBackupName?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                NikTvSecondaryActionButton(
                                    onClick = {
                                        githubPendingRestore = null
                                        githubSelectedBackupName = null
                                        githubRestoreMessage = null
                                    }
                                ) {
                                    Text("Choose another backup")
                                }
                            }

                            else -> {
                                githubRestoreMessage?.let { message ->
                                    Text(
                                        message,
                                        color =
                                            if (githubBackupFiles.isEmpty()) {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            } else {
                                                MaterialTheme.colorScheme.error
                                            }
                                    )
                                }

                                if (githubBackupFiles.isNotEmpty()) {
                                    LazyColumn(
                                        modifier = Modifier.heightIn(max = 340.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        items(
                                            githubBackupFiles,
                                            key = { it.path }
                                        ) { backup ->
                                            ListItem(
                                                headlineContent = {
                                                    Text(
                                                        backup.name,
                                                        maxLines = 4,
                                                        overflow = TextOverflow.Visible,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                },
                                                supportingContent = {
                                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                        Text(
                                                            "${formatOfflineBytes(backup.size)} · " + if (backup.encrypted) "Encrypted backup" else "Readable JSON",
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                        Text(
                                                            backup.path,
                                                            maxLines = 2,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                },
                                                leadingContent = {
                                                    Icon(if (backup.encrypted) Icons.Default.Lock else Icons.Default.Description, null)
                                                },
                                                trailingContent = {
                                                    Icon(
                                                        Icons.Default.ChevronRight,
                                                        "Open ${backup.name}"
                                                    )
                                                },
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable {
                                                        val config =
                                                            normalizedGitHubBackupConfig()
                                                        githubRestoreLoading = true
                                                        githubRestoreMessage =
                                                            "Loading ${backup.name}…"
                                                        githubSelectedBackupName =
                                                            backup.name

                                                        scope.launch {
                                                            runCatching {
                                                                githubBackupManager
                                                                    .downloadAndDecryptBackup(
                                                                        backup,
                                                                        config
                                                                    )
                                                            }.onSuccess { decoded ->
                                                                githubPendingRestore =
                                                                    decoded
                                                                githubRestoreMessage =
                                                                    null
                                                            }.onFailure { error ->
                                                                githubPendingRestore =
                                                                    null
                                                                githubRestoreMessage =
                                                                    error.message
                                                                        ?: "Could not open backup"
                                                            }
                                                            githubRestoreLoading = false
                                                        }
                                                    },
                                                colors = ListItemDefaults.colors(
                                                    containerColor =
                                                        MaterialTheme.colorScheme
                                                            .surfaceContainerHigh
                                                )
                                            )
                                        }
                                    }
                                }

                            }
                        }
                    }
                },
                confirmButton = {
                    if (
                        githubPendingRestore != null &&
                        !githubRestoreLoading
                    ) {
                        NikTvPrimaryActionButton(
                            onClick = {
                                val decoded =
                                    githubPendingRestore ?: return@NikTvPrimaryActionButton
                                importBackupContent(decoded.rawBackup)
                                githubRestoreDialogOpen = false
                                githubPendingRestore = null
                                githubBackupMessage =
                                    "GitHub backup restored"
                                githubBackupSucceeded = true
                            }
                        ) {
                            Text("Restore this backup")
                        }
                    }
                },
                dismissButton = {
                    NikTvTextActionButton(
                        enabled = !githubRestoreLoading,
                        onClick = {
                            githubRestoreDialogOpen = false
                            githubPendingRestore = null
                        }
                    ) {
                        Text("Close")
                    }
                }
            )
        }

        SettingsSection("App updates") {
            Column {
                ResponsiveSettingsOptionRow(
                    icon = Icons.Default.AdminPanelSettings,
                    title = "Require updates before using NikTV",
                    subtitle =
                        if (BuildConfig.DEBUG) {
                            "Development build · disabled by default"
                        } else {
                            "Block access until an available update is installed"
                        },
                    trailingContent = {
SettingsSwitch(
                            checked = updateEnforcementEnabled,
                            onCheckedChange =
                                AppUpdates::setUpdateEnforcementEnabled,
                            modifier = Modifier
                        )
                    }
                )
                HorizontalDivider()
                ResponsiveSettingsOptionRow(
                    icon = Icons.Default.Refresh,
                    title = "Check for updates on startup",
                    subtitle =
                        if (startupUpdateCheckEnabled) {
                            "Enabled · check for an update whenever NikTV starts"
                        } else {
                            "Disabled · no launch-time update check"
                        },
                    trailingContent = {
SettingsSwitch(
                            checked = startupUpdateCheckEnabled,
                            onCheckedChange =
                                AppUpdates::setStartupUpdateCheckEnabled,
                            modifier = Modifier
                        )
                    }
                )
                HorizontalDivider()

                val updatePackageSummary =
                    if (updatePackagePreference == UpdatePackage.AUTO) {
                        "Automatic · ${AppUpdates.effectiveUpdatePackage().displayName} detected"
                    } else {
                        "Use ${updatePackagePreference.displayName} for future updates"
                    }

                if (modernSettingsRows) {
                    CompactSettingsOptionRow(
                        icon = Icons.Default.SystemUpdate,
                        title = "Update APK",
                        subtitle = updatePackageSummary,
                        belowContent = {
                            Spacer(Modifier.height(8.dp))
                            FlowRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 40.dp, end = 2.dp),
                                horizontalArrangement =
                                    Arrangement.spacedBy(8.dp),
                                verticalArrangement =
                                    Arrangement.spacedBy(8.dp)
                            ) {
                                UpdatePackage.entries.forEach { option ->
                                    FilterChip(
                                        selected =
                                            updatePackagePreference == option,
                                        onClick = {
                                            AppUpdates.setUpdatePackage(option)
                                            availableUpdate = null
                                            updateMessage =
                                                "Update APK set to ${if (option == UpdatePackage.AUTO) AppUpdates.effectiveUpdatePackage().displayName else option.displayName}"
                                        },
                                        label = {
                                            Text(option.displayName)
                                        },
                                        modifier =
                                            Modifier.remoteFocusFrame(
                                                RoundedCornerShape(10.dp)
                                            )
                                    )
                                }
                            }
                        }
                    )
                } else {
                    Column(
                        Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 12.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Update APK",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            updatePackageSummary,
                            style = MaterialTheme.typography.bodySmall,
                            color =
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        FlowRow(
                            horizontalArrangement =
                                Arrangement.spacedBy(8.dp),
                            verticalArrangement =
                                Arrangement.spacedBy(8.dp)
                        ) {
                            UpdatePackage.entries.forEach { option ->
                                FilterChip(
                                    selected =
                                        updatePackagePreference == option,
                                    onClick = {
                                        AppUpdates.setUpdatePackage(option)
                                        availableUpdate = null
                                        updateMessage =
                                            "Update APK set to ${if (option == UpdatePackage.AUTO) AppUpdates.effectiveUpdatePackage().displayName else option.displayName}"
                                    },
                                    label = { Text(option.displayName) },
                                    modifier = Modifier.remoteFocusFrame(
                                        RoundedCornerShape(10.dp)
                                    )
                                )
                            }
                        }
                    }
                }

                HorizontalDivider()
                ResponsiveSettingsOptionRow(
                    icon = Icons.Default.SystemUpdateAlt,
                    title = "One-click update",
                    subtitle =
                        if (oneClickUpdating) {
                            "Checking for an update…"
                        } else {
                            "Check now, download an available update, then open Android's installer"
                        },
                    modifier = Modifier
                        .focusRequester(oneClickUpdateRequester)
                        .remoteFocusFrame(RoundedCornerShape(14.dp))
                        .clickable {
                            if (!oneClickUpdating && !checkingUpdate) {
                                runOneClickUpdate()
                            }
                        },
                    trailingContent = {
                        if (oneClickUpdating) {
                            CircularProgressIndicator(
                                Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.ChevronRight,
                                "Run one-click update"
                            )
                        }
                    }
                )
                HorizontalDivider()

                val versionStatus =
                    buildString {
                        append(downloadStatus)
                        if (
                            downloadState !is UpdateDownloadState.Idle &&
                            updateMessage != null
                        ) {
                            append(" · ")
                            append(updateMessage)
                        }
                    }

                ResponsiveSettingsOptionRow(
                    icon = Icons.Default.SystemUpdate,
                    title = "NikTV ${BuildConfig.VERSION_NAME}",
                    subtitle = versionStatus,
                    modifier = Modifier
                        .focusRequester(versionRequester)
                        .remoteFocusFrame(RoundedCornerShape(14.dp))
                        .clickable {
                            if (!checkingUpdate && !oneClickUpdating) {
                                checkingUpdate = true
                                updateMessage = "Checking for updates…"
                                scope.launch {
                                    runCatching { AppUpdates.check() }
                                        .onSuccess { update ->
                                            availableUpdate = update
                                            updateMessage =
                                                if (update == null) {
                                                    "You're up to date"
                                                } else {
                                                    "Version ${update.version} is available"
                                                }
                                        }
                                        .onFailure {
                                            updateMessage =
                                                updateCheckFailureMessage(it)
                                        }
                                    checkingUpdate = false
                                    if (availableUpdate == null) {
                                        delay(80L)
                                        runCatching {
                                            versionRequester.requestFocus()
                                        }
                                    }
                                }
                            }
                        },
                    trailingContent = {
                        if (checkingUpdate) {
                            CircularProgressIndicator(
                                Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.ChevronRight,
                                "Check for updates"
                            )
                        }
                    }
                )

                val updateProgressModifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start =
                            if (modernSettingsRows) 56.dp else 16.dp,
                        end = 16.dp
                    )

                when (val download = downloadState) {
                    is UpdateDownloadState.Queued -> {
                        LinearProgressIndicator(updateProgressModifier)
                    }
                    is UpdateDownloadState.Downloading -> {
                        if (download.totalBytes != null) {
                            LinearProgressIndicator(
                                progress = {
                                    (download.percent ?: 0) / 100f
                                },
                                modifier = updateProgressModifier
                            )
                        } else {
                            LinearProgressIndicator(updateProgressModifier)
                        }
                    }
                    is UpdateDownloadState.Paused -> {
                        if (download.totalBytes != null) {
                            LinearProgressIndicator(
                                progress = {
                                    (
                                        download.bytesDownloaded.toFloat() /
                                            download.totalBytes
                                        ).coerceIn(0f, 1f)
                                },
                                modifier = updateProgressModifier
                            )
                        }
                    }
                    else -> Unit
                }

                downloadedBytes?.let { (bytes, total) ->
                    Text(
                        buildString {
                            append(
                                "Downloaded ${formatDownloadBytes(bytes)}"
                            )
                            total?.let {
                                append(" of ${formatDownloadBytes(it)}")
                            }
                        },
                        Modifier.padding(
                            start =
                                if (modernSettingsRows) 56.dp
                                else 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 8.dp
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (
                    downloadState is UpdateDownloadState.Ready ||
                    downloadState is UpdateDownloadState.InstallerLaunched
                ) {
                    val version =
                        when (val download = downloadState) {
                            is UpdateDownloadState.Ready ->
                                download.version
                            is UpdateDownloadState.InstallerLaunched ->
                                download.version
                            else -> ""
                        }
                    Text(
                        "Saved in ${AppUpdates.savedLocation(version)}",
                        Modifier.padding(
                            start =
                                if (modernSettingsRows) 56.dp
                                else 16.dp,
                            end = 16.dp,
                            top = 4.dp,
                            bottom = 4.dp
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = 12.dp,
                                vertical = 8.dp
                            ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NikTvPrimaryActionButton(
                            onClick = {
                                downloadActionMessage = null
                                runCatching {
                                    AppUpdates.install(context)
                                }.onFailure {
                                    downloadActionMessage = it.message
                                }
                            },
                            modifier = Modifier.remoteFocusFrame()
                        ) {
                            Text("Install")
                        }
                        NikTvSecondaryActionButton(
                            onClick = {
                                downloadActionMessage = null
                                runCatching {
                                    AppUpdates.openDownloads(context)
                                }.onFailure {
                                    downloadActionMessage = it.message
                                }
                            },
                            modifier = Modifier.remoteFocusFrame()
                        ) {
                            Text("Open Downloads")
                        }
                    }
                }

                if (downloadState is UpdateDownloadState.Failed) {
                    NikTvPrimaryActionButton(
                        onClick = {
                            val failed =
                                downloadState as UpdateDownloadState.Failed
                            requestUpdateDownload(
                                UpdateInfo(
                                    failed.version,
                                    failed.downloadUrl
                                )
                            )
                        },
                        modifier = Modifier
                            .padding(
                                horizontal = 16.dp,
                                vertical = 8.dp
                            )
                            .remoteFocusFrame()
                    ) {
                        Text("Retry download")
                    }
                }

                HorizontalDivider()
                ResponsiveSettingsOptionRow(
                    icon = Icons.Default.DeleteSweep,
                    title = "Delete older update APKs",
                    subtitle =
                        obsoleteApks?.let { cleanup ->
                            if (cleanup.fileCount == 0) {
                                "No obsolete NikTV installers found"
                            } else {
                                "${cleanup.fileCount} installer${if (cleanup.fileCount == 1) "" else "s"} · ${formatDownloadBytes(cleanup.totalBytes)}"
                            }
                        } ?: "Checking Downloads/NikTV…",
                    modifier = Modifier
                        .remoteFocusFrame(RoundedCornerShape(14.dp))
                        .clickable(enabled = !cleaningObsoleteApks) {
                            if ((obsoleteApks?.fileCount ?: 0) == 0) {
                                apkCleanupMessage =
                                    if (obsoleteApks == null) {
                                        "Still checking Downloads/NikTV for older installers"
                                    } else {
                                        "No obsolete NikTV installers found"
                                    }
                                return@clickable
                            }
                            cleaningObsoleteApks = true
                            apkCleanupMessage = null
                            scope.launch {
                                runCatching {
                                    AppUpdates.deleteObsoleteDownloadedApks(
                                        context
                                    )
                                }.onSuccess { result ->
                                    apkCleanupMessage =
                                        "Deleted ${result.deletedCount} installer${if (result.deletedCount == 1) "" else "s"} and reclaimed ${formatDownloadBytes(result.deletedBytes)}"
                                    obsoleteApks =
                                        AppUpdates.obsoleteDownloadedApks(
                                            context
                                        )
                                }.onFailure {
                                    apkCleanupMessage =
                                        "Could not delete old installers: ${it.message}"
                                }
                                cleaningObsoleteApks = false
                            }
                        },
                    trailingContent = {
                        if (cleaningObsoleteApks) {
                            CircularProgressIndicator(
                                Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                Icons.Default.ChevronRight,
                                "Delete older update APKs"
                            )
                        }
                    }
                )

                apkCleanupMessage?.let {
                    Text(
                        it,
                        Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 8.dp
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                downloadActionMessage?.let {
                    Text(
                        it,
                        Modifier.padding(
                            horizontal = 16.dp,
                            vertical = 8.dp
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        TmdbCredentialSettingsSection()
        SettingsSection("Data & reset") {
            ResponsiveSettingsOptionRow(
                icon = Icons.AutoMirrored.Filled.Logout,
                title = "Clear all app data",
                subtitle =
                    "Remove every profile, cache, favorite, recent item, and session",
                iconTint = MaterialTheme.colorScheme.error,
                titleColor = MaterialTheme.colorScheme.error,
                subtitleColor =
                    MaterialTheme.colorScheme.error.copy(alpha = .78f),
                modifier = Modifier
                    .remoteFocusFrame(RoundedCornerShape(14.dp))
                    .clickable(onClick = logout),
                trailingContent = {
                    Icon(
                        Icons.Default.ChevronRight,
                        "Clear all app data",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            )

        }
        if (
            LocalSettingsDestination.current ==
                SettingsDestination.SYSTEM
        ) {
            Text(
                "NikTV keeps the active profile and session in this app's private storage. Expired sessions are refreshed automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        } }
        if (compactSettingsHeader) {
            HorizontalPager(
                state = settingsPagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                CompositionLocalProvider(
                    LocalSettingsDestination provides
                        settingsDestinations[page]
                ) {
                    settingsPageContent()
                }
            }
        } else {
            Row(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF07080A))
            ) {
                SettingsDestinationRail(
                    selected = selectedSettingsDestination,
                    onSelected = { selectedSettingsDestination = it },
                    requesters = settingsRailRequesters,
                    detailRequester = settingsSummaryEntryRequester,
                    modifier = Modifier
                        .width(
                            when {
                                settingsIsTv -> 248.dp
                                tabletSettingsLayout -> 204.dp
                                else -> 218.dp
                            }
                        )
                        .fillMaxHeight()
                        .padding(padding)
                )
                VerticalDivider(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(
                            top = padding.calculateTopPadding(),
                            bottom = padding.calculateBottomPadding()
                        ),
                    color = Color(0xFF25272D)
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    CompositionLocalProvider(
                        LocalSettingsDestination provides
                            selectedSettingsDestination
                    ) {
                        settingsPageContent()
                    }
                }
            }
        }
    }
    pendingRemoval?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove ${target.name}?") },
            text = { Text("This removes its saved credentials and session. Other profiles remain available.") },
            confirmButton = { NikTvTextActionButton(onClick = { removeProfile(target); pendingRemoval = null }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { NikTvTextActionButton(onClick = { pendingRemoval = null }) { Text("Cancel") } }
        )
    }
    availableUpdate?.let { update ->
        Dialog(onDismissRequest = {
            restoreVersionFocus = true
            availableUpdate = null
        }) {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 620.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                tonalElevation = 2.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(48.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.SystemUpdate, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("NikTV ${update.version} is available", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("Current version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("Download the signed APK to ${AppUpdates.savedLocation(update.version)}. Android will ask you to confirm installation when it is ready.")
                    downloadActionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        NikTvSecondaryActionButton(
                            onClick = {
                                AppUpdates.dismissPendingUpdate(update)
                                restoreVersionFocus = true
                                availableUpdate = null
                                pendingPermissionUpdate = null
                            },
                            modifier = Modifier.height(44.dp)
                                .focusProperties { canFocus = updateDialogNavigationEnabled }
                                .remoteFocusFrame(CircleShape),
                            shape = CircleShape
                        ) { Text("Later") }
                        NikTvPrimaryActionButton(
                            onClick = { requestUpdateDownload(update) },
                            enabled = AppUpdates.canStartDownload(update),
                            modifier = Modifier.height(44.dp).focusRequester(updateDownloadRequester).remoteFocusFrame(CircleShape),
                            shape = CircleShape
                        ) {
                            Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (AppUpdates.canWritePublicDownloads(context)) "Download" else "Allow & download")
                        }
                    }
                }
            }
        }
    }
}

private fun updateCheckFailureMessage(error: Throwable): String {
    val detail = error.message.orEmpty()
    return if (detail.contains("HTTP 404", ignoreCase = true)) {
        "The update is still being published. Try again shortly."
    } else {
        "Could not check for updates: ${detail.ifBlank { "Unknown error" }}"
    }
}

@Composable
internal fun LiveTvPlaybackScreen(
    state: NikTvState,
    play: (MediaItem) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRetryAlternateDecoder: (Long) -> Unit,
    onPlaybackAuthorizationFailure: (Long) -> Unit,
    onPlayPrevious: () -> Unit,
    onPlayNext: () -> Unit,
    onProgress: (String, Long, Long) -> Unit,
    toggleFavorite: (MediaItem) -> Unit,
    loadMoreCatalog: () -> Unit,
    refreshPlaybackQueue: () -> Unit,
    loadMorePlaybackQueue: () -> Boolean
) {
    val playing = state.nowPlaying ?: return
    val channels = playing.episodeQueue.ifEmpty { listOf(playing.media) }
    var fullscreen by remember {
        mutableStateOf(false)
    }

    /*
     * One stable FocusRequester per channel.
     */
    val channelFocusRequesters = remember {
        mutableMapOf<String, FocusRequester>()
    }

    val channelListState = rememberLazyListState()

    /*
     * Used only when returning from fullscreen.
     */
    var restorePlayingChannelRequest by remember {
        mutableIntStateOf(0)
    }

    /*
     * Load More state.
     */
    var loadMorePending by remember {
        mutableStateOf(false)
    }

    var loadMoreObservedLoading by remember {
        mutableStateOf(false)
    }

    /*
     * Number of channels before pagination.
     *
     * When new channels are appended, this is the index of
     * the first newly loaded channel.
     */
    var loadMoreStartItemCount by remember {
        mutableIntStateOf(0)
    }

    /*
     * Preserve the exact viewport that existed when Load More
     * was activated.
     *
     * This prevents the first newly loaded channel from being
     * moved to the top of the screen.
     */
    var loadMoreFirstVisibleItemIndex by remember {
        mutableIntStateOf(0)
    }

    var loadMoreFirstVisibleItemScrollOffset by remember {
        mutableIntStateOf(0)
    }

    val playerConfiguration =
        LocalConfiguration.current

    val narrowPlayerLayout =
        playerConfiguration.screenWidthDp < 900

    val mobilePlaylistLayout =
        playerConfiguration.screenWidthDp < 600

    val mobilePlayerHeight =
        (playerConfiguration.screenWidthDp.dp * 9f / 16f)

    val playerWidthFraction =
        if (narrowPlayerLayout) {
            0.58f
        } else {
            0.72f
        }

    val channelWidthFraction =
        1f - playerWidthFraction

    /*
     * Used when we deliberately want to navigate to a channel:
     *
     * - opening Live TV
     * - returning from fullscreen
     *
     * Pagination does NOT use this because it intentionally
     * places the requested channel at the start of the viewport.
     */
    suspend fun focusChannelAt(
        index: Int,
        channel: MediaItem
    ) {
        if (index < 0) return

        val requester =
            channelFocusRequesters.getOrPut(channel.id) {
                FocusRequester()
            }

        channelListState.scrollToItem(
            index = index,
            scrollOffset = 0
        )

        /*
         * Wait for LazyColumn to actually lay out the row.
         */
        snapshotFlow {
            channelListState.layoutInfo.visibleItemsInfo
                .any { visibleItem ->
                    visibleItem.index == index
                }
        }.first { visible ->
            visible
        }

        /*
         * Give the FocusRequester modifier one frame to attach.
         */
        withFrameNanos { }

        runCatching {
            requester.requestFocus()
        }
    }

    /*
     * Initial Live TV entry.
     */
    LaunchedEffect(Unit) {
        val playingIndex =
            channels.indexOfFirst {
                it.id == playing.media.id
            }

        val playingChannel =
            channels.getOrNull(
                playingIndex
            )

        if (
            playingIndex >= 0 &&
            playingChannel != null
        ) {
            focusChannelAt(
                index = playingIndex,
                channel = playingChannel
            )
        }
    }

    /*
     * Restore the playing row only after leaving fullscreen.
     */
    LaunchedEffect(
        restorePlayingChannelRequest
    ) {
        if (
            restorePlayingChannelRequest == 0 ||
            fullscreen
        ) {
            return@LaunchedEffect
        }

        val playingIndex =
            channels.indexOfFirst {
                it.id == playing.media.id
            }

        val playingChannel =
            channels.getOrNull(
                playingIndex
            )

        if (
            playingIndex >= 0 &&
            playingChannel != null
        ) {
            focusChannelAt(
                index = playingIndex,
                channel = playingChannel
            )
        }
    }

    /*
     * Seamless pagination focus handoff.
     *
     * Expected behaviour:
     *
     * 8
     * 9
     * 10
     * Load More     <- focus
     *
     * after loading:
     *
     * 8
     * 9
     * 10
     * 11            <- focus
     *
     * The viewport itself should remain essentially unchanged.
     */
    LaunchedEffect(
        loadMorePending,
        state.playbackQueueLoadingMore,
        channels.size
    ) {
        if (!loadMorePending) {
            return@LaunchedEffect
        }

        /*
         * Loading started.
         *
         * Leave focus on Load More.
         */
        if (state.playbackQueueLoadingMore) {
            loadMoreObservedLoading = true
            return@LaunchedEffect
        }

        val receivedNewChannels =
            channels.size >
                    loadMoreStartItemCount

        val loadFinished =
            loadMoreObservedLoading ||
                    receivedNewChannels

        if (!loadFinished) {
            return@LaunchedEffect
        }

        if (receivedNewChannels) {
            val firstNewChannel =
                channels.getOrNull(
                    loadMoreStartItemCount
                )

            if (firstNewChannel != null) {
                /*
                 * Pre-create the requester.
                 */
                val requester =
                    channelFocusRequesters.getOrPut(
                        firstNewChannel.id
                    ) {
                        FocusRequester()
                    }

                /*
                 * Restore exactly the viewport that existed when
                 * Load More was pressed.
                 *
                 * Since pagination only appends channels, existing
                 * channel indexes have not changed.
                 */
                channelListState.scrollToItem(
                    index =
                        loadMoreFirstVisibleItemIndex,

                    scrollOffset =
                        loadMoreFirstVisibleItemScrollOffset
                )

                /*
                 * Wait until the first new channel exists in
                 * LazyColumn's visible layout.
                 *
                 * Because it replaces the Load More row visually,
                 * it should already be near the bottom of the
                 * viewport rather than at the top.
                 */
                snapshotFlow {
                    channelListState.layoutInfo
                        .visibleItemsInfo
                        .any { visibleItem ->
                            visibleItem.index ==
                                    loadMoreStartItemCount
                        }
                }.first { visible ->
                    visible
                }

                withFrameNanos { }

                /*
                 * Change focus only.
                 *
                 * Do not scroll to the new channel.
                 */
                runCatching {
                    requester.requestFocus()
                }
            }
        }

        /*
         * Pagination is completely finished.
         *
         * No delayed focus operations remain after this point.
         */
        loadMorePending = false
        loadMoreObservedLoading = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Color(0xFF090909)
            )
    ) {
        PlayerScreen(
            media = playing,

            onBack =
                if (fullscreen) {
                    {
                        fullscreen = false
                        restorePlayingChannelRequest++
                        Unit
                    }
                } else {
                    onBack
                },

            onRetry =
                onRetry,

            onRetryAlternateDecoder =
                onRetryAlternateDecoder,

            onPlaybackAuthorizationFailure =
                onPlaybackAuthorizationFailure,

            onPlayPrevious =
                onPlayPrevious,

            onPlayNext =
                onPlayNext,

            onProgress =
                onProgress,

            controlsTimeoutSeconds =
                state.playerControlsTimeoutSeconds,

            playbackEngine =
                state.playbackEngine,

            modifier =
                if (fullscreen) {
                    Modifier.fillMaxSize()
                } else if (mobilePlaylistLayout) {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .align(Alignment.TopCenter)
                } else {
                    Modifier
                        .fillMaxWidth(
                            playerWidthFraction
                        )
                        .aspectRatio(
                            16f / 9f
                        )
                        .align(
                            Alignment.CenterStart
                        )
                },

            embeddedMode =
                !fullscreen,

            fullscreenOverride =
                fullscreen,

            onFullscreenChanged = {
                if (
                    fullscreen &&
                    !it
                ) {
                    restorePlayingChannelRequest++
                }

                fullscreen = it
            }
        )

        if (!fullscreen) {
            Column(
                modifier = (if (mobilePlaylistLayout) {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxSize()
                        .padding(top = mobilePlayerHeight)
                } else {
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxWidth(channelWidthFraction)
                        .fillMaxHeight()
                })
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF1A1A1A),
                                Color(0xFF0D0D0D)
                            )
                        )
                    )
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(
                            if (mobilePlaylistLayout) {
                                WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                            } else {
                                WindowInsetsSides.Top + WindowInsetsSides.End + WindowInsetsSides.Bottom
                            }
                        )
                    )
                    .padding(
                        top = if (mobilePlaylistLayout) 12.dp else 8.dp,
                        bottom = 8.dp
                    ),

                verticalArrangement =
                    Arrangement.spacedBy(
                        5.dp
                    )
            ) {
                /*
                 * LIVE TV HEADER
                 */
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = if (mobilePlaylistLayout) 16.dp else 6.dp
                        ),

                    verticalArrangement =
                        Arrangement.spacedBy(
                            1.dp
                        )
                ) {
                    Text(
                        text =
                            "LIVE · ${
                                state.selectedCategory?.title
                                    ?: "Channels"
                            }",

                        color =
                            Color(0xFFE50914),

                        style =
                            if (mobilePlaylistLayout) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,

                        fontWeight =
                            FontWeight.Bold,

                        maxLines = 1,

                        overflow =
                            TextOverflow.Ellipsis
                    )

                    Text(
                        text =
                            playing.media.title,

                        color =
                            Color.White,

                        style =
                            if (mobilePlaylistLayout) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,

                        fontWeight =
                            FontWeight.Bold,

                        maxLines = 1,

                        overflow =
                            TextOverflow.Ellipsis
                    )

                    Text(
                        text =
                            playing.media
                                .liveProgramme
                                ?.title
                                ?: "${channels.size} channels",

                        color =
                            Color.LightGray,

                        style =
                            if (mobilePlaylistLayout) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,

                        maxLines = 1,

                        overflow =
                            TextOverflow.Ellipsis
                    )

                    NikTvTextActionButton(onClick = refreshPlaybackQueue) {
                        Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Clear cache & refresh")
                    }
                }

                /*
                 * CHANNEL LIST
                 */
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),

                    state =
                        channelListState,

                    contentPadding =
                        PaddingValues(
                            vertical = 2.dp
                        ),

                    verticalArrangement =
                        Arrangement.spacedBy(
                            3.dp
                        )
                ) {
                    items(
                        items =
                            channels,

                        /*
                         * Stable keys preserve row identity when
                         * another page is appended.
                         */
                        key = { item ->
                            "live-player-${item.id}"
                        }
                    ) { item ->
                        val isPlaying =
                            item.id ==
                                    playing.media.id

                        val itemFocusRequester =
                            channelFocusRequesters.getOrPut(
                                item.id
                            ) {
                                FocusRequester()
                            }

                        ModernMediaListCard(
                            item =
                                item,

                            modifier =
                                Modifier
                                    .focusRequester(
                                        itemFocusRequester
                                    ),

                            onClick = {
                                if (isPlaying) {
                                    fullscreen = true
                                } else {
                                    play(item)
                                }
                            },

                            isFavorite =
                                state.favorites.any {
                                    it.kind ==
                                            FavoriteKind.CHANNEL &&
                                            it.media.id ==
                                            item.id
                                },

                            toggleFavorite = {
                                toggleFavorite(
                                    item
                                )
                            },

                            supportingText =
                                liveChannelSupportingText(
                                    item
                                ),

                            compact =
                                true,

                            isCurrentlyPlaying =
                                isPlaying
                        )
                    }

                    /*
                     * LOAD MORE CHANNELS
                     *
                     * Keep this row alive while pagination is pending.
                     *
                     * This is especially important on the final page,
                     * because catalogHasMore may become false before
                     * focus has moved to the newly appended channel.
                     */
                    if (
                        state.playbackQueueHasMore ||
                        state.playbackQueueLoadingMore ||
                        loadMorePending
                    ) {
                        item(
                            key =
                                "live-player-load-more"
                        ) {
                            NikTvPrimaryActionButton(
                                onClick = {
                                    /*
                                     * Button deliberately stays enabled
                                     * so it never loses focus simply
                                     * because loading started.
                                     *
                                     * Ignore repeated activations instead.
                                     */
                                    if (
                                        state.playbackQueueLoadingMore ||
                                        loadMorePending
                                    ) {
                                        return@NikTvPrimaryActionButton
                                    }

                                    /*
                                     * The first new channel will appear
                                     * at this index after append.
                                     */
                                    loadMoreStartItemCount =
                                        channels.size

                                    /*
                                     * Capture EXACT viewport position
                                     * before modifying the list.
                                     */
                                    loadMoreFirstVisibleItemIndex =
                                        channelListState
                                            .firstVisibleItemIndex

                                    loadMoreFirstVisibleItemScrollOffset =
                                        channelListState
                                            .firstVisibleItemScrollOffset

                                    loadMoreObservedLoading =
                                        false

                                    loadMorePending =
                                        loadMorePlaybackQueue()
                                },

                                /*
                                 * IMPORTANT:
                                 *
                                 * No enabled=false here.
                                 *
                                 * Keeping the button focusable prevents
                                 * focus from being evicted while the
                                 * network request is running.
                                 */

                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(
                                        46.dp
                                    )
                                    .remoteFocusFrame(
                                        RoundedCornerShape(
                                            10.dp
                                        )
                                    ),

                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor =
                                            Color(
                                                0xFFE50914
                                            ),

                                        contentColor =
                                            Color.White
                                    )
                            ) {
                                if (
                                    state.playbackQueueLoadingMore ||
                                    loadMorePending
                                ) {
                                    CircularProgressIndicator(
                                        modifier =
                                            Modifier.size(
                                                20.dp
                                            ),

                                        strokeWidth =
                                            2.dp,

                                        color =
                                            Color.White
                                    )
                                } else {
                                    Icon(
                                        imageVector =
                                            Icons.Default.ExpandMore,

                                        contentDescription =
                                            null
                                    )
                                }

                                Spacer(
                                    modifier =
                                        Modifier.width(
                                            8.dp
                                        )
                                )

                                Text(
                                    text =
                                        if (
                                            state.catalogLoadingMore ||
                                            loadMorePending
                                        ) {
                                            "Loading channels…"
                                        } else {
                                            "Load more channels"
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlaybackEngineSettingsContent(
    selectedEngine: PlaybackEngine,
    setPlaybackEngine: (PlaybackEngine) -> Unit,
    compact: Boolean,
    entryRequester: FocusRequester,
    upRequester: FocusRequester? = null,
    downRequester: FocusRequester
) {
    val engines = listOf(
        Triple(
            PlaybackEngine.AUTO,
            "Auto",
            "Learn compatibility automatically and use VLC when needed."
        ),
        Triple(
            PlaybackEngine.MEDIA3,
            "ExoPlayer",
            "Use NikTV decoder fallback and recovery."
        ),
        Triple(
            PlaybackEngine.VLC,
            "VLC",
            "Use software decoding and broad format compatibility."
        )
    )

    if (compact) {
        val selectedDescription =
            engines.firstOrNull { it.first == selectedEngine }
                ?.third
                ?: "Choose the playback engine NikTV should use."

        CompactSettingsOptionRow(
            icon = Icons.Default.PlayCircle,
            title = "Playback engine",
            subtitle = selectedDescription,
            belowContent = {
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 40.dp, end = 2.dp)
                ) {
                    engines.forEachIndexed {
                            index,
                            (engine, label, _) ->
                        val shape =
                            uniformSegmentShape(
                                index,
                                engines.size
                            )
                        val selected =
                            selectedEngine == engine
                        SegmentedButton(
                            selected = selected,
                            onClick = {
                                setPlaybackEngine(engine)
                            },
                            modifier = Modifier
                                .then(
                                    if (selected) {
                                        Modifier.focusRequester(
                                            entryRequester
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .focusProperties {
                                    if (upRequester != null) {
                                        up = upRequester
                                    }
                                    down = downRequester
                                }
                                .remoteFocusFrame(shape),
                            shape = shape
                        ) {
                            Text(
                                label,
                                style =
                                    MaterialTheme.typography
                                        .labelLarge,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        )
    } else {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Used for Live TV, movies and episodes",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "Auto learns compatibility per series. You can force a specific engine here.",
                color = Color.Gray
            )
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = 2
            ) {
                engines.forEach {
                        (engine, label, description) ->
                    val selected = selectedEngine == engine
                    Surface(
                        onClick = {
                            setPlaybackEngine(engine)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(min = 150.dp)
                            .remoteFocusFrame(
                                RoundedCornerShape(16.dp)
                            ),
                        shape = RoundedCornerShape(16.dp),
                        color =
                            if (selected) {
                                Color(0xFF351416)
                            } else {
                                Color(0xFF1A1F2E)
                            },
                        border = BorderStroke(
                            if (selected) 2.dp else 1.dp,
                            if (selected) {
                                Color(0xFFE50914)
                            } else {
                                Color(0xFF30384B)
                            }
                        )
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment =
                                Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected,
                                onClick = null
                            )
                            Column(
                                Modifier.padding(start = 6.dp)
                            ) {
                                Text(
                                    label,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    description,
                                    style =
                                        MaterialTheme.typography
                                            .bodySmall,
                                    color = Color.Gray,
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            }
        }
    }

}

@Composable
internal fun AppearanceSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    requester: FocusRequester,
    upRequester: FocusRequester,
    downRequester: FocusRequester,
    valueText: String = "${(value * 100).toInt()}%",
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(valueText, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .focusRequester(requester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> {
                            upRequester.requestFocus()
                            true
                        }
                        Key.DirectionDown -> {
                            downRequester.requestFocus()
                            true
                        }
                        else -> false
                    }
                }
        )
    }
}

@Composable
internal fun ScheduleTimelineRow(
    entry: VideoAppearanceScheduleEntry,
    profiles: List<VideoAppearanceProfile>,
    canRemove: Boolean,
    onChange: (VideoAppearanceScheduleEntry) -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        Modifier.fillMaxWidth().focusGroup(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1B2130),
        border = BorderStroke(1.dp, Color(0xFF30384B))
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ScheduleTimeControl(
                    label = "Start",
                    minutes = entry.startMinutes,
                    modifier = Modifier.weight(1f),
                    onChange = { onChange(entry.copy(startMinutes = it)) }
                )
                ScheduleTimeControl(
                    label = "End",
                    minutes = entry.endMinutes,
                    modifier = Modifier.weight(1f),
                    onChange = { onChange(entry.copy(endMinutes = it)) }
                )
                ScheduleProfileControl(
                    profileId = entry.profileId,
                    profiles = profiles,
                    modifier = Modifier.weight(1.2f),
                    onChange = { onChange(entry.copy(profileId = it)) }
                )
                if (canRemove) {
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.remoteFocusFrame(CircleShape)
                    ) { Icon(Icons.Default.DeleteOutline, "Remove time period") }
                }
            }
        }
    }
}

@Composable
internal fun ScheduleTimeControl(
    label: String,
    minutes: Int,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.Gray, style = MaterialTheme.typography.labelMedium)
        IconButton(
            onClick = { onChange((minutes - 15 + 1440) % 1440) },
            modifier = Modifier.remoteFocusFrame(CircleShape)
        ) { Icon(Icons.Default.Remove, "$label 15 minutes earlier") }
        Text(
            formatScheduleTime(minutes),
            Modifier.widthIn(min = 92.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontWeight = FontWeight.SemiBold
        )
        IconButton(
            onClick = { onChange((minutes + 15) % 1440) },
            modifier = Modifier.remoteFocusFrame(CircleShape)
        ) { Icon(Icons.Default.Add, "$label 15 minutes later") }
    }
}

@Composable
internal fun ScheduleProfileControl(
    profileId: String,
    profiles: List<VideoAppearanceProfile>,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit
) {
    val profile = profiles.firstOrNull { it.id == profileId } ?: profiles.first()
    val profileIndex = profiles.indexOf(profile)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(videoAppearanceIcon(profile.id), null, Modifier.size(22.dp), tint = Color(0xFFFF6973))
        IconButton(
            onClick = { onChange(profiles[(profileIndex - 1 + profiles.size) % profiles.size].id) },
            modifier = Modifier.remoteFocusFrame(CircleShape)
        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous profile") }
        Text(profile.name, Modifier.widthIn(min = 112.dp), fontWeight = FontWeight.SemiBold)
        IconButton(
            onClick = { onChange(profiles[(profileIndex + 1) % profiles.size].id) },
            modifier = Modifier.remoteFocusFrame(CircleShape)
        ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next profile") }
    }
}

internal fun formatScheduleTime(minutes: Int): String {
    val normalized = (minutes % 1440 + 1440) % 1440
    val hour = normalized / 60
    val minute = normalized % 60
    val displayHour = (hour % 12).takeIf { it != 0 } ?: 12
    val period = if (hour < 12) "AM" else "PM"
    return "$displayHour:${minute.toString().padStart(2, '0')} $period"
}

private val LocalSettingsDestination =
    compositionLocalOf<SettingsDestination?> { null }

@Composable
private fun SettingsDestinationHeader(
    destination: SettingsDestination,
    compact: Boolean
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF2A1215),
                border = BorderStroke(1.dp, Color(0xFF6F2028))
            ) {
                Icon(
                    destination.icon(),
                    null,
                    Modifier
                        .padding(if (compact) 8.dp else 10.dp)
                        .size(if (compact) 20.dp else 24.dp),
                    tint = Color(0xFFFF6973)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    destination.title,
                    style =
                        if (compact) {
                            MaterialTheme.typography.titleLarge
                        } else {
                            MaterialTheme.typography.headlineSmall
                        },
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFF5F5F7)
                )
                Text(
                    destination.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF9B9FA8)
                )
            }
            if (!compact) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("v${BuildConfig.VERSION_NAME}") }
                )
            }
        }
    }
}

@Composable
private fun SettingsSummaryRow(
    activeProfile: String,
    defaultPlayer: String,
    backup: String,
    updates: String,
    currentDestination: SettingsDestination,
    railRequester: FocusRequester,
    entryRequester: FocusRequester,
    onSelect: (SettingsDestination) -> Unit
) {
    val summaries =
        listOf(
            SettingsSummary(
                Icons.Default.AccountCircle,
                "Active profile",
                activeProfile,
                SettingsDestination.PROFILES
            ),
            SettingsSummary(
                Icons.Default.PlayCircle,
                "Default player",
                defaultPlayer,
                SettingsDestination.GENERAL
            ),
            SettingsSummary(
                Icons.Default.CloudDone,
                "Backup",
                backup,
                SettingsDestination.CATALOG
            ),
            SettingsSummary(
                Icons.Default.SystemUpdate,
                "Updates",
                updates,
                SettingsDestination.SYSTEM
            )
        )

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        summaries.forEachIndexed { index, summary ->
            Surface(
                onClick = { onSelect(summary.destination) },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 82.dp)
                    .then(
                        if (index == 0) {
                            Modifier.focusRequester(entryRequester)
                        } else {
                            Modifier
                        }
                    )
                    .then(
                        if (index == 0) {
                            Modifier.focusProperties {
                                left = railRequester
                            }
                        } else {
                            Modifier
                        }
                    )
                    .remoteFocusFrame(RoundedCornerShape(14.dp)),
                shape = RoundedCornerShape(14.dp),
                color =
                    if (summary.destination == currentDestination) {
                        Color(0xFF241418)
                    } else {
                        Color(0xFF12151B)
                    },
                border = BorderStroke(
                    1.dp,
                    if (summary.destination == currentDestination) {
                        Color(0xFF6F2028)
                    } else {
                        Color(0xFF2B2F37)
                    }
                )
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        summary.icon,
                        null,
                        Modifier.size(22.dp),
                        tint = Color(0xFFFF6973)
                    )
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            summary.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF9B9FA8),
                            maxLines = 1
                        )
                        Text(
                            summary.value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFF5F5F7),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private data class SettingsSummary(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val destination: SettingsDestination
)

@Composable
private fun SettingsDestinationRail(
    selected: SettingsDestination,
    onSelected: (SettingsDestination) -> Unit,
    requesters: Map<SettingsDestination, FocusRequester>,
    detailRequester: FocusRequester,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color(0xFF0B0D10)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF5F5F7)
            )
            Text(
                "Customize NikTV",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF81858E)
            )
            Spacer(Modifier.height(12.dp))

            SettingsDestination.entries.forEach { destination ->
                val isSelected = destination == selected
                val shape = RoundedCornerShape(14.dp)
                Surface(
                    onClick = { onSelected(destination) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp)
                        .focusRequester(requesters.getValue(destination))
                        .focusProperties {
                            right = detailRequester
                        }
                        .remoteFocusFrame(shape)
                        .semantics {
                            role = Role.Tab
                            this.selected = isSelected
                        },
                    shape = shape,
                    color =
                        if (isSelected) {
                            Color(0xFF3A1014)
                        } else {
                            Color.Transparent
                        },
                    border =
                        if (isSelected) {
                            BorderStroke(1.dp, Color(0xFFE50914))
                        } else {
                            BorderStroke(1.dp, Color.Transparent)
                        }
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            destination.icon(),
                            null,
                            Modifier.size(22.dp),
                            tint =
                                if (isSelected) {
                                    Color(0xFFFF6973)
                                } else {
                                    Color(0xFFB4B7BF)
                                }
                        )
                        Text(
                            destination.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight =
                                if (isSelected) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Medium
                                },
                            color =
                                if (isSelected) {
                                    Color.White
                                } else {
                                    Color(0xFFD4D6DA)
                                }
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            Text(
                "NikTV ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF686D76)
            )
        }
    }
}

@Composable
private fun CompactSettingsOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    horizontalPadding: Dp = 16.dp,
    verticalPadding: Dp = 12.dp,
    trailingContent: @Composable RowScope.() -> Unit = {},
    belowContent: @Composable ColumnScope.() -> Unit = {}
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val tabletSettingsLayout =
        !context.isTvLikeDevice(configuration) &&
            configuration.smallestScreenWidthDp >= 600

    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (tabletSettingsLayout) {
                    Modifier.heightIn(min = 72.dp)
                } else {
                    Modifier
                }
            )
            .padding(
                horizontal = horizontalPadding,
                vertical = verticalPadding
            )
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = iconTint
                )
            }

            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = titleColor
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = subtitleColor
                )
            }

            trailingContent()
        }

        belowContent()
    }
}

@Composable
private fun ResponsiveSettingsOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailingContent: @Composable RowScope.() -> Unit = {}
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val compact = configuration.screenWidthDp < 600
    val tablet =
        !context.isTvLikeDevice(configuration) &&
            configuration.smallestScreenWidthDp >= 600

    if (compact || tablet) {
        CompactSettingsOptionRow(
            icon = icon,
            title = title,
            subtitle = subtitle,
            modifier = modifier,
            iconTint = iconTint,
            titleColor = titleColor,
            subtitleColor = subtitleColor,
            trailingContent = trailingContent
        )
    } else {
        ListItem(
            headlineContent = {
                Text(title, color = titleColor)
            },
            supportingContent = {
                Text(subtitle, color = subtitleColor)
            },
            leadingContent = {
                Icon(icon, null, tint = iconTint)
            },
            trailingContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    content = trailingContent
                )
            },
            modifier = modifier,
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent
            )
        )
    }
}

@Composable
private fun CompactOrientationSetting(
    entryRequester: FocusRequester,
    upRequester: FocusRequester,
    downRequester: FocusRequester? = null
) {
    val context = LocalContext.current
    val selected by rememberUiOrientationMode()

    CompactSettingsOptionRow(
        icon = Icons.Default.ScreenRotation,
        title = "Screen orientation",
        subtitle =
            "Auto uses portrait on phones and landscape on tablets and TVs.",
        belowContent = {
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 40.dp, end = 2.dp)
            ) {
                UiOrientationMode.entries.forEachIndexed { index, mode ->
                    val shape =
                        uniformSegmentShape(
                            index,
                            UiOrientationMode.entries.size
                        )
                    SegmentedButton(
                        selected = selected == mode,
                        onClick = {
                            UiOrientationPreferences.set(
                                context,
                                mode
                            )
                        },
                        modifier = Modifier
                            .then(
                                if (index == 0) {
                                    Modifier.focusRequester(
                                        entryRequester
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .focusProperties {
                                up = upRequester
                                if (downRequester != null) {
                                    down = downRequester
                                }
                            }
                            .remoteFocusFrame(shape),
                        shape = shape
                    ) {
                        Text(
                            mode.title,
                            style =
                                MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    )
}

@Composable
internal fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    val destination = LocalSettingsDestination.current
    if (
        destination != null &&
        settingsDestinationFor(title) != destination
    ) {
        return
    }

    val danger = title == "Data & reset"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 6.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (danger) MaterialTheme.colorScheme.error else Color(0xFFB9BDC6)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = if (danger) Color(0xFF171012) else Color(0xFF111318),
            border = BorderStroke(
                1.dp,
                if (danger) Color(0xFF542229) else Color(0xFF2B2F37)
            )
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                content = content
            )
        }
    }
}

@Composable
internal fun SettingsValueRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val compact = configuration.screenWidthDp < 600
    val tablet =
        !context.isTvLikeDevice(configuration) &&
            configuration.smallestScreenWidthDp >= 600

    if (compact || tablet) {
        CompactSettingsOptionRow(
            icon = icon,
            title = label,
            subtitle = value
        )
    } else {
        ListItem(
            headlineContent = { Text(label) },
            supportingContent = { Text(value, maxLines = 2) },
            leadingContent = { Icon(icon, null) },
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent
            )
        )
    }
}

@Composable
internal fun RowScope.ExpressiveBottomNavigationItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor by animateColorAsState(
        if (selected) Color(0xFF4A171B) else Color(0xFF191B20),
        label = "navigationContainer"
    )
    val contentColor by animateColorAsState(
        if (selected) Color(0xFFFFA2A8) else Color(0xFFB4B7BF),
        label = "navigationContent"
    )
    Surface(
        onClick = onClick,
        modifier = modifier
            .semantics {
                role = Role.Tab
                this.selected = selected
            },
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, if (selected) null else label, Modifier.size(22.dp))
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally()
            ) {
                Text(
                    label,
                    Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
internal fun SettingsBottomNavigation(
    currentDestination: SettingsDestination,
    selectDestination: (SettingsDestination) -> Unit
) {
    Surface(color = Color(0xFF101216), tonalElevation = 8.dp) {
        Box(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Row(
                Modifier
                    .widthIn(max = 448.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .animateContentSize(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsDestination.entries.forEach { destination ->
                    val selected = destination == currentDestination
                    ExpressiveBottomNavigationItem(
                        icon = destination.icon(),
                        label = destination.title,
                        selected = selected,
                        onClick = { selectDestination(destination) },
                        modifier = if (selected) Modifier.weight(1f) else Modifier.width(56.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun TmdbCredentialSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var owner by remember { mutableStateOf(RemoteCredentials.owner(context)) }
    var repository by remember { mutableStateOf(RemoteCredentials.repository(context)) }
    var filePath by remember { mutableStateOf(RemoteCredentials.filePath(context)) }
    var tokenInput by remember { mutableStateOf("") }
    var syncing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val values = RemoteCredentials.values
    val updatedAt = RemoteCredentials.lastUpdatedAt

    SettingsSection("Private configuration") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Read a private GitHub repository. The token and NikTV values " +
                    "are encrypted on this device. Cached values are used immediately and refreshed every 24 hours.",
                style = MaterialTheme.typography.bodyMedium
            )
            TvSafeSettingsTextField(
                value = owner,
                onValueChange = { owner = it },
                icon = Icons.Default.Person,
                title = "GitHub owner",
                subtitle = "Account that owns the private repository",
                placeholder = "nikhilmenghani"
            )
            TvSafeSettingsTextField(
                value = repository,
                onValueChange = { repository = it },
                icon = Icons.Default.Folder,
                title = "Private GitHub repository",
                subtitle = "Enter the repository name",
                placeholder = "myenv"
            )
            TvSafeSettingsTextField(
                value = filePath,
                onValueChange = { filePath = it },
                icon = Icons.Default.Description,
                title = "Configuration file",
                subtitle = "Enter the file name in the repository (for example .env)",
                placeholder = ".env"
            )
            TvSafeSettingsTextField(
                value = tokenInput,
                onValueChange = { tokenInput = it },
                icon = Icons.Default.Key,
                title = "GitHub read token",
                subtitle = if (RemoteCredentials.configured(context)) "Configured · leave blank to keep current token" else "Enter once, or use the token saved for GitHub backups",
                password = true
            )
            NikTvSecondaryActionButton(
                onClick = {
                    syncing = true
                    message = null
                    scope.launch {
                        try {
                            RemoteCredentials.saveConnection(context, tokenInput, owner, repository, filePath)
                            tokenInput = ""
                            RemoteCredentials.refresh(context, force = true)
                            message = "Configuration synced successfully"
                        } catch (failure: Exception) {
                            message = failure.message ?: "Could not sync configuration"
                        } finally {
                            syncing = false
                        }
                    }
                },
                enabled = !syncing && owner.isNotBlank() && repository.isNotBlank() && filePath.isNotBlank() &&
                    (tokenInput.isNotBlank() || RemoteCredentials.configured(context) ||
                        com.nikhil.niktv.data.GitHubBackupManager(context).deviceToken().isNotBlank())
            ) { Text(if (syncing) "Syncing…" else "Save and sync") }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(
                if (updatedAt > 0L) "Last sync: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(updatedAt))}"
                else "Last sync: never",
                style = MaterialTheme.typography.bodySmall
            )
            SettingsValueRow(Icons.Default.Key, "TMDB", if (values["NIKTV_TMDB_API_KEY"].isNullOrBlank() && values["NIKTV_TMDB_READ_ACCESS_TOKEN"].isNullOrBlank()) "Not configured" else "Available")
            SettingsValueRow(Icons.Default.Subtitles, "OpenSubtitles", if (values["OPEN_SUBTITLES_KEY"].isNullOrBlank()) "Not configured" else "Available")
        }
    }
}

internal fun String.credentialDiagnosticValue(revealed: Boolean): String = when {
    isBlank() -> "Not embedded in this build"
    revealed -> this
    length <= 8 -> "Embedded · ${"•".repeat(length)}"
    else -> "Embedded · ${take(4)}${"•".repeat(8)}${takeLast(4)} · $length characters"
}

internal fun CatalogType.icon() = when (this) { CatalogType.LIVE_TV -> Icons.Default.LiveTv; CatalogType.MOVIES -> Icons.Default.Movie; CatalogType.SERIES -> Icons.Default.VideoLibrary; CatalogType.RADIO -> Icons.Default.Radio }
internal fun CatalogType.favoriteKind() = when (this) {
    CatalogType.LIVE_TV, CatalogType.RADIO -> FavoriteKind.CHANNEL
    CatalogType.MOVIES -> FavoriteKind.MOVIE
    CatalogType.SERIES -> FavoriteKind.SERIES
}
internal fun PortalType.displayName() = when (this) { PortalType.STALKER -> "Stalker / MAG"; PortalType.XTREAM -> "Xtream Codes" }
internal fun String.isAuthorizationFailureText() =
    contains("Authorization failed", ignoreCase = true) || contains("HTTP status: 401") || contains("HTTP status: 403")
internal fun CatalogType.itemLabel(count: Int) = when (this) {
    CatalogType.LIVE_TV -> if (count == 1) "channel" else "channels"
    CatalogType.MOVIES -> if (count == 1) "movie" else "movies"
    CatalogType.SERIES -> if (count == 1) "series" else "series"
    CatalogType.RADIO -> if (count == 1) "station" else "stations"
}
internal fun FavoriteKind.sectionTitle() = when (this) {
    FavoriteKind.CHANNEL -> "Channels"
    FavoriteKind.MOVIE -> "Movies"
    FavoriteKind.SERIES -> "Series"
    FavoriteKind.EPISODE -> "Episodes"
}
internal fun FavoriteKind.mediaTypeLabel() = when (this) {
    FavoriteKind.CHANNEL -> "Live TV"
    FavoriteKind.MOVIE -> "Movie"
    FavoriteKind.SERIES -> "Series"
    FavoriteKind.EPISODE -> "Episode"
}
internal fun String.episodeNumberFromTitle(): Int? {
    val patterns = listOf(
        Regex("(?i)S\\d+[ ._-]*E(?:P(?:ISODE)?)?[ ._-]*(\\d+)"),
        Regex("(?i)\\bEP(?:ISODE)?[ ._:-]*(\\d+)"),
        Regex("(?i)\\bE[ ._:-]*(\\d+)"),
        Regex("\\b(\\d+)\\b")
    )
    return patterns.firstNotNullOfOrNull { pattern ->
        pattern.findAll(this).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}

internal fun MediaItem.actionEpisodeLabel(): String {
    val season = seasonNumber ?: title.seasonNumberFromTitle()
    val episode = episodeNumber ?: title.episodeNumberFromTitle()
    return when {
        season != null && episode != null -> "S$season · Episode $episode"
        episode != null -> "Episode $episode"
        else -> title.replace(Regex("[,.|]\\s*\\d{4}[-/]\\d{1,2}.*$"), "")
            .trim().ifBlank { title }.take(28).trimEnd('.', ',', '-', ' ')
    }
}

@Composable
internal fun BackupSettingsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    ResponsiveSettingsOptionRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        modifier = Modifier
            .then(if (enabled) Modifier.remoteFocusFrame(RoundedCornerShape(8.dp)) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        titleColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.5f),
        trailingContent = { Icon(Icons.Default.ChevronRight, null) }
    )
}
