package com.nikhil.niktv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nikhil.niktv.discovery.DiscoveryAction
import com.nikhil.niktv.discovery.DiscoveryGlassPanel

internal val LocalSettingsPage = staticCompositionLocalOf { SettingsPage.PLAYBACK }
internal val LocalSettingsTv = staticCompositionLocalOf { false }
internal val LocalSettingsRailFocus = staticCompositionLocalOf<FocusRequester?> { null }
internal val LocalOpenSettingsPage = staticCompositionLocalOf<(SettingsPage) -> Unit> { {} }
internal val LocalSettingsPageRequesters = staticCompositionLocalOf<Map<SettingsPage, FocusRequester>> { emptyMap() }
internal class SettingsFocusEntries {
    private val entries = linkedMapOf<Any, FocusRequester>()
    fun register(key: Any, requester: FocusRequester) { entries[key] = requester }
    fun remove(key: Any) { entries.remove(key) }
    fun requestFirst() = entries.values.firstOrNull()?.let { runCatching { it.requestFocus() }.getOrDefault(false) } ?: false
}
internal val LocalSettingsFocusEntries = staticCompositionLocalOf<SettingsFocusEntries?> { null }

@Composable
internal fun Modifier.settingsFocusEntry(enabled: Boolean = true): Modifier {
    val entries = LocalSettingsFocusEntries.current
    val key = remember { Any() }
    val requester = remember { FocusRequester() }
    DisposableEffect(entries, enabled) {
        if (enabled) entries?.register(key, requester)
        onDispose { entries?.remove(key) }
    }
    return if (enabled) this.focusRequester(requester) else this
}

@Composable
internal fun CompactSettingsTopBar(profile: String?, back: () -> Unit, compact: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 24.dp, vertical = if (compact) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        DiscoveryGlassPanel(Modifier, null, androidx.compose.ui.geometry.Rect.Zero) {
            Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DiscoveryAction("Back", Icons.AutoMirrored.Filled.ArrowBack, iconOnly = true, onClick = back)
                Text("Settings", Modifier.padding(end = 16.dp), color = Color.White,
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.weight(1f))
        profile?.let { Text(it, Modifier.widthIn(max = 240.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = SettingsMuted, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** A phone uses the same category rows without reserving a permanent navigation rail. */
@Composable
internal fun SettingsCategoryIndex(modifier: Modifier = Modifier, scrollState: ScrollState = rememberScrollState(),
    requesters: Map<SettingsDestination, FocusRequester> = emptyMap(),
    onSelect: (SettingsDestination) -> Unit) {
    Column(modifier.verticalScroll(scrollState).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsDestination.entries.forEach { destination ->
            SettingsControlRow(destination.title, description = destination.subtitle,
                modifier = requesters[destination]?.let { Modifier.focusRequester(it) } ?: Modifier,
                onClick = { onSelect(destination) })
        }
    }
}

/** The entire row is one target, including its decorative switch/value. */
@Composable
internal fun SettingsControlRow(
    title: String,
    value: String = "",
    description: String? = null,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    onToggle: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    danger: Boolean = false,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(10.dp)
    val rail = LocalSettingsRailFocus.current
    val interaction = if (checked != null && onToggle != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onToggle)
        else if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier
    Surface(modifier = modifier.fillMaxWidth()
        .settingsFocusEntry(enabled && (onClick != null || onToggle != null))
        .focusProperties { if (rail != null) left = rail }
        .remoteFocusFrame(shape).then(interaction), shape = shape, color = SettingsSurface) {
        BoxWithConstraints {
        val narrow = maxWidth < 440.dp
        Row(Modifier.heightIn(min = if (LocalSettingsTv.current) 68.dp else 60.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = if (danger) MaterialTheme.colorScheme.error else Color(0xFFF3F5F8),
                    fontSize = if (LocalSettingsTv.current) 17.sp else 15.sp, fontWeight = FontWeight.Medium)
                description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = SettingsMuted, style = MaterialTheme.typography.bodySmall)
                }
                if (narrow && checked == null && value.isNotBlank()) Text(value, color = SettingsMuted,
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (checked != null) Switch(checked, onCheckedChange = null,
                modifier = Modifier.focusProperties { canFocus = false }.clearAndSetSemantics {})
            else {
                if (!narrow && value.isNotBlank()) Text(value, color = SettingsMuted,
                    modifier = Modifier.widthIn(max = 200.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = if (LocalSettingsTv.current) 15.sp else 14.sp)
                if (onClick != null) Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = SettingsMuted)
            }
        }
        }
    }
}

@Composable
internal fun SettingsLinkRow(page: SettingsPage, value: String = "", title: String = page.title) {
    val open = LocalOpenSettingsPage.current
    val requester = LocalSettingsPageRequesters.current[page]
    SettingsControlRow(title, value,
        modifier = if (requester != null) Modifier.focusRequester(requester) else Modifier,
        onClick = { open(page) })
}

@Composable
internal fun <T> SettingsPickerRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    description: String = "",
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val dialogWidth = (configuration.screenWidthDp - 32).coerceIn(240, 520).dp
    val dialogHeight = (configuration.screenHeightDp * .9f).dp
    var open by rememberSaveable { mutableStateOf(false) }
    var restoreFocus by remember { mutableStateOf(false) }
    val rowFocus = remember { FocusRequester() }
    val selectedFocus = remember { FocusRequester() }
    fun dismiss() { open = false; restoreFocus = true }
    LaunchedEffect(open) {
        if (open) {
            withFrameNanos { }
            withFrameNanos { }
            runCatching { selectedFocus.requestFocus() }
        } else if (restoreFocus) {
            withFrameNanos { }
            runCatching { rowFocus.requestFocus() }
            restoreFocus = false
        }
    }
    SettingsControlRow(title, options.firstOrNull { it.first == selected }?.second.orEmpty(),
        modifier = modifier.focusRequester(rowFocus), onClick = { open = true })
    if (open) Dialog(onDismissRequest = ::dismiss) {
        Surface(Modifier.widthIn(max = dialogWidth).fillMaxWidth().heightIn(max = dialogHeight), shape = RoundedCornerShape(12.dp),
            color = SettingsSurface, border = BorderStroke(1.dp, SettingsOutline)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.heightIn(max = dialogHeight * .3f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = Color.White)
                    if (description.isNotBlank()) Text(description, color = SettingsMuted, style = MaterialTheme.typography.bodyMedium)
                }
                val initial = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = initial)
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = (configuration.screenHeightDp * .55f).dp),
                    state = listState, contentPadding = PaddingValues(4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(options) { index, (value, label) ->
                        val isSelected = value == selected
                        Surface(onClick = { onSelect(value); dismiss() },
                            modifier = Modifier.fillMaxWidth()
                                .semantics { role = Role.RadioButton; this.selected = isSelected }
                                .then(if (index == initial) Modifier.focusRequester(selectedFocus) else Modifier)
                                .remoteFocusFrame(RoundedCornerShape(8.dp)),
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) Color(0xFF39151E) else Color(0xFF1B2028),
                            border = if (isSelected) BorderStroke(1.dp, Color(0xFFE50930)) else null) {
                            Row(Modifier.heightIn(min = 52.dp).padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(label, Modifier.weight(1f), color = Color.White)
                                if (isSelected) Icon(Icons.Default.Check, "Selected", tint = Color(0xFFFF697A))
                            }
                        }
                    }
                }
                NikTvSecondaryActionButton(onClick = ::dismiss, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
            }
        }
    }
}

/** Focus selects a row; an explicit click/OK opens the editor and its keyboard. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsTextEditRow(title: String, value: String, onValueChange: (String) -> Unit,
    description: String, modifier: Modifier = Modifier, placeholder: String? = null, password: Boolean = false) {
    val configuration = LocalConfiguration.current
    val dialogWidth = (configuration.screenWidthDp - 32).coerceIn(240, 520).dp
    val dialogHeight = (configuration.screenHeightDp * .9f).dp
    var open by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf("") }
    var restore by remember { mutableStateOf(false) }
    val row = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    val cancel = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun dismiss(save: Boolean) {
        if (save) onValueChange(draft)
        keyboard?.hide()
        open = false
        restore = true
    }
    LaunchedEffect(open) {
        if (open) {
            withFrameNanos { }
            field.requestFocus()
            keyboard?.show()
        } else if (restore) {
            withFrameNanos { }
            runCatching { row.requestFocus() }
            restore = false
        }
    }
    SettingsControlRow(title, if (value.isBlank()) placeholder ?: "Not set" else if (password) "••••••••" else value,
        modifier = modifier.focusRequester(row), onClick = { draft = value; open = true })
    if (open) Dialog(onDismissRequest = { dismiss(false) }) {
        Surface(Modifier.widthIn(max = dialogWidth).fillMaxWidth().heightIn(max = dialogHeight), shape = RoundedCornerShape(10.dp),
            color = SettingsSurface, contentColor = Color.White, border = BorderStroke(1.dp, SettingsOutline)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(description, color = SettingsMuted)
                OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().focusRequester(field)
                    .focusProperties { down = cancel }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                            keyboard?.hide()
                            cancel.requestFocus()
                            true
                        } else if (event.type == KeyEventType.KeyDown &&
                            event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                            keyboard?.show()
                            true
                        } else false
                    },
                    singleLine = true, shape = RoundedCornerShape(10.dp),
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, showKeyboardOnFocus = false),
                    keyboardActions = KeyboardActions(onDone = { dismiss(true) }))
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NikTvSecondaryActionButton(onClick = { dismiss(false) },
                        modifier = Modifier.focusRequester(cancel).focusProperties { up = field }) { Text("Cancel") }
                    NikTvPrimaryActionButton(onClick = { dismiss(true) }) { Text("Done") }
                }
            }
        }
    }
}
