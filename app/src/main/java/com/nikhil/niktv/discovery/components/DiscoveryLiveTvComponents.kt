package com.nikhil.niktv.discovery.components

import com.nikhil.niktv.ui.*

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

/** Some portals prepend an unexpanded PHP time format to EPG titles. */
internal fun liveProgrammeDisplayTitle(title: String): String =
    title.replace(Regex("^\\s*H:i\\s+", RegexOption.IGNORE_CASE), "").trim()

internal fun liveChannelSupportingText(
    item: MediaItem,
    programme: LiveProgramme? = item.currentLiveProgramme(System.currentTimeMillis())
): String? {
    programme?.let { programme ->
        val cleanProgramme =
            if (
                programme.title.trim().equals(
                    item.title.trim(),
                    ignoreCase = true
                )
            ) {
                programme.copy(title = "")
            } else {
                programme
            }

        return liveProgrammeSummary(cleanProgramme)
            .takeIf { it.isNotBlank() }
    }

    val description = item.description
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    return description?.takeIf {
        !it.equals(item.title.trim(), ignoreCase = true)
    }
}

internal fun liveProgrammeSummary(
    programme: LiveProgramme
): String {
    val formatter = java.text.SimpleDateFormat(
        "h:mm a",
        java.util.Locale.getDefault()
    )

    val schedule = when {
        programme.startTimeMillis != null &&
                programme.endTimeMillis != null -> {

            "${formatter.format(java.util.Date(programme.startTimeMillis))}" +
                    "–${formatter.format(java.util.Date(programme.endTimeMillis))}"
        }

        programme.startTimeMillis != null -> {
            "From ${
                formatter.format(
                    java.util.Date(programme.startTimeMillis)
                )
            }"
        }

        else -> null
    }

    return listOfNotNull(
        liveProgrammeDisplayTitle(programme.title).takeIf { it.isNotBlank() },
        schedule
    ).joinToString("  •  ")
}

@Composable
internal fun ModernSectionHeader(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 600.dp
        if (compact) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                subtitle?.let { Text(it, color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
                action?.let { actions ->
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) { actions() }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    subtitle?.let { Text(it, color = Color.Gray, style = MaterialTheme.typography.labelMedium) }
                }
                action?.invoke()
            }
        }
    }
}




@Composable
internal fun MobileLibrarySwitcher(
    downloadsSelected: Boolean,
    storageSelected: Boolean,
    openFavorites: () -> Unit,
    openOfflineDownloads: () -> Unit,
    openStorage: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFF090909)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            MobileLibraryTab(
                label = "My List",
                icon = Icons.Default.FavoriteBorder,
                selected = !downloadsSelected && !storageSelected,
                onClick = openFavorites
            )
            MobileLibraryTab(
                label = "Downloads",
                icon = Icons.Default.DownloadDone,
                selected = downloadsSelected && !storageSelected,
                onClick = openOfflineDownloads
            )
            MobileLibraryTab(
                label = "Storage",
                icon = Icons.Default.Storage,
                selected = storageSelected,
                onClick = openStorage
            )
        }
    }
}

@Composable
private fun RowScope.MobileLibraryTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .height(42.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color(0xFF241012) else Color(0xFF17191E),
        border = BorderStroke(
            1.dp,
            if (selected) Color(0xFFE50914) else Color(0xFF2D3036)
        )
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (selected) Color.White else Color(0xFFAEB0B6)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) Color.White else Color(0xFFAEB0B6),
                maxLines = 1
            )
        }
    }
}
