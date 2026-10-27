package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Key
import com.nikhil.niktv.data.ParentalControls
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nikhil.niktv.data.validParentalPin

@Composable
internal fun ParentalPinPrompt(vm: NikTvViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { vm.cancelParentalPin() }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    if (state.parentalPrompt != null) {
        val context = LocalContext.current
        var pin by remember { mutableStateOf("") }
        ParentalPinDialog(
            title = state.parentalPrompt!!,
            pin = pin,
            onPin = { entered ->
                pin = entered
                if (validParentalPin(entered) && ParentalControls.accepts(context, entered)) {
                    vm.submitParentalPin(entered)
                }
            },
            error = if (validParentalPin(pin) &&
                (state.parentalPinError || !ParentalControls.accepts(context, pin))) {
                "Incorrect PIN. Try again."
            } else null,
            dismiss = vm::cancelParentalPin,
            confirm = { vm.submitParentalPin(pin) }
        )
    }
}

@Composable
internal fun ParentalControlsSettings(vm: NikTvViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var changingPin by remember { mutableStateOf(false) }
    SettingsControlRow("Protect sensitive content", checked = state.parentalControlsEnabled,
        onToggle = vm::setParentalControlsEnabled, description = "Require a PIN for sensitive channels, movies and series.")
    if (state.parentalControlsEnabled) SettingsPickerRow("Lock again after", ParentalControls.relockOptions,
        state.parentalRelockMinutes, vm::setParentalRelockMinutes,
        "Sensitive content also locks when NikTV goes into the background.")
    SettingsControlRow("Change PIN", onClick = { vm.authorizeParentalSettings { changingPin = true } })
    if (changingPin) {
        var pin by remember { mutableStateOf("") }
        var confirmation by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        ParentalPinDialog(
            title = "Change parental PIN",
            pin = pin,
            onPin = { pin = it; error = null },
            confirmation = confirmation,
            onConfirmation = { confirmation = it; error = null },
            error = error,
            dismiss = { changingPin = false },
            confirm = {
                if (pin != confirmation) error = "PINs do not match."
                else { vm.changeParentalPin(pin); changingPin = false }
            }
        )
    }
}

@Composable
internal fun ParentalPinDialog(
    title: String, pin: String, onPin: (String) -> Unit,
    error: String?, dismiss: () -> Unit, confirm: () -> Unit,
    confirmation: String? = null, onConfirmation: ((String) -> Unit)? = null
) {
    val focus = remember { FocusRequester() }
    val confirmationFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var editingField by remember { mutableStateOf<Int?>(null) }
    val pinInteraction = remember { MutableInteractionSource() }
    val confirmationInteraction = remember { MutableInteractionSource() }
    val pinPressed by pinInteraction.collectIsPressedAsState()
    val confirmationPressed by confirmationInteraction.collectIsPressedAsState()
    LaunchedEffect(pinPressed, confirmationPressed) {
        if (pinPressed) editingField = 0
        if (confirmationPressed) editingField = 1
    }
    LaunchedEffect(editingField) {
        if (editingField != null) { withFrameNanos { }; keyboard?.show() }
    }
    fun Modifier.pinKeyboard(field: Int, next: FocusRequester): Modifier = onFocusChanged {
        if (!it.isFocused && editingField == field) { editingField = null; keyboard?.hide() }
    }.onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) false
        else when (event.key) {
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { editingField = field; keyboard?.show(); true }
            Key.DirectionDown -> { editingField = null; keyboard?.hide(); next.requestFocus(); true }
            else -> false
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        shape = RoundedCornerShape(10.dp),
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = pin, onValueChange = { onPin(it.filter { c -> c in '0'..'9' }.take(4)) },
                    label = { Text(if (confirmation == null) "PIN" else "New PIN") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    readOnly = editingField != 0, interactionSource = pinInteraction,
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done, showKeyboardOnFocus = false),
                    keyboardActions = KeyboardActions(onDone = { if (validParentalPin(pin) && confirmation == null) confirm() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).pinKeyboard(0, if (confirmation != null) confirmationFocus else cancelFocus)
                )
                if (confirmation != null) OutlinedTextField(
                    value = confirmation,
                    onValueChange = { onConfirmation?.invoke(it.filter { c -> c in '0'..'9' }.take(4)) },
                    label = { Text("Confirm new PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    readOnly = editingField != 1, interactionSource = confirmationInteraction,
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done, showKeyboardOnFocus = false),
                    keyboardActions = KeyboardActions(onDone = { if (validParentalPin(pin) && validParentalPin(confirmation)) confirm() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(confirmationFocus).pinKeyboard(1, cancelFocus)
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NikTvSecondaryActionButton(onClick = dismiss, modifier = Modifier.weight(1f).height(48.dp)
                    .focusRequester(cancelFocus).focusProperties { up = if (confirmation != null) confirmationFocus else focus }) { Text("Cancel") }
                NikTvPrimaryActionButton(onClick = confirm, enabled = validParentalPin(pin) &&
                    (confirmation == null || validParentalPin(confirmation)),
                    modifier = Modifier.weight(1f).height(48.dp)) { Text(if (confirmation == null) "Unlock" else "Save") }
            }
        }
    )
}
