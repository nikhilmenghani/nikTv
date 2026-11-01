package com.nikhil.niktv.ui

import android.content.Context
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nikhil.niktv.sports.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal data class CricketBadgePosition(val x: Float = .95f, val y: Float = .08f) {
    fun safe() = CricketBadgePosition(x.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: .95f,
        y.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: .08f)
    fun move(dx: Float, dy: Float, width: Float, height: Float) = CricketBadgePosition(
        x + if (width > 0) dx / width else 0f, y + if (height > 0) dy / height else 0f).safe()
}

internal val LocalCricketLiveScoresRepository = staticCompositionLocalOf<CricketLiveScoresRepository?> { null }

internal data class CricketScoreReading(val id: String, val title: String, val text: String, val score: CricketLiveScore?)

internal class CricketScoreController(context: Context) {
    internal var movementWidth = 0f
    internal var movementHeight = 0f
    internal val movementStep = context.resources.displayMetrics.density * 18f
    private val preferences = context.applicationContext.getSharedPreferences("cricket_score_overlay", Context.MODE_PRIVATE)
    var selected by mutableStateOf(preferences.getInt("match", 0).takeIf { it > 0 }?.let {
        CricketLiveMatch(it, preferences.getString("title", "Cricket").orEmpty(), preferences.getString("description", "").orEmpty(), "", "",
            hundred = preferences.getBoolean("hundred", false))
    })
        private set
    var score by mutableStateOf<CricketLiveScore?>(null)
    var scoreError by mutableStateOf<String?>(null)
    var lastSuccess by mutableLongStateOf(0L)
    var refreshIntervalMillis by mutableLongStateOf(10_000L)
    var matches by mutableStateOf<List<CricketLiveMatch>>(emptyList())
    var listError by mutableStateOf<String?>(null)
    var listLoading by mutableStateOf(false)
    var pickerOpen by mutableStateOf(false)
    var moving by mutableStateOf(false)
    var refresh by mutableIntStateOf(0)
    var reading by mutableStateOf<CricketScoreReading?>(null)
        private set
    var readingRefreshRequested by mutableStateOf(false)
        private set
    var expanded by mutableStateOf(preferences.getBoolean("expanded", false))
        private set
    var position by mutableStateOf(CricketBadgePosition(preferences.getFloat("x", .95f), preferences.getFloat("y", .08f)).safe())

    fun select(match: CricketLiveMatch) {
        score = null; scoreError = null; lastSuccess = 0; reading = null; readingRefreshRequested = false
        selected = match; pickerOpen = false; moving = false
        preferences.edit().putInt("match", match.id).putString("title", match.title)
            .putString("description", match.description).putBoolean("hundred", match.hundred).apply()
        refresh++
    }
    fun stop() {
        selected = null; score = null; scoreError = null; reading = null; readingRefreshRequested = false; moving = false
        preferences.edit().remove("match").apply()
    }
    fun savePosition() { position = position.safe(); preferences.edit().putFloat("x", position.x).putFloat("y", position.y).apply() }
    fun resetPosition() { position = CricketBadgePosition(); savePosition() }
    fun updateExpanded(value: Boolean) { expanded = value; preferences.edit().putBoolean("expanded", value).apply() }
    fun showCommentary() {
        val latest = score ?: return
        val ball = latest.lastDelivery ?: return
        reading = CricketScoreReading(ball.id, "Last ball · ${ball.over}", ball.description.ifBlank { "Commentary is not available for this delivery yet." }, latest)
    }
    fun showHighlight(event: CricketScoreEvent) { reading = CricketScoreReading(event.id, event.title, event.detail, score) }
    fun dismissReading() { reading = null; readingRefreshRequested = false }
    fun refreshTile() { readingRefreshRequested = reading != null; refresh++ }
    fun refreshFailed() { readingRefreshRequested = false }
    fun receivedScore(latest: CricketLiveScore, highlights: List<CricketScoreEvent>) {
        score = latest
        if (readingRefreshRequested) { readingRefreshRequested = false; showCommentary() }
        else if (reading == null) highlights.lastOrNull()?.let(::showHighlight)
    }
}

/** Movement owns directional input before either a native video surface or playback controls. */
internal fun Modifier.cricketScoreMovement(controller: CricketScoreController): Modifier = onPreviewKeyEvent { event ->
    if (!controller.moving || event.key !in setOf(Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp,
            Key.DirectionDown, Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) false
    else {
        if (event.type == KeyEventType.KeyDown) {
            val step = controller.movementStep
            when (event.key) {
                Key.DirectionLeft -> controller.position = controller.position.move(-step, 0f, controller.movementWidth, controller.movementHeight)
                Key.DirectionRight -> controller.position = controller.position.move(step, 0f, controller.movementWidth, controller.movementHeight)
                Key.DirectionUp -> controller.position = controller.position.move(0f, -step, controller.movementWidth, controller.movementHeight)
                Key.DirectionDown -> controller.position = controller.position.move(0f, step, controller.movementWidth, controller.movementHeight)
                else -> { controller.moving = false; controller.savePosition() }
            }
        }
        true
    }
}

@Composable
internal fun rememberCricketScoreController(): CricketScoreController {
    val context = LocalContext.current
    val controller = remember(context.applicationContext) { CricketScoreController(context) }
    val suppliedRepository = LocalCricketLiveScoresRepository.current
    val repository = remember(suppliedRepository) { suppliedRepository ?: CricketLiveScoresRepository() }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val selected = controller.selected
    LaunchedEffect(selected, active, controller.refresh) {
        if (selected == null || !active) return@LaunchedEffect
        val tracker = CricketScoreEventTracker()
        var failures = 0
        while (true) {
            val timing = repository.timing()
            controller.refreshIntervalMillis = timing.widgetMillis
            try {
                val result = repository.score(selected)
                if (controller.selected?.id != selected.id) break
                // Preserve a valid score if an innings break temporarily omits miniscore.
                val current = if (result.innings.isEmpty()) result.copy(innings = controller.score?.innings ?: selected.scores) else result
                controller.receivedScore(current, tracker.accept(result.events))
                controller.scoreError = null; controller.lastSuccess = SystemClock.elapsedRealtime(); failures = 0
                if (result.complete) break
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (controller.selected?.id != selected.id) break
                controller.refreshFailed()
                controller.scoreError = if (error is SportsException) error.message else "Score connection interrupted. Retrying…"
                failures++
            }
            delay(if (failures == 0) timing.widgetMillis else (timing.widgetMillis shl failures.coerceAtMost(3)).coerceAtMost(120_000L))
        }
    }
    LaunchedEffect(controller.pickerOpen, active, controller.refresh) {
        if (!controller.pickerOpen || !active) return@LaunchedEffect
        while (true) {
            controller.listLoading = true
            try { controller.matches = repository.matches(); controller.listError = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { controller.listError = if (error is SportsException) error.message else "Could not load matches. Please retry." }
            finally { controller.listLoading = false }
            delay(repository.timing().matchesMillis)
        }
    }
    return controller
}

@Composable
internal fun CricketScoreOverlay(controller: CricketScoreController, visible: Boolean = true) {
    val selected = controller.selected
    val density = LocalDensity.current
    val badgeFocus = remember { FocusRequester() }
    val badgeInteraction = remember { MutableInteractionSource() }
    val badgePressed by badgeInteraction.collectIsPressedAsState()
    var badgeSize by remember { mutableStateOf(IntSize.Zero) }
    var clock by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(selected?.id) {
        while (selected != null) { clock = SystemClock.elapsedRealtime(); delay(1_000L) }
    }
    LaunchedEffect(controller.moving) {
        if (controller.moving) { delay(100); runCatching { badgeFocus.requestFocus() } }
    }
    BackHandler(controller.moving) { controller.moving = false; controller.savePosition() }
    BackHandler(controller.reading != null && !controller.pickerOpen && !controller.moving) { controller.dismissReading() }
    if (selected != null && visible) BoxWithConstraints(Modifier.fillMaxSize().zIndex(5f)) {
        val inset = with(density) { 12.dp.toPx() }
        val travelX = (constraints.maxWidth - badgeSize.width - inset * 2).coerceAtLeast(0f)
        val travelY = (constraints.maxHeight - badgeSize.height - inset * 2).coerceAtLeast(0f)
        SideEffect { controller.movementWidth = travelX; controller.movementHeight = travelY }
        val position = controller.position.safe()
        val reading = controller.reading
        val score = reading?.score ?: controller.score
        val detailed = controller.expanded || reading != null || controller.moving
        val readingTextHeight = (maxHeight - 210.dp).coerceIn(32.dp, 200.dp)
        val badgeShape = RoundedCornerShape(8.dp)
        val stale = controller.scoreError != null || (controller.lastSuccess > 0 && clock - controller.lastSuccess > maxOf(15_000L, controller.refreshIntervalMillis * 3))
        val accent = when {
            controller.moving -> Color(0xFF90CAF9)
            stale -> Color(0xFFFFCA6A)
            reading?.title?.contains("WICKET") == true -> Color(0xFFFF7272)
            else -> Color(0xFF62E3AC)
        }
        Surface(
            modifier = Modifier.offset { IntOffset((inset + position.x * travelX).roundToInt(), (inset + position.y * travelY).roundToInt()) }
                .widthIn(max = (maxWidth - 24.dp).coerceAtMost(if (reading != null) 380.dp else if (detailed) 244.dp else 300.dp))
                .onSizeChanged { badgeSize = it }
                .border(if (controller.moving) 2.dp else 1.dp,
                    Brush.linearGradient(listOf(Color.White.copy(alpha = if (badgePressed) .6f else .32f),
                        accent.copy(alpha = if (badgePressed) .6f else .38f), Color.White.copy(alpha = .08f))), badgeShape)
                .focusRequester(badgeFocus)
                .onPreviewKeyEvent { key ->
                    if (!controller.moving || key.type != KeyEventType.KeyDown) false else {
                        val step = with(density) { 18.dp.toPx() }
                        when (key.key) {
                            Key.DirectionLeft -> controller.position = controller.position.move(-step, 0f, travelX, travelY)
                            Key.DirectionRight -> controller.position = controller.position.move(step, 0f, travelX, travelY)
                            Key.DirectionUp -> controller.position = controller.position.move(0f, -step, travelX, travelY)
                            Key.DirectionDown -> controller.position = controller.position.move(0f, step, travelX, travelY)
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { controller.moving = false; controller.savePosition() }
                            else -> return@onPreviewKeyEvent false
                        }
                        true
                    }
                }
                .focusable(enabled = controller.moving)
                .pointerInput(controller) {
                    detectDragGestures(onDragEnd = controller::savePosition, onDragCancel = controller::savePosition) { change, drag ->
                        change.consume()
                        controller.position = controller.position.move(drag.x, drag.y, controller.movementWidth, controller.movementHeight)
                    }
                }
                .clickable(interactionSource = badgeInteraction, indication = null) {
                    if (controller.moving) { controller.moving = false; controller.savePosition() }
                    else controller.pickerOpen = true
                }
                .semantics { contentDescription = "Live cricket score. ${selected.title}. ${score?.innings?.joinToString { it.label }.orEmpty()}. Drag to move; tap for matches." },
            shape = badgeShape, color = Color.Transparent, contentColor = Color.White,
            shadowElevation = 10.dp,
        ) {
            Column(Modifier.animateContentSize(spring(stiffness = 400f))
                .background(Brush.linearGradient(listOf(Color(0xCF263440), Color(0xAE0C131E))))
                .padding(horizontal = 10.dp, vertical = if (detailed) 7.dp else 3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val innings = score?.innings?.takeIf { it.isNotEmpty() } ?: selected.scores
                if (!detailed) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(5.dp).background(accent, androidx.compose.foundation.shape.CircleShape))
                        val current = innings.lastOrNull()
                        AnimatedContent(targetState = current?.let { "${it.team} ${it.runs}/${it.wickets}" } ?: selected.title,
                            label = "Compact live total", modifier = Modifier.weight(1f, fill = false),
                            transitionSpec = { (slideInVertically(tween(220)) { it / 2 } + fadeIn()) togetherWith
                                (slideOutVertically(tween(180)) { -it / 2 } + fadeOut()) }) { total ->
                            Text(total, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        current?.let { Text(it.overs, fontSize = 10.sp, lineHeight = 13.sp, color = Color.White.copy(alpha = .65f), maxLines = 1) }
                        score?.lastDelivery?.let { CricketLastBall(it, stale, compact = true) }
                        CricketBadgeToggle(controller, detailed)
                    }
                } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(5.dp).background(accent, androidx.compose.foundation.shape.CircleShape))
                    Text(if (score?.complete == true) "FINAL" else if (stale) "RECONNECTING" else "LIVE", color = accent, fontSize = 9.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
                    Text(selected.title, Modifier.weight(1f), color = Color.White.copy(alpha = .7f), fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (reading == null) IconButton(onClick = controller::showCommentary, enabled = score?.lastDelivery != null, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.ChatBubbleOutline, "Read last-ball commentary", Modifier.size(15.dp))
                    }
                    IconButton(onClick = controller::refreshTile, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Refresh, if (reading != null) "Refresh commentary" else "Refresh live score", Modifier.size(15.dp))
                    }
                    CricketBadgeToggle(controller, detailed)
                }
                if (innings.isEmpty()) Text(if (stale) "Score unavailable" else "Connecting to score…", fontSize = 12.sp, lineHeight = 16.sp)
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        innings.takeLast(2).forEach { inning ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                Text(inning.team, color = Color.White.copy(alpha = .72f), fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.widthIn(min = 26.dp, max = 44.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                AnimatedContent(targetState = "${inning.runs}/${inning.wickets}", label = "Total score",
                                    transitionSpec = { (slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(220))) togetherWith
                                        (slideOutVertically(tween(200)) { -it / 2 } + fadeOut(tween(160))) }) { total ->
                                    Text(total, fontSize = 19.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold,
                                        maxLines = 1, softWrap = false)
                                }
                                Text(inning.overs, color = Color.White.copy(alpha = .5f), fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1)
                            }
                        }
                    }
                    score?.lastDelivery?.let { CricketLastBall(it, stale, compact = reading == null) }
                }
                val note = when {
                    controller.moving -> "Move with arrows · OK to save"
                    stale -> if (controller.lastSuccess == 0L) "Waiting for a score update" else "Last updated ${((clock - controller.lastSuccess) / 1000).coerceAtLeast(0)}s ago"
                    score?.complete == true -> score.status
                    score?.recentBalls?.isNotBlank() == true -> score.recentBalls
                    else -> score?.status ?: selected.status
                }
                if (note.isNotBlank()) Text(note, color = Color.White.copy(alpha = .55f), fontSize = 9.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (reading != null) {
                    HorizontalDivider(Modifier.padding(vertical = 3.dp), color = accent.copy(alpha = .3f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(reading.title, Modifier.weight(1f), color = accent, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.ExtraBold)
                        IconButton(onClick = controller::dismissReading, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, "Dismiss commentary", Modifier.size(16.dp))
                        }
                    }
                    val textScroll = key(reading.id) { rememberScrollState() }
                    Text(reading.text, Modifier.fillMaxWidth().heightIn(max = readingTextHeight).verticalScroll(textScroll),
                        fontSize = 12.sp, lineHeight = 17.sp)
                    Text(if (controller.readingRefreshRequested) "Refreshing…" else if (textScroll.canScrollForward) "Scroll for full commentary · held until dismissed" else if (controller.score?.lastDelivery?.id != reading.score?.lastDelivery?.id)
                        "New delivery available · refresh when ready" else "Held for reading · dismiss when done",
                        fontSize = 9.sp, lineHeight = 12.sp, color = Color.White.copy(alpha = .5f))
                }
                }
            }
        }
    }
    if (controller.pickerOpen && visible) CricketMatchPicker(controller)
}

@Composable
private fun CricketBadgeToggle(controller: CricketScoreController, detailed: Boolean) {
    IconButton(onClick = {
        controller.updateExpanded(!detailed)
        if (detailed) { controller.dismissReading(); controller.moving = false; controller.savePosition() }
    }, modifier = Modifier.size(28.dp)) {
        Icon(if (detailed) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            if (detailed) "Collapse score badge" else "Expand score badge", Modifier.size(18.dp), tint = Color.White.copy(alpha = .65f))
    }
}

@Composable
private fun CricketLastBall(delivery: CricketDelivery, stale: Boolean, compact: Boolean = false) {
    val scale = remember { Animatable(1f) }
    LaunchedEffect(delivery.id) {
        scale.snapTo(.85f)
        scale.animateTo(1f, spring(dampingRatio = .55f, stiffness = 350f))
    }
    val color = when {
        stale -> Color(0xFFFFCA6A)
        delivery.value.equals("W", true) -> Color(0xFFFF7272)
        delivery.value in setOf("4", "6") -> Color(0xFF62E3AC)
        else -> Color(0xFF97C9FF)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (!compact) Text("LAST BALL", color = Color.White.copy(alpha = .45f), fontSize = 7.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .5.sp)
        Box(Modifier.size(if (compact) 23.dp else 32.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .background(color.copy(alpha = .13f), RoundedCornerShape(8.dp))
            .border(1.dp, color.copy(alpha = .25f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            AnimatedContent(targetState = delivery, label = "Last ball", transitionSpec = {
                (slideInVertically(tween(240)) { it } + fadeIn()) togetherWith (slideOutVertically(tween(180)) { -it } + fadeOut())
            }) { ball -> Text(if (ball.value == "0") "•" else ball.value, color = color,
                fontSize = if (compact) 11.sp else if (ball.value.length > 2) 12.sp else 19.sp,
                lineHeight = if (compact) 14.sp else 22.sp, fontWeight = FontWeight.ExtraBold) }
        }
    }
}

@Composable
private fun CricketMatchPicker(controller: CricketScoreController) {
    val entry = remember { FocusRequester() }
    Dialog(onDismissRequest = { controller.pickerOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp), contentAlignment = Alignment.Center) {
            val wide = maxWidth > 580.dp && maxWidth > maxHeight
            Surface(Modifier.widthIn(max = 860.dp).fillMaxWidth().heightIn(max = maxHeight.coerceAtMost(560.dp))
                .initialFocusEntry(entry), shape = RoundedCornerShape(24.dp),
                color = Color.Transparent, contentColor = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp,
                    Brush.linearGradient(listOf(Color.White.copy(alpha = .28f), Color.White.copy(alpha = .06f))))) {
                Column(Modifier.background(Brush.linearGradient(listOf(Color(0xDA2A3039), Color(0xC5111721)))).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Live cricket scores", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        NikTvSecondaryActionButton({ controller.refresh++ }, Modifier.size(40.dp), contentPadding = PaddingValues(0.dp)) {
                            Icon(Icons.Default.Refresh, "Refresh matches", Modifier.size(19.dp))
                        }
                        NikTvSecondaryActionButton({ controller.pickerOpen = false }, Modifier.size(40.dp).focusRequester(entry), contentPadding = PaddingValues(0.dp)) {
                            Icon(Icons.Default.Close, "Done", Modifier.size(19.dp))
                        }
                    }
                    if (wide) Row(Modifier.fillMaxWidth().weight(1f, fill = false).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CricketBadgeControls(controller, Modifier.weight(.36f).verticalScroll(rememberScrollState()))
                        VerticalDivider(Modifier.fillMaxHeight(), color = Color.White.copy(alpha = .1f))
                        CricketMatchesList(controller, Modifier.weight(.64f).verticalScroll(rememberScrollState()))
                    } else Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CricketBadgeControls(controller, Modifier.fillMaxWidth())
                        HorizontalDivider(color = Color.White.copy(alpha = .1f))
                        CricketMatchesList(controller, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun CricketBadgeControls(controller: CricketScoreController, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val selected = controller.selected
        if (selected == null) {
            Text("Choose a match", fontWeight = FontWeight.SemiBold)
            Text("Show its live score while you watch. Drag the badge to position it.", color = Color.White.copy(alpha = .6f), style = MaterialTheme.typography.bodySmall)
        } else {
            Text(selected.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text("Following this match", color = Color.White.copy(alpha = .55f), style = MaterialTheme.typography.labelSmall)
            NikTvActionButton(if (controller.expanded) "Use compact score pill" else "Show badge details",
                { controller.updateExpanded(!controller.expanded) }, Modifier.fillMaxWidth().height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NikTvActionButton("Move badge", { controller.pickerOpen = false; controller.moving = true }, Modifier.weight(1f).height(36.dp))
                NikTvActionButton("Reset position", controller::resetPosition, Modifier.weight(1f).height(36.dp))
            }
            NikTvActionButton("Turn off live scoring", controller::stop, Modifier.fillMaxWidth().height(36.dp))
        }
    }
}

@Composable
private fun CricketMatchesList(controller: CricketScoreController, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (controller.listLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        controller.listError?.let { Text(it, color = Color(0xFFFFCA6A), style = MaterialTheme.typography.bodySmall) }
        if (!controller.listLoading && controller.listError == null && controller.matches.isEmpty()) Text("No live matches right now.")
        controller.matches.forEach { match ->
            NikTvSecondaryActionButton({ controller.select(match) }, Modifier.fillMaxWidth(), contentPadding = PaddingValues(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(match.title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 17.sp)
                    if (match.description.isNotBlank()) Text(match.description, color = Color.White.copy(alpha = .55f), fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (match.scores.isNotEmpty()) Text(match.scores.joinToString(" · ") { it.label }, color = Color.White.copy(alpha = .85f), fontSize = 11.sp, lineHeight = 14.sp)
                    if (match.status.isNotBlank()) Text(match.status, color = Color.White.copy(alpha = .55f), fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (controller.selected?.id == match.id) { Spacer(Modifier.width(6.dp)); Icon(Icons.Default.Check, "Following", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}
