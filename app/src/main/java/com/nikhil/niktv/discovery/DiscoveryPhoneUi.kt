package com.nikhil.niktv.discovery

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.request.transformations
import com.nikhil.niktv.R
import com.nikhil.niktv.data.thumbnailArtworkUrl
import com.nikhil.niktv.model.DashboardSurface
import com.nikhil.niktv.ui.remoteCombinedClickable
import com.nikhil.niktv.ui.tileFocusOutline

internal enum class DiscoveryPhoneDestination(val title: String) {
    HOME("Home"), LIVE("Live TV"), SEARCH("Search"), LIBRARY("My List")
}

/** Geometry is device-width based; text scale never changes artwork ratios. */
internal fun phoneDiscoveryPosterWidth(widthDp: Int) = ((widthDp - 52f) / 3f).coerceIn(76f, 144f)
internal fun usesDiscoveryPhoneLayout(smallestWidthDp: Int, television: Boolean) = smallestWidthDp < 600 && !television

@Composable
internal fun DiscoveryPhoneBottomNavigation(selected: DiscoveryPhoneDestination?,
    select: (DiscoveryPhoneDestination) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
        shape = RoundedCornerShape(26.dp), color = Color(0xF01B1D23),
        border = BorderStroke(1.dp, Color(0xFF484A51)), shadowElevation = 5.dp) {
        Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            DiscoveryPhoneDestination.entries.forEach { destination ->
                val active = destination == selected
                val icon = when (destination) {
                    DiscoveryPhoneDestination.HOME -> Icons.Default.Home
                    DiscoveryPhoneDestination.LIVE -> Icons.Default.LiveTv
                    DiscoveryPhoneDestination.SEARCH -> Icons.Default.Search
                    DiscoveryPhoneDestination.LIBRARY -> Icons.Default.VideoLibrary
                }
                Surface(onClick = { select(destination) },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics { this.selected = active },
                    shape = RoundedCornerShape(20.dp),
                    color = if (active) Color(0xFF3D3E45) else Color.Transparent,
                    contentColor = if (active) Color.White else Color(0xFFB1B1B9)) {
                    Column(Modifier.padding(horizontal = 2.dp, vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(icon, null, Modifier.size(22.dp), tint = if (active) Color(0xFFFF6778) else Color(0xFFB1B1B9))
                        Text(destination.title, fontSize = 11.sp, lineHeight = 14.sp,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DiscoveryPhoneHeader(surface: DashboardSurface, detail: Boolean, tab: FocusRequester,
    play: FocusRequester, back: () -> Unit, select: (DashboardSurface) -> Unit,
    classic: (String) -> Unit, menu: Boolean, changeMenu: (Boolean) -> Unit,
    customize: () -> Unit, categoryManagerOpen: Boolean = false, categoryPopupChanged: (Boolean) -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (detail) DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true, onClick = back)
            else Box(Modifier.size(42.dp).drawWithCache {
                // Keep the transparent logo readable over bright artwork without changing its size.
                val radius = size.maxDimension * .85f
                val halo = Brush.radialGradient(
                    0f to Color.Black.copy(alpha = .9f),
                    .5f to Color.Black.copy(alpha = .75f),
                    1f to Color.Transparent,
                    center = Offset(size.width / 2f, size.height / 2f), radius = radius)
                onDrawBehind { drawCircle(halo, radius = radius) }
            }.clip(RoundedCornerShape(8.dp))) {
                Image(painterResource(R.drawable.niktv_logo_foreground), "NikTV", Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = 2.4f; scaleY = 2.4f })
            }
            Spacer(Modifier.weight(1f))
            if (!detail) {
                DiscoveryCategoryAccess(surface, categoryManagerOpen, compact = true, popupChanged = categoryPopupChanged)
                Spacer(Modifier.width(6.dp))
                DiscoveryAction("Settings", Icons.Default.AccountCircle, iconOnly = true) { classic("Settings") }
                Spacer(Modifier.width(6.dp))
                DiscoveryBrowseMenu(menu, changeMenu, surface, classic, customize)
            }
        }
        if (!detail) {
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(24.dp), color = Color(0xBE27272D),
                border = BorderStroke(1.dp, Color.White.copy(alpha = .22f))) {
                Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    trialSurfaces.forEach { destination ->
                        val active = destination == surface
                        Surface(onClick = { select(destination) },
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                                .semantics { contentDescription = destination.trialTitle(); selected = active }
                                .then(if (active) Modifier.focusRequester(tab).focusProperties { down = play } else Modifier),
                            shape = RoundedCornerShape(20.dp), color = if (active) Color.White else Color.Transparent,
                            contentColor = if (active) Color.Black else Color(0xFFDDDEE3)) {
                            Box(Modifier.padding(horizontal = 4.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                                Text(if (destination == DashboardSurface.HOME) "All" else destination.trialTitle().removeSuffix(" 2"),
                                    fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A portrait crop and a baked vertical gradient, never a stretched TV backdrop or live blur. */
@Composable
internal fun DiscoveryPhoneHero(card: DiscoveryCard?, rank: Int?, height: Dp, detail: Boolean,
    loading: Boolean, inList: Boolean, playRequester: FocusRequester, infoRequester: FocusRequester,
    play: () -> Unit, add: () -> Unit, info: () -> Unit, previous: () -> Unit, next: () -> Unit,
    carousel: Boolean, index: Int, count: Int, playLabel: String, playEnabled: Boolean,
    reduceMotion: Boolean, onFocus: () -> Unit, onDown: () -> Unit, header: (@Composable () -> Unit)? = null,
    showSynopsis: Boolean = true, compactDetail: Boolean = false) {
    val context = LocalContext.current
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    var drag by remember { mutableFloatStateOf(0f) }
    val dragThreshold = with(LocalDensity.current) { 56.dp.toPx() }
    val portraitPoster = card?.backdrop.isNullOrBlank()
    val picture = thumbnailArtworkUrl(card?.backdrop ?: card?.poster, if (portraitPoster) 780 else 1280)
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().heightIn(min = height)) {
            Crossfade(picture, modifier = Modifier.matchParentSize(), animationSpec = tween(if (reduceMotion) 0 else 250), label = "phoneHeroArtwork") { url ->
                AsyncImage(ImageRequest.Builder(context).data(url).size(if (portraitPoster) 780 else 1280, if (portraitPoster) 1170 else 720)
                    .transformations(DiscoveryPhoneBackdropTransformation()).crossfade(false).build(), null,
                    Modifier.fillMaxSize(),
                    contentScale = if (compactDetail && !portraitPoster) ContentScale.FillWidth else ContentScale.Crop,
                    alignment = if (compactDetail && !portraitPoster) Alignment.TopCenter else Alignment.Center)
            }
            // Gesture area excludes the actions so a swipe cannot release into Play/Add.
            Box(Modifier.fillMaxWidth().height((height * .53f).coerceAtLeast(180.dp)).align(Alignment.TopCenter)
                .pointerInput(carousel, dragThreshold) {
                    if (carousel) detectHorizontalDragGestures(onDragStart = { drag = 0f },
                        onDragEnd = { if (drag < -dragThreshold) next() else if (drag > dragThreshold) previous() },
                        onHorizontalDrag = { change, amount -> change.consume(); drag += amount })
                })
            Column(Modifier.fillMaxWidth()) {
                header?.invoke()
                Spacer(Modifier.height(if (compactDetail) 64.dp else if (detail) (height * .4f).coerceAtLeast(180.dp) else (height * .25f).coerceAtLeast(100.dp)))
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (loading) LinearProgressIndicator(Modifier.width(110.dp))
                    rank?.let { Text("#$it · TRENDING TODAY", color = Color(0xFFE5E5EA), fontSize = 11.sp,
                        letterSpacing = 1.sp, fontWeight = FontWeight.Bold) }
                    var logoReady by remember(card?.key, card?.titleLogo) { mutableStateOf(false) }
                    Box(Modifier.fillMaxWidth().height(82.dp * fontScale), contentAlignment = Alignment.Center) {
                        if (!logoReady) Text(card?.title ?: "Discover something to watch", color = Color.White,
                            fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (!card?.titleLogo.isNullOrBlank()) AsyncImage(card?.titleLogo, card?.title,
                            Modifier.fillMaxSize().graphicsLayer { alpha = if (logoReady) 1f else 0f }, contentScale = ContentScale.Fit,
                            onSuccess = { logoReady = true }, onError = { logoReady = false })
                    }
                    val metadata = card?.let { listOfNotNull(it.date?.take(4),
                        it.runtime?.takeIf { detail }?.let { minutes -> "${minutes} min" },
                        it.genres.take(if (detail) 3 else 2).joinToString(" · ").takeIf(String::isNotBlank),
                        it.certification?.takeIf { detail }).joinToString(" · ") }.orEmpty()
                    Box(Modifier.fillMaxWidth().height(36.dp * fontScale), contentAlignment = Alignment.Center) {
                        if (metadata.isNotBlank()) Text(metadata, color = Color(0xFFCDCDD3), fontSize = 13.sp, lineHeight = 18.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(Modifier.fillMaxWidth().onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) { onDown(); true } else false
                    }, horizontalArrangement = Arrangement.spacedBy(if (compactDetail) 8.dp else 12.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (!detail) PhoneHeroIcon(if (inList) "Remove from My List" else "Add to My List",
                            if (inList) Icons.Default.Check else Icons.Default.Add, card != null, add,
                            Modifier.onFocusChanged { if (it.isFocused) onFocus() })
                        Surface(onClick = play, enabled = card != null && playEnabled, shape = RoundedCornerShape(if (compactDetail) 10.dp else 24.dp),
                            color = Color.White, contentColor = Color.Black,
                            modifier = Modifier.then(if (detail) Modifier.weight(1f) else Modifier.widthIn(min = 140.dp))
                                .heightIn(min = 48.dp).focusRequester(playRequester).onFocusChanged { if (it.isFocused) onFocus() }) {
                            Row(Modifier.padding(horizontal = if (compactDetail) 8.dp else 18.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(24.dp))
                                Text(if (compactDetail) playLabel.replace("Episode ", "E") else playLabel,
                                    fontWeight = FontWeight.Bold, fontSize = if (compactDetail) 13.sp else 15.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (detail && compactDetail) DiscoveryAction(if (inList) "In My List" else "My List",
                            if (inList) Icons.Default.Check else Icons.Default.Add, enabled = card != null,
                            horizontalPadding = 8.dp, onFocus = onFocus, onClick = add)
                        if (!detail) PhoneHeroIcon("Info", Icons.Default.Info, card != null, info,
                            Modifier.focusRequester(infoRequester).onFocusChanged { if (it.isFocused) onFocus() })
                    }
                    if (detail && !compactDetail) DiscoveryAction(if (inList) "In My List" else "My List",
                        if (inList) Icons.Default.Check else Icons.Default.Add, enabled = card != null,
                        modifier = Modifier.onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) { onDown(); true } else false
                        }, onFocus = onFocus, onClick = add)
                    if (carousel) Row(Modifier.onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) { onDown(); true } else false
                    }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PhoneHeroIcon("Previous featured title", Icons.Default.ChevronLeft, true, previous,
                            Modifier.onFocusChanged { if (it.isFocused) onFocus() })
                        Text("${index + 1} / $count", color = Color.LightGray, fontSize = 11.sp)
                        PhoneHeroIcon("Next featured title", Icons.Default.ChevronRight, true, next,
                            Modifier.onFocusChanged { if (it.isFocused) onFocus() })
                    }
                }
            }
        }
        if (detail && showSynopsis && !card?.overview.isNullOrBlank()) Text(card?.overview.orEmpty(),
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = Color(0xFFD6D6DC), fontSize = 15.sp, lineHeight = 23.sp)
    }
}

@Composable
private fun PhoneHeroIcon(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean, click: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = click, enabled = enabled, modifier = modifier.size(48.dp).semantics { contentDescription = label },
        shape = CircleShape, color = Color(0xC42A2B32), border = BorderStroke(1.dp, Color(0xFF62636B)), contentColor = Color.White) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(24.dp)) }
    }
}

@Composable
internal fun DiscoveryPhoneMediaCard(title: String, artwork: String?, subtitle: String, width: Dp,
    wide: Boolean, modifier: Modifier = Modifier, progress: Float? = null, matchCount: Int? = null,
    onClick: () -> Unit, onLongClick: (() -> Unit)?, onFocus: () -> Unit) {
    val context = LocalContext.current
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    var focused by remember { mutableStateOf(false) }
    var failed by remember(artwork) { mutableStateOf(false) }
    val focusBorder = remember { mutableFloatStateOf(0f) }
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.width(width).onFocusChanged {
        focused = it.isFocused; focusBorder.floatValue = if (focused) 1f else 0f
        if (focused) onFocus()
    }.semantics(mergeDescendants = true) { contentDescription = title }
        .remoteCombinedClickable(onClick = onClick, onLongClick = onLongClick,
            interactionSource = remember { MutableInteractionSource() }, showIndication = true)) {
        // Keep the existing card footprint; place readable metadata inside it like My List.
        Box(Modifier.fillMaxWidth().height(phoneDiscoveryCardHeight(width.value, wide, fontScale).dp)
            .tileFocusOutline(true, focusBorder, shape, Color(0xFF30313B), 2.dp).clip(shape).background(Color(0xFF1E2029))) {
            AsyncImage(ImageRequest.Builder(context).data(thumbnailArtworkUrl(artwork, if (wide) 640 else 342))
                .size(if (wide) 640 else 342, if (wide) 360 else 513).crossfade(false).build(), null,
                Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { failed = true })
            if (artwork.isNullOrBlank() || failed) Icon(Icons.Default.Movie, null,
                Modifier.align(Alignment.Center).size(28.dp), tint = Color.Gray)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0f to Color.Transparent, .38f to Color.Transparent,
                .72f to Color.Black.copy(alpha = .65f), 1f to Color.Black.copy(alpha = .97f))))
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(
                start = 8.dp, end = 8.dp, top = 8.dp, bottom = if (progress != null) 10.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = Color.White, fontSize = if (wide) 14.sp else 11.sp,
                    lineHeight = if (wide) 19.sp else 15.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) Text(subtitle, color = Color(0xFFD0D1D7), fontSize = if (wide) 11.sp else 10.sp,
                    lineHeight = if (wide) 15.sp else 13.sp, maxLines = if (wide) 2 else 1, overflow = TextOverflow.Ellipsis)
            }
            progress?.let { value -> Canvas(Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter)) {
                drawRect(Color.DarkGray); drawRect(Color(0xFFE50925), size = size.copy(width = size.width * value.coerceIn(0f, 1f)))
            } }
            matchCount?.let { Text(it.toString(), color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).background(Color(0xFFE50925), RoundedCornerShape(6.dp)).padding(5.dp)) }
        }
    }
}
