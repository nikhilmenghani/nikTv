package com.nikhil.niktv.ui

import androidx.compose.runtime.*
import com.nikhil.niktv.data.ProfilePin
import com.nikhil.niktv.data.UserProfile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ProfilePinRequest(profile: UserProfile, val deleting: Boolean, val allowOffline: Boolean = !deleting,
    private val verifyProfile: (suspend () -> UserProfile)? = null) {
    var profile by mutableStateOf(profile)
        private set
    var checking by mutableStateOf(profile.pinVerifier == null)
        private set
    var checkError by mutableStateOf<String?>(null)
        private set
    var setupVerified by mutableStateOf(false)
        private set
    var usingOffline by mutableStateOf(false)
        private set
    val result = CompletableDeferred<Boolean>()
    var authorizedVerifier: String? = null
        private set
    var newPinVerifier: String? = null
        private set

    fun refresh(current: UserProfile) {
        check(current.id == profile.id)
        if (profile.pinVerifier != null && current.pinVerifier == null) return
        profile = current
        if (current.pinVerifier != null) {
            checking = false; checkError = null; setupVerified = false
        }
    }

    suspend fun checkRemotePassword() {
        if (profile.pinVerifier != null) return
        checking = true; checkError = null; setupVerified = false
        try {
            val current = requireNotNull(verifyProfile) { "Password verification is unavailable." }.invoke()
            // A live sync may already have supplied a password while this read was pending.
            if (profile.pinVerifier == null || current.pinVerifier != null) refresh(current)
            setupVerified = profile.pinVerifier == null
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (profile.pinVerifier == null) checkError = "Could not check the password on GitHub. Check your connection and retry."
        } finally { checking = false }
    }

    fun useOffline(): Boolean {
        if (!allowOffline || checking || checkError == null) return false
        usingOffline = true
        setupVerified = false
        checkError = null
        return true
    }

    fun completeUnlock(verifier: String?): Boolean {
        if (profile.pinVerifier != verifier) return false
        authorizedVerifier = verifier
        return result.complete(true)
    }

    fun completeSetup(verifier: String): Boolean {
        if (deleting || profile.pinVerifier != null || !setupVerified || checking || checkError != null) return false
        newPinVerifier = verifier
        return result.complete(true)
    }
}

@Composable
internal fun ProfilePinPrompt(request: ProfilePinRequest) {
    val profile = request.profile
    val setting = !request.deleting && profile.pinVerifier == null && request.setupVerified
    var pin by remember(request, profile.pinVerifier) { mutableStateOf("") }
    var confirmation by remember(request, profile.pinVerifier) { mutableStateOf("") }
    var error by remember(request, profile.pinVerifier) { mutableStateOf<String?>(null) }
    var busy by remember(request) { mutableStateOf(false) }
    var attempt by remember(request) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    DisposableEffect(request) { onDispose { request.result.complete(false) } }
    LaunchedEffect(request, attempt) { request.checkRemotePassword() }
    ParentalPinDialog(
        title = when {
            request.checking || request.checkError != null -> "Check password for ${profile.name}"
            setting -> "Set password for ${profile.name}"
            else -> "Unlock ${profile.name}"
        },
        pin = pin, onPin = { if (!busy) pin = it }, error = error,
        confirmation = confirmation.takeIf { setting }, onConfirmation = { if (!busy) confirmation = it },
        pinLabel = if (setting) "New password (4 digits)" else "PIN", confirmationLabel = "Confirm password",
        confirmLabel = if (setting) "Set password" else "Unlock",
        checking = request.checking, verificationError = request.checkError, retry = { attempt++ },
        offline = if (request.allowOffline) ({ request.useOffline() }) else null,
        notice = if (request.usingOffline && profile.pinVerifier == null)
            "Use the override PIN to open this cached profile offline." else null,
        dismiss = { request.result.complete(false) }, confirm = {
            if (!busy) {
                if (setting && pin != confirmation) {
                    error = "The PINs do not match."
                    return@ParentalPinDialog
                }
                busy = true
                scope.launch {
                    try {
                        if (setting) {
                            val verifier = withContext(Dispatchers.Default) { ProfilePin.create(pin) }
                            if (!request.completeSetup(verifier)) error = "The profile changed. Enter its existing password."
                        } else {
                            val verifier = profile.pinVerifier
                            val accepted = withContext(Dispatchers.Default) { ProfilePin.verifies(verifier, pin) }
                            if (!accepted || !request.completeUnlock(verifier)) error = "Incorrect PIN. Try again."
                        }
                        pin = ""
                    } finally { busy = false }
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
