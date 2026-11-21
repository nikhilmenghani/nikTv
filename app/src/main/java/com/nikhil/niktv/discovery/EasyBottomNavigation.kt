package com.nikhil.niktv.discovery

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.ui.LocalRemoteFocusColor
import com.nikhil.niktv.ui.remoteFocusFrame

internal val easyBottomDestinations = listOf(EasyHomeAction.LIVE, EasyHomeAction.MOVIES,
    EasyHomeAction.SERIES, EasyHomeAction.SEARCH, EasyHomeAction.SAVED)

internal data class EasyNavigation(
    val selected: EasyHomeAction?,
    val requesters: Map<EasyHomeAction, FocusRequester>,
    val select: (EasyHomeAction) -> Unit
)

internal val LocalEasyNavigation = staticCompositionLocalOf<EasyNavigation?> { null }

/** A bounded sibling of the scrolling page, so cards never disappear beneath the dock. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EasyBottomNavigation(navigation: EasyNavigation) {
    val keyboardVisible = WindowInsets.isImeVisible
    val palette = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        // Keep targets mounted across IME dismissal, as on Standard mobile Search.
        layout(placeable.width, if (keyboardVisible) 0 else placeable.height) {
            if (!keyboardVisible) placeable.placeRelative(0, 0)
        }
    }, color = palette.background) {
        Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            easyBottomDestinations.forEachIndexed { index, action ->
                var focused by remember(action) { mutableStateOf(false) }
                val active = navigation.selected == action
                val shape = RoundedCornerShape(10.dp)
                Surface(onClick = { navigation.select(action) },
                    modifier = Modifier.weight(1f).focusRequester(navigation.requesters.getValue(action))
                        .focusProperties {
                            left = easyBottomDestinations.getOrNull(index - 1)?.let(navigation.requesters::getValue) ?: FocusRequester.Cancel
                            right = easyBottomDestinations.getOrNull(index + 1)?.let(navigation.requesters::getValue) ?: FocusRequester.Cancel
                            down = FocusRequester.Cancel
                        }
                        .onFocusChanged { focused = it.isFocused }.remoteFocusFrame(shape, drawBorder = false)
                        .semantics { selected = active; role = Role.Tab },
                    shape = shape,
                    color = if (focused) palette.onSurface.copy(alpha = .12f)
                        else if (active) palette.primary.copy(alpha = .12f) else palette.background,
                    contentColor = if (active) palette.primary else palette.onSurfaceVariant,
                    border = BorderStroke(2.dp, if (focused) LocalRemoteFocusColor.current else palette.background.copy(alpha = 0f))) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 2.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(action.icon, null, Modifier.size(22.dp))
                        Text(action.label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
