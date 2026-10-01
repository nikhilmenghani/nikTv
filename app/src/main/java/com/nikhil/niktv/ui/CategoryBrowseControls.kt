package com.nikhil.niktv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.nikhil.niktv.data.adultContentLabel
import com.nikhil.niktv.model.Category
import com.nikhil.niktv.model.CategoryBrowseMode
import com.nikhil.niktv.model.CategoryOrganization

@Composable
internal fun collectionFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Color(0xFF1B1E24), unfocusedContainerColor = SettingsSurface,
    focusedTextColor = Color.White, unfocusedTextColor = Color.White,
    focusedBorderColor = Color(0xFFE7E9EF), unfocusedBorderColor = SettingsOutline,
    focusedLabelColor = Color.White, unfocusedLabelColor = SettingsMuted,
    focusedLeadingIconColor = Color.White, unfocusedLeadingIconColor = SettingsMuted,
    cursorColor = MaterialTheme.colorScheme.primary
)

@Composable
private fun CollectionField(value: String, change: (String) -> Unit, label: String,
    modifier: Modifier = Modifier, icon: ImageVector = Icons.Default.Search) {
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(value, change, modifier.fillMaxWidth(), singleLine = true,
        shape = RoundedCornerShape(14.dp), colors = collectionFieldColors(),
        label = { Text(label) }, leadingIcon = { Icon(icon, null) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        trailingIcon = if (value.isNotEmpty()) {{
            IconButton(onClick = { change("") }) { Icon(Icons.Default.Close, "Clear $label") }
        }} else null)
}

@Composable
internal fun CategoryBrowseControls(
    mode: CategoryBrowseMode, query: String,
    onMode: (CategoryBrowseMode) -> Unit, onQuery: (String) -> Unit,
    create: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(mode.label, color = Color.White, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text("IPTV categories", color = SettingsMuted, style = MaterialTheme.typography.bodySmall)
            }
            ModernHeaderOptionsIcon("Find a category", Icons.Default.Search) {
                searchOpen = !searchOpen
                if (!searchOpen) { onQuery(""); keyboard?.hide() }
            }
            ModernHeaderOptionsIcon("Browse by", Icons.Default.Tune) { menu = true }
            ModernHeaderOptionsIcon("Create collection", Icons.Default.Add) { create() }
        }
        // Match the existing header's reserved space for captions below focused icons.
        Spacer(Modifier.height(38.dp))
        AnimatedVisibility(searchOpen, enter = fadeIn(), exit = fadeOut()) {
            CollectionField(query, onQuery, "Find a category or collection", Modifier.focusRequester(searchFocus))
        }
    }
    LaunchedEffect(searchOpen) {
        if (searchOpen) { withFrameNanos { }; searchFocus.requestFocus(); keyboard?.show() }
    }
    if (menu) BrowseModeDialog(mode, dismiss = { menu = false }, select = {
        onMode(it)
        menu = false
    })
}

@Composable
private fun BrowseModeDialog(mode: CategoryBrowseMode, dismiss: () -> Unit, select: (CategoryBrowseMode) -> Unit) {
    val configuration = LocalConfiguration.current
    val largeDevice = LocalContext.current.isModernTileTv(configuration) ||
        configuration.smallestScreenWidthDp >= 600
    val dialogWidth = minOf(680, configuration.screenWidthDp - if (largeDevice) 48 else 32)
    // Account for dialog padding, list padding, column gap, and compact icon/button padding.
    // Leave enough text width for the longest labels to wrap naturally without tiny tap targets.
    val phoneLabelWidth = (dialogWidth - 48 - 4 - 12) / 2f - 48
    val columns = if (largeDevice || (configuration.screenWidthDp >= 360 &&
        phoneLabelWidth >= 80 * configuration.fontScale.coerceAtLeast(1f))) 2 else 1
    val options = CategoryBrowseMode.entries
    val selectedIndex = options.indexOf(mode).coerceAtLeast(0)
    val selectedRow = selectedIndex / columns
    val selectedFocus = remember(columns) { FocusRequester() }
    var initialFocusRequested by remember(columns) { mutableStateOf(false) }
    // The description occupies item zero. Compose an offscreen selection before requesting focus.
    val listState = key(columns) {
        rememberLazyListState(initialFirstVisibleItemIndex = if (selectedRow == 0) 0 else selectedRow + 1)
    }
    CollectionDialog(
        title = "Browse by", subtitle = "Choose how to organize your IPTV categories.",
        icon = Icons.Default.Tune, dismiss = dismiss, listState = listState,
        dialogWidth = if (columns == 2) dialogWidth.dp else null,
        actions = { NikTvSecondaryActionButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") } }
    ) {
        items(options.chunked(columns), key = { it.first().name }) { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { option ->
                    CollectionChoice(option.label, option == mode, onClick = { select(option) },
                        compact = !largeDevice && columns == 2,
                        modifier = Modifier.weight(1f).fillMaxHeight().then(
                            if (option == options[selectedIndex]) Modifier.focusRequester(selectedFocus) else Modifier))
                    if (option == options[selectedIndex]) {
                        // Read the dialog's window/input mode, not the underlying screen's window.
                        // Also restore the starting point when a touch user switches to a D-pad.
                        val windowFocused = LocalWindowInfo.current.isWindowFocused
                        val inputMode = LocalInputModeManager.current.inputMode
                        LaunchedEffect(windowFocused, inputMode) {
                            if (!initialFocusRequested && windowFocused && inputMode == InputMode.Keyboard) {
                                withFrameNanos { }
                                initialFocusRequested = selectedFocus.requestFocus()
                            }
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Same surface, corner radius and full-row actions as the app's exit/settings dialogs. */
@Composable
private fun CollectionDialog(
    title: String, subtitle: String, icon: ImageVector, dismiss: () -> Unit,
    actions: @Composable () -> Unit, listState: LazyListState = rememberLazyListState(),
    dialogWidth: Dp? = null,
    content: LazyListScope.() -> Unit
) {
    val configuration = LocalConfiguration.current
    val bodyHeight = (configuration.screenHeightDp * .52f).dp
    AlertDialog(
        modifier = if (dialogWidth != null) Modifier.width(dialogWidth) else Modifier,
        properties = DialogProperties(usePlatformDefaultWidth = dialogWidth == null),
        onDismissRequest = dismiss, shape = RoundedCornerShape(24.dp),
        containerColor = Color(0xFF181818), titleContentColor = Color.White,
        textContentColor = Color.LightGray,
        icon = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = bodyHeight),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(2.dp)) {
                item("description") {
                    Text(subtitle, color = SettingsMuted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp))
                }
                content()
            }
        },
        confirmButton = actions
    )
}

@Composable
private fun CollectionChoice(label: String, chosen: Boolean, onClick: () -> Unit, multiple: Boolean = false,
    modifier: Modifier = Modifier, compact: Boolean = false) {
    NikTvSecondaryActionButton(onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics {
            role = if (multiple) Role.Checkbox else Role.RadioButton
            selected = chosen
        }, contentPadding = PaddingValues(horizontal = if (compact) 10.dp else 14.dp, vertical = 12.dp)) {
        Icon(if (chosen) Icons.Default.CheckCircle else Icons.Default.FolderOpen,
            if (chosen) "Selected" else null, Modifier.size(20.dp),
            tint = if (chosen) MaterialTheme.colorScheme.primary else SettingsMuted)
        Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
internal fun CreateCategoryCollectionDialog(
    categories: List<Category>, dismiss: () -> Unit, save: (String, List<String>) -> Unit
) {
    var name by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val available = remember(categories, query) {
        categories.filterNot { it.isAdult || adultContentLabel(it.title) }
            .filter { CategoryOrganization.matchesTitle(it.title, query) }
    }
    CollectionDialog("Create collection", "Choose the provider categories to include.", Icons.Default.Add,
        dismiss, actions = {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NikTvSecondaryActionButton(onClick = dismiss, modifier = Modifier.weight(1f).fillMaxHeight()) { Text("Cancel") }
                NikTvPrimaryActionButton(onClick = { save(name.trim(), selected) },
                    modifier = Modifier.weight(1f).fillMaxHeight(), enabled = name.isNotBlank() && selected.isNotEmpty()) {
                    Text("Create", fontWeight = FontWeight.Bold)
                }
            }
        }
    ) {
        item("name") { CollectionField(name, { name = it.take(60) }, "Collection name", icon = Icons.Default.FolderOpen) }
        item("search") { CollectionField(query, { query = it }, "Find provider categories") }
        item("selection-count") {
            Text("${selected.size} categories selected", color = SettingsMuted,
                style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 4.dp))
        }
        items(available, key = { "category:${it.id}" }) { category ->
            CollectionChoice(category.title, category.id in selected, multiple = true, onClick = {
                selected = if (category.id in selected) selected - category.id else selected + category.id
            })
        }
    }
}

@Composable
internal fun CollectionSourceFilter(categories: List<Category>, selected: String?, select: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val qualities = remember(categories) {
        categories.flatMap { CategoryOrganization.labels(it, CategoryBrowseMode.QUALITY) }.distinct().sorted()
    }
    val label = if (selected?.startsWith("@quality:") == true) "Quality: ${selected.substringAfter(':')}"
        else categories.firstOrNull { it.id == selected }?.title ?: "All included categories"
    NikTvSecondaryActionButton(onClick = { query = ""; open = true }, modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Icon(Icons.Default.Tune, null, Modifier.size(20.dp), tint = SettingsMuted)
        Spacer(Modifier.width(12.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Icon(Icons.Default.ExpandMore, "Filter this collection", Modifier.size(20.dp))
    }
    if (open) CollectionDialog("Filter this collection", "Browse all titles or narrow by quality or provider category.",
        Icons.Default.Tune, dismiss = { open = false }, actions = {
            NikTvSecondaryActionButton(onClick = { open = false }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    ) {
        item("search") { CollectionField(query, { query = it }, "Find a provider category") }
        item("all") { CollectionChoice("All included categories", selected == null, { select(null); open = false }) }
        items(qualities.filter { CategoryOrganization.matchesTitle(it, query) }, key = { "quality:$it" }) { quality ->
            CollectionChoice("Quality: $quality", selected == "@quality:$quality", { select("@quality:$quality"); open = false })
        }
        items(categories.filter { CategoryOrganization.matchesTitle(it.title, query) }, key = { "category:${it.id}" }) { category ->
            CollectionChoice(category.title, category.id == selected, { select(category.id); open = false })
        }
    }
}
