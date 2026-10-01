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
            if (event == Lifecycle.Event.ON_STOP) { vm.lockAdultContent(); vm.cancelParentalPin() }
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
    SettingsGroupHeading("Parental controls", "Manage access to sensitive content on this device.")
    CompactSettingsOptionRow(
        icon = Icons.Default.Lock,
        title = "Protect sensitive content",
        subtitle = "Require a PIN to access sensitive channels, movies and series.",
        trailingContent = {
            SettingsSwitch(checked = state.parentalControlsEnabled, onCheckedChange = vm::setParentalControlsEnabled)
        }
    )
    SettingsDivider()
    CompactSettingsOptionRow(
        icon = Icons.Default.Timer,
        title = "Lock again after",
        subtitle = "Choose how long sensitive content stays unlocked. Access also locks when the app goes into the background.",
        belowContent = {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.padding(start = 40.dp)) {
                SettingsChoiceGrid(
                    options = ParentalControls.relockOptions,
                    selected = state.parentalRelockMinutes,
                    onSelect = vm::setParentalRelockMinutes
                )
            }
        }
    )
    SettingsDivider()
    CompactSettingsOptionRow(
        icon = Icons.Default.Key,
        title = "Parental PIN",
        subtitle = "Update the PIN used to manage sensitive content access.",
        belowContent = {
            Spacer(Modifier.height(8.dp))
            NikTvSecondaryActionButton(
                onClick = { vm.authorizeParentalSettings { changingPin = true } },
                modifier = Modifier.padding(start = 40.dp)
            ) { Text("Change PIN") }
        }
    )
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
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = pin, onValueChange = { onPin(it.filter { c -> c in '0'..'9' }.take(4)) },
                    label = { Text(if (confirmation == null) "PIN" else "New PIN") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (validParentalPin(pin) && confirmation == null) confirm() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                )
                if (confirmation != null) OutlinedTextField(
                    value = confirmation,
                    onValueChange = { onConfirmation?.invoke(it.filter { c -> c in '0'..'9' }.take(4)) },
                    label = { Text("Confirm new PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (validParentalPin(pin) && validParentalPin(confirmation)) confirm() }),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NikTvSecondaryActionButton(onClick = dismiss, modifier = Modifier.weight(1f).height(48.dp)) { Text("Cancel") }
                NikTvPrimaryActionButton(onClick = confirm, enabled = validParentalPin(pin) &&
                    (confirmation == null || validParentalPin(confirmation)),
                    modifier = Modifier.weight(1f).height(48.dp)) { Text(if (confirmation == null) "Unlock" else "Save") }
            }
        }
    )
}
