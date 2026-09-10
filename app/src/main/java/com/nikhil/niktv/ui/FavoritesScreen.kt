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
internal fun ModernFavoritesScreen(
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

@Composable
internal fun ModernFavoriteCard(
    favorite: FavoriteItem,
    aspectRatio: Float,
    open: () -> Unit,
    remove: () -> Unit
) {
    var removalConfirmationOpen by rememberSaveable(favorite.key) {
        mutableStateOf(false)
    }
    val cancelRemovalRequester = remember(favorite.key) { FocusRequester() }

    LaunchedEffect(removalConfirmationOpen) {
        if (removalConfirmationOpen) {
            withFrameNanos { }
            runCatching { cancelRemovalRequester.requestFocus() }
        }
    }

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
            onLongClick = { removalConfirmationOpen = true },
            titleMaxLines = Int.MAX_VALUE
        ) {
            Text(
                listOfNotNull(
                    favorite.kind.mediaTypeLabel(),
                    favorite.categoryTitle?.takeIf { it.isNotBlank() }
                ).joinToString(" · "),
                color = Color(0xFFB3B3B3),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    if (removalConfirmationOpen) {
        AlertDialog(
            onDismissRequest = { removalConfirmationOpen = false },
            icon = {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Remove from My List?") },
            text = {
                Text(
                    "Remove “${favorite.media.title}” from My List? " +
                        "You can add it again later."
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { removalConfirmationOpen = false },
                    modifier = Modifier
                        .focusRequester(cancelRemovalRequester)
                        .remoteFocusFrame(CircleShape)
                ) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        removalConfirmationOpen = false
                        remove()
                    },
                    modifier = Modifier.remoteFocusFrame(CircleShape),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Remove")
                }
            }
        )
    }
}

@Composable
internal fun ModernScreenTopBar(title: String, close: () -> Unit, openSearch: (() -> Unit)? = null, openSettings: (() -> Unit)? = null) {
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
