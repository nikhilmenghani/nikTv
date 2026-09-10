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

@Composable
internal fun ProfileScreen(saved: PortalProfile?, profiles: List<PortalProfile>, editorOpen: Boolean, loading: Boolean, openSettings: (PortalProfile?) -> Unit, connect: (PortalProfile) -> Unit, selectProfile: (PortalProfile) -> Unit, addProfile: () -> Unit, cancelEditor: () -> Unit, importBackup: (android.net.Uri) -> Unit, openOfflineDownloads: () -> Unit) {
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(importBackup)
    }

    if (profiles.isNotEmpty() && !editorOpen) {
        // PROFILE_CHOOSER_POLISH_V18
        //
        // Keep NikTV red as an accent instead of turning the entire focused
        // tile red. TV focus uses motion + a light ring + a restrained red
        // glow, which reads more like a premium media UI and less like an
        // error/alert state.
        val configuration = LocalConfiguration.current
        val chooserContext = LocalContext.current
        val isTv = chooserContext.isTvLikeDevice(configuration)

        val compactLandscape =
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                    configuration.screenHeightDp < 500

        val scrollState = rememberScrollState()

        val preferredProfileKey =
            remember(saved, profiles) {
                saved?.cacheKey()
                    ?.takeIf { savedKey ->
                        profiles.any { it.cacheKey() == savedKey }
                    }
                    ?: profiles.firstOrNull()?.cacheKey()
            }

        val preferredRequester =
            remember(preferredProfileKey) {
                FocusRequester()
            }

        LaunchedEffect(isTv, preferredProfileKey) {
            if (isTv && preferredProfileKey != null) {
                withFrameNanos { }
                delay(80L)
                runCatching {
                    preferredRequester.requestFocus()
                }
            }
        }

        Column(
            modifier = Modifier
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
                    if (compactLandscape) 4.dp else 20.dp
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
                color = Color(0xFFF5F5F7)
            )

            Text(
                "Choose an IPTV profile",
                color = Color(0xFF9B9FA8),
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
                        top = if (compactLandscape) 10.dp else 30.dp
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
                    val profileKey = profile.cacheKey()
                    val lastUsed =
                        saved?.cacheKey() == profileKey

                    ProfileChooserTile(
                        title = profile.name,
                        subtitle = profile.portalType.displayName(),
                        icon = if (profile.portalType == PortalType.STALKER) {
                            Icons.Default.Tv
                        } else {
                            Icons.Default.Key
                        },
                        compact = compactLandscape,
                        selected = lastUsed,
                        modifier =
                            if (
                                isTv &&
                                profileKey == preferredProfileKey
                            ) {
                                Modifier.focusRequester(
                                    preferredRequester
                                )
                            } else {
                                Modifier
                            }
                    ) {
                        selectProfile(profile)
                    }
                }

                ProfileChooserTile(
                    title = "Offline",
                    subtitle = "Downloaded movies & episodes",
                    icon = Icons.Default.DownloadDone,
                    compact = compactLandscape
                ) { openOfflineDownloads() }

                ProfileChooserTile(
                    title = "Settings",
                    subtitle = "Playback, display & orientation",
                    icon = Icons.Default.Settings,
                    compact = compactLandscape
                ) {
                    openSettings(saved ?: profiles.firstOrNull())
                }

                ProfileChooserTile(
                    title = "Add profile",
                    subtitle = "New connection",
                    icon = Icons.Default.Add,
                    compact = compactLandscape,
                    onClick = addProfile
                )

                ProfileChooserTile(
                    title = "Import backup",
                    subtitle = "Restore NikTV setup",
                    icon = Icons.Default.FileDownload,
                    compact = compactLandscape
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

            if (isTv) {
                Spacer(
                    Modifier.height(
                        if (compactLandscape) 8.dp else 20.dp
                    )
                )
                Text(
                    "D-pad to move  •  OK to select",
                    color = Color(0xFF70757E),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        return
    }

    val context = LocalContext.current
    val generatedIdentity = remember(context) { cast4kLegacyDeviceIdentity(context) }
    val initial = saved ?: PortalProfile("", "", "")
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
        if (saved == null) {
            name = ""; url = ""; mac = ""; serial = ""; username = ""; password = ""
            advanced = false
        }
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { keyboard?.hide(); connect(PortalProfile(name.trim(), url.trim(), mac.trim(), serial.trim(), portalType, username.trim(), password)) }, enabled = !loading && name.isNotBlank() && url.isNotBlank() && credentialsReady, modifier = Modifier.weight(1f)) { Text(if (saved == null) "Add profile" else "Save profile") }
                    if (saved == null) OutlinedButton(onClick = { keyboard?.hide(); openSettings(null) }, modifier = Modifier.weight(1f)) { Text("Skip Profile") }
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
                    modifier = Modifier.fillMaxWidth().remoteFocusFrame(RoundedCornerShape(10.dp))
                ) { Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Import NikTV backup") }
                Text("Only connect to services you are authorized to access.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

@Composable
internal fun ProfileChooserTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    compact: Boolean = false,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 170),
        label = "profileChooserTileFocus"
    )

    val tileWidth = if (compact) 128.dp else 156.dp
    val tileHeight = if (compact) 148.dp else 190.dp
    val iconPlateSize = if (compact) 58.dp else 74.dp
    val iconSize = if (compact) 30.dp else 38.dp
    val outerPadding = if (compact) 10.dp else 14.dp
    val shape =
        RoundedCornerShape(
            if (compact) 14.dp else 18.dp
        )

    val scale =
        1f + (0.045f * focusProgress)

    val iconScale =
        1f + (0.08f * focusProgress)

    val backgroundColor =
        lerp(
            Color(0xFF15171B),
            Color(0xFF22252B),
            focusProgress
        )

    val borderColor =
        lerp(
            Color(0xFF30343B),
            Color(0xFFF2F3F5),
            focusProgress
        )

    val iconBackground =
        lerp(
            Color(0xFF24272D),
            Color(0xFF35191D),
            focusProgress
        )

    val iconColor =
        lerp(
            Color(0xFFB8BCC4),
            Color.White,
            focusProgress
        )

    val titleColor =
        lerp(
            Color(0xFFD4D7DC),
            Color.White,
            focusProgress
        )

    val subtitleColor =
        lerp(
            Color(0xFF858B94),
            Color(0xFFBFC3CA),
            focusProgress
        )

    Column(
        modifier
            .width(tileWidth)
            .height(tileHeight)
            .zIndex(focusProgress)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = (14f * focusProgress).dp,
                shape = shape,
                clip = false,
                ambientColor = Color(0x66000000),
                spotColor = Color(0x55E50914)
            )
            .clip(shape)
            .background(backgroundColor)
            .border(
                width = (1f + focusProgress).dp,
                color = borderColor,
                shape = shape
            )
            .semantics {
                this.selected = selected
                role = Role.Button
            }
            .onFocusChanged {
                focused = it.isFocused
            }
            .clickable(onClick = onClick)
            .padding(outerPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(iconPlateSize)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    }
                    .clip(
                        RoundedCornerShape(
                            if (compact) 14.dp else 18.dp
                        )
                    )
                    .background(iconBackground),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    title,
                    Modifier.size(iconSize),
                    tint = iconColor
                )
            }

            if (selected) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(
                            if (compact) 22.dp else 24.dp
                        ),
                    shape = CircleShape,
                    color = Color(0xFFE50914),
                    shadowElevation = 3.dp
                ) {
                    Box(
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = "Last used profile",
                            modifier = Modifier.size(
                                if (compact) 14.dp else 15.dp
                            ),
                            tint = Color.White
                        )
                    }
                }
            }
        }

        Spacer(
            Modifier.height(
                if (compact) 7.dp else 10.dp
            )
        )

        Text(
            title,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = if (compact) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.titleMedium
            },
            fontWeight =
                if (focused || selected) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Medium
                }
        )

        Spacer(
            Modifier.height(
                if (compact) 2.dp else 4.dp
            )
        )

        Text(
            subtitle,
            color = subtitleColor,
            maxLines = if (compact) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall,
            textAlign =
                androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(Modifier.weight(1f))

        Box(
            Modifier
                .width(
                    if (compact) 32.dp else 42.dp
                )
                .height(3.dp)
                .graphicsLayer {
                    alpha = focusProgress
                }
                .clip(CircleShape)
                .background(Color(0xFFE50914))
        )
    }
}
