package com.nikhil.niktv.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.nikhil.niktv.BuildConfig
import com.nikhil.niktv.data.artworkRequest
import com.nikhil.niktv.data.cast4kLegacyDeviceIdentity
import com.nikhil.niktv.model.*
import com.nikhil.niktv.update.AppUpdates
import com.nikhil.niktv.update.UpdateDownloadState
import com.nikhil.niktv.update.UpdateInfo
import com.nikhil.niktv.update.formatDownloadBytes
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private val NikColors = darkColorScheme(
    primary = Color(0xFFE50914), onPrimary = Color.White,
    primaryContainer = Color(0xFF7F1016), onPrimaryContainer = Color.White,
    secondary = Color(0xFFE50914), onSecondary = Color.White,
    secondaryContainer = Color(0xFF3A1518), onSecondaryContainer = Color.White,
    background = Color(0xFF090909), onBackground = Color.White,
    surface = Color(0xFF141414), onSurface = Color.White,
    surfaceVariant = Color(0xFF262626), onSurfaceVariant = Color(0xFFD1D1D1),
    outline = Color(0xFF666666)
)
private val XtreamColors = NikColors
private val visibleCatalogTypes = listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)
private val menuActivationKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

private fun Context.isTvLikeDevice(configuration: Configuration): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION ||
        !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
private val visibleSearchTypes = listOf(SearchContentType.LIVE_TV, SearchContentType.SERIES, SearchContentType.MOVIES)

private fun UpdateDownloadState.updateInfoOrNull(): UpdateInfo? = when (this) {
    is UpdateDownloadState.Queued -> UpdateInfo(version, downloadUrl)
    is UpdateDownloadState.Downloading -> UpdateInfo(version, downloadUrl)
    is UpdateDownloadState.Paused -> UpdateInfo(version, downloadUrl)
    is UpdateDownloadState.Ready -> UpdateInfo(version, downloadUrl)
    is UpdateDownloadState.Installing -> UpdateInfo(version, downloadUrl)
    is UpdateDownloadState.InstallerLaunched -> UpdateInfo(version, downloadUrl)
    is UpdateDownloadState.Failed -> UpdateInfo(version, downloadUrl)
    UpdateDownloadState.Idle -> null
}

private fun uniformSegmentShape(index: Int, count: Int): RoundedCornerShape = when (index) {
    0 -> RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
    count - 1 -> RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
    else -> RoundedCornerShape(0.dp)
}

@Composable
private fun Modifier.remoteFocusFrame(
    shape: Shape = RoundedCornerShape(12.dp)
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTv = context.isTvLikeDevice(configuration)

    /*
     * FIRE_TV_STABLE_FOCUS_V13
     *
     * Lazy containers already keep D-pad focus visible on TV. Re-running
     * bringIntoView() and drawing a large glow for every focus hop makes
     * Fire TV navigation look like the entire viewport is bouncing/flashing.
     *
     * TV therefore gets a crisp, local border/background only.
     * Touch/mobile/tablet retain the existing glow + bringIntoView behavior.
     */
    return this
        .bringIntoViewRequester(bringIntoViewRequester)
        .onFocusChanged {
            focused = it.isFocused

            if (it.isFocused && !isTv) {
                scope.launch {
                    bringIntoViewRequester.bringIntoView()
                }
            }
        }
        .then(
            if (focused) {
                Modifier
                    .then(
                        if (!isTv) {
                            Modifier.shadow(
                                16.dp,
                                shape,
                                ambientColor = Color(0xFFE50914),
                                spotColor = Color(0xFFE50914)
                            )
                        } else {
                            Modifier
                        }
                    )
                    .background(Color(0xFF3A1014), shape)
                    .border(
                        if (isTv) 3.dp else 4.dp,
                        Color(0xFFFF3340),
                        shape
                    )
            } else {
                Modifier
            }
        )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.remoteCombinedClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?
): Modifier {
    val scope = rememberCoroutineScope()
    var keyIsDown by remember { mutableStateOf(false) }
    var longPressReached by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }

    return this
        .onPreviewKeyEvent { event ->
            if (event.key !in menuActivationKeys) return@onPreviewKeyEvent false
            when (event.type) {
                KeyEventType.KeyDown -> {
                    if (!keyIsDown) {
                        keyIsDown = true
                        longPressReached = false
                        longPressJob?.cancel()
                        longPressJob = scope.launch {
                            delay(android.view.ViewConfiguration.getLongPressTimeout().toLong())
                            longPressReached = true
                        }
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    val invokeLongClick = keyIsDown && longPressReached && onLongClick != null
                    longPressJob?.cancel()
                    longPressJob = null
                    keyIsDown = false
                    longPressReached = false
                    if (invokeLongClick) onLongClick?.invoke() else onClick()
                    true
                }
                else -> false
            }
        }
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
}
private fun String.withoutConfigurationQuotes(): String = trim().let { value ->
    if (value.length >= 2 && ((value.first() == '"' && value.last() == '"') ||
            (value.first() == '\'' && value.last() == '\''))) value.substring(1, value.length - 1).trim()
    else value
}

private tailrec fun android.content.Context.findHostActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

@Composable
fun NikTvApp(vm: NikTvViewModel = viewModel()) {
    val orientationMode by rememberUiOrientationMode()

    // APP_WIDE_ORIENTATION_OWNER_V12
    ApplyUiOrientation(orientationMode)

    val state by vm.state.collectAsStateWithLifecycle()
    val pendingUpdate by AppUpdates.pendingUpdate.collectAsStateWithLifecycle()
    val updateDownloadState by AppUpdates.downloadState.collectAsStateWithLifecycle()
    val updateEnforcementEnabled by AppUpdates.updateEnforcementEnabled.collectAsStateWithLifecycle()
    var discoveredUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var checkingForRequiredUpdate by remember { mutableStateOf(
        pendingUpdate == null && updateDownloadState.updateInfoOrNull() == null
    ) }
    val clipboard = LocalClipboardManager.current
    val playbackProfileKey = state.session?.profile?.cacheKey().orEmpty()
    val liveTvPlaybackDesign by rememberPlaybackDesign(playbackProfileKey, CatalogType.LIVE_TV)
    val moviePlaybackDesign by rememberPlaybackDesign(playbackProfileKey, CatalogType.MOVIES)
    val seriesPlaybackDesign by rememberPlaybackDesign(playbackProfileKey, CatalogType.SERIES)

    LaunchedEffect(updateEnforcementEnabled) {
        if (updateEnforcementEnabled && pendingUpdate == null && updateDownloadState.updateInfoOrNull() == null) {
            discoveredUpdate = runCatching { AppUpdates.check() }.getOrNull()
        }
        checkingForRequiredUpdate = false
    }
    val requiredUpdate = discoveredUpdate ?: pendingUpdate ?: updateDownloadState.updateInfoOrNull()
    val profileColors = if (state.savedProfile?.portalType == PortalType.XTREAM) XtreamColors else NikColors
    MaterialTheme(colorScheme = profileColors) {
        Surface(Modifier.fillMaxSize()) {
          if (updateEnforcementEnabled && (checkingForRequiredUpdate || requiredUpdate != null)) {
            MandatoryNikTvUpdateScreen(
                checking = checkingForRequiredUpdate,
                update = requiredUpdate,
                downloadState = updateDownloadState,
                enforcementEnabled = updateEnforcementEnabled,
                setEnforcementEnabled = AppUpdates::setUpdateEnforcementEnabled
            )
          } else {
           Box(Modifier.fillMaxSize()) {
            when {
                state.nowPlaying?.catalogType == CatalogType.LIVE_TV &&
                    liveTvPlaybackDesign == PlaybackDesign.SIDE_LIST -> LiveTvPlaybackScreen(
                    state = state,
                    play = vm::openMedia,
                    onBack = vm::closePlayer,
                    onRetry = vm::retryPlayback,
                    onRetryAlternateDecoder = vm::retryPlaybackWithAlternateDecoder,
                    onPlaybackAuthorizationFailure = vm::retryPlaybackAfterAuthorizationFailure,
                    onPlayPrevious = vm::playPreviousEpisode,
                    onPlayNext = vm::playNextEpisode,
                    onProgress = vm::savePlaybackProgress,
                    toggleFavorite = vm::toggleFavorite,
                    loadMoreCatalog = vm::loadMoreCatalog
                )

                state.nowPlaying?.catalogType == CatalogType.LIVE_TV &&
                    liveTvPlaybackDesign == PlaybackDesign.SHOWCASE -> ShowcasePlaybackScreen(
                    state = state,
                    play = vm::openMedia,
                    onBack = vm::closePlayer,
                    onRetry = vm::retryPlayback,
                    onRetryAlternateDecoder = vm::retryPlaybackWithAlternateDecoder,
                    onPlaybackAuthorizationFailure = vm::retryPlaybackAfterAuthorizationFailure,
                    onPlayPrevious = vm::playPreviousEpisode,
                    onPlayNext = vm::playNextEpisode,
                    onProgress = vm::savePlaybackProgress,
                    toggleFavorite = vm::toggleFavorite,
                    loadMoreCatalog = vm::loadMoreCatalog
                )

                state.nowPlaying?.catalogType == CatalogType.MOVIES &&
                    moviePlaybackDesign == PlaybackDesign.SHOWCASE -> ShowcasePlaybackScreen(
                    state = state,
                    play = vm::openMedia,
                    onBack = vm::closePlayer,
                    onRetry = vm::retryPlayback,
                    onRetryAlternateDecoder = vm::retryPlaybackWithAlternateDecoder,
                    onPlaybackAuthorizationFailure = vm::retryPlaybackAfterAuthorizationFailure,
                    onPlayPrevious = vm::playPreviousEpisode,
                    onPlayNext = vm::playNextEpisode,
                    onProgress = vm::savePlaybackProgress,
                    toggleFavorite = vm::toggleFavorite,
                    loadMoreCatalog = vm::loadMoreCatalog
                )

                state.nowPlaying?.catalogType == CatalogType.SERIES &&
                    seriesPlaybackDesign == PlaybackDesign.SHOWCASE -> ShowcasePlaybackScreen(
                    state = state,
                    play = vm::openMedia,
                    onBack = vm::closePlayer,
                    onRetry = vm::retryPlayback,
                    onRetryAlternateDecoder = vm::retryPlaybackWithAlternateDecoder,
                    onPlaybackAuthorizationFailure = vm::retryPlaybackAfterAuthorizationFailure,
                    onPlayPrevious = vm::playPreviousEpisode,
                    onPlayNext = vm::playNextEpisode,
                    onProgress = vm::savePlaybackProgress,
                    toggleFavorite = vm::toggleFavorite,
                    loadMoreCatalog = vm::loadMoreCatalog
                )

                state.nowPlaying != null -> PlayerScreen(
                    media = state.nowPlaying!!,
                    onBack = vm::closePlayer,
                    onRetry = vm::retryPlayback,
                    onRetryAlternateDecoder = vm::retryPlaybackWithAlternateDecoder,
                    onPlaybackAuthorizationFailure = vm::retryPlaybackAfterAuthorizationFailure,
                    onPlayPrevious = vm::playPreviousEpisode,
                    onPlayNext = vm::playNextEpisode,
                    onProgress = vm::savePlaybackProgress,
                    controlsTimeoutSeconds = state.playerControlsTimeoutSeconds,
                    startFullscreen = true
                )
                state.restoring -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.profileLoadProgress != null -> ProfileLoadingScreen(
                    profileName = state.savedProfile?.name,
                    message = state.profileLoadMessage,
                    progress = state.profileLoadProgress ?: 0f
                )
                state.session == null -> ProfileScreen(state.savedProfile, state.profiles, state.profileEditorOpen, state.loading, vm::connect, vm::switchProfile, vm::addProfile, vm::cancelProfileEditor, vm::importBackup)
                else -> CatalogScreen(
                    state = state,
                    selectType = vm::openCatalogType,
                    selectCategory = vm::loadCategory,
                    play = vm::openMedia,
                    closeSeries = vm::closeSeries,
                    refreshCatalog = vm::refreshCatalog,
                    openFavorites = vm::openFavorites,
                    closeFavorites = vm::closeFavorites,
                    openHome = vm::openHome,
                    openRecent = vm::openRecent,
                    removeRecent = vm::removeRecent,
                    clearRecent = vm::clearRecent,
                    openFavorite = vm::openFavorite,
                    toggleFavorite = vm::toggleFavorite,
                    toggleFavoriteEntry = { vm.toggleFavorite(it) },
                    openSettings = vm::openSettings,
                    closeSettings = vm::closeSettings,
                    reauthenticate = vm::reauthenticate,
                    editProfile = vm::editProfile,
                    logout = vm::logout,
                    setCacheIntervalMinutes = vm::setCacheIntervalMinutes
                    ,setPlayerControlsTimeoutSeconds = vm::setPlayerControlsTimeoutSeconds
                    ,setSeriesStartSeason = vm::setSeriesStartSeason
                    ,loadSeriesSeason = vm::loadSeriesSeason
                    ,toggleSeriesWatch = vm::toggleSeriesWatch
                    ,openWatchedEpisode = vm::openWatchedEpisode
                    ,setBrowseLayout = vm::setBrowseLayout
                    ,openSearch = vm::openSearch
                    ,closeSearch = vm::closeSearch
                    ,setSearchType = vm::setSearchType
                    ,setSearchQuery = vm::setSearchQuery
                    ,search = vm::search
                    ,useRecentSearch = vm::useRecentSearch
                    ,deleteRecentSearch = vm::deleteRecentSearch
                    ,openSearchResult = vm::openSearchResult
                    ,loadMoreSearch = vm::loadMoreSearch
                    ,loadMoreCatalog = vm::loadMoreCatalog
                    ,loadMoreEpisodes = vm::loadMoreEpisodes
                    ,setSearchCategory = vm::setSearchCategory
                    ,addProfile = vm::addProfile
                    ,exportBackup = vm::exportBackup
                    ,importBackup = vm::importBackup
                    ,openProfileSwitcher = vm::openProfileSwitcher
                    ,switchProfile = vm::switchProfile
                    ,removeProfile = vm::removeProfile
                    ,openCategoryManager = vm::openCategoryManager
                )
            }
            if (state.categoryManagerOpen) {
                CategoryManagerDialog(
                    state = state,
                    close = vm::closeCategoryManager,
                    setType = vm::setCategoryManagerType,
                    toggleCategory = { type, id -> vm.toggleCategoryFilter(type, id) },
                    selectAll = { vm.selectAllCategories(it) },
                    deselectAll = { vm.deselectAllCategories(it) },
                    setFilter = { type, list -> vm.setCategoryFilter(type, list) }
                )
            }
            if (state.loading && state.profileLoadProgress == null) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .42f)), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error?.let { error ->
                val authorizationExpired = error.isAuthorizationFailureText()
                var showDiagnostics by remember(error) { mutableStateOf(!authorizationExpired) }
                val reauthenticateRequester = remember(error) { FocusRequester() }
                LaunchedEffect(error, authorizationExpired) {
                    if (authorizationExpired) reauthenticateRequester.requestFocus()
                }
                AlertDialog(
                    onDismissRequest = vm::dismissError,
                    confirmButton = {
                        if (authorizationExpired) Button(
                            onClick = { vm.dismissError(); vm.reauthenticate() },
                            modifier = Modifier.focusRequester(reauthenticateRequester)
                        ) { Text("Re-authenticate") }
                        else TextButton(onClick = { clipboard.setText(AnnotatedString(error)) }) { Text("Copy diagnostics") }
                    },
                    dismissButton = { TextButton(onClick = vm::dismissError) { Text("Close") } },
                    title = { Text(if (authorizationExpired) "Session expired" else "Portal diagnostics") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (authorizationExpired) {
                                Text("The portal rejected the saved authorization token even though the HTTP request completed. Your profile credentials are still saved; request a fresh session to continue.")
                                if (!showDiagnostics) TextButton(onClick = { showDiagnostics = true }) { Icon(Icons.Default.Info, null); Spacer(Modifier.width(8.dp)); Text("Show diagnostics") }
                            }
                            if (showDiagnostics) SelectionContainer {
                                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(error, style = MaterialTheme.typography.bodySmall)
                                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(error)) }) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Copy diagnostics") }
                                }
                            }
                        }
                    }
                )
            }
            state.backupMessage?.let { message ->
                AlertDialog(
                    onDismissRequest = vm::dismissBackupMessage,
                    confirmButton = { Button(onClick = vm::dismissBackupMessage) { Text("OK") } },
                    title = { Text("NikTV backup") },
                    text = { Text(message) }
                )
            }
           }
          }
        }
    }
}

@Composable
private fun MandatoryNikTvUpdateScreen(
    checking: Boolean,
    update: UpdateInfo?,
    downloadState: UpdateDownloadState,
    enforcementEnabled: Boolean,
    setEnforcementEnabled: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val actionRequester = remember { FocusRequester() }
    var actionError by remember { mutableStateOf<String?>(null) }
    var permissionUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val pending = permissionUpdate
        permissionUpdate = null
        if (granted && pending != null) {
            runCatching { AppUpdates.downloadAndInstall(context, pending) }
                .onFailure { actionError = it.message ?: "Could not start the update" }
        } else if (!granted) {
            actionError = AppUpdates.PUBLIC_DOWNLOADS_PERMISSION_MESSAGE
        }
    }
    val busy = downloadState is UpdateDownloadState.Queued ||
        downloadState is UpdateDownloadState.Downloading ||
        downloadState is UpdateDownloadState.Paused ||
        downloadState is UpdateDownloadState.Installing
    val actionable = !checking && update != null && !busy

    fun downloadAndInstall(target: UpdateInfo) {
        actionError = null
        if (!AppUpdates.canWritePublicDownloads(context) && AppUpdates.requiresLegacyStoragePermission()) {
            permissionUpdate = target
            AppUpdates.deferDownloadAndInstall(context, target)
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            runCatching { AppUpdates.downloadAndInstall(context, target) }
                .onFailure { actionError = it.message ?: "Could not start the update" }
        }
    }

    LaunchedEffect(actionable, downloadState) {
        if (actionable) {
            withFrameNanos { }
            runCatching { actionRequester.requestFocus() }
        }
    }
    BackHandler(enabled = true) { }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF090909)) {
        Box(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF181818))
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 34.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Surface(Modifier.size(76.dp), CircleShape, color = Color(0xFFE50914)) {
                        Box(contentAlignment = Alignment.Center) {
                            if (checking) CircularProgressIndicator(color = Color.White)
                            else Icon(Icons.Default.SystemUpdateAlt, null, Modifier.size(38.dp), tint = Color.White)
                        }
                    }
                    Text(
                        if (checking) "Checking for updates…" else "Update required",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (checking) "Confirming that NikTV is up to date."
                        else "NikTV ${update?.version} is available. Update from ${BuildConfig.VERSION_NAME} to continue.",
                        color = Color(0xFFB3B3B3),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    when (val state = downloadState) {
                        is UpdateDownloadState.Downloading -> {
                            LinearProgressIndicator(
                                progress = { (state.percent ?: 0) / 100f },
                                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(999.dp)),
                                color = Color(0xFFE50914)
                            )
                            Text(
                                "Downloading ${state.percent?.let { "$it%" } ?: "…"} · ${formatDownloadBytes(state.bytesDownloaded)}",
                                color = Color.LightGray
                            )
                        }
                        is UpdateDownloadState.Queued -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFFE50914))
                            Text("Preparing download…", color = Color.LightGray)
                        }
                        is UpdateDownloadState.Paused -> {
                            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFFE50914))
                            Text(state.reason, color = Color.LightGray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                        is UpdateDownloadState.Installing -> {
                            CircularProgressIndicator(color = Color(0xFFE50914))
                            Text("Opening Android’s installer…", color = Color.LightGray)
                        }
                        is UpdateDownloadState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        else -> Unit
                    }
                    actionError?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }

                    if (!checking && update != null) {
                        Button(
                            onClick = {
                                when (downloadState) {
                                    is UpdateDownloadState.Ready,
                                    is UpdateDownloadState.InstallerLaunched -> runCatching { AppUpdates.install(context) }
                                        .onFailure { actionError = it.message ?: "Could not open the installer" }
                                    else -> downloadAndInstall(update)
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().height(54.dp)
                                .focusRequester(actionRequester)
                                .remoteFocusFrame(RoundedCornerShape(10.dp)),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914), contentColor = Color.White)
                        ) {
                            Icon(Icons.Default.Download, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (downloadState) {
                                    is UpdateDownloadState.Ready -> if (downloadState.awaitingUnknownSourcesPermission) "Allow installation" else "Install update"
                                    is UpdateDownloadState.InstallerLaunched -> "Install update"
                                    is UpdateDownloadState.Failed -> "Retry download"
                                    else -> "Download & Install"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                        OutlinedButton(
                            onClick = { settingsOpen = true },
                            modifier = Modifier.fillMaxWidth().height(50.dp).remoteFocusFrame(RoundedCornerShape(10.dp)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Settings, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Update settings")
                        }
                    }
                    Text(
                        "The latest version is required to continue using NikTV.",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
    if (settingsOpen) {
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text("Update settings") },
            text = {
                ListItem(
                    headlineContent = { Text("Require updates before using NikTV") },
                    supportingContent = {
                        Text(if (BuildConfig.DEBUG) "Off by default for Android Studio development builds." else "Turn off to continue without installing an available update.")
                    },
                    trailingContent = {
                        Switch(
                            checked = enforcementEnabled,
                            onCheckedChange = {
                                setEnforcementEnabled(it)
                                if (!it) settingsOpen = false
                            }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            },
            confirmButton = { TextButton(onClick = { settingsOpen = false }) { Text("Done") } }
        )
    }
}

@Composable
private fun ProfileLoadingScreen(profileName: String?, message: String, progress: Float) {
    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF070707), Color(0xFF111111), Color.Black))
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(0.72f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Surface(shape = CircleShape, color = Color(0xFFE50914), modifier = Modifier.size(84.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("N", color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black)
                }
            }
            Text(
                text = profileName?.let { "Loading $it" } ?: "Loading profile",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(999.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ProfileScreen(saved: PortalProfile?, profiles: List<PortalProfile>, editorOpen: Boolean, loading: Boolean, connect: (PortalProfile) -> Unit, selectProfile: (PortalProfile) -> Unit, addProfile: () -> Unit, cancelEditor: () -> Unit, importBackup: (android.net.Uri) -> Unit) {
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(importBackup)
    }

    // PROFILE_CHOOSER_APP_SETTINGS_V12
    var appSettingsOpen by rememberSaveable {
        mutableStateOf(false)
    }
    if (profiles.isNotEmpty() && !editorOpen) {
        val configuration = LocalConfiguration.current

        val compactLandscape =
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    configuration.screenHeightDp < 500

        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090909))
                .safeDrawingPadding()
                .then(
                    if (compactLandscape) {
                        Modifier.verticalScroll(scrollState)
                    } else {
                        Modifier
                    }
                )
                .padding(
                    horizontal = if (compactLandscape) 12.dp else 24.dp,
                    vertical = if (compactLandscape) 8.dp else 24.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement =
                if (compactLandscape) Arrangement.Top
                else Arrangement.Center
        ) {
            Text(
                "N",
                style = if (compactLandscape) {
                    MaterialTheme.typography.headlineLarge
                } else {
                    MaterialTheme.typography.displayLarge
                },
                fontWeight = FontWeight.Black,
                color = Color(0xFFE50914)
            )

            Spacer(
                Modifier.height(
                    if (compactLandscape) 4.dp else 24.dp
                )
            )

            Text(
                "Who's watching?",
                style = if (compactLandscape) {
                    MaterialTheme.typography.headlineMedium
                } else {
                    MaterialTheme.typography.displaySmall
                },
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                "Choose an IPTV profile",
                color = Color.LightGray,
                style = if (compactLandscape) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.bodyMedium
                }
            )

            FlowRow(
                modifier = Modifier
                    .widthIn(max = 900.dp)
                    .padding(
                        top = if (compactLandscape) 10.dp else 32.dp
                    ),
                horizontalArrangement = Arrangement.spacedBy(
                    if (compactLandscape) 12.dp else 24.dp,
                    Alignment.CenterHorizontally
                ),
                verticalArrangement = Arrangement.spacedBy(
                    if (compactLandscape) 12.dp else 24.dp
                )
            ) {
                profiles.forEach { profile ->
                    ProfileChooserTile(
                        title = profile.name,
                        subtitle = profile.portalType.displayName(),
                        icon = if (profile.portalType == PortalType.STALKER) {
                            Icons.Default.Tv
                        } else {
                            Icons.Default.Key
                        },
                        compact = compactLandscape
                    ) {
                        selectProfile(profile)
                    }
                }

                ProfileChooserTile(
                    "Settings",
                    "Display & orientation",
                    Icons.Default.Settings,
                    compactLandscape
                ) {
                    appSettingsOpen = true
                }

                ProfileChooserTile(
                    "Add profile",
                    "New connection",
                    Icons.Default.Add,
                    compactLandscape,
                    addProfile
                )

                ProfileChooserTile(
                    "Import backup",
                    "Restore NikTV setup",
                    Icons.Default.FileDownload,
                    compactLandscape
                ) {
                    importLauncher.launch(
                        arrayOf(
                            "application/json",
                            "text/json",
                            "text/plain"
                        )
                    )
                }
            }
        }

        if (appSettingsOpen) {
            OrientationSettingsDialog(
                onDismiss = {
                    appSettingsOpen = false
                }
            )
        }

        return
    }
    val context = LocalContext.current
    val generatedIdentity = remember(context) { cast4kLegacyDeviceIdentity(context) }
    val stalkerDefaults = remember(generatedIdentity) { PortalProfile(
        BuildConfig.DEFAULT_PROFILE_NAME.withoutConfigurationQuotes(),
        BuildConfig.DEFAULT_PORTAL_URL.withoutConfigurationQuotes(),
        BuildConfig.DEFAULT_MAC_ADDRESS.withoutConfigurationQuotes().ifBlank { generatedIdentity.macAddress },
        BuildConfig.DEFAULT_SERIAL_NUMBER.withoutConfigurationQuotes().ifBlank { generatedIdentity.serialNumber },
        PortalType.STALKER
    ) }
    val xtreamDefaults = remember { PortalProfile(
        BuildConfig.XTREAM_PROFILE_NAME.withoutConfigurationQuotes(),
        BuildConfig.XTREAM_PORTAL_URL.withoutConfigurationQuotes(),
        macAddress = "",
        portalType = PortalType.XTREAM,
        username = BuildConfig.XTREAM_USERNAME.withoutConfigurationQuotes(),
        password = BuildConfig.XTREAM_PASSWORD.withoutConfigurationQuotes()
    ) }
    val initial = saved ?: stalkerDefaults
    var name by remember(saved, editorOpen) { mutableStateOf(initial.name) }
    var url by remember(saved, editorOpen) { mutableStateOf(initial.portalUrl) }
    var mac by remember(saved, editorOpen) { mutableStateOf(initial.macAddress) }
    var serial by remember(saved, editorOpen) { mutableStateOf(initial.serialNumber) }
    var portalType by remember(saved, editorOpen) { mutableStateOf(saved?.portalType ?: PortalType.STALKER) }
    var username by remember(saved, editorOpen) { mutableStateOf(initial.username) }
    var password by remember(saved, editorOpen) { mutableStateOf(initial.password) }
    var advanced by remember(saved, editorOpen) { mutableStateOf(initial.serialNumber.isNotBlank()) }
    fun useDefaults(type: PortalType) {
        portalType = type
        if (saved != null) return
        val defaults = if (type == PortalType.STALKER) stalkerDefaults else xtreamDefaults
        name = defaults.name; url = defaults.portalUrl; mac = defaults.macAddress
        serial = defaults.serialNumber; username = defaults.username; password = defaults.password
        advanced = defaults.serialNumber.isNotBlank()
    }
    val nameFocus = remember { FocusRequester() }; val urlFocus = remember { FocusRequester() }
    val credentialFocus = remember { FocusRequester() }; val lastFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val profileConfiguration = LocalConfiguration.current
    val profileIsTv = context.isTvLikeDevice(profileConfiguration)
    var editingField by remember { mutableStateOf<String?>(null) }
    fun Modifier.profileTextField(field: String): Modifier = this
        .onFocusChanged {
            if (it.isFocused && !profileIsTv) editingField = field
            if (!it.isFocused && editingField == field) {
                editingField = null
                keyboard?.hide()
            }
        }
        .onPreviewKeyEvent { event ->
            if (profileIsTv && editingField != field && event.type == KeyEventType.KeyUp &&
                event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
            ) {
                editingField = field
                keyboard?.show()
                true
            } else false
        }
    Box(Modifier.fillMaxSize().background(Color(0xFF090909)).statusBarsPadding().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 520.dp).fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (profiles.isNotEmpty()) IconButton(onClick = cancelEditor) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to profiles") }
                    Column { Text(if (saved == null) "Add profile" else "Edit ${saved.name}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Credentials stay local to this profile", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(portalType == PortalType.STALKER, { useDefaults(PortalType.STALKER) }, uniformSegmentShape(0, 2), modifier = Modifier.remoteFocusFrame(uniformSegmentShape(0, 2))) { Text("Stalker / MAG") }
                    SegmentedButton(portalType == PortalType.XTREAM, { useDefaults(PortalType.XTREAM) }, uniformSegmentShape(1, 2), modifier = Modifier.remoteFocusFrame(uniformSegmentShape(1, 2))) { Text("Xtream") }
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().focusRequester(nameFocus).profileTextField("name"), label = { Text("Profile name") }, singleLine = true, readOnly = profileIsTv && editingField != "name", keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { editingField = null; urlFocus.requestFocus() }))
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().focusRequester(urlFocus).profileTextField("url"), label = { Text("Portal URL") }, placeholder = { Text("https://provider.example") }, singleLine = true, readOnly = profileIsTv && editingField != "url", keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { editingField = null; credentialFocus.requestFocus() }))
                if (portalType == PortalType.XTREAM) {
                    OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth().focusRequester(credentialFocus).profileTextField("username"), label = { Text("Username") }, singleLine = true, readOnly = profileIsTv && editingField != "username", keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { editingField = null; lastFocus.requestFocus() }))
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().focusRequester(lastFocus).profileTextField("password"), label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, readOnly = profileIsTv && editingField != "password", keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { editingField = null; keyboard?.hide() }))
                } else {
                    OutlinedTextField(mac, { mac = it }, Modifier.fillMaxWidth().focusRequester(credentialFocus).profileTextField("mac"), label = { Text("MAC address") }, placeholder = { Text("00:1A:79:XX:XX:XX") }, singleLine = true, readOnly = profileIsTv && editingField != "mac", keyboardOptions = KeyboardOptions(imeAction = if (advanced) ImeAction.Next else ImeAction.Done), keyboardActions = KeyboardActions(onNext = { editingField = null; lastFocus.requestFocus() }, onDone = { editingField = null; keyboard?.hide() }))
                    TextButton(onClick = {
                        mac = generatedIdentity.macAddress
                        serial = generatedIdentity.serialNumber
                        advanced = true
                    }) {
                        Icon(Icons.Default.AutoFixHigh, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Generate compatible device identity")
                    }
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced identity" else "Advanced identity") }
                    if (advanced) OutlinedTextField(serial, { serial = it }, Modifier.fillMaxWidth().focusRequester(lastFocus).profileTextField("serial"), label = { Text("Portal serial number (optional)") }, supportingText = { Text("Use the serial registered for this MAC, or leave blank to generate one.") }, singleLine = true, readOnly = profileIsTv && editingField != "serial", keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { editingField = null; keyboard?.hide() }))
                }
                val credentialsReady = if (portalType == PortalType.XTREAM) username.isNotBlank() && password.isNotBlank() else mac.isNotBlank()
                Button(onClick = { keyboard?.hide(); connect(PortalProfile(name.trim(), url.trim(), mac.trim(), serial.trim(), portalType, username.trim(), password)) }, enabled = !loading && name.isNotBlank() && url.isNotBlank() && credentialsReady, modifier = Modifier.fillMaxWidth()) { Text(if (saved == null) "Add profile" else "Save profile") }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
                    modifier = Modifier.fillMaxWidth().remoteFocusFrame()
                ) { Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Import NikTV backup") }
                Text("Only connect to services you are authorized to access.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun ProfileChooserTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    val tileWidth = if (compact) 128.dp else 156.dp
    val imageSize = if (compact) 92.dp else 124.dp
    val iconSize = if (compact) 42.dp else 56.dp
    val spacing = if (compact) 4.dp else 8.dp

    Column(
        Modifier
            .width(tileWidth)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(if (compact) 4.dp else 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        Box(
            Modifier
                .size(imageSize)
                .then(
                    if (focused) {
                        Modifier.shadow(
                            18.dp,
                            RoundedCornerShape(10.dp),
                            ambientColor = Color(0xFFE50914),
                            spotColor = Color(0xFFE50914)
                        )
                    } else {
                        Modifier
                    }
                )
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1F2937))
                .border(
                    if (focused) 4.dp else 2.dp,
                    if (focused) Color(0xFFE50914)
                    else Color(0xFF374151),
                    RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon,
                title,
                Modifier.size(iconSize),
                tint = if (focused) Color.White else Color.LightGray
            )
        }

        Text(
            title,
            color = if (focused) Color.White else Color.LightGray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = if (compact) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.titleMedium
            }
        )

        Text(
            subtitle,
            color = Color.Gray,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun CatalogScreen(
    state: NikTvState,
    selectType: (CatalogType) -> Unit,
    selectCategory: (Category) -> Unit,
    play: (MediaItem) -> Unit,
    closeSeries: () -> Unit,
    refreshCatalog: () -> Unit,
    openFavorites: () -> Unit,
    closeFavorites: () -> Unit,
    openHome: () -> Unit,
    openRecent: (RecentItem) -> Unit,
    removeRecent: (RecentItem) -> Unit,
    clearRecent: (FavoriteKind) -> Unit,
    openFavorite: (FavoriteItem) -> Unit,
    toggleFavorite: (MediaItem) -> Unit,
    toggleFavoriteEntry: (FavoriteItem) -> Unit,
    openSettings: () -> Unit,
    closeSettings: () -> Unit,
    reauthenticate: () -> Unit,
    editProfile: () -> Unit,
    logout: () -> Unit,
    setCacheIntervalMinutes: (Int) -> Unit,
    setPlayerControlsTimeoutSeconds: (Int) -> Unit,
    setSeriesStartSeason: (SeriesStartSeason) -> Unit,
    loadSeriesSeason: (Int) -> Unit,
    toggleSeriesWatch: () -> Unit,
    openWatchedEpisode: (WatchedSeries, MediaItem) -> Unit,
    setBrowseLayout: (BrowseLayout) -> Unit,
    openSearch: () -> Unit,
    closeSearch: () -> Unit,
    setSearchType: (SearchContentType) -> Unit,
    setSearchQuery: (String) -> Unit,
    search: (Boolean) -> Unit,
    useRecentSearch: (RecentSearch) -> Unit,
    deleteRecentSearch: (RecentSearch) -> Unit,
    openSearchResult: (MediaItem) -> Unit,
    loadMoreSearch: () -> Unit,
    loadMoreCatalog: () -> Unit,
    loadMoreEpisodes: () -> Unit,
    setSearchCategory: (String) -> Unit,
    addProfile: () -> Unit,
    exportBackup: (android.net.Uri) -> Unit,
    importBackup: (android.net.Uri) -> Unit,
    openProfileSwitcher: () -> Unit,
    switchProfile: (PortalProfile) -> Unit,
    removeProfile: (PortalProfile) -> Unit,
    openCategoryManager: (CatalogType) -> Unit
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val activity = context.findHostActivity()
    val isTv = context.isTvLikeDevice(configuration)
    val wide = configuration.screenWidthDp >= 720
    var exitConfirmationOpen by rememberSaveable { mutableStateOf(false) }
    val exitFocusRequester = remember { FocusRequester() }

    BackHandler(enabled = state.settingsOpen, onBack = closeSettings)
    BackHandler(enabled = !state.settingsOpen && state.searchOpen, onBack = closeSearch)
    BackHandler(enabled = !state.settingsOpen && !state.searchOpen && state.favoritesOpen, onBack = closeFavorites)
    BackHandler(
        enabled = !state.settingsOpen && !state.searchOpen && !state.favoritesOpen && state.selectedSeries != null,
        onBack = closeSeries
    )
    BackHandler(
        enabled = !exitConfirmationOpen && !state.settingsOpen && !state.searchOpen && !state.favoritesOpen && state.selectedSeries == null
    ) {
        if (state.homeOpen) exitConfirmationOpen = true else openHome()
    }

    LaunchedEffect(exitConfirmationOpen) {
        if (exitConfirmationOpen) {
            withFrameNanos { }
            runCatching { exitFocusRequester.requestFocus() }
        }
    }

    // Determine the current content to show in the main pane
    @Composable
    fun MainContent(modifier: Modifier = Modifier) {
        Box(modifier) {
            when {
                state.settingsOpen -> ModernSettingsScreen(
                    state = state,
                    closeSettings = closeSettings,
                    reauthenticate = reauthenticate,
                    editProfile = editProfile,
                    addProfile = addProfile,
                    exportBackup = exportBackup,
                    importBackup = importBackup,
                    switchProfile = switchProfile,
                    removeProfile = removeProfile,
                    logout = logout,
                    setCacheIntervalMinutes = setCacheIntervalMinutes,
                    setPlayerControlsTimeoutSeconds = setPlayerControlsTimeoutSeconds,
                    setSeriesStartSeason = setSeriesStartSeason,
                    openCategoryManager = openCategoryManager
                )
                state.searchOpen -> ModernSearchScreen(
                    state = state,
                    close = closeSearch,
                    setType = setSearchType,
                    setCategory = setSearchCategory,
                    setQuery = setSearchQuery,
                    search = search,
                    useRecent = useRecentSearch,
                    deleteRecent = deleteRecentSearch,
                    openResult = openSearchResult,
                    loadMore = loadMoreSearch,
                    toggleFavorite = toggleFavoriteEntry
                )
                state.favoritesOpen -> ModernFavoritesScreen(
                    state = state,
                    openFavorite = openFavorite,
                    toggleFavorite = toggleFavoriteEntry,
                    openSearch = openSearch,
                    openSettings = openSettings,
                    closeFavorites = closeFavorites
                )
                state.selectedSeries != null -> ModernSeriesDetailScreen(
                    state = state,
                    play = play,
                    closeSeries = closeSeries,
                    toggleFavorite = toggleFavorite,
                    toggleSeriesWatch = toggleSeriesWatch,
                    loadSeriesSeason = loadSeriesSeason,
                    openSearch = openSearch,
                    openSettings = openSettings,
                    refreshCatalog = refreshCatalog,
                    loadMoreEpisodes = loadMoreEpisodes
                )
                else -> ModernBrowseScreen(
                    state = state,
                    selectType = selectType,
                    selectCategory = selectCategory,
                    play = play,
                    openHome = openHome,
                    openRecent = openRecent,
                    removeRecent = removeRecent,
                    clearRecent = clearRecent,
                    openWatchedEpisode = openWatchedEpisode,
                    toggleFavorite = toggleFavorite,
                    toggleFavoriteEntry = toggleFavoriteEntry,
                    setBrowseLayout = setBrowseLayout,
                    openFavorite = openFavorite,
                    openFavorites = openFavorites,
                    openSearch = openSearch,
                    openSettings = openSettings,
                    openProfileSwitcher = openProfileSwitcher,
                    refreshCatalog = refreshCatalog,
                    openCategoryManager = openCategoryManager,
                    loadMoreCatalog = loadMoreCatalog
                )
            }
        }
    }

    if (wide || isTv) {
        Row(Modifier.fillMaxSize().background(Color(0xFF090909))) {
            ModernSideRail(
                state = state,
                selectType = selectType,
                openHome = openHome,
                openFavorites = openFavorites,
                openSearch = openSearch,
                openSettings = openSettings,
                openProfileSwitcher = openProfileSwitcher,
                // TABLET_NAVIGATION_TITLES_V12
                expanded = true,
                modifier = Modifier
                    .width(if (isTv) 196.dp else 176.dp)
                    .fillMaxHeight()
            )
            MainContent(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        MainContent(Modifier.fillMaxSize())
    }

    if (exitConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { exitConfirmationOpen = false },
            icon = { Icon(Icons.Default.ExitToApp, null, tint = Color(0xFFE50914)) },
            title = { Text("Exit NikTV?") },
            text = { Text("Are you sure you want to close the app?") },
            dismissButton = {
                TextButton(onClick = { exitConfirmationOpen = false }) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    onClick = { activity?.finishAffinity() },
                    modifier = Modifier.focusRequester(exitFocusRequester),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914), contentColor = Color.White)
                ) {
                    Text("Exit", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            shape = RoundedCornerShape(24.dp),
            containerColor = Color(0xFF181818),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    }
}



@Composable
private fun ModernBrowseScreen(
    state: NikTvState,
    selectType: (CatalogType) -> Unit,
    selectCategory: (Category) -> Unit,
    play: (MediaItem) -> Unit,
    openHome: () -> Unit,
    openRecent: (RecentItem) -> Unit,
    removeRecent: (RecentItem) -> Unit,
    clearRecent: (FavoriteKind) -> Unit,
    openWatchedEpisode: (WatchedSeries, MediaItem) -> Unit,
    toggleFavorite: (MediaItem) -> Unit,
    toggleFavoriteEntry: (FavoriteItem) -> Unit,
    setBrowseLayout: (BrowseLayout) -> Unit,
    openFavorite: (FavoriteItem) -> Unit,
    openFavorites: () -> Unit,
    openSearch: () -> Unit,
    openSettings: () -> Unit,
    openProfileSwitcher: () -> Unit,
    refreshCatalog: () -> Unit,
    openCategoryManager: (CatalogType) -> Unit,
    loadMoreCatalog: () -> Unit
) {
    val home = state.homeOpen
    val layoutToggleRequester = remember { FocusRequester() }
    val firstChannelRequester = remember { FocusRequester() }

    // MOVIE_DASHBOARD_PAGINATION_FOCUS_V2
    //
    // Movies pagination owns focus explicitly. The Load More item remains
    // focusable during the request, then focus is handed to the first movie
    // appended by that request instead of falling back to the Profile control.
    val catalogGridState = rememberLazyGridState()
    val movieFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    /*
     * LIVE_TV_DASHBOARD_PAGINATION_FOCUS_V13
     *
     * Live TV uses the same focus-handoff principle as Movies. The focused
     * Load More button remains alive during the request, then focus moves to
     * the first newly appended channel.
     */
    val liveTvFocusRequesters =
        remember { mutableMapOf<String, FocusRequester>() }

    var liveTvLoadMorePending by remember {
        mutableStateOf(false)
    }
    var liveTvLoadMoreObservedLoading by remember {
        mutableStateOf(false)
    }
    var liveTvLoadMoreStartItemCount by remember {
        mutableIntStateOf(0)
    }
    var liveTvLoadMoreFirstVisibleItemIndex by remember {
        mutableIntStateOf(0)
    }
    var liveTvLoadMoreFirstVisibleItemScrollOffset by remember {
        mutableIntStateOf(0)
    }

    var movieLoadMorePending by remember { mutableStateOf(false) }
    var movieLoadMoreObservedLoading by remember { mutableStateOf(false) }
    var movieLoadMoreStartItemCount by remember { mutableIntStateOf(0) }

    /*
     * Preserve the exact grid viewport while a Movies page is loading.
     * This lets pagination feel like new tiles were simply appended below
     * the current content instead of causing the existing grid to jump.
     */
    var movieLoadMoreFirstVisibleItemIndex by remember {
        mutableIntStateOf(0)
    }
    var movieLoadMoreFirstVisibleItemScrollOffset by remember {
        mutableIntStateOf(0)
    }

    val hero = if (home) state.recentlyPlayed.firstOrNull()?.media ?: state.favorites.firstOrNull()?.media else state.items.firstOrNull()
    val configuration = LocalConfiguration.current
    val browseContext = LocalContext.current
    val isTv = browseContext.isTvLikeDevice(configuration)
    val isWide = configuration.screenWidthDp >= 720 || isTv
    val profileKey = state.savedProfile?.cacheKey().orEmpty()

    var liveTvColumns by rememberSaveable {
        mutableIntStateOf(
            if (isWide) 2 else 1
        )
    }

    val maxLiveTvColumns = when {
        isTv || configuration.screenWidthDp >= 1200 -> 4
        configuration.screenWidthDp >= 900 -> 3
        configuration.screenWidthDp >= 600 -> 2
        else -> 1
    }

    LaunchedEffect(maxLiveTvColumns) {
        if (liveTvColumns > maxLiveTvColumns) {
            liveTvColumns = maxLiveTvColumns
        }
    }

    val maxMovieColumns = when {
        isTv || configuration.screenWidthDp >= 1400 -> 8
        configuration.screenWidthDp >= 1200 -> 7
        configuration.screenWidthDp >= 1000 -> 6
        configuration.screenWidthDp >= 800 -> 5
        configuration.screenWidthDp >= 600 -> 4
        else -> 3
    }

    val defaultMovieColumns = when {
        isTv -> 6
        configuration.screenWidthDp >= 1000 -> 5
        configuration.screenWidthDp >= 600 -> 4
        else -> 3
    }

    val storedMovieColumns by rememberCatalogColumns(
        profileKey = profileKey,
        type = CatalogType.MOVIES,
        defaultValue = defaultMovieColumns
    )

    val movieColumns = storedMovieColumns.coerceIn(2, maxMovieColumns)

    val columns = when {
        state.selectedType == CatalogType.LIVE_TV -> liveTvColumns
        state.selectedType == CatalogType.MOVIES -> movieColumns
        isWide -> 6
        else -> 3
    }

    val aspectRatio =
        if (state.selectedType == CatalogType.MOVIES) 1f else 16f / 9f
    val gridSpan: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

    LaunchedEffect(state.selectedType, state.selectedCategory?.id, state.items.firstOrNull()?.id) {
        if (!home && state.selectedType == CatalogType.LIVE_TV && state.items.isNotEmpty()) {
            delay(180L)
            runCatching { firstChannelRequester.requestFocus() }
        }
    }

    /*
     * Seamless Movies pagination.
     *
     * Keep the focused Load More item in the composition while loading.
     * Once state.items grows, scroll to the actual first appended movie,
     * wait until Compose lays it out, then request focus on that tile.
     */
    LaunchedEffect(
        movieLoadMorePending,
        state.catalogLoadingMore,
        state.items.size,
        state.selectedType,
        state.selectedCategory?.id
    ) {
        if (!movieLoadMorePending) {
            return@LaunchedEffect
        }

        if (state.selectedType != CatalogType.MOVIES) {
            movieLoadMorePending = false
            movieLoadMoreObservedLoading = false
            return@LaunchedEffect
        }

        if (state.catalogLoadingMore) {
            movieLoadMoreObservedLoading = true
            return@LaunchedEffect
        }

        val receivedNewMovies =
            state.items.size > movieLoadMoreStartItemCount

        val loadFinished =
            movieLoadMoreObservedLoading || receivedNewMovies

        if (!loadFinished) {
            return@LaunchedEffect
        }

        /*
         * Normally this is the first newly loaded movie.
         * If the portal returns an empty final page, move focus to the
         * last existing movie before removing Load More so focus still
         * cannot escape to Profile.
         */
        val targetMovieIndex =
            if (receivedNewMovies) {
                movieLoadMoreStartItemCount
            } else {
                state.items.lastIndex
            }

        val targetMovie = state.items.getOrNull(targetMovieIndex)

        if (targetMovie != null) {
            val requester =
                movieFocusRequesters.getOrPut(targetMovie.id) {
                    FocusRequester()
                }

            /*
             * LazyVerticalGrid indexes include full-width items above the
             * movie cards:
             *
             * wide/TV: hero + categories + catalog header = 3
             * narrow : top bar + hero + categories + catalog header = 4
             */
            val catalogItemOffset =
                if (isWide) 3 else 4

            val targetGridIndex =
                catalogItemOffset + targetMovieIndex

            /*
             * MOVIE_DASHBOARD_SMOOTH_APPEND_V6
             *
             * First restore the exact pre-request viewport. This neutralizes
             * any LazyGrid anchor movement caused by the full-width Load More
             * item moving after newly appended movies.
             */
            catalogGridState.scrollToItem(
                movieLoadMoreFirstVisibleItemIndex,
                movieLoadMoreFirstVisibleItemScrollOffset
            )

            withFrameNanos { }

            val targetAlreadyVisible =
                catalogGridState
                    .layoutInfo
                    .visibleItemsInfo
                    .any { visible ->
                        visible.index == targetGridIndex
                    }

            if (!targetAlreadyVisible) {
                /*
                 * Do not align the new tile hard to the top. Keep roughly
                 * two-thirds of a viewport of previous-page context above it,
                 * which makes the append read as a continuation rather than
                 * a page jump.
                 */
                val layoutInfo =
                    catalogGridState.layoutInfo

                val viewportHeight =
                    (
                        layoutInfo.viewportEndOffset -
                            layoutInfo.viewportStartOffset
                        )
                        .coerceAtLeast(1)

                val contextualScrollOffset =
                    -(viewportHeight * 2 / 3)

                catalogGridState.animateScrollToItem(
                    index = targetGridIndex,
                    scrollOffset = contextualScrollOffset
                )
            }

            withTimeoutOrNull(1_500L) {
                snapshotFlow {
                    catalogGridState
                        .layoutInfo
                        .visibleItemsInfo
                        .any { visible ->
                            visible.index == targetGridIndex
                        }
                }.first { it }
            }

            withFrameNanos { }

            val focused = runCatching {
                requester.requestFocus()
            }.isSuccess

            if (!focused) {
                delay(60L)
                runCatching {
                    requester.requestFocus()
                }
            }
        }

        /*
         * Clear pending only AFTER focus was moved away from Load More.
         * On the final page this allows the button to disappear safely.
         */
        movieLoadMorePending = false
        movieLoadMoreObservedLoading = false
    }

    /*
     * LIVE_TV_SMOOTH_APPEND_V13
     *
     * Preserve the viewport while channels append, then smoothly reveal and
     * focus the first new channel. If the final page is empty, focus the last
     * existing channel before the Load More item disappears.
     */
    LaunchedEffect(
        liveTvLoadMorePending,
        state.catalogLoadingMore,
        state.items.size,
        state.selectedType,
        state.selectedCategory?.id
    ) {
        if (!liveTvLoadMorePending) {
            return@LaunchedEffect
        }

        if (state.selectedType != CatalogType.LIVE_TV) {
            liveTvLoadMorePending = false
            liveTvLoadMoreObservedLoading = false
            return@LaunchedEffect
        }

        if (state.catalogLoadingMore) {
            liveTvLoadMoreObservedLoading = true
            return@LaunchedEffect
        }

        val receivedNewChannels =
            state.items.size > liveTvLoadMoreStartItemCount

        val loadFinished =
            liveTvLoadMoreObservedLoading ||
                receivedNewChannels

        if (!loadFinished) {
            return@LaunchedEffect
        }

        val targetChannelIndex =
            if (receivedNewChannels) {
                liveTvLoadMoreStartItemCount
            } else {
                state.items.lastIndex
            }

        val targetChannel =
            state.items.getOrNull(targetChannelIndex)

        if (targetChannel != null) {
            val requester =
                if (targetChannelIndex == 0) {
                    firstChannelRequester
                } else {
                    liveTvFocusRequesters
                        .getOrPut(targetChannel.id) {
                            FocusRequester()
                        }
                }

            val catalogItemOffset =
                if (isWide) 3 else 4

            val targetGridIndex =
                catalogItemOffset +
                    targetChannelIndex

            catalogGridState.scrollToItem(
                liveTvLoadMoreFirstVisibleItemIndex,
                liveTvLoadMoreFirstVisibleItemScrollOffset
            )

            withFrameNanos { }

            val targetAlreadyVisible =
                catalogGridState
                    .layoutInfo
                    .visibleItemsInfo
                    .any { visible ->
                        visible.index ==
                            targetGridIndex
                    }

            if (!targetAlreadyVisible) {
                val layoutInfo =
                    catalogGridState.layoutInfo

                val viewportHeight =
                    (
                        layoutInfo.viewportEndOffset -
                            layoutInfo.viewportStartOffset
                        )
                        .coerceAtLeast(1)

                val contextualScrollOffset =
                    -(viewportHeight * 2 / 3)

                catalogGridState
                    .animateScrollToItem(
                        index = targetGridIndex,
                        scrollOffset =
                            contextualScrollOffset
                    )
            }

            withTimeoutOrNull(1_500L) {
                snapshotFlow {
                    catalogGridState
                        .layoutInfo
                        .visibleItemsInfo
                        .any { visible ->
                            visible.index ==
                                targetGridIndex
                        }
                }.first { it }
            }

            withFrameNanos { }

            val focused =
                runCatching {
                    requester.requestFocus()
                }.isSuccess

            if (!focused) {
                delay(60L)
                runCatching {
                    requester.requestFocus()
                }
            }
        }

        liveTvLoadMorePending = false
        liveTvLoadMoreObservedLoading = false
    }

    ModernGrid(
        columns = columns,
        state = catalogGridState,
        modifier = Modifier.fillMaxSize().background(Color(0xFF090909)),
        contentPadding = PaddingValues(bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
            if (!isWide) item("modern-top", span = gridSpan) {
                ModernTopBar(state, home, openHome, selectType, openFavorites, openSearch, openSettings, openProfileSwitcher)
            }
            item("modern-hero", span = gridSpan) {
                val recent = state.recentlyPlayed.firstOrNull()
                when {
                    !home && state.selectedType == CatalogType.LIVE_TV ->
                        LiveTvPreviewPlaceholder(state.selectedCategory?.title)

                    !home && state.selectedType == CatalogType.MOVIES ->
                        MovieBrowseHeaderPlaceholder(state.selectedCategory?.title)

                    else ->
                        ModernHero(
                            hero,
                            if (home && recent != null) {{ openRecent(recent) }} else null,
                            if (!home && hero != null) {{ play(hero) }} else null,
                            state.savedProfile?.name.orEmpty()
                        )
                }
            }
            if (!home) item("modern-categories", span = gridSpan) {
                val profileKey = state.savedProfile?.cacheKey()
                val filterKey = "$profileKey|${state.selectedType.name}"
                val isFiltered = state.categoryFilters.containsKey(filterKey)
                Surface(color = Color(0xFF111111), shadowElevation = 8.dp) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // CATEGORY_REFRESH_FIRST_V6
                        item {
                            AssistChip(
                                onClick = refreshCatalog,
                                modifier = Modifier
                                    .remoteFocusFrame(CircleShape)
                                    .focusProperties {
                                        if (state.items.isNotEmpty()) {
                                            down = layoutToggleRequester
                                        }
                                    },
                                label = { Text("Refresh") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Refresh,
                                        null,
                                        Modifier.size(16.dp)
                                    )
                                }
                            )
                        }

                        item {
                            AssistChip(
                                onClick = { openCategoryManager(state.selectedType) },
                                modifier = Modifier.remoteFocusFrame(CircleShape).focusProperties {
                                    if (state.items.isNotEmpty()) down = layoutToggleRequester
                                },
                                label = { Text(if (isFiltered) "Filtered (${state.categories.size})" else "Categories") },
                                leadingIcon = { Icon(Icons.Default.Tune, null, Modifier.size(16.dp)) }
                            )
                        }
                        items(state.categories, key = { it.id }) { category ->
                            val selected = state.selectedCategory?.id == category.id
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                TextButton(onClick = { selectCategory(category) }, modifier = Modifier.remoteFocusFrame(CircleShape).focusProperties {
                                    if (state.items.isNotEmpty()) down = layoutToggleRequester
                                }) {
                                    Text(category.title, color = if (selected) Color.White else Color.LightGray)
                                }
                                Box(Modifier.width(30.dp).height(3.dp).background(if (selected) Color(0xFFE50914) else Color.Transparent))
                            }
                        }
                    }
                }
            }
            if (home) {
                val newEpisodes = state.watchedSeries.flatMap { watched -> watched.newEpisodes.map { watched to it } }
                if (newEpisodes.isNotEmpty()) item("watch-list-updates", span = gridSpan) {
                    ModernRail(
                        "New Episodes",
                        newEpisodes,
                        media = { (watched, episode) ->
                            if (episode.logo.isNullOrBlank() && !watched.series.logo.isNullOrBlank()) {
                                episode.copy(logo = watched.series.logo)
                            } else episode
                        },
                        open = { (watched, episode) -> openWatchedEpisode(watched, episode) },
                        progress = { (_, episode) -> state.playbackProgress.progressFor(episode) },
                        subtitle = { (watched, _) -> watched.series.title },
                        titleMaxLines = Int.MAX_VALUE,
                        subtitleMaxLines = 2
                    )
                }
                val recents = state.recentlyPlayed.filterNot { it.kind == FavoriteKind.EPISODE }
                if (recents.isNotEmpty()) item("continue", span = gridSpan) {
                    ModernRail(
                        "Continue Watching", recents, { it.media }, openRecent,
                        progress = { recent -> state.playbackProgress.progressFor(recent.lastPlayed ?: recent.media) },
                        remove = removeRecent,
                        isFavorite = { recent -> state.favorites.any { it.kind == recent.kind && it.media.id == recent.media.id } },
                        toggleFavorite = { recent ->
                            toggleFavoriteEntry(FavoriteItem(recent.kind, recent.media, recent.series))
                        }
                    )
                }
                if (state.favorites.isNotEmpty()) item("my-list", span = gridSpan) {
                    ModernRail(
                        "My List", state.favorites, { it.media }, openFavorite,
                        subtitle = { favorite -> listOfNotNull(favorite.kind.mediaTypeLabel(), favorite.categoryTitle).joinToString(" · ") },
                        titleMaxLines = Int.MAX_VALUE,
                        subtitleMaxLines = 2
                    )
                }
                FavoriteKind.entries.filterNot { it == FavoriteKind.EPISODE }.forEach { kind ->
                    val entries = recents.filter { it.kind == kind }
                    if (entries.isNotEmpty()) item("recent-${kind.name}", span = gridSpan) {
                        ModernRail(
                            "Recently Played ${kind.sectionTitle()}", entries, { it.media }, openRecent,
                            remove = removeRecent,
                            isFavorite = { recent -> state.favorites.any { it.kind == recent.kind && it.media.id == recent.media.id } },
                            toggleFavorite = { recent ->
                                toggleFavoriteEntry(FavoriteItem(recent.kind, recent.media, recent.series))
                            },
                            clear = { clearRecent(kind) }
                        )
                    }
                }
                if (recents.isEmpty() && state.favorites.isEmpty()) item("home-empty", span = gridSpan) {
                    Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.PlayCircle, null, Modifier.size(64.dp), tint = Color.DarkGray)
                            Text("Nothing watched yet", color = Color.LightGray, style = MaterialTheme.typography.titleMedium)
                            Text("Browse Live TV, Movies, or Series to get started", color = Color.Gray)
                        }
                    }
                }
            } else if (state.categories.isEmpty()) {
                item("modern-empty-categories", span = gridSpan) {
                    Box(Modifier.fillMaxWidth().height(260.dp).padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(Icons.Default.FilterListOff, null, Modifier.size(48.dp), tint = Color.LightGray)
                            Text("No categories enabled for ${state.selectedType.title}", color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Text("Adjust your category filters to include content.", color = Color.LightGray)
                            Button(onClick = { openCategoryManager(state.selectedType) }) { Text("Manage categories") }
                        }
                    }
                }
            } else if (state.items.isNotEmpty()) {
                item("catalog-header", span = gridSpan) {
                    ModernSectionHeader(
                        state.selectedCategory?.title ?: state.selectedType.title,
                        when (state.selectedType) {
                            CatalogType.MOVIES ->
                                "${state.items.size} movies · ←/→ browse · OK play · Hold OK My List"
                            else ->
                                "${state.items.size} ${state.selectedType.itemLabel(state.items.size)}"
                        },
                        action = when (state.selectedType) {
                            CatalogType.LIVE_TV -> {
                                {
                                    LiveTvColumnSelector(
                                        selectedColumns = liveTvColumns,
                                        maxColumns = maxLiveTvColumns,
                                        onColumnsChanged = {
                                            liveTvColumns = it
                                        },
                                        selectorFocusRequester = layoutToggleRequester,
                                        firstChannelFocusRequester = firstChannelRequester
                                    )
                                }
                            }

                            CatalogType.MOVIES -> {
                                {
                                    CatalogColumnSelector(
                                        selectedColumns = movieColumns,
                                        maxColumns = maxMovieColumns,
                                        onColumnsChanged = {
                                            PlaybackUiPreferences.setCatalogColumns(
                                                context = browseContext,
                                                profileKey = profileKey,
                                                type = CatalogType.MOVIES,
                                                columns = it
                                            )
                                        },
                                        selectorFocusRequester = layoutToggleRequester
                                    )
                                }
                            }

                            else -> {
                                {
                                    FilledTonalIconButton(
                                        onClick = {
                                            setBrowseLayout(
                                                if (state.browseLayout == BrowseLayout.GRID) {
                                                    BrowseLayout.LIST
                                                } else {
                                                    BrowseLayout.GRID
                                                }
                                            )
                                        },
                                        modifier = Modifier
                                            .focusRequester(layoutToggleRequester)
                                            .focusProperties {
                                                up = FocusRequester.Default
                                            }
                                            .remoteFocusFrame(CircleShape)
                                    ) {
                                        Icon(
                                            if (state.browseLayout == BrowseLayout.GRID) {
                                                Icons.Default.ViewList
                                            } else {
                                                Icons.Default.GridView
                                            },
                                            if (state.browseLayout == BrowseLayout.GRID) {
                                                "Show as list"
                                            } else {
                                                "Show as grid"
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    )
                }
                itemsIndexed(
                    items = state.items,

                    key = { _, item ->
                        "catalog-${state.selectedType}-${item.id}"
                    },

                    span = { _, _ ->
                        when {
                            state.selectedType == CatalogType.LIVE_TV ->
                                GridItemSpan(1)

                            state.selectedType == CatalogType.MOVIES ->
                                GridItemSpan(1)

                            state.browseLayout == BrowseLayout.LIST ->
                                GridItemSpan(maxLineSpan)

                            else ->
                                GridItemSpan(1)
                        }
                    }
                ) { index, item ->

                    when {

                        state.selectedType == CatalogType.LIVE_TV -> {

                            val liveTvRequester =
                                if (index == 0) {
                                    null
                                } else {
                                    liveTvFocusRequesters
                                        .getOrPut(item.id) {
                                            FocusRequester()
                                        }
                                }

                            LiveTvChannelCard(
                                item = item,
                                index = index,
                                columnCount = liveTvColumns,
                                firstChannelFocusRequester = firstChannelRequester,
                                itemFocusRequester = liveTvRequester,
                                columnSelectorFocusRequester = layoutToggleRequester,

                                isFavorite = state.favorites.any {
                                    it.kind == FavoriteKind.CHANNEL &&
                                            it.media.id == item.id
                                },

                                onPlay = {
                                    play(item)
                                },

                                onToggleFavorite = {
                                    toggleFavorite(item)
                                }
                            )
                        }

                        state.selectedType == CatalogType.MOVIES -> {

                            val movieRequester =
                                movieFocusRequesters.getOrPut(item.id) {
                                    FocusRequester()
                                }

                            ModernPosterCard(
                                item = item,
                                aspectRatio = aspectRatio,
                                modifier = Modifier.padding(horizontal = 4.dp),
                                onClick = {
                                    play(item)
                                },
                                isFavorite = state.favorites.any {
                                    it.media.id == item.id &&
                                        it.kind == FavoriteKind.MOVIE
                                },
                                toggleFavorite = {
                                    toggleFavorite(item)
                                },
                                focusRequester = movieRequester,
                                unfocusedScale = 0.90f,
                                focusedScale = 1.06f
                            )
                        }

                        state.browseLayout == BrowseLayout.LIST -> {

                            ModernMediaListCard(
                                item = item,
                                onClick = {
                                    play(item)
                                },
                                isFavorite = state.favorites.any {
                                    it.media.id == item.id &&
                                            it.kind == state.selectedType.favoriteKind()
                                },
                                toggleFavorite = {
                                    toggleFavorite(item)
                                }
                            )
                        }

                        else -> {

                            ModernPosterCard(
                                item = item,
                                aspectRatio = aspectRatio,
                                modifier = Modifier.padding(horizontal = 4.dp),
                                onClick = {
                                    play(item)
                                },
                                isFavorite = state.favorites.any {
                                    it.media.id == item.id &&
                                            it.kind == state.selectedType.favoriteKind()
                                },
                                toggleFavorite = {
                                    toggleFavorite(item)
                                }
                            )
                        }
                    }
                }
                if (
                    state.selectedType in setOf(
                        CatalogType.LIVE_TV,
                        CatalogType.MOVIES,
                        CatalogType.SERIES
                    ) &&
                    (
                        state.catalogHasMore ||
                            (
                                state.selectedType == CatalogType.MOVIES &&
                                    movieLoadMorePending
                            ) ||
                            (
                                state.selectedType == CatalogType.LIVE_TV &&
                                    liveTvLoadMorePending
                            )
                    )
                ) {
                    item("catalog-load-more", span = gridSpan) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            var loadMoreFocused by remember {
                                mutableStateOf(false)
                            }

                            val movieLoadMoreScale by
                                androidx.compose.animation.core.animateFloatAsState(
                                    targetValue =
                                        if (isTv) {
                                            1f
                                        } else if (
                                            state.selectedType == CatalogType.MOVIES &&
                                            loadMoreFocused
                                        ) {
                                            1.10f
                                        } else if (
                                            state.selectedType == CatalogType.MOVIES
                                        ) {
                                            0.94f
                                        } else {
                                            1f
                                        },
                                    animationSpec =
                                        androidx.compose.animation.core.tween(
                                            durationMillis = 140
                                        ),
                                    label = "movieDashboardLoadMoreScale"
                                )

                            Button(
                                onClick = {
                                    if (state.catalogLoadingMore) {
                                        return@Button
                                    }

                                    if (
                                        state.selectedType ==
                                            CatalogType.MOVIES
                                    ) {
                                        if (movieLoadMorePending) {
                                            return@Button
                                        }

                                        movieLoadMoreStartItemCount =
                                            state.items.size

                                        movieLoadMoreFirstVisibleItemIndex =
                                            catalogGridState
                                                .firstVisibleItemIndex

                                        movieLoadMoreFirstVisibleItemScrollOffset =
                                            catalogGridState
                                                .firstVisibleItemScrollOffset

                                        movieLoadMoreObservedLoading =
                                            false
                                        movieLoadMorePending =
                                            true
                                    } else if (
                                        state.selectedType ==
                                            CatalogType.LIVE_TV
                                    ) {
                                        if (liveTvLoadMorePending) {
                                            return@Button
                                        }

                                        liveTvLoadMoreStartItemCount =
                                            state.items.size

                                        liveTvLoadMoreFirstVisibleItemIndex =
                                            catalogGridState
                                                .firstVisibleItemIndex

                                        liveTvLoadMoreFirstVisibleItemScrollOffset =
                                            catalogGridState
                                                .firstVisibleItemScrollOffset

                                        liveTvLoadMoreObservedLoading =
                                            false
                                        liveTvLoadMorePending =
                                            true
                                    }

                                    loadMoreCatalog()
                                },

                                /*
                                 * Movies deliberately remain enabled while
                                 * loading so the focused button stays a valid
                                 * focus owner. Other catalog types retain the
                                 * existing behavior.
                                 */
                                enabled =
                                    state.selectedType in setOf(
                                        CatalogType.MOVIES,
                                        CatalogType.LIVE_TV
                                    ) ||
                                        !state.catalogLoadingMore,

                                modifier = Modifier
                                    .height(48.dp)
                                    .graphicsLayer {
                                        scaleX = movieLoadMoreScale
                                        scaleY = movieLoadMoreScale
                                    }
                                    .onFocusChanged {
                                        loadMoreFocused = it.isFocused
                                    }
                                    .remoteFocusFrame(
                                        RoundedCornerShape(10.dp)
                                    ),

                                shape = RoundedCornerShape(10.dp),

                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFE50914),
                                    contentColor = Color.White
                                )
                            ) {
                                val loading =
                                    state.catalogLoadingMore ||
                                        (
                                            state.selectedType ==
                                                CatalogType.MOVIES &&
                                                movieLoadMorePending
                                            ) ||
                                        (
                                            state.selectedType ==
                                                CatalogType.LIVE_TV &&
                                                liveTvLoadMorePending
                                            )

                                if (loading) {
                                    CircularProgressIndicator(
                                        Modifier.size(20.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        if (
                                            state.selectedType ==
                                                CatalogType.LIVE_TV
                                        ) {
                                            "Loading channels…"
                                        } else {
                                            "Loading titles…"
                                        },
                                        color = Color.White
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Add,
                                        null,
                                        Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (
                                            state.selectedType ==
                                                CatalogType.LIVE_TV
                                        ) {
                                            "Load more channels"
                                        } else {
                                            "Load more titles"
                                        },
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            } else item("modern-empty", span = gridSpan) {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    Text("Nothing to show in this category", color = Color.White.copy(alpha = .72f))
                }
            }
    }
}

private fun liveChannelSupportingText(
    item: MediaItem
): String? {
    item.liveProgramme?.let { programme ->
        return liveProgrammeSummary(programme)
    }

    val description = item.description
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    return description?.takeIf {
        !it.equals(item.title.trim(), ignoreCase = true)
    }
}

private fun liveProgrammeSummary(
    programme: LiveProgramme
): String {
    val formatter = java.text.SimpleDateFormat(
        "h:mm a",
        java.util.Locale.getDefault()
    )

    val schedule = when {
        programme.startTimeMillis != null &&
                programme.endTimeMillis != null -> {

            "${formatter.format(java.util.Date(programme.startTimeMillis))}" +
                    "–${formatter.format(java.util.Date(programme.endTimeMillis))}"
        }

        programme.startTimeMillis != null -> {
            "From ${
                formatter.format(
                    java.util.Date(programme.startTimeMillis)
                )
            }"
        }

        else -> null
    }

    return listOfNotNull(
        programme.title.takeIf { it.isNotBlank() },
        schedule
    ).joinToString("  •  ")
}

@Composable
private fun LiveProgrammeFooter(programme: LiveProgramme) {
    val formatter = remember { java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()) }
    val schedule = remember(programme.startTimeMillis, programme.endTimeMillis) {
        when {
            programme.startTimeMillis != null && programme.endTimeMillis != null ->
                "${formatter.format(java.util.Date(programme.startTimeMillis))}–${formatter.format(java.util.Date(programme.endTimeMillis))}"
            programme.startTimeMillis != null -> "From ${formatter.format(java.util.Date(programme.startTimeMillis))}"
            else -> null
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            programme.title,
            color = Color(0xFFE6E6E6),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        schedule?.let { Text(it, color = Color(0xFFB3B3B3), style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun ModernGrid(
    columns: Int,
    state: LazyGridState = rememberLazyGridState(),
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(10.dp),
    content: LazyGridScope.() -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        horizontalArrangement = horizontalArrangement,
        content = content
    )
}

@Composable
private fun LiveTvChannelCard(
    item: MediaItem,
    index: Int,
    columnCount: Int,
    firstChannelFocusRequester: FocusRequester,
    itemFocusRequester: FocusRequester? = null,
    columnSelectorFocusRequester: FocusRequester,
    isFavorite: Boolean,
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    val isFirstChannel = index == 0

    // If there are 4 columns, indexes 0,1,2,3 are the first row.
    // If there are 2 columns, indexes 0,1 are the first row.
    val isFirstRow = index < columnCount

    val cardModifier = Modifier
        .then(
            when {
                itemFocusRequester != null ->
                    Modifier.focusRequester(
                        itemFocusRequester
                    )

                isFirstChannel ->
                    Modifier.focusRequester(
                        firstChannelFocusRequester
                    )

                else ->
                    Modifier
            }
        )
        .focusProperties {
            if (isFirstRow) {
                up = columnSelectorFocusRequester
            }
        }

    ModernMediaListCard(
        item = item,
        modifier = cardModifier,
        onClick = onPlay,
        isFavorite = isFavorite,
        toggleFavorite = onToggleFavorite,
        supportingText = liveChannelSupportingText(item),
        compact = true
    )
}

@Composable
private fun LiveTvColumnSelector(
    selectedColumns: Int,
    maxColumns: Int,
    onColumnsChanged: (Int) -> Unit,
    selectorFocusRequester: FocusRequester,
    firstChannelFocusRequester: FocusRequester
) {
    SingleChoiceSegmentedButtonRow {
        (1..maxColumns).forEachIndexed { index, count ->

            val shape = uniformSegmentShape(
                index = index,
                count = maxColumns
            )

            SegmentedButton(
                selected = selectedColumns == count,
                onClick = {
                    onColumnsChanged(count)
                },
                shape = shape,
                modifier = Modifier
                    .then(
                        if (selectedColumns == count) {
                            Modifier.focusRequester(
                                selectorFocusRequester
                            )
                        } else {
                            Modifier
                        }
                    )
                    .focusProperties {
                        // Down from the column selector always enters
                        // the channel grid at the first channel.
                        down = firstChannelFocusRequester

                        // Let Compose find the category row above.
                        up = FocusRequester.Default
                    }
                    .remoteFocusFrame(shape)
            ) {
                Text("$count")
            }
        }
    }
}

@Composable
private fun ModernSectionHeader(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
            subtitle?.let { Text(it, color = Color.Gray, style = MaterialTheme.typography.labelMedium) }
        }
        action?.invoke()
    }
}

@Composable
private fun ModernSideRail(
    state: NikTvState,
    selectType: (CatalogType) -> Unit,
    openHome: () -> Unit,
    openFavorites: () -> Unit,
    openSearch: () -> Unit,
    openSettings: () -> Unit,
    openProfileSwitcher: () -> Unit,
    expanded: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(modifier, color = Color(0xFF070707), shadowElevation = 12.dp) {
        Column(
            Modifier.statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (expanded) 10.dp else 6.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .clickable(onClick = openProfileSwitcher)
                    .remoteFocusFrame(RoundedCornerShape(10.dp))
                    .padding(horizontal = if (expanded) 12.dp else 0.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
            ) {
                Text(
                    state.savedProfile?.name.orEmpty().ifBlank { "Profile" },
                    color = Color.White,
                    style = if (expanded) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(12.dp))
            ModernRailButton(Icons.Default.Home, "Home", state.homeOpen, expanded, openHome)
            visibleCatalogTypes.forEach { type ->
                ModernRailButton(type.icon(), type.title, !state.homeOpen && !state.favoritesOpen && state.selectedType == type, expanded) { selectType(type) }
            }
            ModernRailButton(Icons.Default.Favorite, "My List", state.favoritesOpen, expanded, openFavorites)
            Spacer(Modifier.height(12.dp))
            ModernRailButton(Icons.Default.Search, "Search", state.searchOpen, expanded, openSearch)
            ModernRailButton(Icons.Default.Settings, "Settings", state.settingsOpen, expanded, openSettings)
        }
    }
}

@Composable
private fun ModernRailButton(icon: ImageVector, label: String, selected: Boolean, expanded: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val railContext = LocalContext.current
    val railConfiguration = LocalConfiguration.current
    val isTv = railContext.isTvLikeDevice(railConfiguration)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(50.dp).padding(vertical = 3.dp)
            .onFocusChanged { focused = it.isFocused }
            .semantics { role = Role.Tab; this.selected = selected }
            .then(
                if (focused && !isTv) {
                    Modifier.shadow(
                        12.dp,
                        shape,
                        ambientColor = Color(0xFFE50914),
                        spotColor = Color(0xFFE50914)
                    )
                } else {
                    Modifier
                }
            ),
        shape = shape,
        color = when {
            focused -> Color(0xFF3A0A0D)
            selected -> Color(0xFF241012)
            else -> Color.Transparent
        },
        border = when {
            focused -> BorderStroke(3.dp, Color(0xFFFF3340))
            selected -> BorderStroke(1.dp, Color(0xFFE50914))
            else -> null
        }
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = if (expanded) 14.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
        ) {
            Icon(icon, label, Modifier.size(24.dp), tint = if (focused || selected) Color.White else Color.Gray)
            if (expanded) {
                Spacer(Modifier.width(14.dp))
                Text(
                    label,
                    color = if (focused || selected) Color.White else Color.LightGray,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun ModernTopBar(
    state: NikTvState,
    home: Boolean,
    openHome: () -> Unit,
    selectType: (CatalogType) -> Unit,
    openFavorites: () -> Unit,
    openSearch: () -> Unit,
    openSettings: () -> Unit,
    openProfileSwitcher: () -> Unit
) {
    /*
     * MOBILE_NAVIGATION_TITLES_V12
     *
     * Every destination has visible text. Horizontal scrolling keeps the
     * labels readable instead of squeezing them on narrow phones.
     */
    Surface(color = Color(0xFF090909)) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
            contentPadding = PaddingValues(
                horizontal = 12.dp,
                vertical = 8.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item("mobile-profile") {
                TextButton(
                    onClick = openProfileSwitcher,
                    modifier = Modifier.remoteFocusFrame(
                        RoundedCornerShape(10.dp)
                    )
                ) {
                    Icon(
                        Icons.Default.AccountCircle,
                        null,
                        Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        state.savedProfile?.name.orEmpty()
                            .ifBlank { "Profile" },
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1
                    )
                }
            }

            item("mobile-home") {
                TextButton(
                    onClick = openHome,
                    modifier = Modifier.remoteFocusFrame(
                        RoundedCornerShape(10.dp)
                    )
                ) {
                    Icon(
                        Icons.Default.Home,
                        null,
                        Modifier.size(18.dp),
                        tint = if (home) Color.White else Color.Gray
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Home",
                        color = if (home) Color.White else Color.Gray
                    )
                }
            }

            visibleCatalogTypes.forEach { type ->
                item("mobile-${type.name}") {
                    val selected =
                        !home &&
                            !state.favoritesOpen &&
                            !state.searchOpen &&
                            !state.settingsOpen &&
                            state.selectedType == type

                    TextButton(
                        onClick = { selectType(type) },
                        modifier = Modifier.remoteFocusFrame(
                            RoundedCornerShape(10.dp)
                        )
                    ) {
                        Icon(
                            type.icon(),
                            null,
                            Modifier.size(18.dp),
                            tint = if (selected) Color.White else Color.Gray
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            type.title,
                            color = if (selected) Color.White else Color.Gray,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            item("mobile-my-list") {
                val selected = state.favoritesOpen
                TextButton(
                    onClick = openFavorites,
                    modifier = Modifier.remoteFocusFrame(
                        RoundedCornerShape(10.dp)
                    )
                ) {
                    Icon(
                        Icons.Default.FavoriteBorder,
                        null,
                        Modifier.size(18.dp),
                        tint = if (selected) Color.White else Color.Gray
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "My List",
                        color = if (selected) Color.White else Color.Gray
                    )
                }
            }

            item("mobile-search") {
                val selected = state.searchOpen
                TextButton(
                    onClick = openSearch,
                    modifier = Modifier.remoteFocusFrame(
                        RoundedCornerShape(10.dp)
                    )
                ) {
                    Icon(
                        Icons.Default.Search,
                        null,
                        Modifier.size(18.dp),
                        tint = if (selected) Color.White else Color.Gray
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Search",
                        color = if (selected) Color.White else Color.Gray
                    )
                }
            }

            item("mobile-settings") {
                val selected = state.settingsOpen
                TextButton(
                    onClick = openSettings,
                    modifier = Modifier.remoteFocusFrame(
                        RoundedCornerShape(10.dp)
                    )
                ) {
                    Icon(
                        Icons.Default.Settings,
                        null,
                        Modifier.size(18.dp),
                        tint = if (selected) Color.White else Color.Gray
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Settings",
                        color = if (selected) Color.White else Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveTvPreviewPlaceholder(categoryTitle: String?) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Top
                )
            )
            .padding(
                horizontal = 20.dp,
                vertical = 8.dp
            ),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF111111)
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = 12.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.LiveTv,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = Color(0xFFE50914)
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "Choose a channel to start watching",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    categoryTitle ?: "Live TV",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ModernHero(item: MediaItem?, recentAction: (() -> Unit)?, catalogAction: (() -> Unit)?, profileName: String) {
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth().heightIn(min = 300.dp).height(50.vh())) {
        if (item?.logo != null) AsyncImage(artworkRequest(context, item), item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFF090909), Color(0xCC090909), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0xFF090909)))))
        Column(Modifier.align(Alignment.BottomStart).widthIn(max = 720.dp).padding(horizontal = 28.dp, vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(item?.title ?: "Welcome to NikTV", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Black, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(item?.description?.takeIf(String::isNotBlank) ?: if (profileName.isBlank()) "Choose something to watch" else "Streaming from $profileName",
                style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = .86f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val action = recentAction ?: catalogAction
            if (action != null) Button(onClick = action, modifier = Modifier.remoteFocusFrame(), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(if (recentAction != null) "Resume" else "Play")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun <T> ModernRail(
    title: String,
    entries: List<T>,
    media: (T) -> MediaItem,
    open: (T) -> Unit,
    aspectRatio: (T) -> Float = { 16f / 9f },
    progress: (T) -> PlaybackProgress? = { null },
    remove: ((T) -> Unit)? = null,
    clear: (() -> Unit)? = null,
    subtitle: (T) -> String? = { null },
    titleMaxLines: Int = 1,
    subtitleMaxLines: Int = 1,
    isFavorite: (T) -> Boolean = { false },
    toggleFavorite: ((T) -> Unit)? = null
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        ModernSectionHeader(title, action = clear?.let { action -> { TextButton(onClick = action) { Text("Clear", color = Color.LightGray) } } })
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries, key = { entry -> "${media(entry).id}-${media(entry).title}" }) { entry ->
                ModernPosterCard(
                    item = media(entry),
                    aspectRatio = aspectRatio(entry),
                    modifier = Modifier.width(180.dp),
                    progress = progress(entry),
                    onClick = { open(entry) },
                    titleMaxLines = titleMaxLines,
                    isFavorite = isFavorite(entry),
                    toggleFavorite = toggleFavorite?.let { action -> { action(entry) } },
                    removeAction = remove?.let { action -> { action(entry) } }
                ) {
                    subtitle(entry)?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            color = Color.Gray,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = subtitleMaxLines,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernArtworkFallback(
    title: String,
    modifier: Modifier = Modifier
) {
    /*
     * MODERN_TYPOGRAPHY_POSTER_V6
     *
     * A missing image becomes a deliberate mini-poster instead of an error
     * state. The title hash picks a stable dark gradient, so different titles
     * get subtle variety while staying inside the NikTV visual language.
     */
    val palette = remember(title) {
        val palettes = listOf(
            Color(0xFF111827) to Color(0xFF3A1014),
            Color(0xFF20242D) to Color(0xFF101827),
            Color(0xFF26172D) to Color(0xFF12121A),
            Color(0xFF162727) to Color(0xFF101416),
            Color(0xFF2B1D16) to Color(0xFF15100D),
            Color(0xFF222222) to Color(0xFF351015)
        )

        palettes[
            (title.hashCode() and Int.MAX_VALUE) %
                palettes.size
        ]
    }

    val initial = remember(title) {
        title
            .trim()
            .firstOrNull()
            ?.uppercaseChar()
            ?.toString()
            ?: "N"
    }

    Box(
        modifier = modifier
            .background(
                Brush.linearGradient(
                    listOf(palette.first, palette.second)
                )
            )
            .padding(12.dp)
    ) {
        Text(
            "NIKTV",
            modifier = Modifier.align(Alignment.TopStart),
            color = Color(0xFFFF7A82),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Black,
            maxLines = 1
        )

        Text(
            initial,
            modifier = Modifier.align(Alignment.Center),
            color = Color.White.copy(alpha = 0.11f),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Black,
            maxLines = 1
        )

        Text(
            title,
            modifier = Modifier.align(Alignment.BottomStart),
            color = Color.White.copy(alpha = 0.96f),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModernPosterCard(
    item: MediaItem,
    aspectRatio: Float,
    modifier: Modifier = Modifier,
    progress: PlaybackProgress? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    titleMaxLines: Int = 1,
    isFavorite: Boolean = false,
    toggleFavorite: (() -> Unit)? = null,
    removeAction: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    unfocusedScale: Float = 1f,
    focusedScale: Float = 1f,
    footer: (@Composable () -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTv = context.isTvLikeDevice(configuration)

    val posterScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue =
            if (isTv) {
                1f
            } else if (focused) {
                focusedScale
            } else {
                unfocusedScale
            },
        animationSpec =
            androidx.compose.animation.core.tween(
                durationMillis = 140
            ),
        label = "catalogPosterScale"
    )
    val artworkModel = remember(item.id, item.title, item.logo) { artworkRequest(context, item) }
    val fraction = if (progress != null && progress.durationMillis > 0L)
        (progress.positionMillis.toFloat() / progress.durationMillis).coerceIn(0f, 1f) else 0f
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (focusRequester != null) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    }
                )
                .graphicsLayer {
                    scaleX = posterScale
                    scaleY = posterScale
                }
                .onFocusChanged { focused = it.isFocused }
                .remoteCombinedClickable(
                onClick = onClick,
                onLongClick = if (toggleFavorite != null || removeAction != null) {
                    { menuOpen = true }
                } else onLongClick
            ),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                .then(if (focused) Modifier.shadow(18.dp, RoundedCornerShape(8.dp), ambientColor = Color(0xFFE50914), spotColor = Color(0xFFE50914)) else Modifier)
                .clip(RoundedCornerShape(8.dp)).background(Color(0xFF242424))
                .border(if (focused) 4.dp else 0.dp, Color(0xFFFF2633), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                if (item.logo.isNullOrBlank()) {
                    ModernArtworkFallback(
                        title = item.title,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    SubcomposeAsyncImage(
                        artworkModel,
                        item.title,
                        Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    ) {
                        when (painter.state.value) {
                            is coil3.compose.AsyncImagePainter.State.Success ->
                                SubcomposeAsyncImageContent()
                            else ->
                                ModernArtworkFallback(
                                    title = item.title,
                                    modifier = Modifier.fillMaxSize()
                                )
                        }
                    }
                }
                if (fraction > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(0xFF333333))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Color(0xFFE50914)))
                }
            }
            Text(
                item.title,
                modifier = if (focused && titleMaxLines == 1) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier,
                color = Color.White,
                style =
                    if (isTv) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.labelLarge
                    },
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis
            )
            footer?.invoke()
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.align(Alignment.TopEnd),
            containerColor = Color(0xFF202020),
            shape = RoundedCornerShape(12.dp)
        ) {
            toggleFavorite?.let { action ->
                DropdownMenuItem(
                    text = { Text(if (isFavorite) "Remove from My List" else "Add to My List") },
                    leadingIcon = { Icon(if (isFavorite) Icons.Default.HeartBroken else Icons.Default.FavoriteBorder, null) },
                    onClick = { menuOpen = false; action() }
                )
            }
            removeAction?.let { action ->
                DropdownMenuItem(
                    text = { Text("Remove from recent") },
                    leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                    onClick = { menuOpen = false; action() }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModernMediaListCard(
    item: MediaItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    isFavorite: Boolean,
    toggleFavorite: () -> Unit,
    supportingText: String? = item.description,
    compact: Boolean = false,
    isCurrentlyPlaying: Boolean = false
) {
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isTv = context.isTvLikeDevice(configuration)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 2.dp else 10.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focused) {
                    Modifier.shadow(
                        16.dp,
                        RoundedCornerShape(12.dp),
                        ambientColor = Color(0xFFE50914),
                        spotColor = Color(0xFFE50914)
                    )
                } else {
                    Modifier
                }
            )
            .remoteCombinedClickable(
                onClick = onClick,
                onLongClick = { menuOpen = true }
            ),
        shape = RoundedCornerShape(12.dp),
        color = when {
            focused -> Color(0xFF292929)
            isCurrentlyPlaying -> Color(0xFF211719)
            else -> Color(0xFF171717)
        },
        border = when {
            focused -> BorderStroke(4.dp, Color(0xFFFF2633))
            isCurrentlyPlaying -> BorderStroke(2.dp, Color(0xFFE50914))
            else -> null
        }
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 6.dp else 8.dp,
                vertical = if (compact) 7.dp else 8.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(
                if (compact) 8.dp else 12.dp
            )
        ) {
            if (isCurrentlyPlaying) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(if (compact) 52.dp else 42.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFFE50914))
                )
            }

            Box(
                modifier = Modifier
                    .width(if (compact) 72.dp else 132.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF242424)),
                contentAlignment = Alignment.Center
            ) {
                if (item.logo.isNullOrBlank()) {
                    Icon(
                        Icons.Default.SmartDisplay,
                        contentDescription = null,
                        modifier = Modifier.size(
                            if (compact) 30.dp else 42.dp
                        ),
                        tint = Color.LightGray
                    )
                } else {
                    SubcomposeAsyncImage(
                        model = artworkRequest(context, item),
                        contentDescription = item.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    ) {
                        when (painter.state.value) {
                            is coil3.compose.AsyncImagePainter.State.Success ->
                                SubcomposeAsyncImageContent()

                            else -> Icon(
                                Icons.Default.SmartDisplay,
                                contentDescription = null,
                                modifier = Modifier.size(
                                    if (compact) 30.dp else 42.dp
                                ),
                                tint = Color.LightGray
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = item.title,
                    color = Color.White,
                    style = if (compact) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.titleMedium
                    },
                    fontWeight = if (focused || isCurrentlyPlaying) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Normal
                    },
                    maxLines = if (compact) 2 else 1,
                    overflow = TextOverflow.Ellipsis
                )

                supportingText
                    ?.takeIf { it.isNotBlank() }
                    ?.let { text ->
                        Text(
                            text = text,
                            color = if (focused) {
                                Color(0xFFD5D5D5)
                            } else {
                                Color(0xFFAAAAAA)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
            }

            if (!compact) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = if (focused) Color.White else Color.Gray
                )
            }
        }

        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = Color(0xFF202020),
            shape = RoundedCornerShape(12.dp)
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (isFavorite) {
                            "Remove from My List"
                        } else {
                            "Add to My List"
                        }
                    )
                },
                leadingIcon = {
                    Icon(
                        if (isFavorite) {
                            Icons.Default.HeartBroken
                        } else {
                            Icons.Default.FavoriteBorder
                        },
                        contentDescription = null
                    )
                },
                onClick = {
                    menuOpen = false
                    toggleFavorite()
                }
            )
        }
    }
}

private fun List<PlaybackProgress>.progressFor(item: MediaItem): PlaybackProgress? =
    firstOrNull { it.key.contains(item.id) }

@Composable
private fun Int.vh(): androidx.compose.ui.unit.Dp = (LocalConfiguration.current.screenHeightDp * this / 100f).dp

@Composable
private fun ModernFavoritesScreen(
    state: NikTvState,
    openFavorite: (FavoriteItem) -> Unit,
    toggleFavorite: (FavoriteItem) -> Unit,
    openSearch: () -> Unit,
    openSettings: () -> Unit,
    closeFavorites: () -> Unit
) {
    val groups = FavoriteKind.entries.mapNotNull { kind ->
        state.favorites.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { kind to it }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(180.dp),
        modifier = Modifier.fillMaxSize().background(Color(0xFF090909)),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item("favorites-top", span = { GridItemSpan(maxLineSpan) }) {
            ModernScreenTopBar("My List", closeFavorites, openSearch, openSettings)
        }
        if (groups.isEmpty()) item("favorites-empty", span = { GridItemSpan(maxLineSpan) }) {
            Box(Modifier.fillMaxWidth().height(360.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.FavoriteBorder, null, Modifier.size(48.dp), tint = Color.Gray)
                    Text("No favorites yet", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text("Press and hold any media item to add it here", color = Color.Gray)
                }
            }
        } else {
            groups.forEach { (kind, favorites) ->
                item("header-${kind.name}", span = { GridItemSpan(maxLineSpan) }) {
                    ModernSectionHeader(kind.sectionTitle(), "${favorites.size} saved")
                }
                items(
                    items = favorites,
                    key = { it.key }
                ) { favorite ->
                    ModernFavoriteCard(
                        favorite = favorite,
                        aspectRatio = 16f / 9f,
                        open = { openFavorite(favorite) },
                        remove = { toggleFavorite(favorite) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernFavoriteCard(
    favorite: FavoriteItem,
    aspectRatio: Float,
    open: () -> Unit,
    remove: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState()
    var removed by remember(favorite.key) { mutableStateOf(false) }
    LaunchedEffect(dismissState.currentValue) {
        if (!removed && dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
            removed = true
            remove()
        }
    }
    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val removalArmed = dismissState.targetValue != SwipeToDismissBoxValue.Settled
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                    .background(if (removalArmed) Color(0xFF7F1D1D) else Color(0xFF090909)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Delete, "Remove ${favorite.media.title}", tint = Color.White)
            }
        }
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF090909)
        ) {
            ModernPosterCard(
                item = favorite.media,
                aspectRatio = aspectRatio,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                onClick = open,
                onLongClick = remove,
                titleMaxLines = Int.MAX_VALUE
            ) {
                Text(
                    listOfNotNull(favorite.kind.mediaTypeLabel(), favorite.categoryTitle?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    color = Color(0xFFB3B3B3),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ModernScreenTopBar(title: String, close: () -> Unit, openSearch: (() -> Unit)? = null, openSettings: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFF090909)).statusBarsPadding().padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = close, modifier = Modifier.remoteFocusFrame(CircleShape)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = Color.White)
        openSearch?.let { IconButton(onClick = it, modifier = Modifier.remoteFocusFrame(CircleShape)) { Icon(Icons.Default.Search, "Search", tint = Color.White) } }
        openSettings?.let { IconButton(onClick = it, modifier = Modifier.remoteFocusFrame(CircleShape)) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModernSearchScreen(
    state: NikTvState,
    close: () -> Unit,
    setType: (SearchContentType) -> Unit,
    setCategory: (String) -> Unit,
    setQuery: (String) -> Unit,
    search: (Boolean) -> Unit,
    useRecent: (RecentSearch) -> Unit,
    deleteRecent: (RecentSearch) -> Unit,
    openResult: (MediaItem) -> Unit,
    loadMore: () -> Unit,
    toggleFavorite: (FavoriteItem) -> Unit
) {
    var categoriesExpanded by rememberSaveable(state.searchType) { mutableStateOf(false) }
    var searchEditing by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val searchConfiguration = LocalConfiguration.current
    val searchContext = LocalContext.current
    val searchIsTv = searchContext.isTvLikeDevice(searchConfiguration)
    fun activateSearchField() {
        searchEditing = true
        focusRequester.requestFocus()
        keyboard?.show()
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF090909)).padding(horizontal = 16.dp)) {
        ModernScreenTopBar("Search", close)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            visibleSearchTypes.forEachIndexed { index, type ->
                val shape = uniformSegmentShape(index, visibleSearchTypes.size)
                SegmentedButton(
                    selected = state.searchType == type,
                    onClick = { setType(type) },
                    shape = shape,
                    modifier = Modifier.remoteFocusFrame(shape)
                ) { Text(type.title, maxLines = 1) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val selectedCategory = state.searchCategories.firstOrNull { it.id == state.searchCategoryId }
            AssistChip(
                onClick = { categoriesExpanded = !categoriesExpanded },
                modifier = Modifier.remoteFocusFrame(CircleShape),
                label = { Text(selectedCategory?.title ?: "All categories", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Default.FilterAlt, null, Modifier.size(18.dp)) },
                trailingIcon = { Icon(if (categoriesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp)) }
            )
            Spacer(Modifier.width(8.dp))
            Text("Filter by category", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        AnimatedVisibility(categoriesExpanded) {
            Surface(Modifier.fillMaxWidth().heightIn(max = 220.dp), shape = RoundedCornerShape(18.dp), color = Color(0xFF111827)) {
                FlowRow(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selected = state.searchCategoryId == "*", onClick = { setCategory("*"); categoriesExpanded = false }, modifier = Modifier.remoteFocusFrame(CircleShape), label = { Text("All categories") })
                    state.searchCategories.filter { it.id != "*" }.forEach { category ->
                        FilterChip(selected = state.searchCategoryId == category.id, onClick = { setCategory(category.id); categoriesExpanded = false }, modifier = Modifier.remoteFocusFrame(CircleShape), label = { Text(category.title) })
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = setQuery,
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                .onFocusChanged {
                    if (it.isFocused && !searchIsTv) searchEditing = true
                    if (!it.isFocused && searchEditing) {
                        searchEditing = false
                        keyboard?.hide()
                    }
                }
                .onPreviewKeyEvent { event ->
                    if (!searchEditing && event.type == KeyEventType.KeyUp &&
                        event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
                    ) {
                        activateSearchField(); true
                    } else false
                }
                .pointerInput(searchEditing, searchIsTv) {
                    if (searchIsTv && !searchEditing) detectTapGestures { activateSearchField() }
                }
                .remoteFocusFrame(RoundedCornerShape(24.dp)),
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            placeholder = { Text("Search ${state.searchType.title.lowercase()}") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                Row {
                    if (state.searchQuery.isNotEmpty()) IconButton(onClick = { setQuery("") }) { Icon(Icons.Default.Close, "Clear") }
                    FilledIconButton(onClick = { search(false) }, enabled = state.searchQuery.isNotBlank() && !state.searchServerLoading) {
                        Icon(Icons.Default.ArrowForward, "Search")
                    }
                }
            },
            readOnly = searchIsTv && !searchEditing,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                searchEditing = false
                keyboard?.hide()
                if (state.searchQuery.isNotBlank()) search(false)
            }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF111827),
                unfocusedContainerColor = Color(0xFF111827),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFFE50914),
                cursorColor = Color(0xFFE50914)
            )
        )
        if (state.recentSearches.isNotEmpty() && state.searchResults.isEmpty()) {
            Text("Recent searches", Modifier.padding(top = 16.dp, bottom = 6.dp), style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                state.recentSearches.filter { it.type in visibleSearchTypes }.forEach { recent ->
                    val recentShape = RoundedCornerShape(10.dp)
                    InputChip(
                        selected = false,
                        onClick = { useRecent(recent) },
                        modifier = Modifier.remoteFocusFrame(recentShape),
                        shape = recentShape,
                        label = {
                            Column {
                                Text(recent.query, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${recent.type.title} · ${recent.categoryTitle}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        colors = InputChipDefaults.inputChipColors(containerColor = Color(0xFF111827), labelColor = Color.LightGray),
                        trailingIcon = {
                            Icon(Icons.Default.Close, "Delete ${recent.query}", Modifier.size(18.dp).clickable { deleteRecent(recent) })
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (state.searchServerLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.searchQuery.isNotBlank() && state.searchResults.isEmpty() && !state.searchServerLoading) {
            Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("No cached results", style = MaterialTheme.typography.titleMedium)
                Text("Search the portal once for this title. Requests are rate-limited for account safety.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { search(true) }, modifier = Modifier.remoteFocusFrame(), border = BorderStroke(1.dp, Color.Gray)) { Icon(Icons.Default.CloudDownload, null); Spacer(Modifier.width(8.dp)); Text("Search server") }
            }
        }
        if (state.searchResults.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${state.searchResults.size} results${if (state.searchUsedServer) " · through page ${state.searchPage}" else " · cached"}", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { search(true) }, modifier = Modifier.remoteFocusFrame(), enabled = !state.searchServerLoading, border = BorderStroke(1.dp, Color.Gray)) { Text("Search server") }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.searchResults, key = { "search-${state.searchType}-${it.id}" }) { item ->
                    val category = state.searchCategories.firstOrNull { it.id == item.portalCategoryId }?.title
                        ?: state.searchCategories.firstOrNull { it.id == state.searchCategoryId }?.title
                    ModernSearchResultRow(
                        item = item,
                        type = state.searchType,
                        categoryTitle = category,
                        isFavorite = state.favorites.any { favorite ->
                            favorite.media.id == item.id && favorite.kind == state.searchType.favoriteKind()
                        },
                        toggleFavorite = {
                            toggleFavorite(FavoriteItem(
                                kind = state.searchType.favoriteKind(),
                                media = item,
                                categoryTitle = category
                            ))
                        },
                        onClick = { openResult(item) }
                    )
                }
                if (state.searchHasMore) item("load-more-${state.searchPage}") {
                    OutlinedButton(onClick = loadMore, enabled = !state.searchServerLoading, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).remoteFocusFrame()) {
                        if (state.searchServerLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.ExpandMore, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Load up to 3 more pages")
                    }
                } else if (state.searchUsedServer) item("all-pages-loaded") {
                    Text("All available result pages loaded", Modifier.fillMaxWidth().padding(16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModernSearchResultRow(
    item: MediaItem,
    type: SearchContentType,
    categoryTitle: String?,
    isFavorite: Boolean,
    toggleFavorite: () -> Unit,
    onClick: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val artworkModel = remember(item.id, item.title, item.logo) { artworkRequest(context, item) }
    val typeLabel = when (type) {
        SearchContentType.LIVE_TV -> "Live TV"
        SearchContentType.MOVIES -> "Movie"
        SearchContentType.SERIES -> "Series"
        SearchContentType.EPISODES -> "Episode"
    }
    val supportingText = item.liveProgramme?.title?.takeIf { it.isNotBlank() } ?: item.description?.takeIf { it.isNotBlank() }
    val compact = LocalConfiguration.current.screenWidthDp < 600
    val shape = RoundedCornerShape(12.dp)
    Box {
    Surface(
        modifier = Modifier.fillMaxWidth().remoteFocusFrame(shape)
            .remoteCombinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
        shape = shape, color = Color(0xFF171717)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                Modifier.width(if (compact) 112.dp else 148.dp)
                    .aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF292929)),
                contentAlignment = Alignment.Center
            ) {
                if (item.logo.isNullOrBlank()) Icon(Icons.Default.SmartDisplay, null, Modifier.size(36.dp), tint = Color.LightGray)
                else SubcomposeAsyncImage(artworkModel, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) {
                    when (painter.state.value) {
                        is coil3.compose.AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                        else -> Icon(Icons.Default.SmartDisplay, null, Modifier.size(36.dp), tint = Color.LightGray)
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SearchMetadataBadge(typeLabel, Color(0xFFE50914))
                    SearchMetadataBadge(
                        categoryTitle?.takeIf { it.isNotBlank() } ?: "Category unavailable",
                        Color(0xFF343434),
                        Modifier.widthIn(max = if (compact) 116.dp else 260.dp)
                    )
                }
                supportingText?.let {
                    Text(
                        if (item.liveProgramme != null) "Now playing: $it" else it,
                        color = Color(0xFFB8B8B8),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Icon(Icons.Default.PlayArrow, "Open ${item.title}", tint = Color.White, modifier = Modifier.size(28.dp))
        }
    }
    DropdownMenu(
        expanded = menuOpen,
        onDismissRequest = { menuOpen = false },
        modifier = Modifier.align(Alignment.TopEnd),
        containerColor = Color(0xFF202020),
        shape = RoundedCornerShape(12.dp)
    ) {
        DropdownMenuItem(
            text = { Text(if (isFavorite) "Remove from My List" else "Add to My List") },
            leadingIcon = { Icon(if (isFavorite) Icons.Default.HeartBroken else Icons.Default.FavoriteBorder, null) },
            onClick = { menuOpen = false; toggleFavorite() }
        )
    }
    }
}

@Composable
private fun SearchMetadataBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(5.dp), color = color) {
        Text(
            text,
            Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ModernSettingsScreen(
    state: NikTvState,
    closeSettings: () -> Unit,
    reauthenticate: () -> Unit,
    editProfile: () -> Unit,
    addProfile: () -> Unit,
    exportBackup: (android.net.Uri) -> Unit,
    importBackup: (android.net.Uri) -> Unit,
    switchProfile: (PortalProfile) -> Unit,
    removeProfile: (PortalProfile) -> Unit,
    logout: () -> Unit,
    setCacheIntervalMinutes: (Int) -> Unit,
    setPlayerControlsTimeoutSeconds: (Int) -> Unit,
    setSeriesStartSeason: (SeriesStartSeason) -> Unit,
    openCategoryManager: (CatalogType) -> Unit
) {
    val profile = state.savedProfile ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    var updateDialogNavigationEnabled by remember { mutableStateOf(false) }
    var restoreVersionFocus by remember { mutableStateOf(false) }
    var pendingPermissionUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    val downloadState by AppUpdates.downloadState.collectAsStateWithLifecycle()
    val pendingUpdate by AppUpdates.pendingUpdate.collectAsStateWithLifecycle()
    val updateEnforcementEnabled by AppUpdates.updateEnforcementEnabled.collectAsStateWithLifecycle()
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
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val update = pendingPermissionUpdate
        if ((granted || AppUpdates.canWritePublicDownloads(context)) && update != null) {
            pendingPermissionUpdate = null
            performDownload(update)
        } else {
            downloadActionMessage =
                "${AppUpdates.PUBLIC_DOWNLOADS_PERMISSION_MESSAGE} Select Allow & download to request it again."
            updateMessage = AppUpdates.PUBLIC_DOWNLOADS_PERMISSION_MESSAGE
        }
    }
    fun requestUpdateDownload(update: UpdateInfo) {
        if (AppUpdates.canWritePublicDownloads(context)) {
            performDownload(update)
        } else {
            pendingPermissionUpdate = update
            downloadActionMessage = AppUpdates.PUBLIC_DOWNLOADS_PERMISSION_MESSAGE
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
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
            updateMessage ?: "Updates are checked on startup and every 24 hours"
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
    Scaffold(
        modifier = Modifier.fillMaxSize().background(Color(0xFF090909)),
        containerColor = Color(0xFF090909),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            ModernScreenTopBar("Settings", closeSettings)
        }
    ) { padding -> Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SettingsSection("Profiles") {
            state.profiles.forEachIndexed { index, saved ->
                ListItem(
                    headlineContent = { Text(saved.name) },
                    supportingContent = { Text("${saved.portalType.displayName()} · ${saved.portalUrl}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(if (saved.portalType == PortalType.STALKER) Icons.Default.Tv else Icons.Default.Key, null) },
                    trailingContent = {
                        Row {
                            if (saved == profile) Icon(Icons.Default.CheckCircle, "Active", tint = MaterialTheme.colorScheme.primary)
                            else TextButton(onClick = { switchProfile(saved) }, modifier = Modifier.remoteFocusFrame()) { Text("Open") }
                            IconButton(onClick = { pendingRemoval = saved }, modifier = Modifier.remoteFocusFrame(CircleShape)) { Icon(Icons.Default.DeleteOutline, "Remove ${saved.name}") }
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
                modifier = Modifier.remoteFocusFrame().clickable(onClick = addProfile),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
        SettingsSection("Category Filters") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        OrientationSettingsSection()

        PlaybackDesignSettingsSection(profile.cacheKey())

        SettingsSection("Connection") {
            SettingsValueRow(Icons.Default.AccountCircle, "Profile", profile.name)
            HorizontalDivider()
            SettingsValueRow(Icons.Default.Language, "Portal", profile.portalUrl)
            HorizontalDivider()
            SettingsValueRow(Icons.Default.Security, "Session", if (state.session != null) "Authenticated" else "Authentication required")
            HorizontalDivider()
            SettingsValueRow(Icons.Default.Wifi, "Device MAC Address", deviceMacAddress)
        }
        SettingsSection("Backup and restore") {
            Text(
                "Backup files contain your portal addresses and credentials. Store them securely.",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Export NikTV setup") },
                supportingContent = { Text("Save profiles, credentials, filters, preferences, favorites, and viewing progress") },
                leadingContent = { Icon(Icons.Default.FileUpload, null) },
                modifier = Modifier.remoteFocusFrame().clickable {
                    val timestamp = java.text.SimpleDateFormat(
                        "yyyyMMdd-HHmmss",
                        java.util.Locale.getDefault()
                    ).format(java.util.Date())
                    exportLauncher.launch("NikTV-${BuildConfig.VERSION_NAME}-$timestamp-backup.json")
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Import NikTV setup") },
                supportingContent = { Text("Restore a backup from another TV; profiles authenticate with fresh sessions") },
                leadingContent = { Icon(Icons.Default.FileDownload, null) },
                modifier = Modifier.remoteFocusFrame().clickable {
                    importLauncher.launch(arrayOf("application/json", "text/json", "text/plain"))
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
        SettingsSection("Account actions") {
            ListItem(
                headlineContent = { Text("Re-authenticate") },
                supportingContent = { Text("Request a fresh session token using the saved profile") },
                leadingContent = { Icon(Icons.Default.Refresh, null) },
                modifier = Modifier.remoteFocusFrame().clickable(onClick = reauthenticate),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Edit connection") },
                supportingContent = { Text("Change portal address or credentials") },
                leadingContent = { Icon(Icons.Default.Edit, null) },
                modifier = Modifier.remoteFocusFrame().clickable(onClick = editProfile),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Clear all app data", color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text("Remove every profile, cache, favorite, recent item, and session") },
                leadingContent = { Icon(Icons.Default.Logout, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.remoteFocusFrame().clickable(onClick = logout),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
        SettingsSection("Catalog cache") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            modifier = Modifier.remoteFocusFrame(RoundedCornerShape(16.dp))
                        )
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
                    modifier = Modifier.focusRequester(versionRequester).remoteFocusFrame().clickable(enabled = !checkingUpdate) {
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
        SettingsSection("Player controls") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Hide controls after", style = MaterialTheme.typography.titleMedium)
                Text(
                    "While video is playing, controls automatically disappear after this period of inactivity.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(3 to "3s", 5 to "5s", 10 to "10s", 15 to "15s").forEachIndexed { index, (seconds, label) ->
                        val timeoutShape = uniformSegmentShape(index, 4)
                        SegmentedButton(
                            selected = state.playerControlsTimeoutSeconds == seconds,
                            onClick = { setPlayerControlsTimeoutSeconds(seconds) },
                            modifier = Modifier.remoteFocusFrame(timeoutShape),
                            shape = timeoutShape
                        ) { Text(label) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTvPlaybackScreen(
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
    loadMoreCatalog: () -> Unit
) {
    val playing = state.nowPlaying ?: return

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
            state.items.indexOfFirst {
                it.id == playing.media.id
            }

        val playingChannel =
            state.items.getOrNull(
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
            state.items.indexOfFirst {
                it.id == playing.media.id
            }

        val playingChannel =
            state.items.getOrNull(
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
        state.catalogLoadingMore,
        state.items.size
    ) {
        if (!loadMorePending) {
            return@LaunchedEffect
        }

        /*
         * Loading started.
         *
         * Leave focus on Load More.
         */
        if (state.catalogLoadingMore) {
            loadMoreObservedLoading = true
            return@LaunchedEffect
        }

        val receivedNewChannels =
            state.items.size >
                    loadMoreStartItemCount

        val loadFinished =
            loadMoreObservedLoading ||
                    receivedNewChannels

        if (!loadFinished) {
            return@LaunchedEffect
        }

        if (receivedNewChannels) {
            val firstNewChannel =
                state.items.getOrNull(
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

            modifier =
                if (fullscreen) {
                    Modifier.fillMaxSize()
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
                modifier = Modifier
                    .align(
                        Alignment.CenterEnd
                    )
                    .fillMaxWidth(
                        channelWidthFraction
                    )
                    .fillMaxHeight()
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
                            WindowInsetsSides.Top +
                                    WindowInsetsSides.End +
                                    WindowInsetsSides.Bottom
                        )
                    )
                    .padding(
                        top = 8.dp,
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
                            horizontal = 6.dp
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
                            MaterialTheme.typography.labelSmall,

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
                            MaterialTheme.typography.titleMedium,

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
                                ?: "${state.items.size} channels",

                        color =
                            Color.LightGray,

                        style =
                            MaterialTheme.typography.bodySmall,

                        maxLines = 1,

                        overflow =
                            TextOverflow.Ellipsis
                    )
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
                            state.items,

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
                        state.catalogHasMore ||
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
                                        state.catalogLoadingMore ||
                                        loadMorePending
                                    ) {
                                        return@Button
                                    }

                                    /*
                                     * The first new channel will appear
                                     * at this index after append.
                                     */
                                    loadMoreStartItemCount =
                                        state.items.size

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
                                        true

                                    loadMoreCatalog()
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
                                    state.catalogLoadingMore ||
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
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF111827),
            content = { Column(content = content) }
        )
    }
}

@Composable
private fun SettingsValueRow(icon: ImageVector, label: String, value: String) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value, maxLines = 2) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

private fun CatalogType.icon() = when (this) { CatalogType.LIVE_TV -> Icons.Default.LiveTv; CatalogType.MOVIES -> Icons.Default.Movie; CatalogType.SERIES -> Icons.Default.VideoLibrary; CatalogType.RADIO -> Icons.Default.Radio }
private fun CatalogType.favoriteKind() = when (this) {
    CatalogType.LIVE_TV, CatalogType.RADIO -> FavoriteKind.CHANNEL
    CatalogType.MOVIES -> FavoriteKind.MOVIE
    CatalogType.SERIES -> FavoriteKind.SERIES
}
private fun PortalType.displayName() = when (this) { PortalType.STALKER -> "Stalker / MAG"; PortalType.XTREAM -> "Xtream Codes" }
private fun String.isAuthorizationFailureText() =
    contains("Authorization failed", ignoreCase = true) || contains("HTTP status: 401") || contains("HTTP status: 403")
private fun CatalogType.itemLabel(count: Int) = when (this) {
    CatalogType.LIVE_TV -> if (count == 1) "channel" else "channels"
    CatalogType.MOVIES -> if (count == 1) "movie" else "movies"
    CatalogType.SERIES -> if (count == 1) "series" else "series"
    CatalogType.RADIO -> if (count == 1) "station" else "stations"
}
private fun FavoriteKind.sectionTitle() = when (this) {
    FavoriteKind.CHANNEL -> "Channels"
    FavoriteKind.MOVIE -> "Movies"
    FavoriteKind.SERIES -> "Series"
    FavoriteKind.EPISODE -> "Episodes"
}
private fun FavoriteKind.mediaTypeLabel() = when (this) {
    FavoriteKind.CHANNEL -> "Live TV"
    FavoriteKind.MOVIE -> "Movie"
    FavoriteKind.SERIES -> "Series"
    FavoriteKind.EPISODE -> "Episode"
}
private fun String.episodeNumberFromTitle(): Int? {
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

private fun MediaItem.actionEpisodeLabel(): String {
    val season = seasonNumber ?: title.seasonNumberFromTitle()
    val episode = episodeNumber ?: title.episodeNumberFromTitle()
    return when {
        season != null && episode != null -> "S$season · Episode $episode"
        episode != null -> "Episode $episode"
        else -> title.replace(Regex("[,.|]\\s*\\d{4}[-/]\\d{1,2}.*$"), "")
            .trim().ifBlank { title }.take(28).trimEnd('.', ',', '-', ' ')
    }
}

private fun String.seasonNumberFromTitle(): Int? {
    val patterns = listOf(
        Regex("(?i)S(?:EASON)?[ ._-]*(\\d+)"),
        Regex("(?i)S(\\d+)[ ._-]*E"),
        Regex("(?i)Season[ ._-]*(\\d+)")
    )
    return patterns.firstNotNullOfOrNull { pattern ->
        pattern.findAll(this).firstOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()
    }
}

private fun naturalTitleCompare(first: String, second: String): Int {
    val tokenPattern = Regex("\\d+|\\D+")
    val firstTokens = tokenPattern.findAll(first.lowercase()).map { it.value }.toList()
    val secondTokens = tokenPattern.findAll(second.lowercase()).map { it.value }.toList()
    for (index in 0 until minOf(firstTokens.size, secondTokens.size)) {
        val firstToken = firstTokens[index]
        val secondToken = secondTokens[index]
        val comparison = if (firstToken.all(Char::isDigit) && secondToken.all(Char::isDigit))
            (firstToken.toLongOrNull() ?: Long.MAX_VALUE).compareTo(secondToken.toLongOrNull() ?: Long.MAX_VALUE)
        else firstToken.compareTo(secondToken)
        if (comparison != 0) return comparison
    }
    return firstTokens.size.compareTo(secondTokens.size)
}

private fun episodeComparator(descending: Boolean): Comparator<MediaItem> = Comparator { first, second ->
    val firstSeason = first.seasonNumber ?: first.title.seasonNumberFromTitle()
    val secondSeason = second.seasonNumber ?: second.title.seasonNumberFromTitle()
    val seasonComp = when {
        firstSeason != null && secondSeason != null && firstSeason != secondSeason -> firstSeason.compareTo(secondSeason)
        firstSeason != null && secondSeason == null -> 1
        firstSeason == null && secondSeason != null -> -1
        else -> 0
    }
    if (seasonComp != 0) {
        return@Comparator if (descending) -seasonComp else seasonComp
    }
    val firstEp = first.episodeNumber ?: first.title.episodeNumberFromTitle()
    val secondEp = second.episodeNumber ?: second.title.episodeNumberFromTitle()
    val epComp = when {
        firstEp != null && secondEp != null && firstEp != secondEp -> firstEp.compareTo(secondEp)
        firstEp != null && secondEp == null -> 1
        firstEp == null && secondEp != null -> -1
        else -> naturalTitleCompare(first.title, second.title)
    }
    if (descending) -epComp else epComp
}

private fun cast4kStyleDeviceMacAddress(context: android.content.Context): String {
    return cast4kLegacyDeviceIdentity(context).macAddress
}
private fun SearchContentType.favoriteKind() = when (this) {
    SearchContentType.LIVE_TV -> FavoriteKind.CHANNEL
    SearchContentType.MOVIES -> FavoriteKind.MOVIE
    SearchContentType.SERIES -> FavoriteKind.SERIES
    SearchContentType.EPISODES -> FavoriteKind.EPISODE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryManagerDialog(
    state: NikTvState,
    close: () -> Unit,
    setType: (CatalogType) -> Unit,
    toggleCategory: (CatalogType, String) -> Unit,
    selectAll: (CatalogType) -> Unit,
    deselectAll: (CatalogType) -> Unit,
    setFilter: (CatalogType, List<String>) -> Unit
) {
    val type = state.categoryManagerType
    val profile = state.savedProfile
    val profileKey = profile?.cacheKey()
    val raw = state.rawCategoriesByType[type].orEmpty().ifEmpty { if (state.selectedType == type) state.categories else emptyList() }
    val filterKey = "$profileKey|${type.name}"
    val enabledIds = state.categoryFilters[filterKey]
    val currentEnabledSet = remember(enabledIds, raw) {
        enabledIds?.toSet() ?: raw.map { it.id }.toSet()
    }
    var searchQuery by rememberSaveable(type) { mutableStateOf("") }
    val filteredRaw = remember(raw, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) raw else raw.filter { it.title.matchesTitleKeywords(query) }
            .sortedByDescending { it.title.titleKeywordScore(query) }
    }
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val gridState = rememberLazyGridState()
    val categoryConfiguration = LocalConfiguration.current
    val categoryContext = LocalContext.current
    val categoryIsTv = categoryContext.isTvLikeDevice(categoryConfiguration)
    val categoryColumns = when {
        categoryIsTv || categoryConfiguration.screenWidthDp >= 840 -> 4
        categoryConfiguration.screenWidthDp >= 600 -> 2
        else -> 1
    }
    val closeRequester = remember { FocusRequester() }
    val typeRequesters = remember { visibleCatalogTypes.associateWith { FocusRequester() } }
    val searchRequester = remember { FocusRequester() }
    val selectAllRequester = remember { FocusRequester() }
    val deselectAllRequester = remember { FocusRequester() }
    val selectMatchingRequester = remember { FocusRequester() }
    val applyRequester = remember { FocusRequester() }
    val categoryRequesters = remember { mutableMapOf<String, FocusRequester>() }
    filteredRaw.forEach { categoryRequesters.getOrPut(it.id) { FocusRequester() } }
    val firstCategoryRequester = filteredRaw.firstOrNull()?.let { categoryRequesters[it.id] }
    val lastCategoryRequester = filteredRaw.lastOrNull()?.let { categoryRequesters[it.id] }
    var searchEditing by remember(type) { mutableStateOf(false) }
    var searchFocused by remember(type) { mutableStateOf(false) }
    var closeFocused by remember { mutableStateOf(false) }
    var applyFocused by remember { mutableStateOf(false) }
    var focusedCategoryId by remember { mutableStateOf<String?>(null) }
    val activateSearch: () -> Unit = {
        if (!searchEditing) {
            searchEditing = true
            scope.launch {
                withFrameNanos { }
                searchRequester.requestFocus()
                keyboardController?.show()
            }
        }
    }

    LaunchedEffect(Unit) {
        typeRequesters.getValue(type).requestFocus()
    }
    LaunchedEffect(type, searchQuery) {
        if (filteredRaw.isNotEmpty()) gridState.scrollToItem(0)
    }
    BackHandler(searchEditing) {
        searchEditing = false
        keyboardController?.hide()
        searchRequester.requestFocus()
    }

    Dialog(
        onDismissRequest = {
            if (searchEditing) {
                searchEditing = false
                keyboardController?.hide()
            } else {
                close()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val categoryPanelModifier = if (categoryIsTv) {
            Modifier.fillMaxWidth(0.97f).fillMaxHeight(0.96f).widthIn(max = 1280.dp)
        } else {
            Modifier.fillMaxSize()
        }
        Surface(
            modifier = categoryPanelModifier,
            shape = if (categoryIsTv) RoundedCornerShape(18.dp) else RoundedCornerShape(0.dp),
            color = Color.Black,
            tonalElevation = if (categoryIsTv) 6.dp else 0.dp
        ) {
            Column(
                Modifier.fillMaxSize()
                    .then(if (!categoryIsTv) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Category Filters", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Selections save immediately · Back also closes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(
                        onClick = close,
                        modifier = Modifier
                            .height(48.dp)
                            .focusRequester(applyRequester)
                            .onFocusChanged { applyFocused = it.isFocused }
                            .border(
                                if (applyFocused) 3.dp else 0.dp,
                                if (applyFocused) MaterialTheme.colorScheme.onPrimary else Color.Transparent,
                                RoundedCornerShape(14.dp)
                            ),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914), contentColor = Color.White)
                    ) { Text("Apply & Close", color = Color.White, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = close,
                        modifier = Modifier
                            .size(48.dp)
                            .focusRequester(closeRequester)
                            .focusProperties {
                                left = typeRequesters.getValue(visibleCatalogTypes.last())
                                down = searchRequester
                            }
                            .onFocusChanged { closeFocused = it.isFocused }
                            .background(
                                if (closeFocused) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                CircleShape
                            )
                    ) { Icon(Icons.Default.Close, "Close") }
                }

                Spacer(Modifier.height(8.dp))

                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    visibleCatalogTypes.forEachIndexed { index, catalogType ->
                        val shape = uniformSegmentShape(index, visibleCatalogTypes.size)
                        SegmentedButton(
                            selected = type == catalogType,
                            onClick = { setType(catalogType) },
                            shape = shape,
                            modifier = Modifier
                                .focusRequester(typeRequesters.getValue(catalogType))
                                .focusProperties {
                                    left = if (index > 0) typeRequesters.getValue(visibleCatalogTypes[index - 1])
                                        else FocusRequester.Cancel
                                    right = if (index < visibleCatalogTypes.lastIndex) {
                                        typeRequesters.getValue(visibleCatalogTypes[index + 1])
                                    } else closeRequester
                                    up = closeRequester
                                    down = firstCategoryRequester ?: searchRequester
                                }
                                .remoteFocusFrame(shape)
                        ) { Text(catalogType.title, maxLines = 1) }
                    }
                }

                val enabledCount = currentEnabledSet.size
                val totalCount = raw.size
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "${filteredRaw.size} matching · $enabledCount of $totalCount active" else "$enabledCount of $totalCount active",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (searchQuery.isNotBlank() && filteredRaw.isNotEmpty()) {
                        TextButton(onClick = {
                            setFilter(type, (currentEnabledSet + filteredRaw.map { it.id }).toList())
                        }, modifier = Modifier.heightIn(min = 64.dp).focusRequester(selectMatchingRequester)) { Text("Select matching") }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 3.dp))

                if (raw.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator()
                            Text("Loading categories…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else if (filteredRaw.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No categories match “${searchQuery.trim()}”", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyVerticalGrid(
                        modifier = Modifier.weight(1f),
                        columns = GridCells.Fixed(categoryColumns),
                        state = gridState,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(vertical = 6.dp)
                    ) {
                        itemsIndexed(filteredRaw, key = { _, category -> category.id }) { index, category ->
                            val selected = category.id in currentEnabledSet
                            val rowRequester = categoryRequesters.getValue(category.id)
                            val rowFocused = focusedCategoryId == category.id
                            Surface(
                                onClick = { toggleCategory(type, category.id) },
                                shape = RoundedCornerShape(12.dp),
                                color = when {
                                    rowFocused -> Color(0xFF292929)
                                    selected -> Color(0xFF351416)
                                    else -> Color(0xFF171717)
                                },
                                border = when {
                                    rowFocused -> BorderStroke(4.dp, Color(0xFFFF3340))
                                    selected -> BorderStroke(1.dp, Color(0xFFE50914).copy(alpha = 0.75f))
                                    else -> BorderStroke(1.dp, Color(0xFF303030))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                                    .focusRequester(rowRequester)
                                    .focusProperties {
                                        if (index < categoryColumns) up = typeRequesters.getValue(type)
                                        if (index >= filteredRaw.size - categoryColumns) down = searchRequester
                                    }
                                    .onFocusChanged {
                                        if (it.isFocused) {
                                            focusedCategoryId = category.id
                                            scope.launch {
                                                if (gridState.layoutInfo.visibleItemsInfo.none { item -> item.index == index }) {
                                                    gridState.animateScrollToItem(index)
                                                }
                                            }
                                        } else if (focusedCategoryId == category.id) {
                                            focusedCategoryId = null
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(44.dp)
                                        .padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        Modifier.size(20.dp).clip(CircleShape)
                                            .background(if (selected) Color(0xFFE50914) else Color.Transparent)
                                            .border(1.dp, if (selected) Color(0xFFE50914) else Color(0xFF777777), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (selected) Icon(Icons.Default.Check, null, Modifier.size(14.dp), tint = Color.White)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = category.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).then(
                                            if (rowFocused) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1.55f)) {
                        Surface(
                            Modifier.fillMaxWidth().height(52.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = Color.Black,
                            border = BorderStroke(
                                if (searchEditing || searchFocused) 4.dp else 1.dp,
                                if (searchEditing || searchFocused) Color(0xFFFF3340) else Color(0xFF666666)
                            )
                        ) {
                            Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = Color.LightGray)
                                Spacer(Modifier.width(6.dp))
                                BasicTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    modifier = Modifier.weight(1f)
                                        .focusRequester(searchRequester)
                                        .focusProperties {
                                            up = lastCategoryRequester ?: typeRequesters.getValue(type)
                                            right = selectAllRequester
                                            down = applyRequester
                                        }
                                        .onFocusChanged {
                                            if (it.isFocused && !categoryIsTv) searchEditing = true
                                            searchFocused = it.isFocused
                                            if (!it.isFocused && searchEditing) {
                                                searchEditing = false
                                                keyboardController?.hide()
                                            }
                                        }
                                        .onPreviewKeyEvent { event ->
                                            if (!searchEditing && event.type == KeyEventType.KeyUp &&
                                                event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
                                            ) {
                                                activateSearch(); true
                                            } else false
                                        },
                                    singleLine = true,
                                    readOnly = categoryIsTv && !searchEditing,
                                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                                    cursorBrush = SolidColor(Color(0xFFE50914)),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                    keyboardActions = KeyboardActions(onDone = {
                                        searchEditing = false
                                        keyboardController?.hide()
                                    }),
                                    decorationBox = { inner ->
                                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                                            if (searchQuery.isEmpty()) Text("Search categories", color = Color.Gray, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                                            inner()
                                        }
                                    }
                                )
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }, modifier = Modifier.focusProperties { canFocus = false }) {
                                        Icon(Icons.Default.Close, "Clear search", tint = Color.LightGray)
                                    }
                                }
                            }
                        }
                        if (categoryIsTv && !searchEditing) {
                            Box(Modifier.matchParentSize().pointerInput(type) { detectTapGestures { activateSearch() } })
                        }
                    }
                    CategoryDialogActionButton(
                        text = "Select All",
                        onClick = { selectAll(type) },
                        modifier = Modifier.weight(1f).focusRequester(selectAllRequester).focusProperties {
                            left = searchRequester
                            right = deselectAllRequester
                            up = lastCategoryRequester ?: typeRequesters.getValue(type)
                            down = applyRequester
                        }
                    )
                    CategoryDialogActionButton(
                        text = "Deselect All",
                        onClick = { deselectAll(type) },
                        modifier = Modifier.weight(1f).focusRequester(deselectAllRequester).focusProperties {
                            left = selectAllRequester
                            right = FocusRequester.Cancel
                            up = lastCategoryRequester ?: typeRequesters.getValue(type)
                            down = applyRequester
                        }
                    )
                }

            }

        }
    }
}

@Composable
private fun CategoryDialogActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .height(52.dp)
            .onFocusChanged { focused = it.isFocused },
        border = BorderStroke(
            if (focused) 3.dp else 1.dp,
            if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (focused) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
        ),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ModernSeriesDetailScreen(
    state: NikTvState,
    play: (MediaItem) -> Unit,
    closeSeries: () -> Unit,
    toggleFavorite: (MediaItem) -> Unit,
    toggleSeriesWatch: () -> Unit,
    loadSeriesSeason: (Int) -> Unit,
    openSearch: () -> Unit,
    openSettings: () -> Unit,
    refreshCatalog: () -> Unit,
    loadMoreEpisodes: () -> Unit
) {
    val series = state.selectedSeries ?: return
    var episodeSortDescending by rememberSaveable(series.id) { mutableStateOf(true) }
    var searchQuery by rememberSaveable(series.id) { mutableStateOf("") }
    var episodeSearchEditing by rememberSaveable(series.id) { mutableStateOf(false) }
    var seasonDropdownExpanded by remember { mutableStateOf(false) }
    val episodeSearchRequester = remember(series.id) { FocusRequester() }
    val returningEpisodeRequester = remember(series.id) { FocusRequester() }
    val episodeListState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current
    val episodeConfiguration = LocalConfiguration.current
    val compactPortrait = episodeConfiguration.screenWidthDp < 600 &&
        episodeConfiguration.orientation == Configuration.ORIENTATION_PORTRAIT

    fun activateEpisodeSearch() {
        episodeSearchEditing = true
        episodeSearchRequester.requestFocus()
        keyboardController?.show()
    }

    val isFavorite = remember(state.favorites, series) {
        state.favorites.any { it.media.id == series.id && it.kind == FavoriteKind.SERIES }
    }
    val isWatched = state.watchedSeries.any { it.series.id == series.id }

    val availableSeasons = state.availableSeriesSeasons
    val selectedSeason = state.selectedSeriesSeason

    val comparator = remember(episodeSortDescending) {
        episodeComparator(episodeSortDescending)
    }

    val seasonFilteredItems = remember(state.items, selectedSeason) {
        state.items
    }

    val filteredEpisodes = remember(seasonFilteredItems, searchQuery, comparator, episodeSortDescending) {
        val query = searchQuery.trim()
        val baseList = if (query.isBlank()) seasonFilteredItems else {
            seasonFilteredItems.filter { ep -> ep.title.matchesTitleKeywords(query) || ep.episodeNumber?.toString() == query }
                .sortedByDescending { it.title.titleKeywordScore(query) }
        }
        if (selectedSeason != null) {
            baseList.sortedWith(comparator)
        } else {
            val seasonComp = if (episodeSortDescending) {
                compareByDescending<Map.Entry<Int?, List<MediaItem>>> { it.key ?: -1 }
            } else {
                compareBy<Map.Entry<Int?, List<MediaItem>>>({ it.key == null }, { it.key ?: Int.MAX_VALUE })
            }
            baseList.groupBy { it.seasonNumber }.entries
                .sortedWith(seasonComp)
                .flatMap { (_, eps) -> eps.sortedWith(comparator) }
        }
    }

    val recentEpisode = remember(state.recentlyPlayed, state.playbackProgress, state.items, series) {
        val recentWatched = state.recentlyPlayed.firstOrNull { recent ->
            recent.kind == FavoriteKind.SERIES && (recent.media.id == series.id || recent.series?.id == series.id)
        }?.lastPlayed
            ?.let { last -> state.items.firstOrNull { it.id == last.id } }
        if (recentWatched != null) return@remember recentWatched
        val lastProgress = state.playbackProgress
            .filter { prog -> prog.key.contains(series.id) }
            .maxByOrNull { it.updatedAtMillis }
        if (lastProgress != null) {
            state.items.firstOrNull { it.id in lastProgress.key }
        } else null
    }

    val latestEpisode = state.items.maxWithOrNull(
        compareBy<MediaItem>({ it.seasonNumber ?: it.title.seasonNumberFromTitle() ?: 0 },
            { it.episodeNumber ?: it.title.episodeNumberFromTitle() ?: 0 }, { it.title })
    )
    val primaryEpisodeToPlay = recentEpisode ?: if (episodeSortDescending) latestEpisode ?: state.items.firstOrNull()
        else state.items.firstOrNull()

    LaunchedEffect(state.playbackReturnFocusId, filteredEpisodes) {
        val returningId = state.playbackReturnFocusId ?: return@LaunchedEffect
        val episodeIndex = filteredEpisodes.indexOfFirst { it.id == returningId }
        if (episodeIndex >= 0) {
            episodeListState.scrollToItem(episodeIndex + 2)
            delay(120L)
            runCatching { returningEpisodeRequester.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF090909))) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = episodeListState,
            contentPadding = PaddingValues(bottom = 64.dp)
        ) {
            item("series-hero") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (compactPortrait) 470.dp else 340.dp)
                ) {
                    val backdropUrl = series.logo
                    if (!backdropUrl.isNullOrBlank()) {
                        SubcomposeAsyncImage(
                            model = artworkRequest(LocalContext.current, series),
                            contentDescription = series.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                                .background(Brush.radialGradient(listOf(Color(0xFF1E293B), Color(0xFF090909))))
                        )
                    }

                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color(0x99000000),
                                        Color(0x22000000),
                                        Color(0xDD090909),
                                        Color(0xFF090909)
                                    )
                                )
                            )
                    )

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = closeSeries,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).remoteFocusFrame(CircleShape)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to browse", tint = Color.White)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(
                            onClick = openSearch,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).remoteFocusFrame(CircleShape)
                        ) {
                            Icon(Icons.Default.Search, "Search", tint = Color.White)
                        }
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = openSettings,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape).remoteFocusFrame(CircleShape)
                        ) {
                            Icon(Icons.Default.Settings, "Settings", tint = Color.White)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = series.title,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                            ) {
                                Text(
                                    text = "SERIES",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            if (availableSeasons.isNotEmpty()) {
                                Text(
                                    text = "${availableSeasons.size} ${if (availableSeasons.size == 1) "Season" else "Seasons"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.LightGray
                                )
                                Text("•", color = Color.Gray)
                            }
                            Text(
                                text = "${state.items.size} Episodes${selectedSeason?.let { " in Season $it" }.orEmpty()}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.LightGray
                            )
                        }

                        if (!series.description.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = series.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.8f),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (primaryEpisodeToPlay != null) {
                                Button(
                                    onClick = { play(primaryEpisodeToPlay) },
                                    modifier = Modifier.remoteFocusFrame(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color.White,
                                        contentColor = Color.Black
                                    ),
                                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, null, Modifier.size(24.dp))
                                    Spacer(Modifier.width(8.dp))
                                    val playLabel = when {
                                        recentEpisode != null -> "Resume (${recentEpisode.actionEpisodeLabel()})"
                                        episodeSortDescending -> "Play Latest (${primaryEpisodeToPlay.actionEpisodeLabel()})"
                                        else -> "Play First Episode"
                                    }
                                    Text(playLabel, fontWeight = FontWeight.Bold)
                                }
                            }

                            if (recentEpisode != null && latestEpisode != null && recentEpisode.id != latestEpisode.id) {
                                FilledTonalButton(
                                    onClick = { play(latestEpisode) },
                                    modifier = Modifier.remoteFocusFrame(),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = Color.White.copy(alpha = 0.15f),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(Icons.Default.SkipNext, null, Modifier.size(22.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Play Latest (${latestEpisode.actionEpisodeLabel()})")
                                }
                            }

                            FilledTonalButton(
                                onClick = { toggleFavorite(series) },
                                modifier = Modifier.remoteFocusFrame(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (isFavorite) MaterialTheme.colorScheme.primaryContainer else Color.White.copy(alpha = 0.15f),
                                    contentColor = if (isFavorite) MaterialTheme.colorScheme.onPrimaryContainer else Color.White
                                )
                            ) {
                                Icon(
                                    if (isFavorite) Icons.Default.Check else Icons.Default.Add,
                                    null,
                                    Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(if (isFavorite) "In My List" else "My List")
                            }

                            FilledTonalButton(
                                onClick = toggleSeriesWatch,
                                modifier = Modifier.remoteFocusFrame(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White.copy(alpha = 0.15f), contentColor = Color.White)
                            ) {
                                Icon(if (isWatched) Icons.Default.NotificationsActive else Icons.Default.NotificationsNone, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (isWatched) "Watching" else "Watch updates")
                            }

                            IconButton(
                                onClick = refreshCatalog,
                                modifier = Modifier.background(Color.White.copy(alpha = 0.15f), CircleShape).remoteFocusFrame(CircleShape)
                            ) {
                                Icon(Icons.Default.Refresh, "Refresh episodes", tint = Color.White)
                            }
                        }
                    }
                }
            }

            item("series-controls") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (availableSeasons.size > 1) {
                            Box {
                                Surface(
                                    onClick = { seasonDropdownExpanded = true },
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF1E2430),
                                    contentColor = Color.White,
                                    modifier = Modifier.height(42.dp).remoteFocusFrame()
                                ) {
                                    Row(
                                        Modifier.padding(horizontal = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = selectedSeason?.let { "Season $it" } ?: "Choose Season",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Icon(Icons.Default.ArrowDropDown, null)
                                    }
                                }

                                DropdownMenu(
                                    expanded = seasonDropdownExpanded,
                                    onDismissRequest = { seasonDropdownExpanded = false }
                                ) {
                                    val orderedSeasons = if (episodeSortDescending) availableSeasons.reversed() else availableSeasons
                                    orderedSeasons.forEach { season ->
                                        DropdownMenuItem(
                                            text = { Text("Season $season") },
                                            onClick = { loadSeriesSeason(season); seasonDropdownExpanded = false },
                                            trailingIcon = if (selectedSeason == season) {{ Icon(Icons.Default.Check, null) }} else null
                                        )
                                    }
                                }
                            }
                        }

                        Surface(
                            onClick = { episodeSortDescending = !episodeSortDescending },
                            shape = RoundedCornerShape(12.dp),
                            color = if (episodeSortDescending) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color(0xFF1E2430),
                            border = BorderStroke(1.dp, if (episodeSortDescending) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent),
                            contentColor = Color.White,
                            modifier = Modifier.height(42.dp).remoteFocusFrame()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (episodeSortDescending) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                    null,
                                    Modifier.size(18.dp),
                                    tint = if (episodeSortDescending) MaterialTheme.colorScheme.primary else Color.White
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (episodeSortDescending) "Latest First" else "Oldest First",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (episodeSortDescending) MaterialTheme.colorScheme.primary else Color.White
                                )
                            }
                        }

                        Spacer(Modifier.weight(1f))

                        Text(
                            text = "${filteredEpisodes.size} episodes",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Gray
                        )
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth()
                            .focusRequester(episodeSearchRequester)
                            .onFocusChanged {
                                if (!it.isFocused && episodeSearchEditing) {
                                    episodeSearchEditing = false
                                    keyboardController?.hide()
                                }
                            }
                            .onPreviewKeyEvent { event ->
                                if (!episodeSearchEditing && event.type == KeyEventType.KeyUp &&
                                    event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)
                                ) {
                                    activateEpisodeSearch()
                                    true
                                } else false
                            }
                            .pointerInput(series.id, episodeSearchEditing) {
                                if (!episodeSearchEditing) detectTapGestures { activateEpisodeSearch() }
                            }
                            .remoteFocusFrame(RoundedCornerShape(16.dp)),
                        placeholder = { Text("Search episode name or number…", color = Color.Gray) },
                        leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.LightGray) },
                        trailingIcon = if (searchQuery.isNotEmpty()) {{
                            IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Default.Close, "Clear", tint = Color.LightGray) }
                        }} else null,
                        singleLine = true,
                        readOnly = !episodeSearchEditing,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            episodeSearchEditing = false
                            keyboardController?.hide()
                        }),
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF141822),
                            unfocusedContainerColor = Color(0xFF10141C),
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color(0xFF222B3D),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            }

            if (state.items.isEmpty() && state.loading) {
                item("episodes-loading") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Text("Loading episodes from server…", color = Color.LightGray)
                        }
                    }
                }
            } else if (filteredEpisodes.isEmpty()) {
                item("episodes-empty") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) "No episodes found for this series." else "No episodes matching “${searchQuery.trim()}”",
                            color = Color.LightGray
                        )
                    }
                }
            } else {
                items(filteredEpisodes, key = { it.id }) { episode ->
                    val progress = remember(state.playbackProgress, episode.id) {
                        state.playbackProgress.firstOrNull { prog ->
                            prog.key.contains(episode.id)
                        } ?: state.playbackProgress.firstOrNull { prog ->
                            prog.key.endsWith(":${episode.id}")
                        }
                    }
                    val isRecent = recentEpisode?.id == episode.id

                    ModernEpisodeCard(
                        episode = episode,
                        series = series,
                        progress = progress,
                        isCurrentResume = isRecent,
                        onClick = { play(episode) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (state.playbackReturnFocusId == episode.id) Modifier.focusRequester(returningEpisodeRequester) else Modifier)
                            .padding(horizontal = 24.dp, vertical = 6.dp)
                    )
                }
                if (state.episodeHasMore && searchQuery.isBlank()) {
                    item("episodes-load-more-${state.episodePage}") {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), contentAlignment = Alignment.Center) {
                            Button(
                                onClick = loadMoreEpisodes,
                                enabled = !state.episodeLoadingMore,
                                modifier = Modifier.fillMaxWidth().height(48.dp).remoteFocusFrame(RoundedCornerShape(10.dp)),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914), contentColor = Color.White)
                            ) {
                                if (state.episodeLoadingMore) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                                else Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (state.episodeLoadingMore) "Loading…" else "Load more episodes", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernEpisodeCard(
    episode: MediaItem,
    series: MediaItem,
    progress: PlaybackProgress?,
    isCurrentResume: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val progressFraction = remember(progress) {
        if (progress != null && progress.durationMillis > 0L) {
            (progress.positionMillis.toFloat() / progress.durationMillis.toFloat()).coerceIn(0f, 1f)
        } else 0f
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (focused) Color(0xFF321417) else if (isCurrentResume) Color(0xFF1B2232) else Color(0xFF121620),
        border = when {
            focused -> BorderStroke(4.dp, Color(0xFFFF2633))
            isCurrentResume -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
            else -> null
        },
        shadowElevation = if (focused) 14.dp else 0.dp,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(136.dp)
                    .height(78.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF1E293B)),
                contentAlignment = Alignment.Center
            ) {
                val imageUrl = episode.logo ?: series.logo
                if (!imageUrl.isNullOrBlank()) {
                    SubcomposeAsyncImage(
                        model = artworkRequest(LocalContext.current, episode.copy(logo = imageUrl)),
                        contentDescription = episode.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayCircle,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        tint = Color.White.copy(alpha = 0.9f)
                    )
                }

                if (progressFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.Black.copy(alpha = 0.6f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progressFraction)
                                .background(Color(0xFFE50914))
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val epNumber = episode.episodeNumber ?: episode.title.episodeNumberFromTitle()
                    val seasonNumber = episode.seasonNumber ?: episode.title.seasonNumberFromTitle()
                    val badge = when {
                        seasonNumber != null && epNumber != null -> "S${seasonNumber}:E${epNumber}"
                        epNumber != null -> "EP $epNumber"
                        else -> null
                    }
                    if (badge != null) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    if (isCurrentResume) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primary
                        ) {
                            Text(
                                text = "LAST WATCHED",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                Text(
                    text = episode.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (!episode.description.isNullOrBlank()) {
                    Text(
                        text = episode.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.LightGray.copy(alpha = 0.8f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Play ${episode.title}",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
