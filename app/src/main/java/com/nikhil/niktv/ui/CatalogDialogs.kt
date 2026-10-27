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
import androidx.compose.ui.platform.LocalView
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
import androidx.compose.ui.window.DialogWindowProvider
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
internal fun TmdbHomeSectionsDialog(
    selected: List<TmdbHomeSection>,
    surface: DashboardSurface,
    close: () -> Unit,
    save: (List<TmdbHomeSection>) -> Unit
) {
    var choices by remember(selected) { mutableStateOf(selected.toSet()) }
    val available = remember(surface) { tmdbSectionsForSurface(surface) }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF181818),
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .fillMaxHeight(0.9f)
                .widthIn(max = 760.dp)
        ) {
            Column {
                Column(Modifier.weight(1f).padding(24.dp)) {
                    Text("${surface.displayTitle()} · TMDB sections", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Only this screen is affected.", color = Color.Gray)
                    Spacer(Modifier.height(16.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(available, key = { it.name }) { section ->
                            val checked = section in choices
                            Surface(
                                onClick = { choices = if (checked) choices - section else choices + section },
                                modifier = Modifier.fillMaxWidth().remoteFocusFrame(RoundedCornerShape(12.dp)),
                                shape = RoundedCornerShape(12.dp),
                                color = if (checked) Color(0xFF351416) else Color(0xFF181818)
                            ) {
                                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked, onCheckedChange = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(section.title, Modifier.weight(1f))
                                    Text(if (section.series) "Series" else "Movies", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
                HorizontalDivider()
                BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
                    val showText = maxWidth >= 300.dp
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NikTvSecondaryActionButton(
                            onClick = { choices = emptySet() },
                            modifier = Modifier.weight(1f).remoteFocusFrame(RoundedCornerShape(10.dp))
                        ) {
                            if (showText) Text("Clear", maxLines = 1) else Icon(Icons.Default.ClearAll, "Clear selections")
                        }
                        NikTvSecondaryActionButton(
                            onClick = close,
                            modifier = Modifier.weight(1f).remoteFocusFrame(RoundedCornerShape(10.dp))
                        ) {
                            if (showText) Text("Cancel", maxLines = 1) else Icon(Icons.Default.Close, "Cancel")
                        }
                        NikTvPrimaryActionButton(
                            onClick = { save(available.filter { it in choices }) },
                            modifier = Modifier.weight(1f).remoteFocusFrame(RoundedCornerShape(10.dp))
                        ) {
                            if (showText) Text("Apply", maxLines = 1) else Icon(Icons.Default.Check, "Apply")
                        }
                    }
                }
            }
        }
    }
}

internal fun tmdbSectionsForSurface(surface: DashboardSurface): List<TmdbHomeSection> =
    when (surface) {
        DashboardSurface.HOME -> TmdbHomeSection.entries
        DashboardSurface.MOVIES -> TmdbHomeSection.entries.filterNot { it.series }
        DashboardSurface.SERIES -> TmdbHomeSection.entries.filter { it.series }
        DashboardSurface.LIVE_TV -> emptyList()
    }

internal fun DashboardSurface.displayTitle() = when (this) {
    DashboardSurface.HOME -> "Home"
    DashboardSurface.LIVE_TV -> "Live TV"
    DashboardSurface.MOVIES -> "Movies"
    DashboardSurface.SERIES -> "Series"
}

@Composable
internal fun ProjectCardConfirmationDialog(
    title: String,
    message: String,
    confirmLabel: String,
    close: () -> Unit,
    confirm: () -> Unit
) {
    Dialog(onDismissRequest = close) {
        Surface(shape = RoundedCornerShape(10.dp), color = Color(0xFF181818), modifier = Modifier.widthIn(max = 620.dp)) {
            Column {
                Column(Modifier.padding(24.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    Text(message, color = Color.LightGray)
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    NikTvSecondaryActionButton(onClick = close, modifier = Modifier.remoteFocusFrame(RoundedCornerShape(10.dp))) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    NikTvPrimaryActionButton(onClick = confirm, modifier = Modifier.remoteFocusFrame(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE50914))) {
                        Text(confirmLabel)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SeriesHeroActionIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val captionPosition = remember(density) {
        FocusCaptionPositionProvider(with(density) { 4.dp.roundToPx() }, with(density) { 16.dp.roundToPx() })
    }
    val showFocusHint = context.usesRemoteNavigation(configuration) && focused

    Box(
        modifier = modifier
            .size(48.dp)
            .zIndex(if (showFocusHint) 1f else 0f)
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .onFocusChanged { focused = it.isFocused }
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                .remoteFocusFrame(CircleShape)
        ) {
            Icon(icon, description, tint = tint)
        }

        if (showFocusHint) {
            androidx.compose.ui.window.Popup(
                popupPositionProvider = captionPosition,
                properties = androidx.compose.ui.window.PopupProperties(focusable = false)
            ) {
            Surface(
                modifier = Modifier.widthIn(max = minOf(320.dp, (configuration.screenWidthDp - 32).coerceAtLeast(1).dp)),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xF2111111),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
            ) {
                Text(
                    text = description,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    softWrap = true
                )
            }
            }
        }
    }
}
