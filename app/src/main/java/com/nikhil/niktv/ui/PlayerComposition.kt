package com.nikhil.niktv.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val LocalPlayerTvDevice = staticCompositionLocalOf<Boolean?> { null }

@Composable
internal fun rememberPlayerTvDevice(): Boolean {
    LocalPlayerTvDevice.current?.let { return it }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration.uiMode) { context.isTvLikeDevice(configuration) }
}

// Follow the active input mode so touch interaction does not inherit D-pad highlights.
@Composable
internal fun playerUsesDirectionalInput(): Boolean =
    rememberPlayerTvDevice() || LocalInputModeManager.current.inputMode == InputMode.Keyboard

// Deliberately non-inline: each layer gets its own generated Compose method.
// Inlining the entire player into Box exceeded ART's method compilation limit
// on Fire TV. Animation is confined to controls, never the video surface.
@Composable
internal fun PlayerSurfaceHost(modifier: Modifier, content: @Composable BoxScope.() -> Unit) {
    // Resolve hardware capabilities once, rather than querying PackageManager
    // for every control and again for every control's focus modifier.
    val isTv = rememberPlayerTvDevice()
    CompositionLocalProvider(LocalPlayerTvDevice provides isTv) {
        Box(modifier = modifier, content = content)
    }
}

@Composable
internal fun BoxScope.PlayerVideoLayer(content: @Composable BoxScope.() -> Unit) {
    content()
}

@Composable
internal fun BoxScope.PlayerControlsLayer(
    visible: Boolean,
    content: @Composable BoxScope.() -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(tween(240, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(200, easing = FastOutSlowInEasing))
    ) {
        Box(Modifier.fillMaxSize().focusProperties { canFocus = visible }, content = content)
    }
}

internal class PlayerOverflowNavigation(val columns: Int, val close: FocusRequester, val buttonWidth: Dp, val compact: Boolean = false) {
    private var destination: FocusRequester? = null
    private var correction: Job? = null
    private var retainUntil = 0L
    fun clear() { correction?.cancel(); destination = null; retainUntil = 0L }
    fun request(target: FocusRequester) {
        correction?.cancel()
        destination = target
        retainUntil = android.os.SystemClock.uptimeMillis() + 450L
        runCatching { target.requestFocus() }
    }
    fun retain(requester: FocusRequester, scope: CoroutineScope) {
        if (android.os.SystemClock.uptimeMillis() >= retainUntil) { clear(); return }
        val expected = destination ?: return
        if (expected == requester) return
        // A settings dialog can return focus after its user has already pressed
        // the next arrow. Keep that newer destination instead of stealing it.
        correction?.cancel()
        correction = scope.launch {
            withFrameNanos { }
            if (destination == expected && android.os.SystemClock.uptimeMillis() < retainUntil) runCatching { expected.requestFocus() }
        }
    }
}
internal val LocalPlayerOverflowNavigation = staticCompositionLocalOf<PlayerOverflowNavigation?> { null }

/** Keep technical settings out of the transport row and reachable in two dimensions. */
@Composable
internal fun PlayerExtraControls(visible: Boolean, entry: FocusRequester, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    if (!visible) return
    val configuration = LocalConfiguration.current
    val shortWide = configuration.usesShortWideLayout(rememberPlayerTvDevice())
    val panelWidth = (configuration.screenWidthDp - 24).coerceAtMost(if (shortWide) 640 else if (configuration.screenWidthDp >= 720) 552 else 360).coerceAtLeast(240).dp
    val columns = when { shortWide && panelWidth >= 540.dp -> 5; panelWidth >= 480.dp -> 4; else -> 3 }
    val close = remember { FocusRequester() }
    Popup(alignment = Alignment.BottomEnd, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Surface(Modifier.padding(12.dp).width(panelWidth).heightIn(max = (configuration.screenHeightDp - 24).coerceAtLeast(180).dp),
            shape = RoundedCornerShape(16.dp), color = Color(0xF2171A20), contentColor = Color.White,
            border = BorderStroke(1.dp, Color.White.copy(alpha = .16f)), shadowElevation = 8.dp) {
            BoxWithConstraints(Modifier.padding(12.dp)) {
                val density = LocalDensity.current
                val spacing = with(density) { 8.dp.roundToPx() }
                // Floor each cell in pixels so rounding cannot wrap a four-column
                // grid into three columns while D-pad routes still assume four.
                val cellWidth = with(density) { ((constraints.maxWidth - spacing * (columns - 1)) / columns).toDp() }
                val navigation = remember(columns, close, cellWidth, shortWide) { PlayerOverflowNavigation(columns, close, cellWidth, shortWide) }
                DisposableEffect(navigation) { onDispose { navigation.clear() } }
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).playerChoiceFocusEntry(entry)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Tab) navigation.clear()
                        false
                    }.pointerInput(navigation) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.changes.any { it.pressed && !it.previousPressed }) navigation.clear()
                            }
                        }
                    }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Playback settings", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp).focusRequester(close)
                            .focusProperties { down = entry; left = entry; up = FocusRequester.Cancel; right = FocusRequester.Cancel }
                            .onPreviewKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                    Key.DirectionDown, Key.DirectionLeft -> { navigation.request(entry); true }
                                    Key.DirectionUp, Key.DirectionRight -> true
                                    else -> false
                                }
                            }) {
                            Icon(Icons.Default.Close, "Close playback settings", Modifier.size(20.dp))
                        }
                    }
                    CompositionLocalProvider(LocalPlayerOverflowNavigation provides navigation) {
                        FlowRow(Modifier.fillMaxWidth(), maxItemsInEachRow = columns,
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlayerOverflowAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier,
    selected: Boolean, progress: Float?, indeterminate: Boolean, badge: String?, onFocused: (Boolean) -> Unit) {
    val navigation = LocalPlayerOverflowNavigation.current ?: return
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Surface(onClick = { navigation.clear(); onClick() }, modifier = modifier.width(navigation.buttonWidth).height(if (navigation.compact) 64.dp else 76.dp)
        .playerControlFocus(shape, scaleOnFocus = false) { focused = it; onFocused(it) }, shape = shape,
        color = if (focused) Color(0xFF3B4D68) else if (selected) Color(0xFF303A49) else Color(0xFF242831),
        contentColor = Color.White) {
        Column(Modifier.padding(horizontal = 5.dp, vertical = if (navigation.compact) 4.dp else 7.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (navigation.compact) 3.dp else 5.dp, Alignment.CenterVertically)) {
            Box(Modifier.height(if (navigation.compact) 24.dp else 27.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (indeterminate) CircularProgressIndicator(Modifier.size(27.dp), strokeWidth = 2.dp)
                else if (progress != null) CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.size(27.dp), strokeWidth = 2.dp)
                Icon(icon, label, Modifier.size(21.dp))
                badge?.let { Text(it, Modifier.align(Alignment.CenterEnd), style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            }
            Text(when {
                label.startsWith("Choose picture mode") -> "Picture mode"
                label.startsWith("Controls timeout") -> "Controls timeout"
                label.startsWith("Video fit") -> "Video fit"
                label.startsWith("Player:") -> "Player"
                label.contains("offline download") -> "Download"
                label == "Live cricket scores" -> "Cricket scores"
                else -> label
            }, style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, lineHeight = if (navigation.compact) 12.sp else 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
internal fun Modifier.playerSecondaryFocus(
    requester: FocusRequester,
    order: List<FocusRequester>,
    toggle: FocusRequester,
    playback: FocusRequester,
    up: FocusRequester
): Modifier {
    val index = order.indexOf(requester)
    LocalPlayerOverflowNavigation.current?.let { navigation ->
        val scope = rememberCoroutineScope()
        val column = index % navigation.columns
        val left = order.getOrNull(index - 1).takeIf { column > 0 } ?: FocusRequester.Cancel
        val right = order.getOrNull(index + 1).takeIf { column < navigation.columns - 1 } ?: FocusRequester.Cancel
        val above = order.getOrNull(index - navigation.columns) ?: navigation.close
        val below = order.getOrNull(index + navigation.columns) ?: FocusRequester.Cancel
        return focusRequester(requester).onFocusChanged { if (it.isFocused) navigation.retain(requester, scope) }
            .focusProperties { this.left = left; this.right = right; this.up = above; this.down = below }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false else {
                    val destination = when (event.key) { Key.DirectionLeft -> left; Key.DirectionRight -> right; Key.DirectionUp -> above; Key.DirectionDown -> below; else -> null }
                    if (destination == null) false else { if (destination != FocusRequester.Cancel) navigation.request(destination); true }
                }
            }
    }
    val previous = order.getOrNull(index - 1) ?: playback
    val next = order.getOrNull(index + 1) ?: toggle
    return focusRequester(requester)
        .focusProperties {
            left = previous
            right = next
            this.up = up
        }
        .playerDpadFocusRoutes(left = previous, right = next, up = up)
}

@Composable
internal fun BoxScope.PlayerOverlayLayer(content: @Composable BoxScope.() -> Unit) {
    content()
}
