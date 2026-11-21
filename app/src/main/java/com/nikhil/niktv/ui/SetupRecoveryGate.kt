package com.nikhil.niktv.ui

import androidx.compose.runtime.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Do not create account/player ViewModels until interrupted setup recovery finishes. */
@Composable
internal fun SetupRecoveryGate(prepare: () -> Unit = {}, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val branding = remember { Animatable(0f) }
    LaunchedEffect(branding) { branding.animateTo(1f, tween(LaunchBrandingDurationMillis)) }
    LaunchedEffect(context) {
        try { com.nikhil.niktv.data.awaitSetupRecovery(context); prepare(); ready = true }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    CompositionLocalProvider(LocalLaunchBrandingProgress provides branding.asState()) {
        if (ready) content() else MaterialTheme(colorScheme = NikColors) {
            if (failed) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("Setup recovery could not finish. Your recovery file is preserved. Restart NikTV to retry.", color = MaterialTheme.colorScheme.onBackground)
                }
            } else NikTvLaunchBranding()
        }
    }
}
