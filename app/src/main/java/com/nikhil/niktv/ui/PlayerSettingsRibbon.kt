package com.nikhil.niktv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class PlayerSettingKind(val title: String, val primaryOrder: Int? = null) {
    SUBTITLES("Subtitles", 0), AUDIO("Audio", 1), SPEED("Speed", 2), FIT("Video fit", 3),
    PICTURE("Picture", 4), PLAYER("Player", 5), DOWNLOAD("Download", 6), SOURCE("Source", 7), REFRESH("Refresh", 0),
    INTRO("Intro timing"), TIMEOUT("Controls timeout"), CRICKET("Cricket scores"), INFORMATION("Stream info"), PIP("Mini player")
}

internal data class PlayerSettingAction(
    val kind: PlayerSettingKind,
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
    val requester: FocusRequester,
    val modifier: Modifier = Modifier,
    val selected: Boolean = false,
    val progress: Float? = null,
    val indeterminateProgress: Boolean = false,
    val badgeText: String? = null,
    val value: String? = null
) {
    fun displayValue(): String = value ?: badgeText ?: when (kind) {
        PlayerSettingKind.PICTURE -> contentDescription.substringAfter(": ", "Standard")
        PlayerSettingKind.PLAYER -> contentDescription.substringAfter(": ", "Auto")
        PlayerSettingKind.FIT -> contentDescription.substringAfter(": ", "Fit")
        PlayerSettingKind.TIMEOUT -> contentDescription.substringAfter(": ", "3 seconds")
        PlayerSettingKind.DOWNLOAD -> if (selected) "Saved" else "Not saved"
        else -> ""
    }
}

/** One horizontal row at every width; narrower/embedded players expose explicit pages. */
internal fun playerRibbonSlots(width: Float, actionCount: Int): Int =
    ((width + 6f) / 70f).toInt().coerceIn(2, 8).coerceAtMost((actionCount + 1).coerceAtLeast(2))

internal fun playerRibbonPrimary(actions: List<PlayerSettingAction>) =
    actions.filter { it.kind.primaryOrder != null }.sortedBy { it.kind.primaryOrder }

internal class PlayerSettingsRibbonState {
    var tools by mutableStateOf(false)
        private set
    var page by mutableIntStateOf(0)
        private set
    private var target by mutableStateOf<FocusRequester?>(null)
    private var lastFocused: FocusRequester? = null
    private var visibleActions: List<PlayerSettingAction> = emptyList()
    private val more = FocusRequester()
    fun entry(actions: List<PlayerSettingAction>): FocusRequester = lastFocused?.takeIf { requester ->
        requester == more || visibleActions.any { it.requester == requester }
    } ?: visibleActions.firstOrNull()?.requester ?: current(actions).firstOrNull()?.requester ?: more
    fun open(actions: List<PlayerSettingAction>, directional: Boolean = true) {
        tools = false; page = 0; visibleActions = emptyList(); lastFocused = null
        target = if (directional) playerRibbonPrimary(actions).firstOrNull()?.requester ?: more else null
    }
    fun restore(requester: FocusRequester) { target = requester }
    fun showingTools(actions: List<PlayerSettingAction>): Boolean =
        actions.firstOrNull { it.requester == target }?.let { it.kind.primaryOrder == null } ?: tools
    fun current(actions: List<PlayerSettingAction>) =
        if (showingTools(actions)) actions.filter { it.kind.primaryOrder == null } else playerRibbonPrimary(actions)
    fun visiblePage(actions: List<PlayerSettingAction>, capacity: Int): Int {
        val index = current(actions).indexOfFirst { it.requester == target }
        return if (index >= 0) index / capacity else page
    }
    fun visible(actions: List<PlayerSettingAction>) { visibleActions = actions }
    fun rememberFocus(requester: FocusRequester) { lastFocused = requester }
    fun changeGroup(actions: List<PlayerSettingAction>, directional: Boolean = true) {
        tools = !showingTools(actions); page = 0; visibleActions = emptyList(); target = null
        target = if (directional) current(actions).firstOrNull()?.requester ?: more else null
    }
    fun changePage(next: Int) { page = next; target = null }
    fun attach(requester: FocusRequester, shownTools: Boolean, shownPage: Int) {
        if (target == requester && runCatching { requester.requestFocus() }.getOrDefault(false)) {
            tools = shownTools; page = shownPage; target = null
        }
    }
    fun moreRequester() = more
}

@Composable
internal fun PlayerSettingsRibbon(
    visible: Boolean,
    actions: List<PlayerSettingAction>,
    state: PlayerSettingsRibbonState,
    toggle: FocusRequester,
    down: FocusRequester,
    onDismiss: () -> Unit,
    onFocused: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (!visible || actions.isEmpty()) return
    val input = LocalInputModeManager.current
    val television = rememberPlayerTvDevice()
    val close = remember { FocusRequester() }
    val previousPage = remember { FocusRequester() }
    val nextPage = remember { FocusRequester() }
    fun dismiss() {
        // The surviving trigger owns focus before the ribbon leaves composition.
        runCatching { toggle.requestFocus() }
        onDismiss()
    }
    fun back() { if (state.showingTools(actions)) state.changeGroup(actions, input.inputMode == InputMode.Keyboard || television) else dismiss() }
    BackHandler(onBack = ::back)
    val short = LocalConfiguration.current.screenHeightDp < 480
    Column(modifier.fillMaxWidth().padding(bottom = 5.dp).onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { back(); true } else false
    }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val slots = playerRibbonSlots(maxWidth.value, maxOf(playerRibbonPrimary(actions).size, actions.count { it.kind.primaryOrder == null }))
            val capacity = slots - 1
            val group = state.current(actions)
            val pages = ((group.size + capacity - 1) / capacity).coerceAtLeast(1)
            val page = state.visiblePage(actions, capacity).coerceIn(0, pages - 1)
            val tools = state.showingTools(actions)
            val shown = group.drop(page * capacity).take(capacity)
            state.visible(shown)
            val more = state.moreRequester()
            val entry = shown.firstOrNull()?.requester ?: more
            val density = LocalDensity.current
            val cell = with(density) { ((constraints.maxWidth - 6.dp.roundToPx() * (slots - 1)) / slots).toDp() }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (tools) "More controls" else "Quick controls", Modifier.weight(1f),
                        color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.labelMedium)
                    if (pages > 1) {
                        Text("${page + 1} / $pages", color = Color.White.copy(alpha = .6f), style = MaterialTheme.typography.labelSmall)
                        RibbonHeaderAction(Icons.Default.ChevronLeft, "Previous controls", previousPage, entry, close, nextPage, onFocused) {
                            state.changePage((page + pages - 1) % pages)
                        }
                        RibbonHeaderAction(Icons.Default.ChevronRight, "Next controls", nextPage, entry, previousPage, close, onFocused) {
                            state.changePage((page + 1) % pages)
                        }
                    }
                    RibbonHeaderAction(Icons.Default.Close, "Close player settings", close, entry,
                        if (pages > 1) nextPage else more, FocusRequester.Cancel, onFocused, ::dismiss)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    shown.forEachIndexed { index, action -> key(action.kind) {
                        val left = shown.getOrNull(index - 1)?.requester ?: FocusRequester.Cancel
                        val right = shown.getOrNull(index + 1)?.requester ?: more
                        RibbonAction(action.icon, action.kind.title, action.displayValue(), action.contentDescription,
                            Modifier.width(cell).then(action.modifier).focusRequester(action.requester)
                                .onGloballyPositioned { state.attach(action.requester, tools, page) }
                                .ribbonFocusRoutes(left, right, close, down),
                            action.selected, action.progress, action.indeterminateProgress, short,
                            onFocused = { focused -> if (focused) state.rememberFocus(action.requester); onFocused(focused) },
                            onClick = action.onClick)
                    } }
                    repeat(capacity - shown.size) { Spacer(Modifier.width(cell)) }
                    RibbonAction(if (tools) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.MoreHoriz,
                        if (tools) "Back" else "More", if (tools) "Quick controls" else "Tools",
                        if (tools) "Back to quick controls" else "More player tools",
                        Modifier.width(cell).focusRequester(more).onGloballyPositioned { state.attach(more, tools, page) }
                            .ribbonFocusRoutes(shown.lastOrNull()?.requester ?: FocusRequester.Cancel, FocusRequester.Cancel, close, down),
                        false, null, false, short, { focused ->
                            if (focused) state.rememberFocus(more)
                            onFocused(focused)
                        }, {
                            // A touch-opened row stays in touch mode; D-pad enters its first tool before drawing.
                            state.changeGroup(actions, input.inputMode == InputMode.Keyboard || television)
                        })
                }
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = .12f))
    }
}

private fun Modifier.ribbonFocusRoutes(left: FocusRequester, right: FocusRequester, up: FocusRequester, down: FocusRequester) =
    focusProperties { this.left = left; this.right = right; this.up = up; this.down = down }
        .playerDpadFocusRoutes(left = left, right = right, up = up, down = down)

@Composable
private fun RibbonHeaderAction(icon: ImageVector, label: String, requester: FocusRequester, down: FocusRequester,
    left: FocusRequester, right: FocusRequester, onFocused: (Boolean) -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(9.dp)
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = onClick, modifier = Modifier.size(40.dp).focusRequester(requester)
        .ribbonFocusRoutes(left, right, FocusRequester.Cancel, down)
        .playerControlFocus(shape, scaleOnFocus = false) { focused = it; onFocused(it) },
        shape = shape, color = if (focused) Color(0xFF344050) else Color.Transparent) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(icon, label, Modifier.size(19.dp)) }
    }
}

@Composable
private fun RibbonAction(icon: ImageVector, label: String, value: String, description: String, modifier: Modifier,
    selected: Boolean, progress: Float?, indeterminate: Boolean, compact: Boolean, onFocused: (Boolean) -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    var focused by remember { mutableStateOf(false) }
    Surface(onClick = onClick, modifier = modifier.height(if (compact) 64.dp else 68.dp)
        .semantics(mergeDescendants = true) { contentDescription = description; stateDescription = value; role = Role.Button }
        .playerControlFocus(shape, scaleOnFocus = false) { focused = it; onFocused(it) },
        shape = shape, color = if (focused) Color(0xFF344050) else if (selected) Color(0xFF252C36) else Color.Transparent) {
        Column(Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(23.dp), contentAlignment = Alignment.Center) {
                if (indeterminate) CircularProgressIndicator(Modifier.fillMaxSize(), strokeWidth = 2.dp)
                else if (progress != null) CircularProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxSize(), strokeWidth = 2.dp)
                Icon(icon, null, Modifier.size(20.dp))
            }
            Text(label, color = Color.White, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value.ifBlank { " " }, color = Color.White.copy(alpha = .64f), fontSize = 11.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
