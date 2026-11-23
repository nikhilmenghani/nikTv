package com.nikhil.niktv.ui

import androidx.compose.runtime.*
import com.nikhil.niktv.data.ProfilePin
import com.nikhil.niktv.data.UserProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ProfilePinRequest(val profile: UserProfile, val deleting: Boolean) {
    val result = CompletableDeferred<Boolean>()
    var newPinVerifier: String? = null
        private set

    fun completeSetup(verifier: String) {
        check(!deleting && profile.pinVerifier == null)
        newPinVerifier = verifier
        result.complete(true)
    }
}

@Composable
internal fun ProfilePinPrompt(request: ProfilePinRequest) {
    if (!request.deleting && request.profile.pinVerifier == null) {
        SetProfilePinPrompt(request.profile, { request.result.complete(false) }) { request.completeSetup(it) }
        DisposableEffect(request) { onDispose { request.result.complete(false) } }
        return
    }
    var pin by remember(request) { mutableStateOf("") }
    var error by remember(request) { mutableStateOf<String?>(null) }
    var busy by remember(request) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(request) { onDispose { request.result.complete(false) } }
    ParentalPinDialog(
        title = "Unlock ${request.profile.name}",
        pin = pin, onPin = { if (!busy) pin = it }, error = error,
        dismiss = { request.result.complete(false) }, confirm = {
            if (!busy) {
                busy = true
                scope.launch {
                    val accepted = withContext(Dispatchers.Default) { ProfilePin.verifies(request.profile.pinVerifier, pin) }
                    pin = ""
                    busy = false
                    if (accepted) request.result.complete(true) else error = "Incorrect PIN. Try again."
                }
            }
        })
}

@Composable
internal fun SetProfilePinPrompt(profile: UserProfile, dismiss: () -> Unit, save: suspend (String) -> Unit) {
    var pin by remember(profile.id) { mutableStateOf("") }
    var confirmation by remember(profile.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ParentalPinDialog("Set password for ${profile.name}", pin, { if (!busy) pin = it }, error, dismiss, {
        if (!busy) {
            if (pin != confirmation) error = "The PINs do not match."
            else {
                busy = true
                scope.launch {
                    try {
                        val verifier = withContext(Dispatchers.Default) { ProfilePin.create(pin) }
                        pin = ""; confirmation = ""
                        save(verifier)
                        dismiss()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "Could not save the PIN. Try again." }
                    finally { busy = false }
                }
            }
        }
    }, confirmation, { if (!busy) confirmation = it },
        confirmLabel = "Set password", pinLabel = "New password (4 digits)", confirmationLabel = "Confirm password")
}
