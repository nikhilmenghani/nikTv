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
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import com.nikhil.niktv.update.DownloadedApkCleanup
import com.nikhil.niktv.update.formatDownloadBytes
import com.nikhil.niktv.data.OfflineMediaDownloads
import com.nikhil.niktv.data.OfflineDownloadStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal val NikColors = darkColorScheme(
    primary = Color(0xFFE50914), onPrimary = Color.White,
    primaryContainer = Color(0xFF7F1016), onPrimaryContainer = Color.White,
    secondary = Color(0xFFE50914), onSecondary = Color.White,
    secondaryContainer = Color(0xFF3A1518), onSecondaryContainer = Color.White,
    background = Color(0xFF090909), onBackground = Color.White,
    surface = Color(0xFF141414), onSurface = Color.White,
    surfaceVariant = Color(0xFF262626), onSurfaceVariant = Color(0xFFD1D1D1),
    outline = Color(0xFF666666)
)
internal val XtreamColors = NikColors
internal val visibleCatalogTypes = listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)
internal val menuActivationKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

internal enum class MobileMainPage(val title: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    LIVE("Live", Icons.Default.LiveTv),
    MOVIES("Movies", Icons.Default.Movie),
    SERIES("Series", Icons.Default.VideoLibrary),
    LIBRARY("Library", Icons.Default.FavoriteBorder),
    DOWNLOADS("Offline", Icons.Default.DownloadDone)
}

internal enum class MobileSettingsPage(val title: String, val icon: ImageVector) {
    APPEARANCE("Appearance", Icons.Default.Palette),
    PLAYBACK("Playback", Icons.Default.PlayCircle),
    CONTENT("Content", Icons.Default.VideoLibrary),
    ACCOUNT("Account", Icons.Default.ManageAccounts)
}

internal val LocalMobileSettingsPage = compositionLocalOf<MobileSettingsPage?> { null }

internal fun settingsPageFor(title: String): MobileSettingsPage = when (title) {
    "Mobile controls", "Picture and video appearance", "Display and screen" ->
        MobileSettingsPage.APPEARANCE
    "Default media player", "Player controls", "Series" ->
        MobileSettingsPage.PLAYBACK
    "Category Filters", "Catalog cache" -> MobileSettingsPage.CONTENT
    else -> MobileSettingsPage.ACCOUNT
}

internal fun NikTvState.mobileMainPage(): MobileMainPage = when {
    offlineDownloadsOpen -> MobileMainPage.DOWNLOADS
    favoritesOpen -> MobileMainPage.LIBRARY
    homeOpen -> MobileMainPage.HOME
    selectedType == CatalogType.LIVE_TV -> MobileMainPage.LIVE
    selectedType == CatalogType.MOVIES -> MobileMainPage.MOVIES
    else -> MobileMainPage.SERIES
}

internal fun Context.isTvLikeDevice(configuration: Configuration): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION ||
        !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
internal val visibleSearchTypes = listOf(SearchContentType.LIVE_TV, SearchContentType.SERIES, SearchContentType.MOVIES)

internal fun formatOfflineBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) { value /= 1024.0; unit++ }
    return if (value >= 100.0) "${value.toInt()} ${units[unit]}" else "%.1f %s".format(value, units[unit])
}

internal fun com.nikhil.niktv.data.OfflineDownloadInfo.progressLabel(): String {
    val percentage = percent?.let { "${it.toInt()}%" }
    val size = totalBytes?.let { "${formatOfflineBytes(bytesDownloaded)} / ${formatOfflineBytes(it)}" }
        ?: formatOfflineBytes(bytesDownloaded)
    return listOfNotNull("Downloading", percentage, size).joinToString(" · ")
}

internal fun UpdateDownloadState.updateInfoOrNull(): UpdateInfo? = when (this) {
    is UpdateDownloadState.Queued -> UpdateInfo(version, downloadUrl)
    is UpdateDownloadState.Downloading -> UpdateInfo(version, downloadUrl)
    is UpdateDownloadState.Paused -> UpdateInfo(version, downloadUrl)
    is UpdateDownloadState.Ready -> UpdateInfo(version, downloadUrl)
    is UpdateDownloadState.Installing -> UpdateInfo(version, downloadUrl)
    is UpdateDownloadState.InstallerLaunched -> UpdateInfo(version, downloadUrl)
    is UpdateDownloadState.Failed -> UpdateInfo(version, downloadUrl)
    UpdateDownloadState.Idle -> null
}

internal fun uniformSegmentShape(index: Int, count: Int): RoundedCornerShape = when (index) {
    0 -> RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
    count - 1 -> RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
    else -> RoundedCornerShape(0.dp)
}

internal val LocalRemoteFocusColor = staticCompositionLocalOf { Color(0xFFFFB3B8) }
internal val LocalRemoteFocusShape = staticCompositionLocalOf<Shape> { CircleShape }
internal val LocalRemoteFocusMargin = staticCompositionLocalOf { 0.dp }

@Composable
internal fun Modifier.remoteFocusFrame(
    shape: Shape = LocalRemoteFocusShape.current
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val margin = with(androidx.compose.ui.platform.LocalDensity.current) { LocalRemoteFocusMargin.current.toPx() }
    var focusSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
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
     * Every device uses a border-only focus treatment; tablets still scroll
     * focused controls into view without adding a colored shadow.
     */
    return this
        .then(if (margin > 0f) Modifier.onSizeChanged { focusSize = it } else Modifier)
        .bringIntoViewRequester(bringIntoViewRequester)
        .onFocusChanged {
            focused = it.isFocused

            if (it.isFocused && (!isTv || margin > 0f)) {
                scope.launch {
                    bringIntoViewRequester.bringIntoView(
                        if (margin > 0f && focusSize.height > 0) androidx.compose.ui.geometry.Rect(
                            0f, -margin, focusSize.width.toFloat(), focusSize.height + margin
                        ) else null
                    )
                }
            }
        }
        .then(
            if (focused) {
                Modifier
                    .border(
                        2.dp,
                        LocalRemoteFocusColor.current,
                        shape
                    )
            } else {
                Modifier
            }
        )
}

// TV_SAFE_TOUCH_SHADOW_V80
// Remote focus must never install a zero-elevation shadow/graphics layer.
// Touch devices keep the existing press/focus shadow treatment.
internal fun Modifier.touchTileShadow(
    isTv: Boolean,
    elevation: Dp,
    shape: Shape,
    clip: Boolean = false,
    ambientColor: Color = Color.Black,
    spotColor: Color = Color.Black
): Modifier =
    if (isTv) {
        this
    } else {
        shadow(
            elevation = elevation,
            shape = shape,
            clip = clip,
            ambientColor = ambientColor,
            spotColor = spotColor
        )
    }

internal fun String.isRedundantTvTileSubtitle(): Boolean {
    val value = trim()
    return value.equals("Movie", ignoreCase = true) ||
        value.equals("Movies", ignoreCase = true) ||
        value.equals("Series", ignoreCase = true) ||
        value.equals("Live TV", ignoreCase = true) ||
        value.startsWith("IPTV ·", ignoreCase = true)
}

@Composable
internal fun Modifier.mobileMainTabSwipe(
    enabled: Boolean,
    currentPage: MobileMainPage,
    onPageSelected: (MobileMainPage) -> Unit
): Modifier {
    val latestOnPageSelected by rememberUpdatedState(onPageSelected)
    if (!enabled) return this

    // DOWNLOADS is a Library sub-tab, not a sixth primary destination.
    // Treat both Library states as the same primary page for horizontal
    // navigation so swiping right from either My List or Downloads returns
    // to Series instead of getting trapped inside Library.
    val swipePages = remember {
        MobileMainPage.entries.filterNot { it == MobileMainPage.DOWNLOADS }
    }
    val swipeCurrentPage =
        if (currentPage == MobileMainPage.DOWNLOADS) {
            MobileMainPage.LIBRARY
        } else {
            currentPage
        }

    val swipeScope = rememberCoroutineScope()
    var swipeOffsetTarget by remember { mutableFloatStateOf(0f) }
    var swipeDragging by remember { mutableStateOf(false) }
    val swipeOffset by animateFloatAsState(
        targetValue = swipeOffsetTarget,
        animationSpec =
            if (swipeDragging) {
                snap()
            } else {
                tween(140, easing = FastOutSlowInEasing)
            },
        label = "mainTabSwipeOffset"
    )

    return graphicsLayer { translationX = swipeOffset }
        .pointerInput(enabled, swipeCurrentPage) {
        val distanceThreshold = 56.dp.toPx()
        val directionRatio = 1.25f
        val destinationEntryOffset = 28.dp.toPx()

        awaitPointerEventScope {
            while (true) {
                val initialEvent = awaitPointerEvent(PointerEventPass.Initial)
                val down = initialEvent.changes.firstOrNull {
                    it.pressed && !it.previousPressed
                } ?: continue

                val pointerId = down.id
                var totalX = 0f
                var totalY = 0f
                var horizontalDragLocked = false
                var cancelled = false

                while (true) {
                    // Let nested LazyRows and other horizontal content claim the
                    // gesture first. The page swipe only handles movement left
                    // unconsumed after child dispatch, which makes blank-space
                    // swipes navigate while tile drags continue scrolling.
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (event.changes.count { it.pressed } > 1) {
                        cancelled = true
                    }

                    val change = event.changes.firstOrNull { it.id == pointerId }
                        ?: break
                    if (change.isConsumed && !horizontalDragLocked) {
                        cancelled = true
                        break
                    }
                    val movement = change.positionChange()
                    totalX += movement.x
                    totalY += movement.y

                    if (!horizontalDragLocked &&
                        kotlin.math.abs(totalX) > 12.dp.toPx() &&
                        kotlin.math.abs(totalX) > kotlin.math.abs(totalY) * directionRatio
                    ) {
                        horizontalDragLocked = true
                    }
                    if (horizontalDragLocked) {
                        change.consume()
                        swipeDragging = true
                        swipeOffsetTarget = totalX.coerceIn(
                            -size.width * 0.28f,
                            size.width * 0.28f
                        )
                    }

                    if (!change.pressed) break
                }

                if (cancelled) {
                    swipeDragging = false
                    swipeOffsetTarget = 0f
                    continue
                }

                val horizontalDistance = kotlin.math.abs(totalX)
                val verticalDistance = kotlin.math.abs(totalY)
                if (
                    horizontalDistance < distanceThreshold ||
                    horizontalDistance <= verticalDistance * directionRatio
                ) {
                    swipeDragging = false
                    swipeOffsetTarget = 0f
                    continue
                }

                val currentIndex = swipePages.indexOf(swipeCurrentPage)
                val targetIndex =
                    if (totalX < 0f) currentIndex + 1
                    else currentIndex - 1
                val target = swipePages.getOrNull(targetIndex)

                if (target != null) {
                    /*
                     * Keep the destination transition intentionally short.
                     *
                     * The old implementation teleported the new page a full
                     * screen width away, waited a fixed 16 ms, then animated
                     * it for 260 ms. That made a successful swipe feel as if
                     * navigation paused after finger-up, especially when the
                     * destination was also recomposing a large browse grid.
                     *
                     * Put the destination only 28 dp off-center and settle on
                     * the next Compose frame. The page change still has clear
                     * directional motion without adding a quarter-second wait.
                     */
                    swipeDragging = true
                    swipeOffsetTarget =
                        if (totalX < 0f) {
                            destinationEntryOffset
                        } else {
                            -destinationEntryOffset
                        }

                    latestOnPageSelected(target)

                    swipeScope.launch {
                        withFrameNanos { }
                        swipeDragging = false
                        swipeOffsetTarget = 0f
                    }
                } else {
                    swipeDragging = false
                    swipeOffsetTarget = 0f
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.remoteCombinedClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    showIndication: Boolean = true
): Modifier {
    val scope = rememberCoroutineScope()
    // Cards with their own focus border never use the platform indication.
    // Avoid device-feature/preference lookups on every focus recomposition.
    val remoteNavigationActive = showIndication &&
        LocalContext.current.usesRemoteNavigation(LocalConfiguration.current)
    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    var keyIsDown by remember { mutableStateOf(false) }
    var longPressReached by remember { mutableStateOf(false) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }

    return this
        .onPreviewKeyEvent { event ->
            if (!enabled) return@onPreviewKeyEvent false
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
                    val activate = keyIsDown && !event.nativeKeyEvent.isCanceled
                    val invokeLongClick = activate && longPressReached && onLongClick != null
                    longPressJob?.cancel()
                    longPressJob = null
                    keyIsDown = false
                    longPressReached = false
                    if (activate) {
                        if (invokeLongClick) onLongClick?.invoke() else onClick()
                    }
                    true
                }
                else -> false
            }
        }
        .then(
            Modifier.combinedClickable(
                interactionSource = resolvedInteractionSource,
                // TV cards draw their own shape-aware focus border. The
                // platform indication is rectangular and leaks beyond
                // rounded poster corners while focused.
                indication = if (remoteNavigationActive || !showIndication) null else LocalIndication.current,
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick
            )
        )
}
internal fun String.withoutConfigurationQuotes(): String = trim().let { value ->
    if (value.length >= 2 && ((value.first() == '"' && value.last() == '"') ||
            (value.first() == '\'' && value.last() == '\''))) value.substring(1, value.length - 1).trim()
    else value
}

internal tailrec fun android.content.Context.findHostActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun NikTvApp(vm: NikTvViewModel = viewModel()) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalPlayAlternateSource provides vm::openAlternateSources,
        LocalRefreshProviderCategories provides { type -> vm.refreshProviderCategories(type) },
        com.nikhil.niktv.discovery.LocalOpenDiscovery provides vm::openDiscovery,
        com.nikhil.niktv.discovery.LocalOpenWatchProviders provides vm::openWatchProviders,
        com.nikhil.niktv.discovery.LocalPlayDiscoveryEpisode provides vm::openDiscoveryEpisode,
        com.nikhil.niktv.discovery.LocalDownloadDiscoveryEpisode provides vm::downloadDiscoveryEpisode,
        LocalChooseIptvMovieVersion provides { movie -> vm.chooseTmdbMovieVersion(com.nikhil.niktv.data.TrendingMovie(movie)) },
        com.nikhil.niktv.discovery.LocalRefreshDiscoveryMatches provides vm::refreshDiscoveryMatches,
        com.nikhil.niktv.discovery.LocalOpenDiscoverySeries provides { vm.openSeriesFromHome(it) },
        com.nikhil.niktv.discovery.LocalOpenProviderDiscovery provides vm::openDiscoveryProviderDetails,
        com.nikhil.niktv.discovery.LocalConfigureProviderDiscovery provides vm::openCategoryManager,
        com.nikhil.niktv.discovery.LocalPlayProviderDiscovery provides vm::playDiscoveryProvider,
        com.nikhil.niktv.discovery.LocalDownloadProviderDiscovery provides { type, item -> vm.downloadForOffline(item, type, null) },
        com.nikhil.niktv.discovery.LocalProviderDiscoveryPageLoader provides vm::loadDiscoveryCategoryPage,
        com.nikhil.niktv.discovery.LocalProviderDetailNavigation provides com.nikhil.niktv.discovery.ProviderDetailNavigation(
            openDiscovery = vm::openDiscovery,
            classic = { label -> when (label) {
                "Home" -> vm.openHome(); "Live TV" -> vm.openCatalogType(CatalogType.LIVE_TV)
                "Movies" -> vm.openDiscovery(DashboardSurface.MOVIES); "Series" -> vm.openDiscovery(DashboardSurface.SERIES)
                "My List" -> vm.openFavorites(); "Offline" -> vm.openOfflineDownloads(); "Search" -> vm.openSearch()
                "Settings" -> vm.openSettings(); "Profile" -> vm.openProfileSwitcher()
            } }, favorite = { vm.toggleFavorite(it) }, playEpisode = vm::openDiscoveryEpisode,
            recent = vm::openRecent, watched = vm::openWatchedEpisode, category = vm::openModernIptvCategory,
            section = vm::openModernTmdbSection, removeRecent = vm::removeRecent,
            markWatched = vm::markEpisodeWatched, dismissEpisode = vm::dismissWatchedEpisode, playProvider = vm::openMediaInContext),
        LocalShowRecommendations provides vm::showRecommendationsOnHome,
        LocalRecommendationTypeFor provides vm::recommendationTypeFor) {
        NikTvAppContent(vm)
    }
}

@Composable
private fun NikTvAppContent(vm: NikTvViewModel) {
    val pairingInvite = com.nikhil.niktv.data.PairingInvites.pending
    LaunchedEffect(pairingInvite) {
        if (pairingInvite != null) vm.openSettings()
    }
    val catalogStateHolder = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val orientationMode by rememberUiOrientationMode()

    val state by vm.state.collectAsStateWithLifecycle()
    val downloadInfos by OfflineDownloadSnapshots.state.collectAsStateWithLifecycle()
    // One owner restores browsing orientation when the player closes.
    ApplyUiOrientation(orientationMode, playingVideo = state.nowPlaying != null)
    ApplyAppBrightness()

    var confirmPlayerDownloadRemoval by remember { mutableStateOf(false) }
    val pendingUpdate by AppUpdates.pendingUpdate.collectAsStateWithLifecycle()
    val updateDownloadState by AppUpdates.downloadState.collectAsStateWithLifecycle()
    val updateEnforcementEnabled by AppUpdates.updateEnforcementEnabled.collectAsStateWithLifecycle()
    val startupUpdateCheckEnabled by AppUpdates.startupUpdateCheckEnabled.collectAsStateWithLifecycle()
    var discoveredUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var checkingForRequiredUpdate by remember { mutableStateOf(
        updateEnforcementEnabled &&
            startupUpdateCheckEnabled &&
            pendingUpdate == null &&
            updateDownloadState.updateInfoOrNull() == null
    ) }
    val appContext = LocalContext.current
    val onScreenDpadEnabled by rememberOnScreenDpadEnabled()
    val appConfiguration = LocalConfiguration.current
    val hostActivity = remember(appContext) {
        appContext.findHostActivity()
    }
    val shouldKeepScreenAwake =
        !state.keepAwakeOnlyDuringPlayback ||
            state.nowPlaying != null

    /*
     * APP_KEEP_AWAKE_OWNER_V17
     *
     * Default: keep NikTV awake while the app is open.
     * Optional setting: only keep awake during playback.
     * This is the sole FLAG_KEEP_SCREEN_ON owner.
     */
    DisposableEffect(hostActivity, shouldKeepScreenAwake) {
        if (shouldKeepScreenAwake) {
            hostActivity?.window?.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        } else {
            hostActivity?.window?.clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        onDispose {
            hostActivity?.window?.clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
    }

    val clipboard = remember(appContext) {
        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    }
    LaunchedEffect(updateEnforcementEnabled) {
        if (
            updateEnforcementEnabled &&
            startupUpdateCheckEnabled &&
            pendingUpdate == null &&
            updateDownloadState.updateInfoOrNull() == null
        ) {
            discoveredUpdate = runCatching { AppUpdates.check() }.getOrNull()
        }
        checkingForRequiredUpdate = false
    }
    val requiredUpdate = discoveredUpdate ?: pendingUpdate ?: updateDownloadState.updateInfoOrNull()
    val profileColors = if (state.savedProfile?.portalType == PortalType.XTREAM) XtreamColors else NikColors
    MaterialTheme(colorScheme = profileColors) {
        state.alternateSources?.let { sources -> AlternateSourceDialog(sources, vm::closeAlternateSources, vm::findAlternateSources, vm::chooseAlternateStream) }
        if (state.profileSwitcherOpen) ModernProfileSwitchDialog(state.profiles, state.savedProfile,
            close = vm::closeProfileSwitcher, select = vm::switchProfile, openChooser = vm::openProfileChooser)
        ParentalPinPrompt(vm)
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
                /*
                 * ALL_CONTENT_FULLSCREEN_PLAYER_V25
                 *
                 * Home, Live TV, Movies and Series remain browse dashboards.
                 * Playback itself has one owner: PlayerScreen. Channels,
                 * movies and episodes are browsed from the in-player queue.
                 */
                state.nowPlaying != null -> {
                  val playback = state.nowPlaying!!
                  val profileKey = state.session?.profile?.cacheKey()
                      ?: state.savedProfile?.cacheKey().orEmpty()
                  val channelCategoryId = state.nowPlaying?.media?.portalCategoryId
                      ?.takeIf { it.isNotBlank() } ?: state.selectedCategory?.id.orEmpty()
                  val channelCategoryTitle = state.rawCategoriesByType[CatalogType.LIVE_TV]
                      ?.firstOrNull { it.id == channelCategoryId }?.title
                      ?: state.selectedCategory?.takeIf { it.id == channelCategoryId }?.title.orEmpty()
                  var pinnedQueueIds by remember(profileKey, channelCategoryId) {
                      mutableStateOf(IptvPinPreferences.pinnedChannelOrder(
                          appContext, profileKey, channelCategoryId
                      ))
                  }
                  PlayerScreen(
                    media = playback,
                    onVideoFrameRendered = { vm.observeLivePlayback(playback, failed = false) },
                    onTerminalPlaybackFailure = { vm.observeLivePlayback(playback, failed = true) },
                    onBack = vm::closePlayer,
                    onRetry = { vm.retryPlayback(it, playback) },
                    onReauthenticate = if (playback.offlinePlayback || playback.alternateSource != null) null else
                        { position -> vm.reauthenticatePlayback(position, playback) },
                    reauthenticating = state.reauthenticating,
                    onRetryAlternateDecoder = { position -> vm.retryPlaybackWithAlternateDecoder(position, playback) },
                    onPlaybackAuthorizationFailure = { position -> vm.retryPlaybackAfterAuthorizationFailure(position, playback) },
                    onPlayPrevious = vm::playPreviousEpisode,
                    onPlayNext = vm::playNextEpisode,
                    onProgress = vm::savePlaybackProgress,
                    onChooseAlternateSource = playback.alternateSource?.let { { position, positionReader -> vm.openPlayingAlternateSources(position, playback, positionReader) } },
                    alternateSourceChooserOpen = state.alternateSources != null,
                    onPlaybackIntentChanged = { vm.setPlaybackIntent(playback.playbackAttemptId, it) },
                    onDownload = {
                        val present = state.offlineDownloads.any { download ->
                            download.profileKey == (state.session?.profile?.cacheKey() ?: state.savedProfile?.cacheKey()) &&
                                download.catalogType == state.nowPlaying?.catalogType &&
                                download.media.id == state.nowPlaying?.media?.id &&
                                downloadInfos[download.key]?.status !in
                                    setOf(OfflineDownloadStatus.FAILED, OfflineDownloadStatus.MISSING)
                        }
                        if (present) confirmPlayerDownloadRemoval = true else vm.downloadNowPlaying()
                    },
                    offlineDownloadPresent = state.offlineDownloads.any { download ->
                        download.profileKey == (state.session?.profile?.cacheKey() ?: state.savedProfile?.cacheKey()) &&
                            download.catalogType == state.nowPlaying?.catalogType &&
                            download.media.id == state.nowPlaying?.media?.id &&
                            downloadInfos[download.key]?.status == OfflineDownloadStatus.COMPLETE
                    },
                    offlineDownloadInProgress = state.offlineDownloads.firstOrNull { download ->
                        download.profileKey == (state.session?.profile?.cacheKey() ?: state.savedProfile?.cacheKey()) &&
                            download.catalogType == state.nowPlaying?.catalogType &&
                            download.media.id == state.nowPlaying?.media?.id
                    }?.let { observedOfflineDownloadInfo(it)?.status }
                        ?.let { it == OfflineDownloadStatus.DOWNLOADING || it == OfflineDownloadStatus.QUEUED } == true,
                    offlineDownloadProgress = state.offlineDownloads.firstOrNull { download ->
                        download.profileKey == (state.session?.profile?.cacheKey() ?: state.savedProfile?.cacheKey()) &&
                            download.catalogType == state.nowPlaying?.catalogType &&
                            download.media.id == state.nowPlaying?.media?.id
                    }?.let { observedOfflineDownloadInfo(it) }
                        ?.takeIf { it.status == OfflineDownloadStatus.DOWNLOADING || it.status == OfflineDownloadStatus.QUEUED }
                        ?.percent?.div(100f),
                    offlineDownloadProgressText = state.offlineDownloads.firstOrNull { download ->
                        download.profileKey == (state.session?.profile?.cacheKey() ?: state.savedProfile?.cacheKey()) &&
                            download.catalogType == state.nowPlaying?.catalogType &&
                            download.media.id == state.nowPlaying?.media?.id
                    }?.let { observedOfflineDownloadInfo(it) }
                        ?.takeIf { it.status == OfflineDownloadStatus.DOWNLOADING || it.status == OfflineDownloadStatus.QUEUED }
                        ?.progressLabel(),
                    onPlayItem = vm::openMedia,
                    onPlayQueueItem = vm::openMediaFromQueue,
                    pinnedQueueIds = playback.queueFilter?.pinnedIds ?: pinnedQueueIds,
                    queueFavoriteIds = state.favorites
                        .asSequence()
                        .filter { it.kind == FavoriteKind.CHANNEL }
                        .map { it.media.id }
                        .toSet(),
                    onToggleQueueFavorite = { item ->
                        vm.toggleFavorite(
                            FavoriteItem(FavoriteKind.CHANNEL, item)
                        )
                    },
                    onToggleQueuePin = { item ->
                        val categoryId = item.portalCategoryId?.takeIf { it.isNotBlank() }
                            ?: channelCategoryId
                        if (categoryId.isNotBlank()) {
                            val updated = IptvPinPreferences.toggleChannel(
                                appContext, profileKey, categoryId, item.id
                            )
                            if (categoryId == channelCategoryId) pinnedQueueIds = updated
                        }
                    },
                    onRefreshQueueProgramme = vm::refreshLiveProgramme,
                    onVisibleQueueChannels = vm::enrichPlayerLiveGuides,
                    liveQueueCategoryTitle = channelCategoryTitle,
                    liveQueueXtream = state.session?.profile?.portalType == PortalType.XTREAM,
                    queueHasMore = state.playbackQueueHasMore,
                    queueLoadingMore = state.playbackQueueLoadingMore,
                    onLoadMoreQueue = vm::loadMorePlaybackQueue,
                    controlsTimeoutSeconds = state.playerControlsTimeoutSeconds,
                    onControlsTimeoutChanged = vm::setPlayerControlsTimeoutSeconds,
                    playbackEngine = state.playbackEngine,
                    onPlaybackEngineChanged = vm::setPlaybackEngine,
                    startFullscreen = true
                )
                }

                state.restoring -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.profileLoadProgress != null -> ProfileLoadingScreen(
                    profileName = state.savedProfile?.name,
                    message = state.profileLoadMessage,
                    progress = state.profileLoadProgress ?: 0f
                )
                state.session == null && state.settingsOpen -> ModernSettingsScreen(
                    state = state,
                    closeSettings = vm::closeSettings,
                    reauthenticate = vm::reauthenticate,
                    editProfile = vm::editProfile,
                    addProfile = vm::addProfile,
                    exportBackup = vm::exportBackup,
                    importBackup = vm::importBackup,
                    importBackupContent = vm::importBackupContent,
                    switchProfile = vm::switchProfile,
                    removeProfile = vm::removeProfile,
                    setPreconfiguredProfileEnabled = vm::setPreconfiguredProfileEnabled,
                    logout = vm::logout,
                    setCacheIntervalMinutes = vm::setCacheIntervalMinutes,
                    setInitialCatalogItems = vm::setInitialCatalogItems,
                    setPlayerControlsTimeoutSeconds = vm::setPlayerControlsTimeoutSeconds,
                    setKeepAwakeOnlyDuringPlayback = vm::setKeepAwakeOnlyDuringPlayback,
                    setAutomaticReauthentication = vm::setAutomaticReauthentication,
                    setModernUiEnabled = vm::setModernUiEnabled,
                    setPlaybackEngine = vm::setPlaybackEngine,
                    setSeriesStartSeason = vm::setSeriesStartSeason,
                    setBrowseLayout = vm::setBrowseLayout,
                    openCategoryManager = vm::openCategoryManager
                )
                state.session == null && !state.offlineDownloadsOpen -> ProfileScreen(state.savedProfile, state.profiles, state.profileEditorOpen, state.loading, vm::openSettingsFromProfileChooser, vm::connect, vm::switchProfile, vm::addProfile, vm::cancelProfileEditor, vm::importBackup, vm::openOfflineDownloads)
                else -> catalogStateHolder.SaveableStateProvider(state.savedProfile?.cacheKey().orEmpty()) { CatalogScreen(
                    state = state,
                    selectType = vm::openCatalogType,
                    openGlobalSearch = vm::openGlobalSearch,
                    selectCategory = vm::loadCategory,
                    play = { vm.openCatalogDetails(it) },
                    playInContext = { item, queue, filter -> vm.openCatalogDetails(item, queue, filter) },
                    openTrendingMovie = vm::openTrendingMovie,
                    selectTmdbMovieMatch = vm::selectTmdbMovieMatch,
                    closeTmdbMovieMatches = vm::closeTmdbMovieMatches,
                    openTrendingSeries = vm::openTrendingSeries,
                    selectTmdbSeriesMatch = vm::selectTmdbSeriesMatch,
                    closeTmdbSeriesMatches = vm::closeTmdbSeriesMatches,
                    closeSeries = vm::closeSeries,
                    refreshCatalog = vm::refreshCatalog,
                    openFavorites = vm::openFavorites,
                    closeFavorites = vm::closeFavorites,
                    openHome = vm::openHome,
                    openRecent = vm::openRecent,
                    openSeriesFromHome = vm::openSeriesFromHome,
                    removeRecent = vm::removeRecent,
                    dismissRecommendation = vm::dismissRecommendation,
                    dismissWatchedEpisode = vm::dismissWatchedEpisode,
                    markEpisodeWatched = vm::markEpisodeWatched,
                    clearRecent = vm::clearRecent,
                    openFavorite = { favorite -> if (favorite.kind == FavoriteKind.MOVIE && favorite.source != FavoriteSource.TMDB) vm.openProviderMovieDetails(favorite.media) else vm.openFavorite(favorite) },
                    toggleFavorite = vm::toggleFavorite,
                    toggleFavoriteEntry = { vm.toggleFavorite(it) },
                    openSettings = vm::openSettings,
                    closeSettings = vm::closeSettings,
                    reauthenticate = vm::reauthenticate,
                    editProfile = vm::editProfile,
                    logout = vm::logout,
                    setCacheIntervalMinutes = vm::setCacheIntervalMinutes
                    ,setInitialCatalogItems = vm::setInitialCatalogItems
                    ,setPlayerControlsTimeoutSeconds = vm::setPlayerControlsTimeoutSeconds
                    ,setKeepAwakeOnlyDuringPlayback = vm::setKeepAwakeOnlyDuringPlayback
                    ,setAutomaticReauthentication = vm::setAutomaticReauthentication
                    ,setModernUiEnabled = vm::setModernUiEnabled
                    ,setPlaybackEngine = vm::setPlaybackEngine
                    ,setSeriesStartSeason = vm::setSeriesStartSeason
                    ,loadSeriesSeason = vm::loadSeriesSeason
                    ,setUseTmdbEpisodeMetadata = vm::setUseTmdbEpisodeMetadata
                    ,refreshTmdbEpisodeCache = vm::forceRefreshTmdbEpisodeCache
                    ,refreshEpisodeTmdb = vm::refreshEpisodeTmdb
                    ,dismissEpisodeTmdbRefresh = vm::dismissEpisodeTmdbRefresh
                    ,downloadForOffline = vm::downloadForOffline
                    ,removeOfflineDownload = vm::removeOfflineDownload
                    ,removeAllOfflineDownloads = vm::removeAllOfflineDownloads
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
                    ,openSearchResult = { item -> if ((item.searchResultType ?: state.searchType) == SearchContentType.MOVIES) vm.openProviderMovieDetails(item) else vm.openSearchResult(item) }
                    ,loadMoreSearch = vm::loadMoreSearch
                    ,loadMoreCatalog = vm::loadMoreCatalog
                    ,loadMoreEpisodes = vm::loadMoreEpisodes
                    ,goToSeriesEpisode = vm::goToSeriesEpisode
                    ,cancelEpisodeDestination = vm::cancelEpisodeDestination
                    ,setSearchCategory = vm::setSearchCategory
                    ,addProfile = vm::addProfile
                    ,exportBackup = vm::exportBackup
                    ,importBackup = vm::importBackup
                    ,importBackupContent = vm::importBackupContent
                    ,openProfileSwitcher = vm::openProfileSwitcher
                    ,switchProfile = vm::switchProfile
                    ,removeProfile = vm::removeProfile
                    ,setPreconfiguredProfileEnabled = vm::setPreconfiguredProfileEnabled
                    ,openCategoryManager = vm::openCategoryManager
                    ,removeCategoryFilter = vm::removeCategoryFilter
                    ,loadMoreCategorySection = vm::loadMoreCategorySection
                    ,setTmdbSections = vm::setTmdbSections
                    ,resetScreenConfiguration = vm::resetScreenConfiguration
                    ,openModernTmdbSection = vm::openModernTmdbSection
                    ,openModernIptvCategory = vm::openModernIptvCategory
                    ,closeModernSection = vm::closeModernSection
                    ,loadMoreModernTmdbSection = vm::loadMoreModernTmdbSection
                    ,enrichFocusedCatalogMetadata = vm::enrichFocusedCatalogMetadata
                    ,enrichVisibleLiveGuides = vm::enrichVisibleLiveGuides
                    ,refreshLiveProgramme = vm::refreshLiveProgramme
                    ,refreshTmdbMatches = vm::refreshTmdbMatches
                    ,findChannelsInCategory = vm::findChannelsInCategory
                    ,cancelCategoryFind = vm::cancelCategoryFind
                    ,openOfflineDownloads = vm::openOfflineDownloads
                    ,closeOfflineDownloads = vm::closeOfflineDownloads
                    ,playOfflineDownload = vm::playOfflineDownload
                    ,playRecording = vm::playRecording
                    ,playProviderMovie = vm::playProviderMovieDetails
                    ,closeProviderMovie = vm::closeProviderMovieDetails
                )
            }
            }
            if (state.categoryManagerOpen) {
                CategoryManagerDialog(
                    state = state,
                    close = vm::closeCategoryManager,
                    applyFilters = vm::applyCategoryFilters
                )
            }
            if (confirmPlayerDownloadRemoval) {
                AlertDialog(
                    onDismissRequest = { confirmPlayerDownloadRemoval = false },
                    title = { Text("Remove offline download?") },
                    text = { Text("Cancel or delete “${state.nowPlaying?.media?.title.orEmpty()}” from offline downloads?") },
                    dismissButton = { NikTvTextActionButton(onClick = { confirmPlayerDownloadRemoval = false }) { Text("Keep") } },
                    confirmButton = { NikTvPrimaryActionButton(onClick = { confirmPlayerDownloadRemoval = false; vm.downloadNowPlaying() }) { Text("Remove") } }
                )
            }
            if (state.feedRefreshing) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.88f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            state.feedRefreshMessage,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White
                        )
                        LinearProgressIndicator(Modifier.widthIn(min = 260.dp, max = 520.dp))
                        Text(
                            "Loading your selected sections",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.LightGray
                        )
                    }
                }
            }
            if (state.loading && state.profileLoadProgress == null) {
                if (state.session == null) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .42f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    // Connected screens remain interactive while a portal or
                    // catalog request runs. A full-screen overlay made a slow
                    // request look like a frozen app and intercepted actions.
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.TopCenter)
                    )
                }
            }
            state.tmdbIptvLookupMiss?.takeIf { it.profileKey == (state.session?.profile ?: state.savedProfile)?.cacheKey() }?.let { miss ->
                val actionFocus = remember(miss) { FocusRequester() }
                LaunchedEffect(miss) { withFrameNanos { }; runCatching { actionFocus.requestFocus() } }
                AlertDialog(
                    onDismissRequest = vm::dismissTmdbIptvLookupMiss,
                    title = { Text("Not found on IPTV") },
                    text = { Text("No IPTV matches were found for \"${(miss.request.series ?: miss.request.item).title}\". You can look for another source to play in NikTV.") },
                    confirmButton = {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            NikTvPrimaryActionButton(onClick = vm::findAlternateSourcesAfterIptvMiss,
                                modifier = Modifier.fillMaxWidth().focusRequester(actionFocus)) {
                                Icon(Icons.Default.AltRoute, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Find alternate sources")
                            }
                            NikTvSecondaryActionButton(onClick = vm::dismissTmdbIptvLookupMiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                        }
                    })
            }
            state.error?.let { error ->
                val authorizationExpired = error.isAuthorizationFailureText()
                var showDiagnostics by remember(error) { mutableStateOf(!authorizationExpired) }
                val reauthenticateRequester = remember(error) { FocusRequester() }
                val diagnosticsCloseRequester = remember(error) { FocusRequester() }
                Dialog(
                    onDismissRequest = { if (!state.reauthenticating) vm.dismissError() },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Surface(
                        modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 620.dp).fillMaxWidth()
                            .initialFocusEntry(if (authorizationExpired) reauthenticateRequester else diagnosticsCloseRequester),
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
                        tonalElevation = 2.dp,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(Modifier.size(48.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    if (authorizationExpired) "Session expired" else "Portal diagnostics",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (authorizationExpired) {
                                Text("The portal rejected the saved authorization token even though the HTTP request completed. Your profile credentials are still saved; request a fresh session to continue.")
                                if (!showDiagnostics) NikTvTextActionButton(onClick = {
                                    reauthenticateRequester.requestFocus()
                                    showDiagnostics = true
                                }) { Icon(Icons.Default.Info, null); Spacer(Modifier.width(8.dp)); Text("Show diagnostics") }
                            }
                            if (showDiagnostics) SelectionContainer {
                                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                                    Text(error, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            PortalDiagnosticsActions(authorizationExpired, state.reauthenticating, showDiagnostics,
                                reauthenticateRequester, diagnosticsCloseRequester,
                                close = vm::dismissError, reauthenticate = vm::reauthenticate,
                                copy = { clipboard.setPrimaryClip(ClipData.newPlainText("NikTV diagnostics", error)) })
                        }
                    }
                }
            }
            state.backupMessage?.let { message ->
                AlertDialog(
                    onDismissRequest = vm::dismissBackupMessage,
                    confirmButton = { NikTvPrimaryActionButton(onClick = vm::dismissBackupMessage) { Text("OK") } },
                    title = { Text("NikTV backup") },
                    text = { Text(message) }
                )
            }
            if (
                onScreenDpadEnabled &&
                appConfiguration.smallestScreenWidthDp < 600 &&
                !appContext.isTvLikeDevice(appConfiguration)
            ) {
                MovableOnScreenDpad(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(16.dp)
                )
            }
           }
          }
        }
    }
}
