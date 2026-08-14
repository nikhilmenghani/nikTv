package com.nikhil.niktv.ui

import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
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

private val NikColors = darkColorScheme(primary = Color(0xFF9B87F5), secondary = Color(0xFF4FD1C5), background = Color(0xFF080B12), surface = Color(0xFF111827))

@Composable
fun NikTvApp(vm: NikTvViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    MaterialTheme(colorScheme = NikColors) {
        Surface(Modifier.fillMaxSize()) {
            when {
                state.nowPlaying != null -> PlayerScreen(state.nowPlaying!!, vm::closePlayer)
                state.session == null -> ProfileScreen(state.savedProfile, state.loading, vm::connect, vm::reconnect)
                else -> CatalogScreen(state, vm::loadType, vm::loadCategory, vm::play, vm::logout)
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
    BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
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
private fun CatalogScreen(state: NikTvState, selectType: (CatalogType) -> Unit, selectCategory: (Category) -> Unit, play: (MediaItem) -> Unit, logout: () -> Unit) {
    val configuration = LocalConfiguration.current
    val isTv = LocalContext.current.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val wide = configuration.screenWidthDp >= 720
    if (wide || isTv) Row(Modifier.fillMaxSize()) {
        NavigationPanel(state, selectType, logout, Modifier.width(220.dp).fillMaxHeight())
        CatalogContent(state, selectCategory, play, Modifier.weight(1f), isTv)
    } else Column(Modifier.fillMaxSize()) {
        CatalogContent(state, selectCategory, play, Modifier.weight(1f), false)
        CatalogBottomNavigation(state, selectType)
    }
}

@Composable
private fun NavigationPanel(state: NikTvState, selectType: (CatalogType) -> Unit, logout: () -> Unit, modifier: Modifier) {
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("nikTv", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(8.dp))
        Spacer(Modifier.weight(1f))
        CatalogType.entries.forEach { type ->
            CatalogNavigationItem(type.title, type.icon(), state.selectedType == type, { selectType(type) }, Modifier.fillMaxWidth(), alwaysShowLabel = true)
        }
        Spacer(Modifier.weight(1f))
        CatalogNavigationItem("Profiles", Icons.Default.Logout, false, logout, Modifier.fillMaxWidth(), alwaysShowLabel = true)
    }
}

@Composable
private fun CatalogBottomNavigation(state: NikTvState, selectType: (CatalogType) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(max = 448.dp).fillMaxWidth().height(76.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CatalogType.entries.forEach { type ->
                val selected = state.selectedType == type
                CatalogNavigationItem(
                    label = type.title,
                    icon = type.icon(),
                    selected = selected,
                    onClick = { selectType(type) },
                    modifier = (if (selected) Modifier.weight(1f) else Modifier.width(64.dp)).fillMaxHeight()
                )
            }
        }
    }
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
private fun CatalogContent(state: NikTvState, selectCategory: (Category) -> Unit, play: (MediaItem) -> Unit, modifier: Modifier, tv: Boolean) {
    var filterExpanded by rememberSaveable(state.selectedType) { mutableStateOf(false) }
    Column(modifier.statusBarsPadding().padding(horizontal = if (tv) 28.dp else 16.dp, vertical = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.selectedType.title, style = if (tv) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text(
                    "${state.items.size} ${state.selectedType.itemLabel(state.items.size)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { filterExpanded = !filterExpanded }) {
                Icon(Icons.Default.FilterAlt, if (filterExpanded) "Close category filters" else "Filter by category")
            }
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
                    Text("Filter ${state.selectedType.title}", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        state.categories.forEach { category ->
                            FilterChip(
                                selected = state.selectedCategory?.id == category.id,
                                onClick = { selectCategory(category); filterExpanded = false },
                                label = { Text(category.title) }
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (state.items.isEmpty() && !state.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No items returned by this category", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            items(state.items, key = { it.id }) { item -> MediaListItem(item, play, tv) }
        }
    }
}

@Composable
private fun MediaListItem(item: MediaItem, play: (MediaItem) -> Unit, tv: Boolean) {
    var focused by remember { mutableStateOf(false) }
    Card(onClick = { play(item) }, modifier = Modifier.onFocusChanged { focused = it.isFocused }.then(if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier), shape = RoundedCornerShape(12.dp)) {
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
