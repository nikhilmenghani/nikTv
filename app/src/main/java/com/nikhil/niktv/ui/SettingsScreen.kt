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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Composable
private fun TvSafeSettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    password: Boolean = false,
    singleLine: Boolean = true
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        (configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION ||
        !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
    var editing by remember { mutableStateOf(!isTv) }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { text -> ({ Text(text) }) },
        supportingText = supportingText?.let { text -> ({ Text(text) }) },
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        readOnly = isTv && !editing,
        singleLine = singleLine,
        modifier = modifier
            .onFocusChanged {
                if (!it.isFocused && isTv) {
                    editing = false
                    keyboard?.hide()
                }
            }
            .onPreviewKeyEvent { event ->
                if (
                    isTv && !editing && event.type == KeyEventType.KeyDown &&
                    event.key in setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
                ) {
                    editing = true
                    scope.launch {
                        delay(50L)
                        keyboard?.show()
                    }
                    true
                } else false
            }
            .remoteFocusFrame(RoundedCornerShape(12.dp))
    )
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
    val generatedIdentity = remember(context) { cast4kLegacyDeviceIdentity(context) }
    val preconfiguredProfiles = remember(generatedIdentity) {
        listOf(
            PortalProfile(
                BuildConfig.DEFAULT_PROFILE_NAME.withoutConfigurationQuotes().ifBlank { "WIO" },
                BuildConfig.DEFAULT_PORTAL_URL.withoutConfigurationQuotes(),
                BuildConfig.DEFAULT_MAC_ADDRESS.withoutConfigurationQuotes().ifBlank { generatedIdentity.macAddress },
                BuildConfig.DEFAULT_SERIAL_NUMBER.withoutConfigurationQuotes().ifBlank { generatedIdentity.serialNumber },
                PortalType.STALKER
            ),
            PortalProfile(
                BuildConfig.XTREAM_PROFILE_NAME.withoutConfigurationQuotes().ifBlank { "Xtream" },
                BuildConfig.XTREAM_PORTAL_URL.withoutConfigurationQuotes(),
                macAddress = "",
                portalType = PortalType.XTREAM,
                username = BuildConfig.XTREAM_USERNAME.withoutConfigurationQuotes(),
                password = BuildConfig.XTREAM_PASSWORD.withoutConfigurationQuotes()
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
    val settingsEntryRequester = remember { FocusRequester() }
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
                    updateMessage = "Could not check: ${it.message}"
                    downloadActionMessage = it.message ?: "Could not check for updates"
                }
            oneClickUpdating = false
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
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { settingsEntryRequester.requestFocus() }
    }
    val settingsConfiguration = LocalConfiguration.current
    val compactSettingsHeader = settingsConfiguration.screenWidthDp < 600
    val mobileSettingsPages = MobileSettingsPage.entries
    val settingsPagerState = rememberPagerState(pageCount = { mobileSettingsPages.size })
    val mobileSettingsTopBarBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
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
                MobileSettingsBottomNavigation(
                    currentPage = mobileSettingsPages[settingsPagerState.currentPage],
                    selectPage = { page ->
                        scope.launch {
                            settingsPagerState.animateScrollToPage(mobileSettingsPages.indexOf(page))
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
                horizontal = if (compactSettingsHeader) 16.dp else 18.dp,
                vertical = if (compactSettingsHeader) 20.dp else 18.dp
            ),
        verticalArrangement = Arrangement.spacedBy(if (compactSettingsHeader) 20.dp else 12.dp)
    ) {
        if (!compactSettingsHeader) Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = Color(0xFF111318),
            border = BorderStroke(1.dp, Color(0xFF292C33)),
            shadowElevation = 6.dp
        ) {
            Column(
                Modifier
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xFF171A20), Color(0xFF111318))
                        )
                    )
                    .padding(if (compactSettingsHeader) 13.dp else 17.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(13.dp),
                        color = Color(0xFF2A1215),
                        border = BorderStroke(1.dp, Color(0xFF6F2028))
                    ) {
                        Icon(
                            Icons.Default.Tune,
                            null,
                            Modifier.padding(if (compactSettingsHeader) 9.dp else 11.dp)
                                .size(if (compactSettingsHeader) 22.dp else 24.dp),
                            tint = Color(0xFFFF6973)
                        )
                    }
                    Text(
                        "Make NikTV yours",
                        modifier = Modifier.weight(1f),
                        style = if (compactSettingsHeader) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.titleLarge
                        },
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFF5F5F7)
                    )
                    if (!compactSettingsHeader) {
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text("v${BuildConfig.VERSION_NAME}") }
                        )
                    }
                }
                Text(
                    "Playback, appearance, content, profiles and updates",
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF9B9FA8),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (compactSettingsHeader) {
                    Text(
                        "NikTV ${BuildConfig.VERSION_NAME}",
                        color = Color(0xFF70757E),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
        val showMobileAppearance =
            settingsConfiguration.smallestScreenWidthDp < 600

        if (showMobileAppearance) SettingsSection("Mobile controls") {
            val onScreenDpad by rememberOnScreenDpadEnabled()
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("On-screen D-pad", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Show a movable remote control overlay for testing focus navigation on this phone.",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = onScreenDpad,
                        onCheckedChange = { OnScreenDpadPreferences.setEnabled(context, it) },
                        modifier = Modifier.remoteFocusFrame(CircleShape)
                    )
                }
            }
        }
        PlaybackEngineSettingsSection(state.playbackEngine, setPlaybackEngine)
        SettingsSection("Player controls") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Hide controls after", style = MaterialTheme.typography.titleMedium)
                Text("While video is playing, controls automatically disappear after this period of inactivity.", color = Color.Gray)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PLAYER_CONTROLS_TIMEOUT_OPTIONS.forEachIndexed { index, seconds ->
                        val shape = uniformSegmentShape(index, PLAYER_CONTROLS_TIMEOUT_OPTIONS.size)
                        SegmentedButton(
                            state.playerControlsTimeoutSeconds == seconds,
                            { setPlayerControlsTimeoutSeconds(seconds) },
                            shape,
                            modifier = Modifier.remoteFocusFrame(shape)
                        ) { Text("${seconds}s") }
                    }
                }
            }
        }
        if (!compactSettingsHeader) SettingsSection("Picture and video appearance") {
            val (appearanceProfiles, activeAppearance) =
                rememberVideoAppearanceProfiles()
            val editableAppearanceProfiles =
                appearanceProfiles.filterNot { it.id == "default" }
            var appearanceSchedule by remember {
                mutableStateOf(VideoAppearancePreferences.schedule(context))
            }
            var scheduleError by remember { mutableStateOf<String?>(null) }
            var editingId by remember(activeAppearance.id) {
                mutableStateOf(
                    editableAppearanceProfiles
                        .firstOrNull { it.id == activeAppearance.id }
                        ?.id
                        ?: editableAppearanceProfiles.first().id
                )
            }
            val editing =
                editableAppearanceProfiles.firstOrNull { it.id == editingId }
                    ?: editableAppearanceProfiles.first()
            var editName by remember(editing.id, editing.name) { mutableStateOf(editing.name) }
            var editBrightness by remember(editing.id, editing.brightness) { mutableFloatStateOf(editing.brightness) }
            var editWarmth by remember(editing.id, editing.warmth) { mutableFloatStateOf(editing.warmth) }
            var editCoolness by remember(editing.id, editing.coolness) { mutableFloatStateOf(editing.coolness) }
            var editTint by remember(editing.id, editing.tint) { mutableFloatStateOf(editing.tint) }
            var editDimming by remember(editing.id, editing.dimming) { mutableFloatStateOf(editing.dimming) }
            val profileNameRequester = remember { FocusRequester() }
            val brightnessRequester = remember { FocusRequester() }
            val warmthRequester = remember { FocusRequester() }
            val coolnessRequester = remember { FocusRequester() }
            val tintRequester = remember { FocusRequester() }
            val dimmingRequester = remember { FocusRequester() }
            val saveProfileRequester = remember { FocusRequester() }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Picture mode profiles", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Choose the active picture mode in the player. Edit filter profiles and scheduled windows here; Default is an immutable unfiltered reference.",
                    color = Color.Gray
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    editableAppearanceProfiles.forEach { profile ->
                        FilterChip(
                            selected = editingId == profile.id,
                            onClick = {
                                editingId = profile.id
                            },
                            label = { Text(profile.name) },
                            leadingIcon = {
                                Icon(
                                    videoAppearanceIcon(profile.id),
                                    null,
                                    Modifier.size(18.dp)
                                )
                            },
                            modifier = Modifier.remoteFocusFrame(CircleShape)
                        )
                    }
                }
                Text("Edit selected profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF101522),
                    border = BorderStroke(1.dp, Color(0xFF2A3244))
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Edit ${editing.name}", style = MaterialTheme.typography.titleMedium)
                        TvSafeSettingsTextField(
                            value = editName,
                            onValueChange = { editName = it },
                            label = "Profile name",
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(profileNameRequester)
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                                        brightnessRequester.requestFocus()
                                        true
                                    } else false
                                }
                        )
                        AppearanceSliderRow(
                            label = "Brightness",
                            value = editBrightness,
                            valueRange = -1f..1f,
                            requester = brightnessRequester,
                            upRequester = profileNameRequester,
                            downRequester = warmthRequester,
                            onValueChange = { editBrightness = it }
                        )
                        AppearanceSliderRow(
                            label = "Warmth",
                            value = editWarmth,
                            valueRange = 0f..1f,
                            requester = warmthRequester,
                            upRequester = brightnessRequester,
                            downRequester = coolnessRequester,
                            onValueChange = { editWarmth = it }
                        )
                        AppearanceSliderRow(
                            label = "Coolness",
                            value = editCoolness,
                            valueRange = 0f..1f,
                            requester = coolnessRequester,
                            upRequester = warmthRequester,
                            downRequester = tintRequester,
                            onValueChange = { editCoolness = it }
                        )
                        AppearanceSliderRow(
                            label = "Color tint",
                            value = editTint,
                            valueRange = -1f..1f,
                            requester = tintRequester,
                            upRequester = coolnessRequester,
                            downRequester = dimmingRequester,
                            valueText = when {
                                editTint > 0f -> "${(editTint * 100).toInt()}% magenta"
                                editTint < 0f -> "${(-editTint * 100).toInt()}% green"
                                else -> "Neutral"
                            },
                            onValueChange = { editTint = it }
                        )
                        AppearanceSliderRow(
                            label = "Dimming",
                            value = editDimming,
                            valueRange = 0f..0.8f,
                            requester = dimmingRequester,
                            upRequester = tintRequester,
                            downRequester = saveProfileRequester,
                            onValueChange = { editDimming = it }
                        )
                        Button(
                            onClick = {
                                VideoAppearancePreferences.update(
                                    context,
                                    editing.copy(
                                        name = editName.trim().ifBlank { editing.name },
                                        brightness = editBrightness,
                                        warmth = editWarmth,
                                        coolness = editCoolness,
                                        tint = editTint,
                                        dimming = editDimming
                                    )
                                )
                            },
                            modifier = Modifier
                                .align(Alignment.End)
                                .focusRequester(saveProfileRequester)
                                .onPreviewKeyEvent { event ->
                                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                                        dimmingRequester.requestFocus()
                                        true
                                    } else false
                                }
                                .remoteFocusFrame(CircleShape),
                            shape = CircleShape
                        ) { Text("Save profile") }
                    }
                }
                OutlinedButton(
                    onClick = {
                        VideoAppearancePreferences.resetRecommendedProfiles(context)
                    },
                    modifier = Modifier
                        .align(Alignment.End)
                        .remoteFocusFrame(CircleShape)
                ) {
                    Icon(Icons.Default.RestartAlt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reset profiles to recommended")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Automatic picture mode", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Scheduled windows override the fallback profile. Windows cannot overlap, and uncovered time uses the fallback.",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = appearanceSchedule.enabled,
                        onCheckedChange = { enabled ->
                            appearanceSchedule = appearanceSchedule.copy(enabled = enabled)
                            VideoAppearancePreferences.setSchedule(context, appearanceSchedule)
                        },
                        modifier = Modifier.remoteFocusFrame(CircleShape)
                    )
                }
                if (appearanceSchedule.enabled) {
                    Text("Scheduled windows", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Only explicit windows override the player choice. Outside a scheduled window, your most recent player selection remains active.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                    scheduleError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    appearanceSchedule.entries.forEachIndexed { index, entry ->
                        key(entry.startMinutes, entry.endMinutes, entry.profileId, index) {
                            ScheduleTimelineRow(
                                entry = entry,
                                profiles = appearanceProfiles,
                                canRemove = true,
                                onChange = { updated ->
                                    val entries = appearanceSchedule.entries.toMutableList()
                                    val originalIndex = entries.indexOf(entry).takeIf { it >= 0 } ?: index
                                    if (updated.startMinutes == updated.endMinutes) {
                                        scheduleError = "Start and end times must be different."
                                        return@ScheduleTimelineRow
                                    }
                                    if (entries.withIndex().any { other ->
                                            other.index != originalIndex && schedulesOverlap(updated, other.value)
                                        }) {
                                        scheduleError = "That time overlaps another scheduled window."
                                        return@ScheduleTimelineRow
                                    }
                                    entries[originalIndex] = updated
                                    scheduleError = null
                                    appearanceSchedule = appearanceSchedule.copy(entries = entries)
                                    VideoAppearancePreferences.setSchedule(context, appearanceSchedule)
                                },
                                onRemove = {
                                    scheduleError = null
                                    appearanceSchedule = appearanceSchedule.copy(
                                        entries = appearanceSchedule.entries.filterNot { it == entry }
                                    )
                                    VideoAppearancePreferences.setSchedule(context, appearanceSchedule)
                                }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                val last = appearanceSchedule.entries.lastOrNull()
                                val preferredStart = ((last?.startMinutes ?: -60) + 60 + 1440) % 1440
                                val candidate = (0 until 96).asSequence()
                                    .map { offset -> (preferredStart + offset * 15) % 1440 }
                                    .map { start ->
                                        VideoAppearanceScheduleEntry(
                                            profileId = VideoAppearancePreferences.activeId(context),
                                            startMinutes = start,
                                            endMinutes = (start + 60) % 1440
                                        )
                                    }
                                    .firstOrNull { proposed ->
                                        appearanceSchedule.entries.none { schedulesOverlap(proposed, it) }
                                    }
                                if (candidate == null) {
                                    scheduleError = "There is no free one-hour window to add."
                                    return@Button
                                }
                                scheduleError = null
                                appearanceSchedule = appearanceSchedule.copy(
                                    entries = appearanceSchedule.entries + candidate
                                )
                                VideoAppearancePreferences.setSchedule(context, appearanceSchedule)
                            },
                            modifier = Modifier.remoteFocusFrame(CircleShape)
                        ) {
                            Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Add time period")
                        }
                        OutlinedButton(
                            onClick = {
                                appearanceSchedule = VideoAppearancePreferences.defaultSchedule(
                                    enabled = appearanceSchedule.enabled
                                )
                                scheduleError = null
                                VideoAppearancePreferences.setSchedule(context, appearanceSchedule)
                            },
                            modifier = Modifier.remoteFocusFrame(CircleShape)
                        ) {
                            Icon(Icons.Default.RestartAlt, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Reset to defaults")
                        }
                    }
                }
            }
        }
        SettingsSection("Display and screen") {
            ListItem(
                headlineContent = {
                    Text("Only keep screen awake during playback")
                },
                supportingContent = {
                    Text(
                        if (state.keepAwakeOnlyDuringPlayback) {
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
                    Switch(
                        checked = state.keepAwakeOnlyDuringPlayback,
                        onCheckedChange = setKeepAwakeOnlyDuringPlayback,
                        modifier = Modifier.remoteFocusFrame(
                            RoundedCornerShape(16.dp)
                        )
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                )
            )
            if (compactSettingsHeader) {
                HorizontalDivider()
                OrientationSettingsSection(Modifier.padding(8.dp))
            }
        }
        SettingsSection("Profiles") {
            Text(
                "Preconfigured profiles",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.titleMedium
            )
            preconfiguredProfiles.forEach { builtIn ->
                val enabled = state.profiles.any { it.cacheKey() == builtIn.cacheKey() }
                ListItem(
                    headlineContent = { Text(builtIn.name) },
                    supportingContent = { Text(if (enabled) "Available on the profile screen" else "Hidden from the profile screen") },
                    leadingContent = { Icon(if (builtIn.portalType == PortalType.STALKER) Icons.Default.Tv else Icons.Default.Key, null) },
                    trailingContent = {
                        Switch(
                            checked = enabled,
                            onCheckedChange = { setPreconfiguredProfileEnabled(builtIn, it) },
                            enabled = builtIn.portalUrl.isNotBlank() &&
                                (builtIn.portalType == PortalType.STALKER ||
                                    (builtIn.username.isNotBlank() && builtIn.password.isNotBlank()))
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
            HorizontalDivider()
            state.profiles.forEachIndexed { index, saved ->
                val isPreconfigured = preconfiguredProfiles.any { it.cacheKey() == saved.cacheKey() }
                ListItem(
                    headlineContent = { Text(saved.name) },
                    supportingContent = { Text(if (isPreconfigured) "Preconfigured ${saved.portalType.displayName()} profile" else "${saved.portalType.displayName()} · ${saved.portalUrl}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(if (saved.portalType == PortalType.STALKER) Icons.Default.Tv else Icons.Default.Key, null) },
                    trailingContent = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (saved == profile) {
                                Icon(Icons.Default.CheckCircle, "Active", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            } else {
                                TextButton(
                                    onClick = { switchProfile(saved) },
                                    modifier = Modifier.height(48.dp).then(if (index == 0) Modifier.focusRequester(settingsEntryRequester) else Modifier).remoteFocusFrame(CircleShape),
                                    shape = CircleShape
                                ) { Text("Open") }
                            }
                            IconButton(
                                onClick = { pendingRemoval = saved },
                                modifier = Modifier.size(48.dp).then(if (index == 0 && saved == profile) Modifier.focusRequester(settingsEntryRequester) else Modifier).remoteFocusFrame(CircleShape)
                            ) { Icon(Icons.Default.DeleteOutline, "Remove ${saved.name}") }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                if (index != state.profiles.lastIndex) HorizontalDivider()
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Add profile") },
                supportingContent = { Text("Connect another Stalker or Xtream service") },
                leadingContent = { Icon(Icons.Default.AddCircleOutline, null) },
                modifier = Modifier.remoteFocusFrame(RoundedCornerShape(14.dp)).clickable(onClick = addProfile),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
        if (!compactSettingsHeader && profile != null) SettingsSection("Category Filters") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Content Visibility", style = MaterialTheme.typography.titleMedium)
                Text("Choose which categories to include for Live TV, Movies, and Series.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                visibleCatalogTypes.forEachIndexed { index, type ->
                    val raw = state.rawCategoriesByType[type].orEmpty().ifEmpty { if (state.selectedType == type) state.categories else emptyList() }
                    val filterKey = "${profile.cacheKey()}|${type.name}"
                    val enabledIds = state.categoryFilters[filterKey]
                    val countSummary = when {
                        raw.isEmpty() -> "Tap to configure"
                        enabledIds == null -> "All ${raw.size} categories active"
                        else -> "${enabledIds.size} of ${raw.size} categories active"
                    }
                    ListItem(
                        headlineContent = { Text(type.title) },
                        supportingContent = { Text(countSummary) },
                        leadingContent = { Icon(type.icon(), null) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, "Configure ${type.title} categories") },
                        modifier = Modifier.remoteFocusFrame().clip(RoundedCornerShape(12.dp)).clickable { openCategoryManager(type) },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    )
                    if (index != visibleCatalogTypes.lastIndex) Spacer(Modifier.height(4.dp))
                }
            }
        }
        val activeMobileSettingsPage = LocalMobileSettingsPage.current
        if (activeMobileSettingsPage == null) {
            OrientationSettingsSection(Modifier.focusGroup())
        }

        if (profile != null &&
            (activeMobileSettingsPage == null || activeMobileSettingsPage == MobileSettingsPage.PLAYBACK)
        ) {
            PlaybackDesignSettingsSection(profile.cacheKey(), Modifier.focusGroup())
        }

        if (profile != null) SettingsSection("Connection") {
            SettingsValueRow(Icons.Default.AccountCircle, "Profile", profile.name)
            HorizontalDivider()
            SettingsValueRow(Icons.Default.Language, "Portal", profile.portalUrl)
            HorizontalDivider()
            SettingsValueRow(Icons.Default.Security, "Session", if (state.session != null) "Authenticated" else "Authentication required")
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Automatically re-authenticate expired sessions") },
                supportingContent = {
                    Text(
                        if (state.automaticReauthentication) {
                            "Retry the interrupted page load or playback once with a fresh session."
                        } else {
                            "Show the Session expired prompt and wait for confirmation."
                        }
                    )
                },
                leadingContent = { Icon(Icons.Default.Security, null) },
                trailingContent = {
                    Switch(
                        checked = state.automaticReauthentication,
                        onCheckedChange = setAutomaticReauthentication,
                        modifier = Modifier.remoteFocusFrame(RoundedCornerShape(16.dp))
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            HorizontalDivider()
            SettingsValueRow(Icons.Default.Wifi, "Device MAC Address", deviceMacAddress)
        }
        TmdbCredentialSettingsSection()
        SettingsSection("Backup and restore") {
            Text(
                "Backup files contain portal addresses and credentials. " +
                    "Use a backup password when storing sensitive backups in a public repository.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Backup mode",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                val backupModes =
                    listOf(
                        com.nikhil.niktv.data.BackupMode.GITHUB to "GitHub",
                        com.nikhil.niktv.data.BackupMode.DEVICE to "Device"
                    )
                SingleChoiceSegmentedButtonRow(
                    Modifier.fillMaxWidth()
                ) {
                    backupModes.forEachIndexed { index, (mode, label) ->
                        val shape =
                            uniformSegmentShape(index, backupModes.size)
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

                if (
                    githubBackupConfig.backupMode ==
                    com.nikhil.niktv.data.BackupMode.GITHUB
                ) {
                    TvSafeSettingsTextField(
                        value = githubBackupConfig.username,
                        onValueChange = {
                            githubBackupConfig =
                                githubBackupConfig.copy(username = it)
                        },
                        label = "GitHub username",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TvSafeSettingsTextField(
                        value = githubBackupConfig.repository,
                        onValueChange = {
                            githubBackupConfig =
                                githubBackupConfig.copy(repository = it)
                        },
                        label = "Repository",
                        placeholder = "tracker",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    TvSafeSettingsTextField(
                        value = githubBackupConfig.token,
                        onValueChange = {
                            githubBackupConfig =
                                githubBackupConfig.copy(token = it)
                        },
                        label = "GitHub personal access token",
                        supportingText = "Defaults to the build-time G_TOKEN. " +
                            "A changed value is stored encrypted on this device.",
                        password = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
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
                        label = "Backup password (optional)",
                        supportingText = "Leave blank for plain JSON. Use 12+ characters " +
                            "to encrypt GitHub backups.",
                        password = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
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

                    Text(
                        "Automatic GitHub backup",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val scheduleOptions =
                        listOf(
                            0 to "Off",
                            6 to "6h",
                            12 to "12h",
                            24 to "24h"
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
                        if (
                            githubBackupConfig.autoBackupIntervalHours == 0
                        ) {
                            "Automatic backup is off."
                        } else {
                            "NikTV checks every " +
                                "${githubBackupConfig.autoBackupIntervalHours} hours " +
                                "and uploads only when backed-up data has changed."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    FilledTonalButton(
                        onClick = {
                            val config =
                                normalizedGitHubBackupConfig()
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
                                        BuildConfig.G_TOKEN.isNotBlank()
                                    ) &&
                                (
                                    githubBackupConfig.passphrase.isBlank() ||
                                        githubBackupConfig.passphrase.length >= 12
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
                } else {
                    Text(
                        "Export opens Android's document picker. Import lets you " +
                            "choose a local NikTV JSON backup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Export backup") },
                supportingContent = {
                    Text(
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
                        }
                    )
                },
                leadingContent = {
                    Icon(
                        if (
                            githubBackupConfig.backupMode ==
                            com.nikhil.niktv.data.BackupMode.GITHUB
                        ) {
                            Icons.Default.CloudUpload
                        } else {
                            Icons.Default.FileUpload
                        },
                        null
                    )
                },
                trailingContent = {
                    if (githubBackupUploading) {
                        CircularProgressIndicator(
                            Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.ChevronRight, null)
                    }
                },
                modifier =
                    Modifier
                        .remoteFocusFrame(RoundedCornerShape(14.dp))
                        .clickable(enabled = !githubBackupUploading) {
                            if (
                                githubBackupConfig.backupMode ==
                                com.nikhil.niktv.data.BackupMode.GITHUB
                            ) {
                                performGitHubExport()
                            } else {
                                val timestamp =
                                    java.text.SimpleDateFormat(
                                        "yyyyMMdd-HHmmss",
                                        java.util.Locale.getDefault()
                                    ).format(java.util.Date())
                                exportLauncher.launch(
                                    "NikTV-${BuildConfig.VERSION_NAME}-$timestamp-backup.json"
                                )
                            }
                        },
                colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    )
            )

            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Import backup") },
                supportingContent = {
                    Text(
                        if (
                            githubBackupConfig.backupMode ==
                            com.nikhil.niktv.data.BackupMode.GITHUB
                        ) {
                            "Choose one of the backups stored in GitHub"
                        } else {
                            "Choose a NikTV JSON backup from this device"
                        }
                    )
                },
                leadingContent = {
                    Icon(
                        if (
                            githubBackupConfig.backupMode ==
                            com.nikhil.niktv.data.BackupMode.GITHUB
                        ) {
                            Icons.Default.CloudDownload
                        } else {
                            Icons.Default.FileDownload
                        },
                        null
                    )
                },
                modifier =
                    Modifier
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
                colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent
                    )
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
                                OutlinedButton(
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
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                },
                                                supportingContent = {
                                                    Text(
                                                        "${formatOfflineBytes(backup.size)} · " + if (backup.encrypted) "encrypted" else "plain JSON"
                                                    )
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
                        Button(
                            onClick = {
                                val decoded =
                                    githubPendingRestore ?: return@Button
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
                    TextButton(
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

        SettingsSection("Account actions") {
            ListItem(
                headlineContent = { Text("Re-authenticate") },
                supportingContent = { Text("Request a fresh session token using the saved profile") },
                leadingContent = { Icon(Icons.Default.Refresh, null) },
                modifier = Modifier.remoteFocusFrame(RoundedCornerShape(14.dp)).clickable(onClick = reauthenticate),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Edit connection") },
                supportingContent = { Text("Change portal address or credentials") },
                leadingContent = { Icon(Icons.Default.Edit, null) },
                modifier = Modifier.remoteFocusFrame(RoundedCornerShape(14.dp)).clickable(onClick = editProfile),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Clear all app data", color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text("Remove every profile, cache, favorite, recent item, and session") },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.remoteFocusFrame(RoundedCornerShape(14.dp)).clickable(onClick = logout),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
        SettingsSection("Catalog cache") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Refresh interval", style = MaterialTheme.typography.titleMedium)
                Text("Categories and media lists are stored on this device and refreshed after this interval.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(30 to "30m", 60 to "1h", 360 to "6h", 1440 to "24h").forEachIndexed { index, (minutes, label) ->
                        val intervalShape = uniformSegmentShape(index, 4)
                        SegmentedButton(
                            selected = state.cacheIntervalMinutes == minutes,
                            onClick = { setCacheIntervalMinutes(minutes) },
                            modifier = Modifier.remoteFocusFrame(intervalShape),
                            shape = intervalShape
                        ) { Text(label) }
                    }
                }
            }
        }
        SettingsSection("Series") {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Default season", style = MaterialTheme.typography.titleMedium)
                Text("Used only when a series has no remembered season. NikTV loads one season at a time.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SeriesStartSeason.entries.forEachIndexed { index, option ->
                        val shape = uniformSegmentShape(index, SeriesStartSeason.entries.size)
                        SegmentedButton(
                            selected = state.seriesStartSeason == option,
                            onClick = { setSeriesStartSeason(option) },
                            modifier = Modifier.remoteFocusFrame(shape),
                            shape = shape
                        ) { Text(if (option == SeriesStartSeason.FIRST) "First season" else "Latest season") }
                    }
                }
            }
        }
        SettingsSection("App updates") {
            Column {
                ListItem(
                    headlineContent = { Text("Require updates before using NikTV") },
                    supportingContent = {
                        Text(
                            if (BuildConfig.DEBUG) "Development build · disabled by default"
                            else "Block access until an available update is installed"
                        )
                    },
                    leadingContent = { Icon(Icons.Default.AdminPanelSettings, null) },
                    trailingContent = {
                        Switch(
                            checked = updateEnforcementEnabled,
                            onCheckedChange = AppUpdates::setUpdateEnforcementEnabled,
                            modifier = Modifier.remoteFocusFrame(CircleShape)
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Check for updates on startup") },
                    supportingContent = {
                        Text(
                            if (startupUpdateCheckEnabled) {
                                "Enabled · check for an update whenever NikTV starts"
                            } else {
                                "Disabled · no launch-time update check"
                            }
                        )
                    },
                    leadingContent = { Icon(Icons.Default.Refresh, null) },
                    trailingContent = {
                        Switch(
                            checked = startupUpdateCheckEnabled,
                            onCheckedChange = AppUpdates::setStartupUpdateCheckEnabled,
                            modifier = Modifier.remoteFocusFrame(CircleShape)
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                HorizontalDivider()
                Column(
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Update APK", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (updatePackagePreference == UpdatePackage.AUTO) {
                            "Automatic · ${AppUpdates.effectiveUpdatePackage().displayName} detected"
                        } else {
                            "Use ${updatePackagePreference.displayName} for future updates"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        UpdatePackage.entries.forEach { option ->
                            FilterChip(
                                selected = updatePackagePreference == option,
                                onClick = {
                                    AppUpdates.setUpdatePackage(option)
                                    availableUpdate = null
                                    updateMessage = "Update APK set to ${if (option == UpdatePackage.AUTO) AppUpdates.effectiveUpdatePackage().displayName else option.displayName}"
                                },
                                label = { Text(option.displayName) },
                                modifier = Modifier.remoteFocusFrame(RoundedCornerShape(10.dp))
                            )
                        }
                    }
                }
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("One-click update") },
                    supportingContent = {
                        Text(
                            if (oneClickUpdating) {
                                "Checking for an update…"
                            } else {
                                "Check now, download an available update, then open Android's installer"
                            }
                        )
                    },
                    leadingContent = { Icon(Icons.Default.SystemUpdateAlt, null) },
                    trailingContent = {
                        if (oneClickUpdating) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.ChevronRight, "Run one-click update")
                        }
                    },
                    modifier = Modifier
                        .remoteFocusFrame(RoundedCornerShape(14.dp))
                        .clickable(enabled = !oneClickUpdating && !checkingUpdate) {
                            runOneClickUpdate()
                        },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("NikTV ${BuildConfig.VERSION_NAME}") },
                    supportingContent = {
                        Column {
                            Text(downloadStatus)
                            if (downloadState !is UpdateDownloadState.Idle && updateMessage != null) {
                                Text(updateMessage!!)
                            }
                        }
                    },
                    leadingContent = { Icon(Icons.Default.SystemUpdate, null) },
                    trailingContent = { if (checkingUpdate) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
                    modifier = Modifier.focusRequester(versionRequester).remoteFocusFrame(RoundedCornerShape(14.dp)).clickable(enabled = !checkingUpdate && !oneClickUpdating) {
                        checkingUpdate = true; updateMessage = "Checking for updates…"
                        scope.launch {
                            runCatching { AppUpdates.check() }
                                .onSuccess { update ->
                                    availableUpdate = update
                                    updateMessage = if (update == null) "You're up to date" else "Version ${update.version} is available"
                                }
                                .onFailure { updateMessage = "Could not check: ${it.message}" }
                            checkingUpdate = false
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                when (val download = downloadState) {
                    is UpdateDownloadState.Queued -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    }
                    is UpdateDownloadState.Downloading -> {
                        if (download.totalBytes != null) {
                            LinearProgressIndicator(
                                progress = { (download.percent ?: 0) / 100f },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                        }
                    }
                    is UpdateDownloadState.Paused -> {
                        if (download.totalBytes != null) {
                            LinearProgressIndicator(
                                progress = {
                                    (download.bytesDownloaded.toFloat() / download.totalBytes)
                                        .coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            )
                        }
                    }
                    else -> Unit
                }
                downloadedBytes?.let { (bytes, total) ->
                    Text(
                        buildString {
                            append("Downloaded ${formatDownloadBytes(bytes)}")
                            total?.let { append(" of ${formatDownloadBytes(it)}") }
                        },
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (downloadState is UpdateDownloadState.Ready ||
                    downloadState is UpdateDownloadState.InstallerLaunched
                ) {
                    val version = when (val download = downloadState) {
                        is UpdateDownloadState.Ready -> download.version
                        is UpdateDownloadState.InstallerLaunched -> download.version
                        else -> ""
                    }
                    Text(
                        "Saved in ${AppUpdates.savedLocation(version)}",
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = {
                            downloadActionMessage = null
                            runCatching { AppUpdates.install(context) }
                                .onFailure { downloadActionMessage = it.message }
                        }, modifier = Modifier.remoteFocusFrame()) { Text("Install") }
                        OutlinedButton(onClick = {
                            downloadActionMessage = null
                            runCatching { AppUpdates.openDownloads(context) }
                                .onFailure { downloadActionMessage = it.message }
                        }, modifier = Modifier.remoteFocusFrame()) { Text("Open Downloads") }
                    }
                }
                if (downloadState is UpdateDownloadState.Failed) {
                    Button(
                        onClick = {
                            val failed = downloadState as UpdateDownloadState.Failed
                            requestUpdateDownload(UpdateInfo(failed.version, failed.downloadUrl))
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).remoteFocusFrame()
                    ) { Text("Retry download") }
                }
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Delete older update APKs") },
                    supportingContent = {
                        Text(
                            obsoleteApks?.let { cleanup ->
                                if (cleanup.fileCount == 0) "No obsolete NikTV installers found"
                                else "${cleanup.fileCount} installer${if (cleanup.fileCount == 1) "" else "s"} · ${formatDownloadBytes(cleanup.totalBytes)}"
                            } ?: "Checking Downloads/NikTV…"
                        )
                    },
                    leadingContent = { Icon(Icons.Default.DeleteSweep, null) },
                    trailingContent = {
                        if (cleaningObsoleteApks) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    },
                    modifier = Modifier
                        .remoteFocusFrame(RoundedCornerShape(14.dp))
                        .clickable(
                            // Keep this row in the TV focus graph even when
                            // there is currently nothing to delete. A disabled
                            // clickable is removed from D-pad traversal.
                            enabled = !cleaningObsoleteApks
                        ) {
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
                                runCatching { AppUpdates.deleteObsoleteDownloadedApks(context) }
                                    .onSuccess { result ->
                                        apkCleanupMessage = "Deleted ${result.deletedCount} installer${if (result.deletedCount == 1) "" else "s"} and reclaimed ${formatDownloadBytes(result.deletedBytes)}"
                                        obsoleteApks = AppUpdates.obsoleteDownloadedApks(context)
                                    }
                                    .onFailure { apkCleanupMessage = "Could not delete old installers: ${it.message}" }
                                cleaningObsoleteApks = false
                            }
                        },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                apkCleanupMessage?.let {
                    Text(
                        it,
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                downloadActionMessage?.let {
                    Text(
                        it,
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
        Text(
            "NikTV keeps the active profile and session in this app's private storage. Expired sessions are refreshed automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        } }
        if (compactSettingsHeader) {
            HorizontalPager(
                state = settingsPagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                CompositionLocalProvider(
                    LocalMobileSettingsPage provides mobileSettingsPages[page]
                ) {
                    settingsPageContent()
                }
            }
        } else {
            CompositionLocalProvider(LocalMobileSettingsPage provides null) {
                settingsPageContent()
            }
        }
    }
    pendingRemoval?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove ${target.name}?") },
            text = { Text("This removes its saved credentials and session. Other profiles remain available.") },
            confirmButton = { TextButton(onClick = { removeProfile(target); pendingRemoval = null }) { Text("Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } }
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
                        FilledTonalButton(
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
                        Button(
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

                    TextButton(onClick = refreshPlaybackQueue) {
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
                            Button(
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
                                        return@Button
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
internal fun PlaybackEngineSettingsSection(
    selectedEngine: PlaybackEngine,
    setPlaybackEngine: (PlaybackEngine) -> Unit
) {
    SettingsSection("Default media player") {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Used for Live TV, movies and episodes", style = MaterialTheme.typography.titleMedium)
            Text("Auto learns compatibility per series. You can force a specific engine here.", color = Color.Gray)
            val engines = listOf(
                Triple(PlaybackEngine.AUTO, "Auto", "Learns failures and uses VLC when needed"),
                Triple(PlaybackEngine.MEDIA3, "Media3", "NikTV decoder fallback and recovery"),
                Triple(PlaybackEngine.VLC, "VLC player", "Software decoding and broad compatibility"),
                Triple(PlaybackEngine.EXOPLAYER, "ExoPlayer", "Native device decoder order")
            )
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                maxItemsInEachRow = 2
            ) {
                engines.forEach { (engine, label, description) ->
                    val selected = selectedEngine == engine
                    Surface(
                        onClick = { setPlaybackEngine(engine) },
                        modifier = Modifier.weight(1f).widthIn(min = 150.dp).remoteFocusFrame(RoundedCornerShape(16.dp)),
                        shape = RoundedCornerShape(16.dp),
                        color = if (selected) Color(0xFF351416) else Color(0xFF1A1F2E),
                        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) Color(0xFFE50914) else Color(0xFF30384B))
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected, onClick = null)
                            Column(Modifier.padding(start = 6.dp)) {
                                Text(label, fontWeight = FontWeight.Bold)
                                Text(description, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 2)
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

@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val mobilePage = LocalMobileSettingsPage.current
    if (mobilePage != null && settingsPageFor(title) != mobilePage) return
    if (mobilePage != null) {
        var expanded by rememberSaveable(title, mobilePage) { mutableStateOf(true) }
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF15171C),
            border = BorderStroke(1.dp, Color(0xFF34373F)),
            shadowElevation = 2.dp
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { expanded = !expanded }
                        .padding(start = 8.dp, end = 2.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .width(4.dp)
                            .height(24.dp)
                            .background(Color(0xFFE50914), CircleShape)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        title,
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFF5F5F7)
                    )
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            if (expanded) "Collapse $title" else "Expand $title"
                        )
                    }
                }
                AnimatedVisibility(visible = expanded) {
                    Surface(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF0F1115),
                        border = BorderStroke(1.dp, Color(0xFF292C33))
                    ) {
                        Column(
                            Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            content = content
                        )
                    }
                }
            }
        }
        return
    }
    Column(Modifier.focusGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .background(Color(0xFFE50914), CircleShape)
            )
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = .15.sp,
                color = Color(0xFFF5F5F7)
            )
        }
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF111318),
            shadowElevation = 3.dp,
            border = BorderStroke(1.dp, Color(0xFF292C33)),
            content = { Column(content = content) }
        )
    }
}

@Composable
internal fun SettingsValueRow(icon: ImageVector, label: String, value: String) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value, maxLines = 2) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
internal fun RowScope.ExpressiveBottomNavigationItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    inactiveWidth: Dp
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
        modifier = (if (selected) Modifier.weight(1f) else Modifier.width(inactiveWidth))
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
            Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
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
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
internal fun MobileSettingsBottomNavigation(
    currentPage: MobileSettingsPage,
    selectPage: (MobileSettingsPage) -> Unit
) {
    Surface(color = Color(0xFF101216), tonalElevation = 8.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .animateContentSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MobileSettingsPage.entries.forEach { page ->
                ExpressiveBottomNavigationItem(
                    icon = page.icon,
                    label = page.title,
                    selected = page == currentPage,
                    onClick = { selectPage(page) },
                    inactiveWidth = 54.dp
                )
            }
        }
    }
}

@Composable
internal fun TmdbCredentialSettingsSection() {
    var revealCredentials by rememberSaveable { mutableStateOf(false) }
    val apiKey = BuildConfig.TMDB_API_KEY.trim()
    val readAccessToken = BuildConfig.TMDB_READ_ACCESS_TOKEN.trim()
    val openSubtitlesKey = BuildConfig.OPEN_SUBTITLES_KEY.trim()

    SettingsSection("Metadata and subtitle diagnostics") {
        ListItem(
            headlineContent = { Text("Reveal embedded credentials") },
            supportingContent = {
                Text("Credentials are hidden by default because anyone viewing this screen can copy them.")
            },
            leadingContent = {
                Icon(if (revealCredentials) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
            },
            trailingContent = {
                Switch(
                    checked = revealCredentials,
                    onCheckedChange = { revealCredentials = it },
                    modifier = Modifier.remoteFocusFrame(CircleShape)
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
        HorizontalDivider()
        SelectionContainer {
            Column {
                SettingsValueRow(
                    Icons.Default.Key,
                    "TMDB API key",
                    apiKey.credentialDiagnosticValue(revealCredentials)
                )
                HorizontalDivider()
                SettingsValueRow(
                    Icons.Default.VpnKey,
                    "TMDB read access token",
                    readAccessToken.credentialDiagnosticValue(revealCredentials)
                )
                HorizontalDivider()
                SettingsValueRow(
                    Icons.Default.Subtitles,
                    "OpenSubtitles API key",
                    openSubtitlesKey.credentialDiagnosticValue(revealCredentials)
                )
            }
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
