package com.nikhil.niktv.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
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
import com.nikhil.niktv.model.*
import com.nikhil.niktv.update.AppUpdates
import com.nikhil.niktv.update.UpdateDownloadState
import com.nikhil.niktv.update.UpdateInfo
import com.nikhil.niktv.update.formatDownloadBytes
import java.security.MessageDigest
import kotlinx.coroutines.launch

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
private val visibleSearchTypes = listOf(SearchContentType.LIVE_TV, SearchContentType.SERIES, SearchContentType.MOVIES)

@Composable
private fun Modifier.remoteFocusFrame(
    shape: Shape = RoundedCornerShape(12.dp)
): Modifier {
    var focused by remember { mutableStateOf(false) }
    return this
        .onFocusChanged { focused = it.isFocused }
        .then(
            if (focused) Modifier
                .shadow(16.dp, shape, ambientColor = Color(0xFFE50914), spotColor = Color(0xFFE50914))
                .background(Color(0xFF3A1014), shape)
                .border(4.dp, Color(0xFFFF3340), shape)
            else Modifier
        )
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
    val state by vm.state.collectAsStateWithLifecycle()
    val pendingUpdate by AppUpdates.pendingUpdate.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(pendingUpdate, state.session, state.restoring) {
        if (pendingUpdate != null && state.session != null && !state.restoring) {
            vm.openSettings()
        }
    }
    val profileColors = if (state.savedProfile?.portalType == PortalType.XTREAM) XtreamColors else NikColors
    MaterialTheme(colorScheme = profileColors) {
        Surface(Modifier.fillMaxSize()) {
            when {
                state.nowPlaying != null -> PlayerScreen(
                    state.nowPlaying!!,
                    vm::closePlayer,
                    vm::retryPlayback,
                    vm::playPreviousEpisode,
                    vm::playNextEpisode,
                    vm::savePlaybackProgress
                )
                state.restoring -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.profileLoadProgress != null -> ProfileLoadingScreen(
                    profileName = state.savedProfile?.name,
                    message = state.profileLoadMessage,
                    progress = state.profileLoadProgress ?: 0f
                )
                state.session == null -> ProfileScreen(state.savedProfile, state.profiles, state.profileEditorOpen, state.loading, vm::connect, vm::switchProfile, vm::addProfile, vm::cancelProfileEditor)
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
                    ,setSearchCategory = vm::setSearchCategory
                    ,addProfile = vm::addProfile
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
        }
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
private fun ProfileScreen(saved: PortalProfile?, profiles: List<PortalProfile>, editorOpen: Boolean, loading: Boolean, connect: (PortalProfile) -> Unit, selectProfile: (PortalProfile) -> Unit, addProfile: () -> Unit, cancelEditor: () -> Unit) {
    if (profiles.isNotEmpty() && !editorOpen) {
        Column(
            Modifier.fillMaxSize().background(Color(0xFF090909)).statusBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("N", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Black, color = Color(0xFFE50914))
            Spacer(Modifier.height(24.dp))
            Text("Who's watching?", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Choose an IPTV profile", color = Color.LightGray)
            FlowRow(Modifier.widthIn(max = 900.dp).padding(top = 32.dp), horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                profiles.forEach { profile ->
                    ProfileChooserTile(profile.name, profile.portalType.displayName(), if (profile.portalType == PortalType.STALKER) Icons.Default.Tv else Icons.Default.Key) {
                        selectProfile(profile)
                    }
                }
                ProfileChooserTile("Add profile", "New connection", Icons.Default.Add, addProfile)
            }
        }
        return
    }
    val stalkerDefaults = remember { PortalProfile(
        BuildConfig.DEFAULT_PROFILE_NAME.withoutConfigurationQuotes(),
        BuildConfig.DEFAULT_PORTAL_URL.withoutConfigurationQuotes(),
        BuildConfig.DEFAULT_MAC_ADDRESS.withoutConfigurationQuotes(),
        BuildConfig.DEFAULT_SERIAL_NUMBER.withoutConfigurationQuotes(),
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
    Box(Modifier.fillMaxSize().background(Color(0xFF090909)).statusBarsPadding().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 520.dp).fillMaxHeight(), colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (profiles.isNotEmpty()) IconButton(onClick = cancelEditor) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to profiles") }
                    Column { Text(if (saved == null) "Add profile" else "Edit ${saved.name}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Credentials stay local to this profile", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(portalType == PortalType.STALKER, { useDefaults(PortalType.STALKER) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Stalker / MAG") }
                    SegmentedButton(portalType == PortalType.XTREAM, { useDefaults(PortalType.XTREAM) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Xtream") }
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().focusRequester(nameFocus), label = { Text("Profile name") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { urlFocus.requestFocus() }))
                OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth().focusRequester(urlFocus), label = { Text("Portal URL") }, placeholder = { Text("https://provider.example") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { credentialFocus.requestFocus() }))
                if (portalType == PortalType.XTREAM) {
                    OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth().focusRequester(credentialFocus), label = { Text("Username") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { lastFocus.requestFocus() }))
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth().focusRequester(lastFocus), label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }))
                } else {
                    OutlinedTextField(mac, { mac = it }, Modifier.fillMaxWidth().focusRequester(credentialFocus), label = { Text("MAC address") }, placeholder = { Text("00:1A:79:XX:XX:XX") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = if (advanced) ImeAction.Next else ImeAction.Done), keyboardActions = KeyboardActions(onNext = { lastFocus.requestFocus() }, onDone = { keyboard?.hide() }))
                    TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced identity" else "Advanced identity") }
                    if (advanced) OutlinedTextField(serial, { serial = it }, Modifier.fillMaxWidth().focusRequester(lastFocus), label = { Text("Portal serial number (optional)") }, supportingText = { Text("Use the serial registered for this MAC, or leave blank to generate one.") }, singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }))
                }
                val credentialsReady = if (portalType == PortalType.XTREAM) username.isNotBlank() && password.isNotBlank() else mac.isNotBlank()
                Button(onClick = { keyboard?.hide(); connect(PortalProfile(name.trim(), url.trim(), mac.trim(), serial.trim(), portalType, username.trim(), password)) }, enabled = !loading && name.isNotBlank() && url.isNotBlank() && credentialsReady, modifier = Modifier.fillMaxWidth()) { Text(if (saved == null) "Add profile" else "Save profile") }
                Text("Only connect to services you are authorized to access.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
private fun ProfileChooserTile(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(
        Modifier.width(156.dp).onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.size(124.dp)
                .then(if (focused) Modifier.shadow(18.dp, RoundedCornerShape(10.dp), ambientColor = Color(0xFFE50914), spotColor = Color(0xFFE50914)) else Modifier)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1F2937))
                .border(if (focused) 4.dp else 2.dp, if (focused) Color(0xFFE50914) else Color(0xFF374151), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, title, Modifier.size(56.dp), tint = if (focused) Color.White else Color.LightGray)
        }
        Text(title, color = if (focused) Color.White else Color.LightGray, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, color = Color.Gray, maxLines = 1, style = MaterialTheme.typography.labelSmall)
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
    setSearchCategory: (String) -> Unit,
    addProfile: () -> Unit,
    switchProfile: (PortalProfile) -> Unit,
    removeProfile: (PortalProfile) -> Unit,
    openCategoryManager: (CatalogType) -> Unit
) {
    val configuration = LocalConfiguration.current
    val context = LocalContext.current
    val activity = context.findHostActivity()
    val isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
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
                    switchProfile = switchProfile,
                    removeProfile = removeProfile,
                    logout = logout,
                    setCacheIntervalMinutes = setCacheIntervalMinutes,
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
                    loadMore = loadMoreSearch
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
                    refreshCatalog = refreshCatalog
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
                expanded = isTv,
                modifier = Modifier.width(if (isTv) 196.dp else 72.dp).fillMaxHeight()
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
    refreshCatalog: () -> Unit,
    openCategoryManager: (CatalogType) -> Unit,
    loadMoreCatalog: () -> Unit
) {
    val home = state.homeOpen
    val layoutToggleRequester = remember { FocusRequester() }
    val hero = if (home) state.recentlyPlayed.firstOrNull()?.media ?: state.favorites.firstOrNull()?.media else state.items.firstOrNull()
    val configuration = LocalConfiguration.current
    val isTv = LocalContext.current.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val isWide = configuration.screenWidthDp >= 720 || isTv
    val columns = when {
        state.selectedType == CatalogType.LIVE_TV -> if (isWide) 6 else 4
        isWide -> 6
        else -> 3
    }
    val aspectRatio = 16f / 9f
    val gridSpan: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

    ModernGrid(
        columns = columns,
        modifier = Modifier.fillMaxSize().background(Color(0xFF090909)),
        contentPadding = PaddingValues(bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
            if (!isWide) item("modern-top", span = gridSpan) {
                ModernTopBar(state, home, openHome, selectType, openFavorites, openSearch, openSettings)
            }
            item("modern-hero", span = gridSpan) {
                val recent = state.recentlyPlayed.firstOrNull()
                ModernHero(hero, if (home && recent != null) {{ openRecent(recent) }} else null,
                    if (!home && hero != null) {{ play(hero) }} else null, state.savedProfile?.name.orEmpty())
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
                        item {
                            AssistChip(onClick = refreshCatalog, modifier = Modifier.remoteFocusFrame(CircleShape).focusProperties {
                                if (state.items.isNotEmpty()) down = layoutToggleRequester
                            }, label = { Text("Refresh") }, leadingIcon = {
                                Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                            })
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
                        "${state.items.size} ${state.selectedType.itemLabel(state.items.size)}",
                        action = {
                            FilledTonalIconButton(onClick = {
                                setBrowseLayout(if (state.browseLayout == BrowseLayout.GRID) BrowseLayout.LIST else BrowseLayout.GRID)
                            }, modifier = Modifier.focusRequester(layoutToggleRequester)
                                .focusProperties { up = FocusRequester.Default }
                                .remoteFocusFrame(CircleShape)) {
                                Icon(if (state.browseLayout == BrowseLayout.GRID) Icons.Default.ViewList else Icons.Default.GridView,
                                    if (state.browseLayout == BrowseLayout.GRID) "Show as list" else "Show as grid")
                            }
                        }
                    )
                }
                items(
                    state.items,
                    key = { "catalog-${state.selectedType}-${it.id}" },
                    span = { if (state.browseLayout == BrowseLayout.LIST) GridItemSpan(maxLineSpan) else GridItemSpan(1) }
                ) { item ->
                    if (state.browseLayout == BrowseLayout.LIST) {
                        ModernMediaListCard(
                            item,
                            onClick = { play(item) },
                            isFavorite = state.favorites.any { it.media.id == item.id && it.kind == state.selectedType.favoriteKind() },
                            toggleFavorite = { toggleFavorite(item) }
                        )
                    } else {
                        ModernPosterCard(
                            item,
                            aspectRatio,
                            Modifier.padding(horizontal = 4.dp),
                            onClick = { play(item) },
                            isFavorite = state.favorites.any { it.media.id == item.id && it.kind == state.selectedType.favoriteKind() },
                            toggleFavorite = { toggleFavorite(item) }
                        )
                    }
                }
                if (state.selectedType in setOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES) && state.catalogHasMore) {
                    item("catalog-load-more", span = gridSpan) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp), contentAlignment = Alignment.Center) {
                            Button(
                                onClick = loadMoreCatalog,
                                enabled = !state.catalogLoadingMore,
                                modifier = Modifier.height(48.dp).remoteFocusFrame(RoundedCornerShape(10.dp)),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914), contentColor = Color.White)
                            ) {
                                if (state.catalogLoadingMore) {
                                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                                    Spacer(Modifier.width(10.dp))
                                    Text("Loading…", color = Color.White)
                                } else {
                                    Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (state.selectedType == CatalogType.LIVE_TV) "Load more channels" else "Load more titles",
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

@Composable
private fun ModernGrid(
    columns: Int,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(12.dp),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.spacedBy(10.dp),
    content: LazyGridScope.() -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        horizontalArrangement = horizontalArrangement,
        content = content
    )
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
    expanded: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(modifier, color = Color(0xFF070707), shadowElevation = 12.dp) {
        Column(
            Modifier.statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = if (expanded) 10.dp else 6.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = if (expanded) 12.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
            ) {
                Text("N", style = MaterialTheme.typography.headlineLarge, color = Color(0xFFE50914), fontWeight = FontWeight.Black)
                if (expanded) {
                    Spacer(Modifier.width(12.dp))
                    Text("NikTV", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.weight(1f))
            ModernRailButton(Icons.Default.Home, "Home", state.homeOpen, expanded, openHome)
            visibleCatalogTypes.forEach { type ->
                ModernRailButton(type.icon(), type.title, !state.homeOpen && !state.favoritesOpen && state.selectedType == type, expanded) { selectType(type) }
            }
            ModernRailButton(Icons.Default.Favorite, "My List", state.favoritesOpen, expanded, openFavorites)
            Spacer(Modifier.weight(1f))
            ModernRailButton(Icons.Default.Search, "Search", state.searchOpen, expanded, openSearch)
            ModernRailButton(Icons.Default.Settings, "Settings", state.settingsOpen, expanded, openSettings)
        }
    }
}

@Composable
private fun ModernRailButton(icon: ImageVector, label: String, selected: Boolean, expanded: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(50.dp).padding(vertical = 3.dp)
            .onFocusChanged { focused = it.isFocused }
            .semantics { role = Role.Tab; this.selected = selected }
            .then(if (focused) Modifier.shadow(12.dp, shape, ambientColor = Color(0xFFE50914), spotColor = Color(0xFFE50914)) else Modifier),
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
private fun ModernTopBar(state: NikTvState, home: Boolean, openHome: () -> Unit, selectType: (CatalogType) -> Unit, openFavorites: () -> Unit, openSearch: () -> Unit, openSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Color(0xFF090909)).statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("N", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = Color(0xFFE50914))
        Spacer(Modifier.width(8.dp))
        TextButton(onClick = openHome, modifier = Modifier.remoteFocusFrame(CircleShape)) { Text("Home", color = if (home) Color.White else Color.Gray) }
        visibleCatalogTypes.forEach { type ->
            TextButton(onClick = { selectType(type) }, modifier = Modifier.remoteFocusFrame(CircleShape), contentPadding = PaddingValues(horizontal = 6.dp)) {
                Text(type.title, color = if (!home && state.selectedType == type) Color.White else Color.Gray, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = openSearch, modifier = Modifier.remoteFocusFrame(CircleShape)) { Icon(Icons.Default.Search, "Search", tint = Color.White) }
        IconButton(onClick = openFavorites, modifier = Modifier.remoteFocusFrame(CircleShape)) { Icon(Icons.Default.FavoriteBorder, "My List", tint = Color.White) }
        IconButton(onClick = openSettings, modifier = Modifier.remoteFocusFrame(CircleShape)) { Icon(Icons.Default.Settings, "Settings", tint = Color.White) }
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
    footer: (@Composable () -> Unit)? = null
) {
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val artworkModel = remember(item.id, item.title, item.logo) { artworkRequest(context, item) }
    val fraction = if (progress != null && progress.durationMillis > 0L)
        (progress.positionMillis.toFloat() / progress.durationMillis).coerceIn(0f, 1f) else 0f
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .combinedClickable(
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
                if (item.logo.isNullOrBlank()) Icon(Icons.Default.SmartDisplay, null, Modifier.size(42.dp), tint = Color.LightGray)
                else SubcomposeAsyncImage(artworkModel, item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) {
                    when (painter.state.value) {
                        is coil3.compose.AsyncImagePainter.State.Success -> SubcomposeAsyncImageContent()
                        else -> Icon(Icons.Default.SmartDisplay, null, Modifier.size(42.dp), tint = Color.LightGray)
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
                style = MaterialTheme.typography.labelLarge,
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
    onClick: () -> Unit,
    isFavorite: Boolean,
    toggleFavorite: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.shadow(16.dp, RoundedCornerShape(12.dp), ambientColor = Color(0xFFE50914), spotColor = Color(0xFFE50914)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true }),
        shape = RoundedCornerShape(12.dp),
        color = if (focused) Color(0xFF292929) else Color(0xFF171717),
        border = if (focused) BorderStroke(4.dp, Color(0xFFFF2633)) else null
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF242424)), contentAlignment = Alignment.Center) {
                if (item.logo.isNullOrBlank()) Icon(Icons.Default.SmartDisplay, null, tint = Color.LightGray)
                else AsyncImage(artworkRequest(context, item), item.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.title, Modifier.then(if (focused) Modifier.basicMarquee(Int.MAX_VALUE) else Modifier), color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                item.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = Color.Gray, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.Default.PlayArrow, null, tint = if (focused) Color.White else Color.Gray)
        }
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
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
    loadMore: () -> Unit
) {
    var categoriesExpanded by rememberSaveable(state.searchType) { mutableStateOf(false) }
    var searchEditing by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val wide = LocalConfiguration.current.screenWidthDp >= 720
    val columns = if (state.searchType == SearchContentType.LIVE_TV) {
        if (wide) 6 else 4
    } else {
        if (wide) 6 else 3
    }
    val aspectRatio = 16f / 9f
    fun activateSearchField() {
        searchEditing = true
        focusRequester.requestFocus()
        keyboard?.show()
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF090909)).padding(horizontal = 16.dp)) {
        ModernScreenTopBar("Search", close)
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visibleSearchTypes) { type ->
                FilterChip(
                    selected = state.searchType == type,
                    onClick = { setType(type) },
                    modifier = Modifier.remoteFocusFrame(CircleShape),
                    label = { Text(type.title) },
                    leadingIcon = { Icon(if (type == SearchContentType.LIVE_TV) Icons.Default.LiveTv else if (type == SearchContentType.MOVIES) Icons.Default.Movie else Icons.Default.VideoLibrary, null) }
                )
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
                .pointerInput(searchEditing) {
                    if (!searchEditing) detectTapGestures { activateSearchField() }
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
            readOnly = !searchEditing,
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
                    InputChip(
                        selected = false,
                        onClick = { useRecent(recent) },
                        modifier = Modifier.remoteFocusFrame(CircleShape),
                        label = { Text(recent.query) },
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
            ModernGrid(
                columns = columns,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.searchResults, key = { "search-${state.searchType}-${it.id}" }) { item ->
                    ModernPosterCard(item, aspectRatio, onClick = { openResult(item) })
                }
                if (state.searchHasMore) item("load-more-${state.searchPage}", span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedButton(onClick = loadMore, enabled = !state.searchServerLoading, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).remoteFocusFrame()) {
                        if (state.searchServerLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.ExpandMore, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Load up to 3 more pages")
                    }
                } else if (state.searchUsedServer) item("all-pages-loaded", span = { GridItemSpan(maxLineSpan) }) {
                    Text("All available result pages loaded", Modifier.fillMaxWidth().padding(16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
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
    switchProfile: (PortalProfile) -> Unit,
    removeProfile: (PortalProfile) -> Unit,
    logout: () -> Unit,
    setCacheIntervalMinutes: (Int) -> Unit,
    setSeriesStartSeason: (SeriesStartSeason) -> Unit,
    openCategoryManager: (CatalogType) -> Unit
) {
    val profile = state.savedProfile ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingRemoval by remember { mutableStateOf<PortalProfile?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var downloadActionMessage by remember { mutableStateOf<String?>(null) }
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var pendingPermissionUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    val downloadState by AppUpdates.downloadState.collectAsStateWithLifecycle()
    val pendingUpdate by AppUpdates.pendingUpdate.collectAsStateWithLifecycle()
    val performDownload: (UpdateInfo) -> Unit = { update ->
        downloadActionMessage = null
        runCatching { AppUpdates.download(context, update) }
            .onSuccess {
                updateMessage = "Downloading ${update.version}…"
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
        SettingsSection("Connection") {
            SettingsValueRow(Icons.Default.AccountCircle, "Profile", profile.name)
            HorizontalDivider()
            SettingsValueRow(Icons.Default.Language, "Portal", profile.portalUrl)
            HorizontalDivider()
            SettingsValueRow(Icons.Default.Security, "Session", if (state.session != null) "Authenticated" else "Authentication required")
            HorizontalDivider()
            SettingsValueRow(Icons.Default.Wifi, "Device MAC Address", deviceMacAddress)
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
                        val intervalShape = when (index) {
                            0 -> RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
                            3 -> RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
                            else -> RoundedCornerShape(0.dp)
                        }
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
                        SegmentedButton(
                            selected = state.seriesStartSeason == option,
                            onClick = { setSeriesStartSeason(option) },
                            modifier = Modifier.remoteFocusFrame(SegmentedButtonDefaults.itemShape(index, SeriesStartSeason.entries.size)),
                            shape = SegmentedButtonDefaults.itemShape(index, SeriesStartSeason.entries.size)
                        ) { Text(if (option == SeriesStartSeason.FIRST) "First season" else "Latest season") }
                    }
                }
            }
        }
        SettingsSection("App updates") {
            Column {
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
                    modifier = Modifier.remoteFocusFrame().clickable(enabled = !checkingUpdate) {
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
        AlertDialog(
            onDismissRequest = { availableUpdate = null },
            title = { Text("NikTV ${update.version} is available") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Download the signed APK to ${AppUpdates.savedLocation(update.version)}. Android will ask you to confirm installation when it is ready.")
                    downloadActionMessage?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { requestUpdateDownload(update) },
                    enabled = AppUpdates.canStartDownload(update)
                ) {
                    Text(
                        if (AppUpdates.canWritePublicDownloads(context)) "Download"
                        else "Allow & download"
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    AppUpdates.dismissPendingUpdate(update)
                    availableUpdate = null
                    pendingPermissionUpdate = null
                }) { Text("Later") }
            },
        )
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
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    val hash = MessageDigest.getInstance("MD5").digest(androidId.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "00:1E:99:${hash.substring(0, 6).chunked(2).joinToString(":")}".uppercase()
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
    val categoryIsTv = categoryContext.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        categoryConfiguration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
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
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
                .widthIn(max = 1080.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color.Black,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
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

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    visibleCatalogTypes.forEachIndexed { index, catalogType ->
                        var typeFocused by remember(catalogType) { mutableStateOf(false) }
                        val selected = type == catalogType
                        Surface(
                            onClick = { setType(catalogType) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) Color(0xFF351416) else Color.Black,
                            border = when {
                                typeFocused -> BorderStroke(4.dp, Color(0xFFFF3340))
                                selected -> BorderStroke(1.dp, Color(0xFFE50914))
                                else -> BorderStroke(1.dp, Color(0xFF666666))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
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
                                .onFocusChanged { typeFocused = it.isFocused }
                        ) {
                            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                if (selected) {
                                    Icon(Icons.Default.Check, null, Modifier.size(17.dp), tint = Color(0xFFE50914))
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(catalogType.title, color = Color.White, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                            }
                        }
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
                                    readOnly = !searchEditing,
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
                        if (!searchEditing) {
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
    refreshCatalog: () -> Unit
) {
    val series = state.selectedSeries ?: return
    var episodeSortDescending by rememberSaveable(series.id) { mutableStateOf(true) }
    var searchQuery by rememberSaveable(series.id) { mutableStateOf("") }
    var episodeSearchEditing by rememberSaveable(series.id) { mutableStateOf(false) }
    var seasonDropdownExpanded by remember { mutableStateOf(false) }
    val episodeSearchRequester = remember(series.id) { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

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

    Box(Modifier.fillMaxSize().background(Color(0xFF090909))) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 64.dp)
        ) {
            item("series-hero") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
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

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
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
                            .padding(horizontal = 24.dp, vertical = 6.dp)
                    )
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
