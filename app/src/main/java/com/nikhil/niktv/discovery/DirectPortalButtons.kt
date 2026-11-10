package com.nikhil.niktv.discovery

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

internal data class DirectPortalAction(val name: String, val icon: ImageVector, val selected: Boolean = false, val open: () -> Unit)
internal val LocalDirectPortalActions = staticCompositionLocalOf<List<DirectPortalAction>> { emptyList() }

@Composable
internal fun DirectPortalButtons(modifier: Modifier = Modifier, labels: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        LocalDirectPortalActions.current.forEach { action ->
            DiscoveryAction(action.name, action.icon, iconOnly = !labels, selected = action.selected,
                horizontalPadding = 6.dp,
                modifier = if (labels) Modifier.weight(1f).heightIn(min = 48.dp) else Modifier.size(48.dp), onClick = action.open)
        }
    }
}
