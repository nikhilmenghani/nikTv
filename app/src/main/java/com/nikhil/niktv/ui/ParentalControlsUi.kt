package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import com.nikhil.niktv.data.ParentalControls
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.data.UserSyncStore
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ParentalPinPrompt(vm: NikTvViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                vm.cancelParentalPin()
                vm.lockAdultContent()
            }
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
internal fun ParentalControlsSettings(profileId: String?, vm: NikTvViewModel = viewModel()) {
    val context = LocalContext.current
    val users = remember(context) { UserSyncStore.get(context) }
    val account by users.selectedAccount.collectAsStateWithLifecycle()
    val profiles by users.profiles.collectAsStateWithLifecycle(emptyList())
    val profile = profiles.firstOrNull { it.id == profileId }
    val owner = account?.let { id -> profile?.let { UserScope(id, it.id) } }
    if (owner == null) {
        Text("Choose a viewing profile first.", Modifier.padding(16.dp))
        return
    }
    val policy by remember(context, owner) { ParentalControls.observe(context, owner) }
        .collectAsStateWithLifecycle(initialValue = null)
    val rules = policy ?: return
    val scope = rememberCoroutineScope()
    val protectFocus = remember(owner) { FocusRequester() }
    val visibilityFocus = remember(owner) { FocusRequester() }
    val relockFocus = remember(owner) { FocusRequester() }
    val pinFocus = remember(owner) { FocusRequester() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var returnFocus by remember(owner) { mutableStateOf<FocusRequester?>(null) }
    var changingPin by remember(owner) { mutableStateOf(false) }
    var saveError by remember(owner) { mutableStateOf<String?>(null) }
    LaunchedEffect(windowFocused, changingPin, owner) {
        if (windowFocused && !changingPin) {
            returnFocus?.requestFocus()
            returnFocus = null
        }
    }
    fun authorize(origin: FocusRequester, onAuthorized: () -> Unit) {
        origin.requestFocus()
        returnFocus = origin
        vm.authorizeParentalSettings(onAuthorized)
    }
    fun update(origin: FocusRequester, edit: suspend () -> Unit) {
        authorize(origin) {
            scope.launch {
                try {
                    if (users.selectedScope.value == owner) vm.lockAdultContent()
                    edit()
                    saveError = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    saveError = "Could not save parental controls. Please try again."
                }
            }
        }
    }
    SettingsControlRow("Protect sensitive content", checked = rules.protectionEnabled,
        modifier = Modifier.focusRequester(protectFocus),
        onToggle = { enabled -> update(protectFocus) { ParentalControls.setProtection(context, owner, enabled) } },
        description = "Require a PIN for sensitive channels, movies and series on this profile.")
    SettingsControlRow("Show sensitive IPTV categories", checked = rules.showSensitiveIptv,
        modifier = Modifier.focusRequester(visibilityFocus),
        onToggle = { visible -> update(visibilityFocus) { ParentalControls.setCategoryVisibility(context, owner, visible) } },
        description = "Show sensitive categories and their titles when browsing or searching IPTV.")
    SettingsPickerRow("Lock again after", ParentalControls.relockOptions, rules.relockMinutes,
        { minutes -> update(relockFocus) { ParentalControls.setRelockMinutes(context, owner, minutes) } },
        "Sensitive content also locks when NikTV goes into the background.", Modifier.focusRequester(relockFocus))
    SettingsControlRow("Change shared PIN", description = "All profiles on this device use this PIN.",
        modifier = Modifier.focusRequester(pinFocus),
        onClick = { authorize(pinFocus) { changingPin = true } })
    saveError?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
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
    confirmation: String? = null, onConfirmation: ((String) -> Unit)? = null,
    confirmLabel: String = if (confirmation == null) "Unlock" else "Save",
    pinLabel: String = if (confirmation == null) "PIN" else "New PIN",
    confirmationLabel: String = "Confirm new PIN",
    checking: Boolean = false, verificationError: String? = null, retry: (() -> Unit)? = null,
    offline: (() -> Unit)? = null, notice: String? = null
) {
    val focus = remember { FocusRequester() }
    val confirmationFocus = remember { FocusRequester() }
    val cancelFocus = remember { FocusRequester() }
    val verificationBlocked = checking || verificationError != null
    var retainConfirmation by remember { mutableStateOf(confirmation != null) }
    var confirmationFocused by remember { mutableStateOf(false) }
    val showConfirmation = confirmation != null || retainConfirmation
    var editingField by remember { mutableStateOf<Int?>(null) }
    val pinInteraction = remember { MutableInteractionSource() }
    val confirmationInteraction = remember { MutableInteractionSource() }
    LaunchedEffect(pinInteraction) {
        pinInteraction.interactions.collect { if (it is PressInteraction.Press) editingField = 0 }
    }
    LaunchedEffect(confirmationInteraction) {
        confirmationInteraction.interactions.collect { if (it is PressInteraction.Press) editingField = 1 }
    }
    AlertDialog(
        shape = RoundedCornerShape(10.dp),
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(confirmation != null) {
                if (confirmation == null && retainConfirmation && confirmationFocused) {
                    editingField = null
                    keyboard?.hide()
                    focus.requestFocus()
                }
                retainConfirmation = confirmation != null
            }
            LaunchedEffect(editingField) {
                if (editingField != null) {
                    withFrameNanos { }
                    keyboard?.show()
                }
            }
            fun Modifier.pinKeyboard(field: Int, next: FocusRequester): Modifier = onFocusChanged {
                if (!it.isFocused && editingField == field) {
                    editingField = null
                    keyboard?.hide()
                }
            }.onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false
                else when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (editingField == field) keyboard?.show() else editingField = field
                        true
                    }
                    Key.DirectionDown -> {
                        editingField = null
                        keyboard?.hide()
                        next.requestFocus()
                        true
                    }
                    else -> false
                }
            }
            Column(Modifier.initialFocusEntry(if (verificationBlocked) cancelFocus else focus), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (checking) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text("Checking GitHub for a saved password…")
                }
                verificationError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (!verificationBlocked) {
                OutlinedTextField(
                    value = pin, onValueChange = { onPin(it.filter { c -> c in '0'..'9' }.take(4)) },
                    label = { Text(pinLabel) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    readOnly = editingField != 0, interactionSource = pinInteraction,
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done, showKeyboardOnFocus = false),
                    keyboardActions = KeyboardActions(onDone = { if (validParentalPin(pin) && confirmation == null) confirm() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).pinKeyboard(0, if (showConfirmation) confirmationFocus else cancelFocus)
                )
                if (showConfirmation) OutlinedTextField(
                    value = confirmation.orEmpty(),
                    onValueChange = { onConfirmation?.invoke(it.filter { c -> c in '0'..'9' }.take(4)) },
                    label = { Text(confirmationLabel) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    readOnly = editingField != 1, interactionSource = confirmationInteraction,
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done, showKeyboardOnFocus = false),
                    keyboardActions = KeyboardActions(onDone = { if (validParentalPin(pin) && validParentalPin(confirmation.orEmpty())) confirm() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(confirmationFocus)
                        .onFocusChanged { confirmationFocused = it.isFocused }.pinKeyboard(1, cancelFocus)
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NikTvSecondaryActionButton(onClick = dismiss, modifier = Modifier.weight(1f).height(48.dp)
                    .focusRequester(cancelFocus).focusProperties { up = if (verificationBlocked) FocusRequester.Cancel else if (showConfirmation) confirmationFocus else focus }) { Text("Cancel") }
                NikTvPrimaryActionButton(onClick = {
                    if (verificationBlocked) {
                        cancelFocus.requestFocus()
                        retry?.invoke()
                    } else confirm()
                }, enabled = if (verificationBlocked) !checking && verificationError != null && retry != null else validParentalPin(pin) &&
                    (confirmation == null || validParentalPin(confirmation)),
                    modifier = Modifier.weight(1f).height(48.dp)) { Text(if (verificationBlocked) "Retry" else confirmLabel) }
                if (!checking && verificationError != null && offline != null) {
                    NikTvSecondaryActionButton(onClick = {
                        cancelFocus.requestFocus()
                        offline()
                    }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Offline") }
                }
            }
        }
    )
}
