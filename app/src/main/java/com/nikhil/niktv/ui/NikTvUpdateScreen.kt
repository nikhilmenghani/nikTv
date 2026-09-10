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
internal fun MandatoryNikTvUpdateScreen(
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
