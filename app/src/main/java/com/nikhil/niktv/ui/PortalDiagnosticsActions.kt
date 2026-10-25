package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp

/** One stable action row in both diagnostics and session-expired popups. */
@Composable
internal fun PortalDiagnosticsActions(
    authorizationExpired: Boolean,
    authenticating: Boolean,
    showDiagnostics: Boolean,
    reauthenticateRequester: FocusRequester,
    closeRequester: FocusRequester,
    close: () -> Unit,
    reauthenticate: () -> Unit,
    copy: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        NikTvSecondaryActionButton(close, Modifier.size(48.dp).focusRequester(closeRequester),
            enabled = !authenticating, contentPadding = PaddingValues(0.dp)) {
            Icon(Icons.Default.Close, "Close", Modifier.size(24.dp))
        }
        if (showDiagnostics) {
            if (authorizationExpired) NikTvSecondaryActionButton(copy, Modifier.size(48.dp), contentPadding = PaddingValues(0.dp)) {
                Icon(Icons.Default.ContentCopy, "Copy diagnostics", Modifier.size(24.dp))
            } else NikTvPrimaryActionButton(copy, Modifier.weight(1f).height(48.dp)) {
                Icon(Icons.Default.ContentCopy, null)
                Spacer(Modifier.width(8.dp))
                Text("Copy diagnostics")
            }
        }
        if (authorizationExpired) NikTvPrimaryActionButton(
            // Keep the focused control placed and focusable while its operation runs.
            onClick = { if (!authenticating) reauthenticate() },
            modifier = Modifier.weight(1f).height(48.dp).focusRequester(reauthenticateRequester)
        ) {
            if (authenticating) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Authenticating…")
            } else Text("Re-authenticate")
        }
    }
}
