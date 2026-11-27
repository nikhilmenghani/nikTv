package com.nikhil.niktv.ui

import androidx.compose.runtime.*
import com.nikhil.niktv.model.isPortalCooldownDiagnostic
import com.nikhil.niktv.model.portalDiagnosticsCountdown
import kotlinx.coroutines.delay

@Composable
internal fun rememberPortalDiagnosticsText(error: String, deadlineMillis: Long?): State<String> =
    produceState(portalDiagnosticsCountdown(error, deadlineMillis, System.currentTimeMillis()), error, deadlineMillis) {
        if (deadlineMillis != null && isPortalCooldownDiagnostic(error)) {
            do {
                val now = System.currentTimeMillis()
                value = portalDiagnosticsCountdown(error, deadlineMillis, now)
                if (now >= deadlineMillis) break
                delay(minOf(1000L, deadlineMillis - now))
            } while (true)
        }
    }
