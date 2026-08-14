package com.nikhil.niktv.ui

import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.nikhil.niktv.model.*
import java.security.MessageDigest

private val NikColors = darkColorScheme(primary = Color(0xFF9B87F5), secondary = Color(0xFF4FD1C5), background = Color(0xFF080B12), surface = Color(0xFF111827))
private val visibleCatalogTypes = listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)

@Composable
fun NikTvApp(vm: NikTvViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    MaterialTheme(colorScheme = NikColors) {
        Surface(Modifier.fillMaxSize()) {
            when {
                state.nowPlaying != null -> PlayerScreen(state.nowPlaying!!, vm::closePlayer)
                state.restoring -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.session == null -> ProfileScreen(state.savedProfile, state.loading, vm::connect, vm::reconnect)
                else -> CatalogScreen(
                    state = state,
                    selectType = { vm.closeSettings(); vm.closeFavorites(); vm.loadType(it) },
                    selectCategory = vm::loadCategory,
                    play = vm::openMedia,
                    closeSeries = vm::closeSeries,
                    prepareFullSearch = vm::prepareFullSearch,
                    refreshFullSearch = vm::refreshFullSearch,
                    openFavorites = vm::openFavorites,
                    closeFavorites = vm::closeFavorites,
                    openFavorite = vm::openFavorite,
                    toggleFavorite = vm::toggleFavorite,
                    toggleFavoriteEntry = { vm.toggleFavorite(it) },
                    openSettings = vm::openSettings,
                    closeSettings = vm::closeSettings,
                    reauthenticate = vm::reauthenticate,
                    editProfile = vm::editProfile,
                    logout = vm::logout
                )
            }
            if (state.loading) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .42f)), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.error?.let { error ->
                AlertDialog(
                    onDismissRequest = vm::dismissError,
                    confirmButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(error)) }) { Text("Copy diagnostics") } },
                    dismissButton = { TextButton(onClick = vm::dismissError) { Text("Close") } },
                    title = { Text("Portal diagnostics") },
                    text = {
                        SelectionContainer {
                            Box(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                                Text(error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ProfileScreen(saved: PortalProfile?, loading: Boolean, connect: (PortalProfile) -> Unit, reconnect: () -> Unit) {
    var name by remember(saved) { mutableStateOf(saved?.name.orEmpty()) }
    var url by remember(saved) { mutableStateOf(saved?.portalUrl.orEmpty()) }
    var mac by remember(saved) { mutableStateOf(saved?.macAddress.orEmpty()) }
    var serial by remember(saved) { mutableStateOf(saved?.serialNumber.orEmpty()) }
    var portalType by remember(saved) { mutableStateOf(saved?.portalType ?: PortalType.STALKER) }
    var username by remember(saved) { mutableStateOf(saved?.username.orEmpty()) }
    var password by remember(saved) { mutableStateOf(saved?.password.orEmpty()) }
    var advanced by remember(saved) { mutableStateOf(saved?.serialNumber?.isNotBlank() == true) }
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        val wide = maxWidth >= 720.dp
        Row(horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (wide) Column(Modifier.widthIn(max = 380.dp)) { Text("nikTv", style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold); Text("Your portal, beautifully organized on every screen.", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Card(Modifier.widthIn(max = 480.dp)) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!wide) Text("nikTv", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text("Connect a portal", style = MaterialTheme.typography.headlineMedium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(portalType == PortalType.STALKER, { portalType = PortalType.STALKER }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Stalker / MAG") }
                        SegmentedButton(portalType == PortalType.XTREAM, { portalType = PortalType.XTREAM }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Xtream") }
                    }
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Profile name") }, singleLine = true)
                    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Portal URL") }, placeholder = { Text("http://provider.example:8080") }, singleLine = true)
                    if (portalType == PortalType.XTREAM) {
                        OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
                        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    } else {
                        OutlinedTextField(mac, { mac = it }, Modifier.fillMaxWidth(), label = { Text("MAC address") }, placeholder = { Text("00:1A:79:XX:XX:XX") }, singleLine = true)
                        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced identity" else "Advanced identity") }
                        if (advanced) OutlinedTextField(serial, { serial = it }, Modifier.fillMaxWidth(), label = { Text("Portal serial number (optional)") }, supportingText = { Text("Use the serial already registered for this MAC. Leave blank to generate one.") }, singleLine = true)
                    }
                    val credentialsReady = if (portalType == PortalType.XTREAM) username.isNotBlank() && password.isNotBlank() else mac.isNotBlank()
                    Button(onClick = { connect(PortalProfile(name.trim(), url.trim(), mac.trim(), serial.trim(), portalType, username.trim(), password)) }, enabled = !loading && name.isNotBlank() && url.isNotBlank() && credentialsReady, modifier = Modifier.fillMaxWidth()) { Text("Authenticate") }
                    if (saved != null) TextButton(onClick = reconnect, Modifier.fillMaxWidth()) { Text("Reconnect to ${saved.name}") }
                    Text("Only connect to portals and media you are authorized to access.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun CatalogScreen(
    state: NikTvState,
    selectType: (CatalogType) -> Unit,
    selectCategory: (Category) -> Unit,
    play: (MediaItem) -> Unit,
    closeSeries: () -> Unit,
    prepareFullSearch: () -> Unit,
    refreshFullSearch: () -> Unit,
    openFavorites: () -> Unit,
    closeFavorites: () -> Unit,
    openFavorite: (FavoriteItem) -> Unit,
    toggleFavorite: (MediaItem) -> Unit,
    toggleFavoriteEntry: (FavoriteItem) -> Unit,
    openSettings: () -> Unit,
    closeSettings: () -> Unit,
    reauthenticate: () -> Unit,
    editProfile: () -> Unit,
    logout: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isTv = LocalContext.current.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val wide = configuration.screenWidthDp >= 720
    BackHandler(enabled = state.settingsOpen, onBack = closeSettings)
    BackHandler(enabled = state.favoritesOpen && !state.settingsOpen, onBack = closeFavorites)
    BackHandler(enabled = state.selectedSeries != null && !state.settingsOpen, onBack = closeSeries)
    if (wide || isTv) Row(Modifier.fillMaxSize()) {
        NavigationPanel(state, selectType, openFavorites, Modifier.width(220.dp).fillMaxHeight())
        if (state.settingsOpen) SettingsContent(state, closeSettings, reauthenticate, editProfile, logout, Modifier.weight(1f))
        else if (state.favoritesOpen) FavoritesContent(state, openFavorite, toggleFavoriteEntry, openSettings, Modifier.weight(1f), isTv)
        else CatalogContent(state, selectCategory, play, toggleFavorite, closeSeries, prepareFullSearch, refreshFullSearch, openSettings, Modifier.weight(1f), isTv, false)
    } else Column(Modifier.fillMaxSize()) {
        if (state.settingsOpen) SettingsContent(state, closeSettings, reauthenticate, editProfile, logout, Modifier.weight(1f))
        else if (state.favoritesOpen) FavoritesContent(state, openFavorite, toggleFavoriteEntry, openSettings, Modifier.weight(1f), false)
        else CatalogContent(state, selectCategory, play, toggleFavorite, closeSeries, prepareFullSearch, refreshFullSearch, openSettings, Modifier.weight(1f), false, true)
        if (!state.settingsOpen) CatalogBottomNavigation(state, selectType, openFavorites)
    }
}

@Composable
private fun NavigationPanel(state: NikTvState, selectType: (CatalogType) -> Unit, openFavorites: () -> Unit, modifier: Modifier) {
    Column(modifier.statusBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("nikTv", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
        Spacer(Modifier.weight(1f))
        visibleCatalogTypes.forEach { type ->
            CatalogNavigationItem(type.title, type.icon(), !state.favoritesOpen && state.selectedType == type, { selectType(type) }, Modifier.fillMaxWidth(), alwaysShowLabel = true)
        }
        CatalogNavigationItem("Favorites", Icons.Default.Favorite, state.favoritesOpen, openFavorites, Modifier.fillMaxWidth(), alwaysShowLabel = true)
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun CatalogBottomNavigation(state: NikTvState, selectType: (CatalogType) -> Unit, openFavorites: () -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(max = 448.dp).fillMaxWidth().height(76.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            visibleCatalogTypes.forEach { type ->
                val selected = !state.favoritesOpen && state.selectedType == type
                CatalogNavigationItem(
                    label = type.title,
                    icon = type.icon(),
                    selected = selected,
                    onClick = { selectType(type) },
                    modifier = (if (selected) Modifier.weight(1f) else Modifier.width(64.dp)).fillMaxHeight()
                )
            }
            CatalogNavigationItem(
                "Favorites", Icons.Default.Favorite, state.favoritesOpen, openFavorites,
                (if (state.favoritesOpen) Modifier.weight(1f) else Modifier.width(64.dp)).fillMaxHeight()
            )
        }
    }
}

@Composable
private fun FavoritesContent(
    state: NikTvState,
    openFavorite: (FavoriteItem) -> Unit,
    toggleFavorite: (FavoriteItem) -> Unit,
    openSettings: () -> Unit,
    modifier: Modifier,
    tv: Boolean
) {
    val groups = FavoriteKind.entries.mapNotNull { kind ->
        state.favorites.filter { it.kind == kind }.takeIf { it.isNotEmpty() }?.let { kind to it }
    }
    Column(modifier.statusBarsPadding().padding(horizontal = if (tv) 28.dp else 16.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Favorites", style = if (tv) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("${state.favorites.size} saved", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "Settings") }
        }
        Spacer(Modifier.height(12.dp))
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.FavoriteBorder, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No favorites yet", style = MaterialTheme.typography.titleMedium)
                    Text("Press and hold any media item to add it here", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            groups.forEach { (kind, favorites) ->
                item("header-${kind.name}") {
                    Text(kind.sectionTitle(), Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                items(favorites, key = { it.key }) { favorite ->
                    MediaListItem(favorite.media, { openFavorite(favorite) }, { toggleFavorite(favorite) }, true, tv)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    state: NikTvState,
    closeSettings: () -> Unit,
    reauthenticate: () -> Unit,
    editProfile: () -> Unit,
    logout: () -> Unit,
    modifier: Modifier
) {
    val profile = state.savedProfile ?: return
    val context = LocalContext.current
    val deviceMacAddress = remember(context) { cast4kStyleDeviceMacAddress(context) }
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = closeSettings) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
            )
        }
    ) { padding -> Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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
                modifier = Modifier.clickable(onClick = reauthenticate),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Edit connection") },
                supportingContent = { Text("Change portal address or credentials") },
                leadingContent = { Icon(Icons.Default.Edit, null) },
                modifier = Modifier.clickable(onClick = editProfile),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Sign out", color = MaterialTheme.colorScheme.error) },
                supportingContent = { Text("Remove the saved profile and session from this device") },
                leadingContent = { Icon(Icons.Default.Logout, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable(onClick = logout),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
        }
        Text(
            "nikTv keeps the active profile and session in this app's private storage. Expired sessions are refreshed automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Surface(
            Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
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

@Composable
private fun CatalogNavigationItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    alwaysShowLabel: Boolean = false
) {
    val containerColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "catalogNavigationContainer"
    )
    val contentColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "catalogNavigationContent"
    )
    Surface(
        onClick = onClick,
        modifier = modifier.semantics { role = Role.Tab; this.selected = selected },
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = if (selected || alwaysShowLabel) null else label, Modifier.size(24.dp))
            AnimatedVisibility(
                visible = selected || alwaysShowLabel,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally()
            ) {
                Text(label, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CatalogContent(state: NikTvState, selectCategory: (Category) -> Unit, play: (MediaItem) -> Unit, toggleFavorite: (MediaItem) -> Unit, closeSeries: () -> Unit, prepareFullSearch: () -> Unit, refreshFullSearch: () -> Unit, openSettings: () -> Unit, modifier: Modifier, tv: Boolean, hasBottomNavigation: Boolean) {
    val activity = LocalContext.current as? Activity
    val filterMaxHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.55f).coerceAtLeast(240.dp)
    var filterExpanded by rememberSaveable(state.selectedType, state.selectedSeries?.id) { mutableStateOf(false) }
    var selectedSeason by rememberSaveable(state.selectedSeries?.id) { mutableStateOf<Int?>(null) }
    var episodeSortDescending by rememberSaveable(state.selectedSeries?.id) { mutableStateOf(false) }
    var sortExpanded by rememberSaveable(state.selectedSeries?.id) { mutableStateOf(false) }
    var searchVisible by rememberSaveable(state.selectedType, state.selectedCategory?.id, state.selectedSeries?.id) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(state.selectedType, state.selectedCategory?.id, state.selectedSeries?.id) { mutableStateOf("") }
    val searchFocusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val seasonItems = if (state.selectedSeries != null && selectedSeason != null) state.items.filter { it.seasonNumber == selectedSeason } else state.items
    val searchableItems = if (searchVisible && searchQuery.isNotBlank() && state.selectedSeries == null) state.fullSearchItems ?: state.items else seasonItems
    val filteredItems = remember(searchableItems, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) searchableItems else searchableItems.filter { item ->
            item.title.contains(query, ignoreCase = true)
        }
    }
    val displayedItems = remember(filteredItems, state.selectedSeries, episodeSortDescending) {
        if (state.selectedSeries == null) filteredItems else {
            val titleComparator = Comparator<MediaItem> { first, second ->
                val firstNumber = first.title.episodeNumberFromTitle()
                val secondNumber = second.title.episodeNumberFromTitle()
                when {
                    firstNumber != null && secondNumber != null && firstNumber != secondNumber -> firstNumber.compareTo(secondNumber)
                    firstNumber != null && secondNumber == null -> -1
                    firstNumber == null && secondNumber != null -> 1
                    else -> naturalTitleCompare(first.title, second.title)
                }
            }.let { if (episodeSortDescending) it.reversed() else it }

            if (selectedSeason != null) filteredItems.sortedWith(titleComparator)
            else filteredItems.groupBy { it.seasonNumber }.entries
                .sortedWith(compareBy({ it.key == null }, { it.key ?: Int.MAX_VALUE }))
                .flatMap { (_, episodes) -> episodes.sortedWith(titleComparator) }
        }
    }
    val resultCount = if (searchQuery.isBlank()) searchableItems.size else filteredItems.size
    val searchTarget = if (state.selectedSeries != null) "episodes" else state.selectedType.searchLabel()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(searchVisible) {
        if (searchVisible) {
            searchFocusRequester.requestFocus()
            keyboard?.show()
        }
    }
    DisposableEffect(searchVisible, activity) {
        if (!searchVisible || activity == null) return@DisposableEffect onDispose { }
        val window = activity.window
        val previousSoftInputMode = window.attributes.softInputMode
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        onDispose { window.setSoftInputMode(previousSoftInputMode) }
    }
    Box(modifier.statusBarsPadding()) {
    Column(Modifier.fillMaxSize().padding(horizontal = if (tv) 28.dp else 16.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (state.selectedSeries != null) {
                IconButton(onClick = closeSeries) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to series") }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    state.selectedSeries?.title ?: state.selectedType.title,
                    style = when { state.selectedSeries != null -> MaterialTheme.typography.headlineMedium; tv -> MaterialTheme.typography.displaySmall; else -> MaterialTheme.typography.headlineLarge },
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "$resultCount ${if (state.selectedSeries != null) resultCount.episodeLabel() else state.selectedType.itemLabel(resultCount)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { filterExpanded = !filterExpanded }) {
                Icon(Icons.Default.FilterAlt, if (filterExpanded) "Close filters" else if (state.selectedSeries != null) "Filter by season" else "Filter by category")
            }
            if (state.selectedSeries != null) Box {
                IconButton(onClick = { sortExpanded = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, "Sort episodes")
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Ascending") },
                        onClick = { episodeSortDescending = false; sortExpanded = false },
                        leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                        trailingIcon = if (!episodeSortDescending) {{ Icon(Icons.Default.Check, null) }} else null
                    )
                    DropdownMenuItem(
                        text = { Text("Descending") },
                        onClick = { episodeSortDescending = true; sortExpanded = false },
                        leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                        trailingIcon = if (episodeSortDescending) {{ Icon(Icons.Default.Check, null) }} else null
                    )
                }
            }
            IconButton(onClick = openSettings) { Icon(Icons.Default.Settings, "Settings") }
        }
        AnimatedVisibility(
            visible = filterExpanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
        ) {
            Surface(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (state.selectedSeries != null) "Choose a season" else "Filter ${state.selectedType.title}", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        Modifier.fillMaxWidth().heightIn(max = filterMaxHeight).verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (state.selectedSeries != null) {
                            FilterChip(
                                selected = selectedSeason == null,
                                onClick = { selectedSeason = null; filterExpanded = false },
                                label = { Text("All seasons") }
                            )
                            state.items.mapNotNull { it.seasonNumber }.distinct().sorted().forEach { season ->
                                FilterChip(
                                    selected = selectedSeason == season,
                                    onClick = { selectedSeason = season; filterExpanded = false },
                                    label = { Text("Season $season") }
                                )
                            }
                        } else state.categories.forEach { category ->
                            FilterChip(selected = state.selectedCategory?.id == category.id,
                                onClick = { selectCategory(category); filterExpanded = false }, label = { Text(category.title) })
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (filteredItems.isEmpty() && !state.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (searchQuery.isBlank()) "No items returned by this category" else "No results for “${searchQuery.trim()}”",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
            val groupedItems = if (state.selectedSeries != null && selectedSeason == null)
                displayedItems.groupBy { it.seasonNumber } else linkedMapOf(selectedSeason to displayedItems)
            groupedItems.forEach { (season, episodes) ->
                if (state.selectedSeries != null && selectedSeason == null) item("season-${season ?: "other"}") {
                    Text(
                        season?.let { "Season $it" } ?: "Other episodes",
                        Modifier.padding(start = 8.dp, top = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                items(episodes, key = { it.id }) { item ->
                val kind = when {
                    state.selectedType == CatalogType.LIVE_TV -> FavoriteKind.CHANNEL
                    state.selectedType == CatalogType.MOVIES -> FavoriteKind.MOVIE
                    state.selectedSeries != null -> FavoriteKind.EPISODE
                    else -> FavoriteKind.SERIES
                }
                MediaListItem(item, { play(item) }, { toggleFavorite(item) }, state.favorites.any { it.kind == kind && it.media.id == item.id }, tv)
                }
            }
        }
    }
        if (!searchVisible) {
            ExtendedFloatingActionButton(
                onClick = { searchVisible = true; prepareFullSearch() },
                icon = { Icon(Icons.Default.Search, null) },
                text = { Text("Search $searchTarget") },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
            )
        }
        AnimatedVisibility(
            visible = searchVisible,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .offset(y = if (hasBottomNavigation && imeVisible) 76.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() else 0.dp)
                .padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
            enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut()
        ) {
            Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp, shadowElevation = 8.dp) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    placeholder = { Text("Search $searchTarget") },
                    leadingIcon = { Icon(Icons.Default.Search, "Search", tint = MaterialTheme.colorScheme.primary) },
                    trailingIcon = {
                        Row {
                            if (state.selectedSeries == null) IconButton(onClick = refreshFullSearch, enabled = !state.fullSearchLoading) {
                                Icon(Icons.Default.Refresh, "Refresh all categories")
                            }
                            IconButton(onClick = {
                                if (searchQuery.isNotEmpty()) searchQuery = ""
                                else { searchVisible = false; keyboard?.hide() }
                            }) {
                                Icon(Icons.Default.Close, if (searchQuery.isNotEmpty()) "Clear search" else "Close search")
                            }
                        }
                    },
                    supportingText = {
                        Text(if (state.fullSearchLoading) "Loading all categories…" else if (state.selectedSeries == null) "Searching all ${state.selectedType.searchLabel()} · refreshes every 30 minutes" else "Searching this series")
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaListItem(item: MediaItem, onClick: () -> Unit, onLongClick: () -> Unit, isFavorite: Boolean, tv: Boolean) {
    var focused by remember { mutableStateOf(false) }
    Card(modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick).onFocusChanged { focused = it.isFocused }.then(if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                item.logo,
                item.title,
                Modifier.width(if (tv) 120.dp else 96.dp).aspectRatio(16f / 10f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF202938)),
                contentScale = ContentScale.Crop
            )
            Column(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text(item.title, maxLines = 2, style = if (tv) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                item.description?.takeIf(String::isNotBlank)?.let {
                    Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (isFavorite) Icon(Icons.Default.Favorite, contentDescription = "Favorite", tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun CatalogType.icon() = when (this) { CatalogType.LIVE_TV -> Icons.Default.LiveTv; CatalogType.MOVIES -> Icons.Default.Movie; CatalogType.SERIES -> Icons.Default.VideoLibrary; CatalogType.RADIO -> Icons.Default.Radio }
private fun CatalogType.itemLabel(count: Int) = when (this) {
    CatalogType.LIVE_TV -> if (count == 1) "channel" else "channels"
    CatalogType.MOVIES -> if (count == 1) "movie" else "movies"
    CatalogType.SERIES -> if (count == 1) "series" else "series"
    CatalogType.RADIO -> if (count == 1) "station" else "stations"
}
private fun CatalogType.searchLabel() = when (this) {
    CatalogType.LIVE_TV -> "channels"
    CatalogType.MOVIES -> "movies"
    CatalogType.SERIES -> "series"
    CatalogType.RADIO -> "stations"
}
private fun Int.episodeLabel() = if (this == 1) "episode" else "episodes"
private fun FavoriteKind.sectionTitle() = when (this) {
    FavoriteKind.CHANNEL -> "Channels"
    FavoriteKind.MOVIE -> "Movies"
    FavoriteKind.SERIES -> "Series"
    FavoriteKind.EPISODE -> "Episodes"
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

private fun cast4kStyleDeviceMacAddress(context: android.content.Context): String {
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    val hash = MessageDigest.getInstance("MD5").digest(androidId.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return "00:1E:99:${hash.substring(0, 6).chunked(2).joinToString(":")}".uppercase()
}
