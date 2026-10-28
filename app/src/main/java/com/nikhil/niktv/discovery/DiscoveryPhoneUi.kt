package com.nikhil.niktv.discovery

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
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
    HOME("Home"), MOVIES("Movies"), SERIES("Series"), LIVE("Live TV"), PROVIDERS("Providers"), LIBRARY("My List")
}

/** Both feeds anchor hero actions to the same full-screen safe viewport. */
@Composable
internal fun phoneDiscoveryHeroHeight(): Dp {
    val density = LocalDensity.current
    val safeHeight = LocalWindowInfo.current.containerSize.height -
        WindowInsets.safeDrawing.getTop(density) - WindowInsets.safeDrawing.getBottom(density)
    return with(density) { safeHeight.toDp() * .67f }.coerceIn(480.dp, 620.dp)
}

/** Geometry is device-width based; text scale never changes artwork ratios. */
internal fun phoneDiscoveryPosterWidth(widthDp: Int) = ((widthDp - 52f) / 3f).coerceIn(76f, 144f)
internal fun usesDiscoveryPhoneLayout(smallestWidthDp: Int, television: Boolean) = smallestWidthDp < 600 && !television

@Composable
internal fun DiscoveryPhoneBottomNavigation(selected: DiscoveryPhoneDestination?,
    select: (DiscoveryPhoneDestination) -> Unit, modifier: Modifier = Modifier) {
    DiscoveryGlassPanel(modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
        artwork = LocalMobileGlassArtwork.current, backdropBounds = LocalMobileGlassBounds.current,
        fitArtwork = LocalMobileGlassFit.current, topAligned = LocalMobileGlassTopAligned.current,
        fillWidth = LocalMobileGlassFillWidth.current) {
        Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            DiscoveryPhoneDestination.entries.forEach { destination ->
                val active = destination == selected
                val icon = when (destination) {
                    DiscoveryPhoneDestination.HOME -> Icons.Default.Home
                    DiscoveryPhoneDestination.LIVE -> Icons.Default.LiveTv
                    DiscoveryPhoneDestination.MOVIES -> Icons.Default.Movie
                    DiscoveryPhoneDestination.SERIES -> Icons.Default.Tv
                    DiscoveryPhoneDestination.PROVIDERS -> Icons.Default.Subscriptions
                    DiscoveryPhoneDestination.LIBRARY -> Icons.Default.VideoLibrary
                }
                Surface(onClick = { select(destination) },
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).semantics { this.selected = active; contentDescription = destination.title },
                    shape = RoundedCornerShape(8.dp),
                    color = if (active) Color(0x663D3E45) else Color.Transparent,
                    contentColor = if (active) Color.White else Color(0xFFB1B1B9)) {
                    Column(Modifier.padding(horizontal = 2.dp, vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(icon, null, Modifier.size(22.dp), tint = if (active) Color(0xFFFF6778) else Color(0xFFB1B1B9))
                        BasicText(destination.title, modifier = Modifier.fillMaxWidth(),
                            style = LocalTextStyle.current.copy(color = if (active) Color.White else Color(0xFFB1B1B9),
                                fontSize = 11.sp, lineHeight = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium),
                            maxLines = 1, softWrap = false,
                            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 11.sp, stepSize = .5.sp))
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
    customize: (() -> Unit)?, profile: String = "", categoryManagerOpen: Boolean = false,
    categoryPopupChanged: (Boolean) -> Unit = {}, searchActive: Boolean = false) {
    CompositionLocalProvider(LocalMobileGlassEnabled provides true) {
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
            if (!detail) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DiscoveryAction("Search", Icons.Default.Search, iconOnly = true, selected = searchActive,
                    modifier = if (searchActive) Modifier.focusRequester(tab).focusProperties { down = play } else Modifier) { classic("Search") }
                DiscoveryAction("Profile: $profile", visualLabel = profile.trim().firstOrNull()?.uppercase() ?: "N",
                    horizontalPadding = 0.dp, modifier = Modifier.size(48.dp).semantics { contentDescription = "Switch profile: $profile" }
                        .then(if (!searchActive) Modifier.focusRequester(tab) else Modifier).focusProperties { down = play }) { classic("Profile") }
                DiscoveryBrowseMenu(menu, changeMenu, surface, classic, customize, categoryManagerOpen, categoryPopupChanged)
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
    showSynopsis: Boolean = true, compactDetail: Boolean = false, categoryActions: (@Composable () -> Unit)? = null, showListAction: Boolean = true,
    artworkAlignment: Alignment = Alignment.Center, shadeArtworkBounds: Boolean = false, leftAligned: Boolean = false,
    supportingTitle: Boolean = false, fitArtwork: Boolean = false) {
    val context = LocalContext.current
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    var drag by remember { mutableFloatStateOf(0f) }
    val dragThreshold = with(LocalDensity.current) { 56.dp.toPx() }
    val portraitPoster = card?.backdrop.isNullOrBlank()
    val picture = thumbnailArtworkUrl(card?.backdrop ?: card?.poster, if (portraitPoster) 780 else 1280)
    var heroBounds by remember(picture) { mutableStateOf(Rect.Zero) }
    val glassSource = MobileGlassSource(picture, heroBounds, fitArtwork,
        (compactDetail && !portraitPoster) || artworkAlignment == Alignment.TopCenter, compactDetail && !portraitPoster && !fitArtwork)
    val publishGlass = LocalPublishMobileGlassSource.current
    LaunchedEffect(glassSource) { publishGlass(glassSource) }
    DisposableEffect(publishGlass) { onDispose { publishGlass(null) } }
    CompositionLocalProvider(LocalMobileGlassEnabled provides true, LocalMobileGlassArtwork provides picture,
        LocalMobileGlassBounds provides heroBounds, LocalMobileGlassFit provides glassSource.fit,
        LocalMobileGlassTopAligned provides glassSource.topAligned, LocalMobileGlassFillWidth provides glassSource.fillWidth) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().heightIn(min = height).onGloballyPositioned { heroBounds = Rect(it.positionInRoot(), it.size.toSize()) }) {
            Crossfade(picture, modifier = Modifier.matchParentSize(), animationSpec = tween(if (reduceMotion) 0 else 250), label = "phoneHeroArtwork") { url ->
                AsyncImage(ImageRequest.Builder(context).data(url).size(if (portraitPoster) 780 else 1280, if (portraitPoster) 1170 else 720)
                    .transformations(DiscoveryPhoneBackdropTransformation()).crossfade(false).build(), null,
                    Modifier.fillMaxSize(),
                    contentScale = if (fitArtwork) ContentScale.Fit else if (compactDetail && !portraitPoster) ContentScale.FillWidth else ContentScale.Crop,
                    alignment = if (compactDetail && !portraitPoster) Alignment.TopCenter else artworkAlignment)
            }
            if (shadeArtworkBounds) Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                0f to Color(0xD9090B10), .3f to Color.Transparent, .6f to Color(0x66090B10), 1f to Color(0xFF090B10))))
            if (shadeArtworkBounds) Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
                0f to Color(0xCC090B10), .16f to Color(0x33090B10), .36f to Color.Transparent,
                .64f to Color.Transparent, .84f to Color(0x33090B10), 1f to Color(0xCC090B10))))
            // Gesture area excludes the actions so a swipe cannot release into Play/Add.
            Box(Modifier.fillMaxWidth().height((height * .53f).coerceAtLeast(180.dp)).align(Alignment.TopCenter)
                .pointerInput(carousel, dragThreshold) {
                    if (carousel) detectHorizontalDragGestures(onDragStart = { drag = 0f },
                        onDragEnd = { if (drag < -dragThreshold) next() else if (drag > dragThreshold) previous() },
                        onHorizontalDrag = { change, amount -> change.consume(); drag += amount })
                })
            Column(Modifier.fillMaxWidth()) {
                header?.invoke()
                Spacer(Modifier.height(if (compactDetail) 52.dp else if (detail) (height * .4f).coerceAtLeast(180.dp) else (height * .25f).coerceAtLeast(100.dp)))
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (compactDetail) 10.dp else 20.dp),
                    horizontalAlignment = if (leftAligned) Alignment.Start else Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (compactDetail) 8.dp else 12.dp)) {
                    // Keep rank/loading in one fixed slot: content arrival must not move actions.
                    if (!detail || rank != null) Box(Modifier.fillMaxWidth().height(24.dp * fontScale),
                        contentAlignment = Alignment.Center) {
                        if (loading) LinearProgressIndicator(Modifier.width(110.dp).semantics { contentDescription = "Loading featured title" })
                        else rank?.let { Text("#$it · TRENDING TODAY", color = Color(0xFFE5E5EA), fontSize = 11.sp, lineHeight = 24.sp,
                            letterSpacing = 1.sp, fontWeight = FontWeight.Bold) }
                    }
                    var logoReady by remember(card?.key, card?.titleLogo) { mutableStateOf(false) }
                    Box(Modifier.fillMaxWidth().then(if (compactDetail) Modifier.heightIn(min = 56.dp * fontScale) else Modifier.height(82.dp * fontScale)), contentAlignment = if (supportingTitle) Alignment.BottomCenter else if (leftAligned) Alignment.CenterStart else Alignment.Center) {
                        if (loading && card == null) Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.width(220.dp).height(18.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFF252B35)))
                            Box(Modifier.width(150.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF252B35)))
                        }
                        else if (supportingTitle || !logoReady) Text(card?.title ?: "Discover something to watch", color = Color.White,
                            fontSize = if (supportingTitle) 17.sp else if (compactDetail) 24.sp else 28.sp, lineHeight = if (supportingTitle) 22.sp else if (compactDetail) 28.sp else 32.sp, fontWeight = if (supportingTitle) FontWeight.Medium else FontWeight.Bold,
                            textAlign = if (leftAligned) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.Center, maxLines = if (supportingTitle) 2 else 3, overflow = TextOverflow.Ellipsis)
                        if (!supportingTitle && !card?.titleLogo.isNullOrBlank()) AsyncImage(card?.titleLogo, card?.title,
                            Modifier.fillMaxWidth().height((if (compactDetail) 56.dp else 82.dp) * fontScale).graphicsLayer { alpha = if (logoReady) 1f else 0f }, contentScale = ContentScale.Fit,
                            onSuccess = { logoReady = true }, onError = { logoReady = false })
                    }
                    val metadata = card?.let { listOfNotNull(if (detail) discoveryReleaseLabel(it) else it.date?.take(4),
                        it.runtime?.takeIf { detail }?.let { minutes -> "${minutes} min" },
                        it.genres.take(if (detail) 3 else 2).joinToString(" · ").takeIf(String::isNotBlank),
                        it.certification?.takeIf { detail }).joinToString(" · ") }.orEmpty()
                    if (compactDetail && card != null) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        discoveryDetailMetadata(card).forEach { line -> Text(line, Modifier.fillMaxWidth(), color = Color(0xFFCDCDD3), fontSize = 12.sp, lineHeight = 16.sp,
                            textAlign = if (leftAligned) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.Center) }
                    } else if (!leftAligned || metadata.isNotBlank()) Box(Modifier.fillMaxWidth().height(36.dp * fontScale), contentAlignment = if (leftAligned) Alignment.CenterStart else Alignment.Center) {
                        if (loading && card == null) Box(Modifier.width(160.dp).height(10.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF252B35)))
                        else if (metadata.isNotBlank()) Text(metadata, color = Color(0xFFCDCDD3), fontSize = 13.sp, lineHeight = 18.sp,
                            textAlign = if (leftAligned) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(Modifier.fillMaxWidth().then(if (detail) Modifier.height(IntrinsicSize.Min) else Modifier).onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) { onDown(); true } else false
                    }, horizontalArrangement = Arrangement.spacedBy(8.dp, if (leftAligned) Alignment.Start else Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (!detail && showListAction) PhoneHeroIcon(if (inList) "Remove from My List" else "Add to My List",
                            if (inList) Icons.Default.Check else Icons.Default.Add, card != null, add,
                            Modifier.onFocusChanged { if (it.isFocused) onFocus() })
                        // Keep the primary action centred exactly as Home's Add / Play / Info row.
                        // Saved heroes omit Add, but retain its non-interactive layout slot.
                        if (!detail && !showListAction && supportingTitle) Spacer(Modifier.size(48.dp))
                        DiscoveryMobileGlassContainer(if (detail) Modifier.weight(1f).fillMaxHeight() else Modifier) {
                        Surface(onClick = play, enabled = card != null && playEnabled, shape = RoundedCornerShape(10.dp),
                            color = Color.Transparent, contentColor = Color.White,
                            modifier = Modifier.then(if (detail) Modifier.fillMaxSize() else Modifier.widthIn(min = 120.dp))
                                .heightIn(min = 48.dp).focusRequester(playRequester).onFocusChanged { if (it.isFocused) onFocus() }) {
                            Row(Modifier.padding(horizontal = if (compactDetail) 6.dp else 18.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(if (compactDetail) 4.dp else 6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(if (compactDetail) 18.dp else 24.dp))
                                Text(if (compactDetail && playLabel.startsWith("Resume ")) "Resume" else if (compactDetail) playLabel.replace("Episode ", "E") else playLabel,
                                    fontWeight = FontWeight.Bold, fontSize = if (compactDetail) 13.sp else 15.sp,
                                    modifier = Modifier.weight(1f, fill = false),
                                    maxLines = if (compactDetail) 1 else 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        }
                        if (detail) Box(Modifier.size(48.dp)) {
                            DiscoveryAction(if (inList) "In My List" else "My List",
                                if (inList) Icons.Default.Check else Icons.Default.Add, enabled = card != null,
                                iconOnly = true,
                                modifier = Modifier.fillMaxSize().heightIn(min = 48.dp),
                                horizontalPadding = 6.dp, onFocus = onFocus, onClick = add)
                        }
                        if (detail) Box(Modifier.size(48.dp)) {
                            DiscoveryTrailerAction(card, iconOnly = true, onFocus = onFocus, compactLabel = compactDetail,
                                modifier = Modifier.fillMaxSize().heightIn(min = 48.dp))
                        } else DiscoveryTrailerAction(card, iconOnly = true, onFocus = onFocus)
                        if (detail && compactDetail) LocalProviderSynopsisActions.current?.invoke()
                        if (!detail) PhoneHeroIcon("Info", Icons.Default.Info, card != null, info,
                            Modifier.focusRequester(infoRequester).onFocusChanged { if (it.isFocused) onFocus() })
                    }
                    categoryActions?.let { actions ->
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) { onDown(); true } else false
                            }) { actions() }
                    }
                    if (carousel || !detail) Row(Modifier.heightIn(min = 48.dp).onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) { onDown(); true } else false
                    }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        PhoneHeroIcon("Previous featured title", Icons.Default.ChevronLeft, carousel, previous,
                            Modifier.onFocusChanged { if (it.isFocused) onFocus() })
                        Text(if (count > 0) "${index + 1} / $count" else "— / —", Modifier.width((48f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp),
                            color = Color.LightGray, fontSize = 11.sp, maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        PhoneHeroIcon("Next featured title", Icons.Default.ChevronRight, carousel, next,
                            Modifier.onFocusChanged { if (it.isFocused) onFocus() })
                    }
                }
            }
        }
        if (detail && showSynopsis && !card?.overview.isNullOrBlank()) Text(card?.overview.orEmpty(),
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp), color = Color(0xFFD6D6DC), fontSize = 15.sp, lineHeight = 23.sp)
    }
    }
}

@Composable
private fun PhoneHeroIcon(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean, click: () -> Unit, modifier: Modifier = Modifier) {
    DiscoveryMobileGlassContainer {
    Surface(onClick = click, enabled = enabled, modifier = modifier.size(48.dp).semantics { contentDescription = label },
        shape = RoundedCornerShape(10.dp), color = Color.Transparent, border = null, contentColor = Color.White) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(24.dp)) }
    }
    }
}

@Composable
internal fun DiscoveryPhoneMediaCard(title: String, artwork: String?, subtitle: String, width: Dp,
    wide: Boolean, modifier: Modifier = Modifier, progress: Float? = null, matchCount: Int? = null,
    onClick: () -> Unit, onLongClick: (() -> Unit)?, onFocus: () -> Unit, source: DiscoveryTileSource? = null) {
    val context = LocalContext.current
    val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    var focused by remember { mutableStateOf(false) }
    var failed by remember(artwork) { mutableStateOf(false) }
    val focusBorder = remember { mutableFloatStateOf(0f) }
    val shape = RoundedCornerShape(12.dp)
    Column(modifier.width(width).onFocusChanged {
        focused = it.isFocused; focusBorder.floatValue = if (focused) 1f else 0f
        if (focused) onFocus()
    }.semantics(mergeDescendants = true) {
        contentDescription = title
        if (source != null || matchCount != null) stateDescription = discoveryTileStateDescription(source, matchCount)
    }
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
            DiscoveryTileIndicators(source, matchCount, Modifier.align(Alignment.TopCenter))
        }
    }
}
