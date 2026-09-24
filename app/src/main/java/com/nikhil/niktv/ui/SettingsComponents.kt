package com.nikhil.niktv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal val SettingsSurface = Color(0xFF111318)
internal val SettingsOutline = Color(0xFF292D35)
internal val SettingsMuted = Color(0xFFA7ADB8)

@Suppress("UNUSED_PARAMETER")
internal fun settingsChoiceShape(index: Int, count: Int) = RoundedCornerShape(10.dp)

/** Equal-width, equal-height choices with space for remote focus outlines. */
@Composable
internal fun SettingsChoiceRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    Row(modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
internal fun RowScope.SettingsChoiceButton(
    selected: Boolean,
    onClick: () -> Unit,
    shape: Shape = RoundedCornerShape(10.dp),
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.weight(1f).heightIn(min = 48.dp).fillMaxHeight()
            .semantics { role = Role.RadioButton; this.selected = selected },
        shape = shape,
        color = if (selected) Color(0xFF35171C) else Color(0xFF1B1E24),
        contentColor = if (selected) Color.White else Color(0xFFCDD1D8),
        border = BorderStroke(1.dp, if (selected) Color(0xFFAC3945) else SettingsOutline)
    ) {
        Box(Modifier.padding(horizontal = 6.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            ProvideTextStyle(MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), content)
        }
    }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp), color = SettingsOutline)
}

@Composable
internal fun SettingsGroupHeading(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(description, style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
    }
}

/** Wrap choices on narrow screens instead of hiding them in a horizontal strip. */
@Composable
internal fun <T> SettingsChoiceGrid(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 480.dp) options.size.coerceAtMost(5) else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.chunked(columns).forEach { row ->
                SettingsChoiceRow(Modifier.fillMaxWidth()) {
                    row.forEach { (value, label) ->
                        SettingsChoiceButton(selected == value, { onSelect(value) },
                            modifier = Modifier.remoteFocusFrame(RoundedCornerShape(10.dp))) {
                            Text(label)
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
