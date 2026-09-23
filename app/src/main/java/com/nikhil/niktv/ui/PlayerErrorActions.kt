package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
    retryModifier: Modifier = Modifier
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
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
}
