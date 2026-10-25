package com.nikhil.niktv.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp

/** One labelled remote/touch target; the decorative switch is not a second stop. */
@Composable
internal fun ProfileAvailabilitySetting(name: String, stalker: Boolean, checked: Boolean,
    available: Boolean, onCheckedChange: (Boolean) -> Unit, onSetupNeeded: () -> Unit,
    modifier: Modifier = Modifier) {
    CompactSettingsOptionRow(
        icon = if (stalker) Icons.Default.Tv else Icons.Default.Key,
        title = name,
        subtitle = if (!available) "Sync private configuration to enable"
            else if (checked) "Available on the profile screen" else "Hidden from the profile screen",
        modifier = modifier
            .then(if (available) Modifier.toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange)
                else Modifier.clickable(role = Role.Button, onClick = onSetupNeeded))
            .remoteFocusFrame(RoundedCornerShape(12.dp)),
        trailingContent = {
            SettingsSwitch(checked, onCheckedChange = null, enabled = available,
                modifier = Modifier.clearAndSetSemantics { })
        }
    )
}
