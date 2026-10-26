package com.nikhil.niktv.ui

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun EpisodeNumberDialog(onGo: (Int) -> Unit, dismiss: () -> Unit) {
    var value by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    val number = value.toIntOrNull()?.takeIf { it > 0 }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    fun activate() { editing = true; scope.launch { withFrameNanos { }; field.requestFocus(); keyboard?.show() } }
    AlertDialog(onDismissRequest = dismiss, containerColor = Color(0xF21A1A1A), shape = RoundedCornerShape(16.dp),
        title = { Text("Go to episode") },
        text = { Column(Modifier.playerChoiceFocusEntry(field), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Enter an episode number in the selected season. Missing pages will load automatically.")
            OutlinedTextField(value, { if (it.length <= 9 && it.all(Char::isDigit)) value = it },
                modifier = Modifier.fillMaxWidth().focusRequester(field)
                    .onFocusChanged { if (!it.isFocused) { editing = false; keyboard?.hide() } }
                    .onPreviewKeyEvent {
                        if (!editing && it.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                            if (it.type == KeyEventType.KeyUp) activate()
                            true
                        } else false
                    }.pointerInput(Unit) { awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) activate()
                    } },
                label = { Text("Episode number") }, readOnly = !editing, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { number?.let { keyboard?.hide(); onGo(it) } }))
        } },
        dismissButton = { NikTvActionButton("Cancel", primary = false, modifier = Modifier.width(112.dp).height(48.dp), onClick = dismiss) },
        confirmButton = { NikTvActionButton("Go", primary = true, modifier = Modifier.width(112.dp).height(48.dp),
            enabled = number != null, onClick = { number?.let { keyboard?.hide(); onGo(it) } }) })
}

@Composable
internal fun EpisodeDestinationDialog(state: EpisodeDestinationState, cancel: () -> Unit, retry: () -> Unit) {
    val close = remember { FocusRequester() }
    AlertDialog(onDismissRequest = cancel, containerColor = Color(0xF21A1A1A), shape = RoundedCornerShape(16.dp),
        title = { Text(if (state.number == null) "Finding the earliest episode" else "Finding episode ${state.number}") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("${state.loaded} episodes loaded · page ${state.page}")
                Text("Loading this season’s remaining pages. Loaded episodes are cached for next time.")
            } else Text(state.message.orEmpty())
        } },
        dismissButton = { key(state.request) {
            // Run entry focus in the dialog's window, once it is ready. Explicitly permit
            // focus after touch as well, without switching the whole app to remote input.
            NikTvActionButton(if (state.running) "Cancel" else "Close", primary = false,
                modifier = Modifier.width(112.dp).height(48.dp).initialFocusEntry(close)
                    .focusRequester(close).focusProperties { canFocus = true }, onClick = cancel)
        } },
        confirmButton = { if (!state.running) NikTvActionButton("Retry", primary = true,
            modifier = Modifier.width(112.dp).height(48.dp), onClick = retry) })
}
