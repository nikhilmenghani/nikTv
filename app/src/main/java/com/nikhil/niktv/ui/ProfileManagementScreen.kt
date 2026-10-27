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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
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

        val compactChooser = isTv || configuration.smallestScreenWidthDp < 600
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

        val remoteChooser = chooserContext.usesRemoteNavigation(configuration) ||
            androidx.compose.ui.platform.LocalInputModeManager.current.inputMode == androidx.compose.ui.input.InputMode.Keyboard

        Column(
            modifier = Modifier
                .fillMaxSize()
                .initialFocusEntry(preferredRequester, remoteChooser)
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
                .verticalScroll(scrollState)
                .padding(
                    horizontal = if (compactChooser) 12.dp else 24.dp,
                    vertical = if (compactChooser) 8.dp else 24.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement =
                if (compactChooser && !isTv && configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) Arrangement.Top
                else Arrangement.Center
        ) {
            Image(
                painter = painterResource(R.drawable.niktv_logo_foreground),
                contentDescription = "NikTV",
                modifier = Modifier
                    .size(if (isTv) 80.dp else if (compactChooser && configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 56.dp else if (compactChooser) 80.dp else 140.dp)
                    // The launcher foreground has generous transparent safe-area padding.
                    .graphicsLayer { scaleX = if (isTv) 2.1f else 1.8f; scaleY = scaleX },
                contentScale = ContentScale.Fit
            )

            Spacer(
                Modifier.height(
                    if (compactChooser) 4.dp else 20.dp
                )
            )

            Text(
                "Who's watching?",
                style = if (compactChooser) {
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
                style = if (compactChooser) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.bodyMedium
                }
            )

            BoxWithConstraints(Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
                val gap = if (compactChooser) 12.dp else 24.dp
                val tileCount = profiles.size + 5
                val capacity = ((maxWidth + gap) / ((if (compactChooser) 148.dp else 180.dp) + gap)).toInt().coerceIn(1, 5)
                val rowCount = (tileCount + capacity - 1) / capacity
                val columns = (tileCount + rowCount - 1) / rowCount
                val portraitPhone = !isTv && compactChooser && configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                val portraitCardHeight = if (portraitPhone) {
                    ((configuration.screenHeightDp.dp - 260.dp - gap * (rowCount - 1)) / rowCount)
                        .coerceIn(140.dp, 260.dp)
                } else null
                val chooserTileWidth = ((maxWidth - gap * (columns - 1)) / columns).coerceAtMost(220.dp)
            BalancedProfileGrid(
                columns = columns,
                gap = gap,
                modifier = Modifier.fillMaxWidth().padding(top = if (compactChooser) 10.dp else 30.dp)
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
                        tileWidth = chooserTileWidth,
                        portraitCardHeight = portraitCardHeight,
                        compact = compactChooser,
                        selected = lastUsed,
                        modifier =
                            if (
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
                    title = "NikTube",
                    subtitle = "Discover YouTube videos",
                    icon = Icons.Default.PlayCircle,
                    tileWidth = chooserTileWidth,
                    portraitCardHeight = portraitCardHeight,
                    compact = compactChooser
                ) {
                    chooserContext.startActivity(android.content.Intent(chooserContext, com.nikhil.niktv.NikTubeActivity::class.java))
                }

                ProfileChooserTile(
                    title = "Offline",
                    subtitle = "Downloaded movies & episodes",
                    icon = Icons.Default.DownloadDone,
                    tileWidth = chooserTileWidth,
                        portraitCardHeight = portraitCardHeight,
                        compact = compactChooser
                ) { openOfflineDownloads() }

                ProfileChooserTile(
                    title = "Settings",
                    subtitle = "Playback, display & orientation",
                    icon = Icons.Default.Settings,
                    tileWidth = chooserTileWidth,
                        portraitCardHeight = portraitCardHeight,
                        compact = compactChooser
                ) {
                    openSettings(saved ?: profiles.firstOrNull())
                }

                ProfileChooserTile(
                    title = "Add profile",
                    subtitle = "New connection",
                    icon = Icons.Default.Add,
                    tileWidth = chooserTileWidth,
                        portraitCardHeight = portraitCardHeight,
                        compact = compactChooser,
                    onClick = addProfile
                )

                ProfileChooserTile(
                    title = "Import backup",
                    subtitle = "Restore NikTV setup",
                    icon = Icons.Default.FileDownload,
                    tileWidth = chooserTileWidth,
                        portraitCardHeight = portraitCardHeight,
                        compact = compactChooser
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
            if (isTv) {
                Spacer(
                    Modifier.height(
                        if (compactChooser) 8.dp else 20.dp
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
    val keyboard = LocalSoftwareKeyboardController.current
    Box(Modifier.fillMaxSize().background(Color(0xFF090909)).statusBarsPadding().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 520.dp).fillMaxHeight(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (profiles.isNotEmpty()) IconButton(onClick = cancelEditor) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to profiles") }
                    Column { Text(if (saved == null) "Add profile" else "Edit ${saved.name}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Credentials stay local to this profile", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(portalType == PortalType.STALKER, { useDefaults(PortalType.STALKER) }, uniformSegmentShape(0, 2), modifier = Modifier.remoteFocusFrame(uniformSegmentShape(0, 2))) { Text("Stalker / MAG") }
                    SegmentedButton(portalType == PortalType.XTREAM, { useDefaults(PortalType.XTREAM) }, uniformSegmentShape(1, 2), modifier = Modifier.remoteFocusFrame(uniformSegmentShape(1, 2))) { Text("Xtream") }
                }
                SettingsTextEditRow("Profile name", name, { name = it }, "Name shown in the profile chooser.")
                SettingsTextEditRow("Portal URL", url, { url = it }, "The provider portal address.", placeholder = "https://provider.example")
                if (portalType == PortalType.XTREAM) {
                    SettingsTextEditRow("Username", username, { username = it }, "The username supplied by your provider.")
                    SettingsTextEditRow("Password", password, { password = it }, "The password supplied by your provider.", password = true)
                } else {
                    SettingsTextEditRow("MAC address", mac, { mac = it }, "The MAC address registered with your provider.", placeholder = "00:1A:79:XX:XX:XX")
                    NikTvTextActionButton(onClick = {
                        mac = generatedIdentity.macAddress
                        serial = generatedIdentity.serialNumber
                        advanced = true
                    }) {
                        Icon(Icons.Default.AutoFixHigh, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Generate compatible device identity")
                    }
                    NikTvTextActionButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide advanced identity" else "Advanced identity") }
                    if (advanced) SettingsTextEditRow("Portal serial number (optional)", serial, { serial = it }, "Use the serial registered for this MAC, or leave blank to generate one.")
                }
                val credentialsReady = if (portalType == PortalType.XTREAM) username.isNotBlank() && password.isNotBlank() else mac.isNotBlank()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NikTvPrimaryActionButton(onClick = { keyboard?.hide(); connect(PortalProfile(name.trim(), url.trim(), mac.trim(), serial.trim(), portalType, username.trim(), password)) }, enabled = !loading && name.isNotBlank() && url.isNotBlank() && credentialsReady, modifier = Modifier.weight(1f)) { Text(if (saved == null) "Add profile" else "Save profile") }
                    if (saved == null) NikTvSecondaryActionButton(onClick = { keyboard?.hide(); openSettings(null) }, modifier = Modifier.weight(1f)) { Text("Skip Profile") }
                }
                NikTvSecondaryActionButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
                    modifier = Modifier.fillMaxWidth().remoteFocusFrame(RoundedCornerShape(10.dp))
                ) { Icon(Icons.Default.FileDownload, null); Spacer(Modifier.width(8.dp)); Text("Import NikTV backup") }
                Text("Only connect to services you are authorized to access.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}

/**
 * Measure explicit rows in pixels: dp rounding cannot push a tile onto another row,
 * and every row is measured even when it extends beyond the viewport.
 */
@Composable
private fun BalancedProfileGrid(columns: Int, gap: Dp, modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content = content, modifier = modifier) { measurables, constraints ->
        val spacing = gap.roundToPx()
        val width = ((constraints.maxWidth - spacing * (columns - 1)) / columns)
            .coerceAtMost(220.dp.roundToPx()).coerceAtLeast(1)
        val uniformHeight = measurables.maxOfOrNull { it.maxIntrinsicHeight(width) } ?: 0
        val children = measurables.map {
            it.measure(androidx.compose.ui.unit.Constraints.fixed(width, uniformHeight))
        }
        val rows = children.chunked(columns)
        val heights = rows.map { row -> row.maxOf { it.height } }
        val height = heights.sum() + spacing * (rows.size - 1).coerceAtLeast(0)
        layout(constraints.maxWidth, height) {
            var y = 0
            rows.forEachIndexed { index, row ->
                var x = (constraints.maxWidth - (row.size * width + spacing * (row.size - 1))) / 2
                row.forEach { child ->
                    child.placeRelative(x, y)
                    x += width + spacing
                }
                y += heights[index] + spacing
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
    tileWidth: Dp = if (compact) 128.dp else 156.dp,
    selected: Boolean = false,
    portraitCardHeight: Dp? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val profileContext = LocalContext.current
    val profileConfiguration = LocalConfiguration.current
    val remoteNavigationActive =
        profileContext.usesRemoteNavigation(profileConfiguration)

    val reducedMotion = android.provider.Settings.Global.getFloat(profileContext.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    // Read animated values in draw/layer callbacks: focus must not remeasure the grid.
    val focusProgress = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(
            durationMillis = if (remoteNavigationActive) 80 else if (focused) 160 else 130,
            easing = FastOutSlowInEasing),
        label = "profileChooserTileFocus"
    )

    val portraitScale = if (portraitCardHeight != null)
        (profileConfiguration.screenWidthDp / 384f).coerceIn(1f, 1.3f) else 1f
    val tileHeight = portraitCardHeight ?: if (compact && remoteNavigationActive) 140.dp else if (compact) 104.dp else 190.dp
    val iconPlateSize = if (portraitCardHeight != null) (portraitCardHeight * 0.35f * portraitScale).coerceIn(48.dp, 74.dp * portraitScale) else if (compact && remoteNavigationActive) 48.dp else if (compact) 32.dp else 74.dp
    val iconSize = if (portraitCardHeight != null) 36.dp * portraitScale else if (compact) 24.dp else 38.dp
    val outerPadding = if (portraitCardHeight != null) 14.dp * portraitScale else if (compact) 8.dp else 14.dp
    val shape =
        RoundedCornerShape(
            if (compact) 14.dp else 18.dp
        )

    Column(
        modifier
            .width(tileWidth)
            .heightIn(min = tileHeight)
            .zIndex(if (focused) 1f else 0f)
            // Fire TV's compositor drops more frames when scaling these large
            // cards. Remote focus uses draw-only fill/border motion instead.
            .then(if (remoteNavigationActive) Modifier else Modifier.graphicsLayer {
                val scale = if (reducedMotion) 1f else 1f + .025f * focusProgress.value
                scaleX = scale
                scaleY = scale
            })
            .clip(shape)
            .drawWithContent {
                val progress = if (remoteNavigationActive) { if (focused) 1f else 0f } else focusProgress.value
                drawRoundRect(lerp(Color(0xFF15171B), Color(0xFF22252B), progress),
                    cornerRadius = CornerRadius((if (compact) 14.dp else 18.dp).toPx()))
                drawContent()
                val stroke = (1f + progress).dp.toPx()
                val inset = stroke / 2f
                drawRoundRect(lerp(Color(0xFF30343B), Color(0xFFF2F3F5), progress),
                    topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius((if (compact) 14.dp else 18.dp).toPx() - inset),
                    style = Stroke(stroke))
            }
            .semantics {
                this.selected = selected
                role = Role.Button
            }
            .onFocusChanged {
                focused = it.isFocused
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                // This tile already supplies its own remote focus treatment.
                indication = if (remoteNavigationActive) null else LocalIndication.current,
                onClick = onClick
            )
            .padding(outerPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(iconPlateSize)
                    .clip(
                        RoundedCornerShape(
                            if (compact) 14.dp else 18.dp
                        )
                    )
                    .drawBehind {
                        val progress = if (remoteNavigationActive) { if (focused) 1f else 0f } else focusProgress.value
                        drawRect(lerp(Color(0xFF24272D), Color(0xFF35191D), progress))
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    null,
                    Modifier.size(iconSize),
                    tint = Color(0xFFE2E4E8)
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
            color = Color(0xFFF0F1F4),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = if (portraitCardHeight != null) {
                MaterialTheme.typography.titleSmall.copy(
                    fontSize = MaterialTheme.typography.titleSmall.fontSize * portraitScale,
                    lineHeight = MaterialTheme.typography.titleSmall.lineHeight * portraitScale
                )
            } else if (compact) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.titleMedium
            },
            // Keep identical glyph metrics while focus moves between tiles.
            fontWeight = FontWeight.SemiBold
        )

        Spacer(
            Modifier.height(
                if (compact) 2.dp else 4.dp
            )
        )

        Text(
            subtitle,
            color = Color(0xFF9DA4AE),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = MaterialTheme.typography.labelSmall.fontSize * portraitScale,
                lineHeight = MaterialTheme.typography.labelSmall.lineHeight * portraitScale
            ),
            textAlign =
                androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(Modifier.height(if (compact) 4.dp else 10.dp))

        Box(
            Modifier
                .width(
                    if (compact) 32.dp else 42.dp
                )
                .height(3.dp)
                .graphicsLayer {
                    alpha = focusProgress.value
                }
                .clip(CircleShape)
                .background(Color(0xFFE50914))
        )
    }
}
