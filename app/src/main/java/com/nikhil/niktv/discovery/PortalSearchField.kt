package com.nikhil.niktv.discovery

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import com.nikhil.niktv.ui.isTvLikeDevice

internal class PortalSearchActivation { var consumed = 0 }

@Composable internal fun PortalSearchField(value: String, change: (String) -> Unit, label: String, modifier: Modifier,
    keyboardType: KeyboardType, activationRequest: Int = 0, editFinished: () -> Unit = {},
    editRequested: () -> Unit = {}, compact: Boolean = false, activationState: PortalSearchActivation? = null, submit: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var fieldFocused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val television = LocalContext.current.isTvLikeDevice(LocalConfiguration.current)
    val keyboard = LocalSoftwareKeyboardController.current
    val interactionSource = remember { MutableInteractionSource() }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White,
        unfocusedBorderColor = Color(0xFF454B58), cursorColor = Color.White,
        focusedPlaceholderColor = Color.White.copy(alpha = .6f), unfocusedPlaceholderColor = Color.White.copy(alpha = .6f))
    val fieldShape = RoundedCornerShape(12.dp)
    val fieldHeight = with(LocalDensity.current) { if (compact) maxOf(36.dp, 16.sp.toDp() + 12.dp) else maxOf(48.dp, 18.sp.toDp() + 20.dp) }
    val ime = rememberPortalImeVisibility()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val currentIme by rememberUpdatedState(ime)
    var sawIme by remember { mutableStateOf(false) }
    var sawImeWindow by remember { mutableStateOf(false) }
    var pendingHide by remember { mutableStateOf(false) }
    var showRequested by remember { mutableStateOf(false) }
    var editSession by remember { mutableIntStateOf(0) }
    var activationKey by remember { mutableStateOf<Int?>(null) }
    val activation = activationState ?: remember { PortalSearchActivation() }
    fun stopEditing(returnControl: Boolean = false) {
        // show() is asynchronous: Back can cancel before the IME window exists.
        // Retain ownership so a late attachment is still hidden by this field.
        pendingHide = pendingHide || currentIme || showRequested
        editing = false; sawIme = false; sawImeWindow = false; showRequested = false
        activationKey = null
        keyboard?.hide()
        if (returnControl) editFinished()
    }
    fun activate() {
        editRequested()
        sawIme = false; sawImeWindow = false; showRequested = false
        activationKey = null
        editing = true; editSession++
    }
    LaunchedEffect(activationRequest) {
        if (activationRequest != activation.consumed && activationRequest > 0) activate()
        activation.consumed = activationRequest
    }
    LaunchedEffect(editing, editSession) {
        if (editing) {
            // Search/Back can hide one IME window while the user immediately
            // starts another edit. Wait for that owned hide to complete before
            // showing the next session, so old insets cannot close the new one.
            if (pendingHide) snapshotFlow { currentIme }.first { !it }
            pendingHide = false
            withFrameNanos { }
            if (editing) {
                requester.requestFocus()
                showRequested = true
                keyboard?.show()
            }
        }
    }
    LaunchedEffect(ime, editing, showRequested, pendingHide) {
        when {
            !editing -> {
                sawIme = false
                if (ime && pendingHide) keyboard?.hide()
            }
            !showRequested -> Unit
            ime -> sawIme = true
            sawIme -> stopEditing(returnControl = true)
        }
    }
    // Fire TV's floating IME can leave ime insets at zero. Its real window
    // handoff still tells us when editing ended, before the next D-pad arrow.
    LaunchedEffect(windowFocused, editing, showRequested) {
        if (editing && showRequested) {
            if (!windowFocused) sawImeWindow = true
            else if (sawImeWindow) stopEditing(returnControl = true)
        }
    }
    // Back may arrive before the keyboard window attaches. Once attached,
    // intercept it before the IME so editing ends in the same key event, not
    // in a later inset-observation effect that could swallow the next arrow.
    BackHandler(enabled = editing, onBack = { stopEditing(returnControl = true) })
    BasicTextField(value, change, modifier.heightIn(min = fieldHeight).focusRequester(requester)
        .onFocusChanged {
            // First placement can report unfocused before the explicit edit
            // request acquires focus. Only a real focus loss ends that session.
            val lostFocus = fieldFocused && !it.isFocused
            fieldFocused = it.isFocused
            if (lostFocus) {
                activationKey = null
                if (editing) stopEditing()
            }
        }
        .onPreInterceptKeyBeforeSoftKeyboard { event ->
            val direction = when (event.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                KeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
                KeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
                else -> null
            }
            if (!editing && direction != null) {
                // A cancelled IME may still be detaching. It has no ownership
                // of directional navigation once this editor is read-only.
                if (event.type == KeyEventType.KeyDown) {
                    if (currentIme || pendingHide) keyboard?.hide()
                    focusManager.moveFocus(direction)
                }
                true
            } else if (editing && event.key == Key.Back) {
                if (event.type == KeyEventType.KeyUp) stopEditing(returnControl = true)
                true
            } else false
        }
        .onPreviewKeyEvent { event ->
            val direction = when (event.nativeKeyEvent.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                KeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                KeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
                KeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
                else -> null
            }
            val vertical = direction == FocusDirection.Up || direction == FocusDirection.Down
            if (direction != null && (vertical || !editing || television || !ime)) {
                // Read-only TextField still handles arrows as cursor movement.
                // Single-line editors have no vertical cursor movement. End
                // editing before moving out, including immediately after Back
                // when Android's IME visibility notification is still pending.
                // Horizontal keys remain cursor controls during active editing.
                if (event.type == KeyEventType.KeyDown) {
                    if (editing) stopEditing()
                    focusManager.moveFocus(direction)
                }
                true
            } else if (event.nativeKeyEvent.keyCode in setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
                val code = event.nativeKeyEvent.keyCode
                when {
                    event.type == KeyEventType.KeyDown && !editing -> { activationKey = code; true }
                    event.type == KeyEventType.KeyUp && activationKey == code -> { activate(); true }
                    // Enter DOWN may submit and make the field read-only. Its
                    // matching UP belongs to that submission, not a new click.
                    event.type == KeyEventType.KeyUp && !editing -> true
                    else -> false
                }
            } else false
        }.pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); activate() } },
        readOnly = !editing, singleLine = true, interactionSource = interactionSource,
        cursorBrush = SolidColor(Color.White), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp, lineHeight = 18.sp, color = Color.White),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search,
            keyboardType = keyboardType, capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
            showKeyboardOnFocus = false),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { stopEditing(); submit() }),
        decorationBox = { inner ->
            OutlinedTextFieldDefaults.DecorationBox(value = value, innerTextField = inner, enabled = true,
                singleLine = true, visualTransformation = VisualTransformation.None, interactionSource = interactionSource,
                placeholder = { Text(label, maxLines = 1, fontSize = 13.sp, lineHeight = 18.sp,
                    overflow = TextOverflow.Ellipsis) }, colors = fieldColors,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = if (compact) 8.dp else 10.dp),
                container = { OutlinedTextFieldDefaults.Container(enabled = true, isError = false,
                    interactionSource = interactionSource, colors = fieldColors, shape = fieldShape,
                    focusedBorderThickness = 2.dp, unfocusedBorderThickness = 1.dp) })
        })
}
