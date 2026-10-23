package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun PlayerErrorActions(
    compact: Boolean,
    canNavigate: Boolean,
    onPrevious: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onNext: () -> Unit,
    onSwitchPlayer: () -> Unit,
    retryModifier: Modifier = Modifier,
    switchPlayerModifier: Modifier = Modifier,
    showAlternatePlayer: Boolean = true,
    onReauthenticate: (() -> Unit)? = null,
    reauthenticating: Boolean = false,
    onChooseSource: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (canNavigate) {
                NikTvSecondaryActionButton(
                    onClick = onPrevious,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (compact) 12.dp else 14.dp)
                ) {
                    Icon(Icons.Default.SkipPrevious, "Previous channel")
                    if (!compact) { Spacer(Modifier.width(5.dp)); Text("Previous") }
                }
            }
            NikTvSecondaryActionButton(
                onClick = onBack,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (compact) 12.dp else 14.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Go back")
                if (!compact) { Spacer(Modifier.width(5.dp)); Text("Back") }
            }
            NikTvPrimaryActionButton(
                onClick = onRetry,
                modifier = retryModifier,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (compact) 12.dp else 14.dp)
            ) {
                Icon(Icons.Default.Refresh, "Retry with fresh link")
                if (!compact) { Spacer(Modifier.width(5.dp)); Text("Retry") }
            }
            if (canNavigate) {
                NikTvSecondaryActionButton(
                    onClick = onNext,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = if (compact) 12.dp else 14.dp)
                ) {
                    Icon(Icons.Default.SkipNext, "Next channel")
                    if (!compact) { Spacer(Modifier.width(5.dp)); Text("Next") }
                }
            }
        }
        val recoveryActions: @Composable (Modifier) -> Unit = { actionModifier ->
            if (onReauthenticate != null) NikTvSecondaryActionButton(
                onClick = onReauthenticate,
                enabled = !reauthenticating,
                modifier = actionModifier,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Default.VpnKey, null)
                Spacer(Modifier.width(7.dp))
                Text(if (reauthenticating) "Signing in…" else "Reauthenticate")
            }
            if (showAlternatePlayer) NikTvSecondaryActionButton(
                onClick = onSwitchPlayer,
                modifier = actionModifier.then(switchPlayerModifier),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp)
            ) {
                Icon(Icons.Default.SwapHoriz, null)
                Spacer(Modifier.width(7.dp))
                Text("Choose player")
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 360.dp && onReauthenticate != null && showAlternatePlayer) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { recoveryActions(Modifier.weight(1f)) }
            } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recoveryActions(Modifier.fillMaxWidth())
            }
        }
        onChooseSource?.let { choose ->
            NikTvSecondaryActionButton(choose, Modifier.fillMaxWidth()) { Text("Choose source and quality") }
        }
    }
}
