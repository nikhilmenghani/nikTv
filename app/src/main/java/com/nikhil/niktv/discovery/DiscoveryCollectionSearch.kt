package com.nikhil.niktv.discovery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.ui.remoteFocusFrame
import kotlinx.coroutines.launch

/** Browsing focus never starts editing. Only activation expands the editor. */
@Composable
internal fun DiscoveryCollectionSearch(query: String, change: (String) -> Unit, compact: Boolean, onActivate: () -> Unit = {}) {
    var expanded by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var filterFocused by remember { mutableStateOf(false) }
    val icon = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    val browsingField = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var imeSeen by remember { mutableStateOf(false) }
    fun finishEditing() {
        editing = false
        imeSeen = false
        keyboard?.hide()
        scope.launch { withFrameNanos { }; if (expanded && !editing) runCatching { browsingField.requestFocus() } }
    }
    fun close() {
        editing = false
        imeSeen = false
        expanded = false
        change("")
        keyboard?.hide()
        scope.launch { withFrameNanos { }; runCatching { icon.requestFocus() } }
    }
    BackHandler(expanded) { if (editing) finishEditing() else close() }
    LaunchedEffect(imeVisible, expanded, editing) {
        if (expanded && editing && imeVisible) imeSeen = true
        else if (expanded && editing && imeSeen) finishEditing()
    }
    LaunchedEffect(expanded, editing) {
        if (expanded && editing) {
            withFrameNanos { }
            if (runCatching { field.requestFocus() }.getOrDefault(false)) keyboard?.show()
        }
    }
    val width by animateDpAsState(if (expanded) (if (compact) 160.dp else 240.dp) else 44.dp,
        tween(180), label = "collectionSearchWidth")
    Row(Modifier.width(width).height(44.dp)) {
        if (expanded) Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(10.dp),
            color = Color(0xFF242831),
            border = BorderStroke(if (filterFocused) 2.dp else 1.dp, if (filterFocused) Color.White else Color(0xFF454B58))) {
            Row(Modifier.fillMaxSize().padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).fillMaxHeight().focusRequester(browsingField)
                    .onFocusChanged { filterFocused = it.hasFocus }
                    .semantics(mergeDescendants = !editing) {
                        if (!editing) onClick(label = "Edit filter") { editing = true; true }
                    }
                    .onPreviewKeyEvent { event ->
                        if (!editing && event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                            if (event.type == KeyEventType.KeyUp) editing = true
                            true
                        } else if (!editing && event.type == KeyEventType.KeyDown) {
                            val direction = when (event.key) {
                                Key.DirectionDown -> FocusDirection.Down
                                Key.DirectionUp -> FocusDirection.Up
                                Key.DirectionLeft -> FocusDirection.Left
                                Key.DirectionRight -> FocusDirection.Right
                                else -> null
                            }
                            direction?.let(focusManager::moveFocus) ?: false
                        } else false
                    }.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            if (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) editing = true
                        }
                    }.focusable(enabled = !editing)) {
                BasicTextField(query, change, modifier = Modifier.fillMaxWidth().align(Alignment.CenterStart)
                    .focusRequester(field).focusProperties { canFocus = editing }, readOnly = !editing,
                    singleLine = true, textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.White),
                    cursorBrush = SolidColor(Color.White),
                    decorationBox = { inner -> Box {
                        if (query.isEmpty()) Text("Search titles", color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        inner()
                    } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, showKeyboardOnFocus = false),
                    keyboardActions = KeyboardActions(onDone = { finishEditing() }))
                }
                IconButton(onClick = ::close, modifier = Modifier.size(40.dp).remoteFocusFrame(CircleShape)) {
                    Icon(Icons.Default.Close, "Close search", Modifier.size(18.dp))
                }
            }
        }
        else DiscoveryAction("Search titles", Icons.Default.Search, iconOnly = true,
            modifier = Modifier.focusRequester(icon)) { onActivate(); expanded = true; editing = true }
    }
}
