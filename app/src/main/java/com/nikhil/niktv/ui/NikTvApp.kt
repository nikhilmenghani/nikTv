package com.nikhil.niktv.ui

import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
        NavigationPanel(state, selectType, logout, Modifier.width(if (isTv) 220.dp else 190.dp).fillMaxHeight())
        CatalogContent(state, selectCategory, play, Modifier.weight(1f), isTv)
    } else Column(Modifier.fillMaxSize()) {
        CatalogContent(state, selectCategory, play, Modifier.weight(1f), false)
        NavigationBar {
            CatalogType.entries.forEach { type -> NavigationBarItem(selected = state.selectedType == type, onClick = { selectType(type) }, icon = { Icon(type.icon(), null) }, label = { Text(type.title) }) }
        }
    }
}

@Composable
private fun NavigationPanel(state: NikTvState, selectType: (CatalogType) -> Unit, logout: () -> Unit, modifier: Modifier) {
    NavigationRail(modifier, header = { Text("nikTv", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(20.dp)) }) {
        CatalogType.entries.forEach { type -> NavigationRailItem(selected = state.selectedType == type, onClick = { selectType(type) }, icon = { Icon(type.icon(), null) }, label = { Text(type.title) }) }
        Spacer(Modifier.weight(1f)); NavigationRailItem(false, logout, { Icon(Icons.Default.Logout, null) }, label = { Text("Profiles") })
    }
}

@Composable
private fun CatalogContent(state: NikTvState, selectCategory: (Category) -> Unit, play: (MediaItem) -> Unit, modifier: Modifier, tv: Boolean) {
    Column(modifier.padding(horizontal = if (tv) 28.dp else 16.dp, vertical = 16.dp)) {
        Text(state.selectedType.title, style = if (tv) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        LazyRow(contentPadding = PaddingValues(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.categories, key = { it.id }) { category -> FilterChip(selected = state.selectedCategory?.id == category.id, onClick = { selectCategory(category) }, label = { Text(category.title) }) }
        }
        if (state.items.isEmpty() && !state.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No items returned by this category", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else LazyVerticalGrid(columns = GridCells.Adaptive(if (tv) 190.dp else 150.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
            items(state.items, key = { it.id }) { item -> MediaCard(item, play) }
        }
    }
}

@Composable
private fun MediaCard(item: MediaItem, play: (MediaItem) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Card(onClick = { play(item) }, modifier = Modifier.onFocusChanged { focused = it.isFocused }.then(if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier), shape = RoundedCornerShape(12.dp)) {
        Column {
            AsyncImage(item.logo, item.title, Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(Color(0xFF202938)), contentScale = ContentScale.Crop)
            Text(item.title, Modifier.padding(12.dp), maxLines = 2, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private fun CatalogType.icon() = when (this) { CatalogType.LIVE_TV -> Icons.Default.LiveTv; CatalogType.MOVIES -> Icons.Default.Movie; CatalogType.SERIES -> Icons.Default.VideoLibrary; CatalogType.RADIO -> Icons.Default.Radio }
