package com.nikhil.niktv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

/** Stable row keys keep focus on the moved item, including at the first/last boundary. */
@Composable
internal fun ReorderItemsDialog(title: String, choices: List<OrderChoice>, close: () -> Unit,
    save: suspend (List<String>) -> Unit) {
    val available = remember { choices.distinctBy { it.id } }
    var ordered by remember { mutableStateOf(available) }
    var moving by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .88f).dp
    val list = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    val moveFocus = remember { FocusRequester() }
    val rowFocus = remember { available.associate { it.id to FocusRequester() } }
    var controllerFocused by remember { mutableStateOf(false) }
    val inputMode = LocalInputModeManager.current.inputMode
    var entered by remember { mutableStateOf(false) }
    fun finishMoving() {
        val id = moving ?: return
        scope.launch {
            val index = ordered.indexOfFirst { it.id == id }
            val visible = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
            if (visible == null || visible.offset < list.layoutInfo.viewportStartOffset ||
                visible.offset + visible.size > list.layoutInfo.viewportEndOffset) list.scrollToItem(index)
            withFrameNanos { }
            // Keep the controller alive until the destination row owns focus.
            if (rowFocus[id]?.requestFocus() == true) moving = null
        }
    }
    fun startMoving(id: String) {
        moving = id
        scope.launch { withFrameNanos { }; moveFocus.requestFocus() }
    }
    fun move(id: String, delta: Int) {
        if (saving) return
        val index = ordered.indexOfFirst { it.id == id }
        val destination = index + delta
        if (index < 0 || destination !in ordered.indices) return
        val visible = list.layoutInfo.visibleItemsInfo.filter {
            it.offset >= list.layoutInfo.viewportStartOffset && it.offset + it.size <= list.layoutInfo.viewportEndOffset
        }
        val first = list.firstVisibleItemIndex
        val offset = list.firstVisibleItemScrollOffset
        ordered = ordered.toMutableList().apply { add(destination, removeAt(index)) }
        // Index anchoring prevents LazyColumn from following a moved first key and
        // hiding its new predecessor. Keep a moved focused row composed at the edges.
        when {
            destination < first -> list.requestScrollToItem(destination)
            destination > (visible.lastOrNull()?.index ?: destination) ->
                list.requestScrollToItem((destination - visible.size.coerceAtLeast(1) + 1).coerceAtLeast(0))
            else -> list.requestScrollToItem(first, offset)
        }
    }
    fun dismiss() { if (!saving) close() }
    Dialog(onDismissRequest = { if (moving != null) finishMoving() else dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Observe the dialog's window: never request focus while the background owns it.
        val dialogFocused = LocalWindowInfo.current.isWindowFocused
        LaunchedEffect(dialogFocused, inputMode) {
            if (!entered && dialogFocused && inputMode == InputMode.Keyboard && available.isNotEmpty()) {
                withFrameNanos { }
                entered = firstFocus.requestFocus()
            }
        }
        BackHandler(moving != null) { finishMoving() }
        Surface(Modifier.widthIn(max = 660.dp).fillMaxWidth(.94f).heightIn(max = maxHeight),
            shape = RoundedCornerShape(22.dp), color = SettingsSurface) {
            // Lazy rows may be disposed as their indices cross the viewport. This
            // continuously mounted target owns keyboard focus for the entire move.
            Column(Modifier.padding(16.dp).focusRequester(moveFocus)
                .focusProperties { canFocus = moving != null }
                .onFocusChanged { controllerFocused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    val id = moving
                    if (!controllerFocused || id == null || saving) false else when (event.key) {
                        Key.DirectionUp -> { if (event.type == KeyEventType.KeyDown) move(id, -1); true }
                        Key.DirectionDown -> { if (event.type == KeyEventType.KeyDown) move(id, 1); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.DirectionLeft, Key.DirectionRight -> {
                            if (event.type == KeyEventType.KeyUp) finishMoving(); true
                        }
                        else -> false
                    }
                }.semantics { moving?.let { id -> contentDescription = "Moving ${available.first { it.id == id }.title}" } }
                .focusable(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text("Use the arrows, or select an item and move it with Up/Down. Press OK to finish moving.",
                    color = SettingsMuted, style = MaterialTheme.typography.bodySmall)
                StableTileFocusViewport {
                    LazyColumn(Modifier.weight(1f, fill = false), state = list, contentPadding = PaddingValues(3.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(ordered, key = { it.id }) { choice ->
                            val index = ordered.indexOfFirst { it.id == choice.id }
                            val rowShape = RoundedCornerShape(12.dp)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OrderAction(onClick = {
                                    if (moving == choice.id) finishMoving() else startMoving(choice.id)
                                }, modifier = Modifier.weight(1f)
                                    .focusRequester(rowFocus.getValue(choice.id))
                                    .then(if (choice.id == available.firstOrNull()?.id) Modifier.focusRequester(firstFocus) else Modifier)
                                    .semantics { selected = moving == choice.id; contentDescription = "Arrange ${choice.title}" },
                                    shape = rowShape, emphasized = moving == choice.id,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                                    BasicText("${index + 1}", Modifier.width(28.dp), style = MaterialTheme.typography.bodyMedium.copy(color = SettingsMuted))
                                    BasicText(choice.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium.copy(color = Color.White))
                                    if (moving == choice.id) OrderIcon(Icons.Default.SwapVert, "Moving", Color.White)
                                }
                                // Boundary controls remain mounted and focusable; pressing them is a no-op.
                                // Disabling a focused arrow would send focus to another control after a move.
                                OrderAction({ move(choice.id, -1) }, Modifier.size(44.dp)
                                    .semantics { contentDescription = "Move ${choice.title} up" },
                                    contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(12.dp)) {
                                    OrderIcon(Icons.Default.KeyboardArrowUp, null, if (index > 0) Color.White else SettingsMuted.copy(alpha = .35f))
                                }
                                OrderAction({ move(choice.id, 1) }, Modifier.size(44.dp)
                                    .semantics { contentDescription = "Move ${choice.title} down" },
                                    contentPadding = PaddingValues(0.dp), shape = RoundedCornerShape(12.dp)) {
                                    OrderIcon(Icons.Default.KeyboardArrowDown, null, if (index < ordered.lastIndex) Color.White else SettingsMuted.copy(alpha = .35f))
                                }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NikTvSecondaryActionButton(::dismiss, Modifier.weight(1f), enabled = !saving) { Text("Cancel") }
                    NikTvPrimaryActionButton({
                        if (!saving) {
                            saving = true; error = null
                            scope.launch {
                                try { save(ordered.map { it.id }); close() }
                                catch (failure: Exception) {
                                    if (failure is kotlinx.coroutines.CancellationException) throw failure
                                    error = "Could not save order. Try again."; saving = false
                                }
                            }
                        }
                    }, Modifier.weight(1f)) {
                        if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("Apply")
                    }
                }
            }
        }
    }
}

/** Same action geometry/colors as NikTvActionSurface, using foundation nodes in
 * relocating lazy items. Material3 ChildSemanticsNode currently crashes when
 * its observed semantics invalidate after a reordered item detaches. */
@Composable
private fun OrderAction(onClick: () -> Unit, modifier: Modifier = Modifier, emphasized: Boolean = false,
    shape: RoundedCornerShape = RoundedCornerShape(12.dp), contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable RowScope.() -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(modifier.onFocusChanged { focused = it.isFocused }.clip(shape)
        .background(if (focused || emphasized) Color(0xFF303A49) else Color.White.copy(alpha = .055f), shape)
        .border(if (focused || emphasized) 2.dp else 1.dp,
            if (focused || emphasized) Color(0xFFE7E9EF) else Color.White.copy(alpha = .14f), shape)
        .semantics(mergeDescendants = true) { role = Role.Button }
        .clickable(role = Role.Button, onClick = onClick)
        .defaultMinSize(minHeight = 44.dp).padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, content = content)
}

@Composable
private fun OrderIcon(vector: ImageVector, description: String?, tint: Color) {
    Image(vector, description, Modifier.size(20.dp), colorFilter = ColorFilter.tint(tint))
}
