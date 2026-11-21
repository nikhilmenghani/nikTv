package com.nikhil.niktv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

internal enum class PlayerSettingKind(val title: String, val primaryOrder: Int? = null) {
    SUBTITLES("Subtitles", 0), AUDIO("Audio", 1), SPEED("Speed", 2), FIT("Video fit", 3),
    PICTURE("Picture", 4), PLAYER("Player", 5), DECODER("Decoder"), DOWNLOAD("Download", 6), SOURCE("Source", 7), REFRESH("Refresh", 0),
    INTRO("Intro timing"), TIMEOUT("Controls timeout"), CRICKET("Cricket scores"), INFORMATION("Stream info"), PIP("Mini player")
}

internal data class PlayerSettingAction(
    val kind: PlayerSettingKind,
    val icon: ImageVector,
    val contentDescription: String,
    val onClick: () -> Unit,
    val requester: FocusRequester,
    val modifier: Modifier = Modifier,
    val selected: Boolean = false,
    val progress: Float? = null,
    val indeterminateProgress: Boolean = false,
    val badgeText: String? = null,
    val value: String? = null
) {
    fun displayValue(): String = value ?: badgeText ?: when (kind) {
        PlayerSettingKind.PICTURE -> contentDescription.substringAfter(": ", "Standard")
        PlayerSettingKind.PLAYER -> contentDescription.substringAfter(": ", "Auto")
        PlayerSettingKind.FIT -> contentDescription.substringAfter(": ", "Fit")
        PlayerSettingKind.TIMEOUT -> contentDescription.substringAfter(": ", "3 seconds")
        PlayerSettingKind.DOWNLOAD -> if (selected) "Saved" else "Not saved"
        else -> ""
    }
}

/** Keep settings beside their trigger; small windows scroll the same bottom row. */
@Composable
internal fun RowScope.PlayerBottomActions(scroll: ScrollState, content: @Composable RowScope.() -> Unit) {
    BoxWithConstraints(Modifier.weight(1f)) {
        Row(Modifier.horizontalScroll(scroll).widthIn(min = maxWidth).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
            content = content)
    }
}

/** Settings expand inline from the persistent bottom-row trigger. */
internal class PlayerSettingsRibbonState {
    private var target: FocusRequester? = null
    private var lastFocused: FocusRequester? = null
    private var actions: List<PlayerSettingAction> = emptyList()
    var revealOnOpen = false
        private set
    fun entry(actions: List<PlayerSettingAction>): FocusRequester =
        lastFocused?.takeIf { requester -> actions.any { it.requester == requester } }
            ?: actions.first().requester
    fun firstOr(fallback: FocusRequester): FocusRequester = actions.firstOrNull()?.requester ?: fallback
    fun open(actions: List<PlayerSettingAction>, directional: Boolean = true) {
        this.actions = actions
        lastFocused = null
        revealOnOpen = !directional
        // Opening only reveals options. The persistent Settings trigger retains
        // focus until the user deliberately enters the row with Left.
        target = null
    }
    fun restore(requester: FocusRequester) {
        revealOnOpen = false
        // Retain a mounted origin before a popup leaves. Recreated engine controls
        // consume the pending request on attachment instead of a delayed correction.
        target = if (runCatching { requester.requestFocus() }.getOrDefault(false)) null else requester
    }
    fun revealed() { revealOnOpen = false }
    fun rememberFocus(requester: FocusRequester) { lastFocused = requester }
    fun attach(requester: FocusRequester) {
        if (target == requester && runCatching { requester.requestFocus() }.getOrDefault(false)) target = null
    }
}

@Composable
internal fun PlayerSettingsRibbon(
    visible: Boolean,
    actions: List<PlayerSettingAction>,
    state: PlayerSettingsRibbonState,
    toggle: FocusRequester,
    left: FocusRequester,
    up: FocusRequester,
    onDismiss: () -> Unit,
    onFocused: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (!visible || actions.isEmpty()) return
    fun dismiss() {
        // The trigger keeps its place in the dock and owns focus before removal.
        runCatching { toggle.requestFocus() }
        onDismiss()
    }
    val reveal = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; if (state.revealOnOpen) { reveal.bringIntoView(); state.revealed() } }
    BackHandler(onBack = ::dismiss)
    Row(modifier.onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { dismiss(); true } else false
    }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        actions.forEachIndexed { index, action -> key(action.kind) {
            val previous = actions.getOrNull(index - 1)?.requester ?: left
            val next = actions.getOrNull(index + 1)?.requester ?: toggle
            PlayerChromeIconButton(action.icon, action.contentDescription, action.onClick,
                modifier = Modifier.then(action.modifier).focusRequester(action.requester)
                    .then(if (index == 0) Modifier.bringIntoViewRequester(reveal) else Modifier)
                    .onGloballyPositioned { state.attach(action.requester) }
                    .semantics { stateDescription = action.displayValue() }
                    .focusProperties { this.left = previous; right = next; this.up = up }
                    .playerDpadFocusRoutes(left = previous, right = next, up = up),
                selected = action.selected, progress = action.progress,
                indeterminateProgress = action.indeterminateProgress, badgeText = action.badgeText,
                size = 44.dp, iconSize = 21.dp, scaleOnFocus = false,
                onFocused = { focused -> if (focused) state.rememberFocus(action.requester); onFocused(focused) })
        } }
    }
}
