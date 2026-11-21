package com.nikhil.niktv.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val contentLanguageOptions = listOf("hi" to "Hindi", "en" to "English", "gu" to "Gujarati", "pa" to "Punjabi",
    "ta" to "Tamil", "te" to "Telugu", "ml" to "Malayalam", "mr" to "Marathi", "bn" to "Bengali", "kn" to "Kannada",
    "ar" to "Arabic", "ur" to "Urdu", "tr" to "Turkish", "ko" to "Korean", "ja" to "Japanese", "fr" to "French",
    "de" to "German", "es" to "Spanish", "pt" to "Portuguese", "ru" to "Russian", "it" to "Italian",
    "ne" to "Nepali", "or" to "Odia", "as" to "Assamese", "bho" to "Bhojpuri")
internal val contentInterestOptions = listOf("entertainment" to "Entertainment", "news" to "News", "kids" to "Kids", "devotional" to "Devotional")
internal val contentSportOptions = listOf("cricket" to "Cricket", "football" to "Football", "tennis" to "Tennis",
    "motorsport" to "Formula 1 / MotoGP", "combat" to "Wrestling / UFC", "golf" to "Golf", "rugby" to "Rugby",
    "basketball" to "Basketball", "baseball" to "Baseball", "american-football" to "American football", "other" to "Other sports")

@Composable
internal fun XtreamContentSettings(profileId: String?, portals: List<PortalProfile>, selectProfile: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val users = remember(context) { UserSyncStore.get(context) }
    val profiles by users.profiles.collectAsStateWithLifecycle(emptyList())
    val account by users.selectedAccount.collectAsStateWithLifecycle()
    val profile = profiles.firstOrNull { it.id == profileId }
    val owner = account?.let { id -> profile?.let { UserScope(id, it.id) } }
    if (owner == null || profile == null) { Text("Choose a viewing profile first."); return }
    val stored by produceState<XtreamContentPolicy?>(null, owner) {
        XtreamContentPreferences.observe(context, owner).collect { value = it }
    }
    val parental by remember(context, owner) { ParentalControls.observe(context, owner) }
        .collectAsStateWithLifecycle(initialValue = null)
    var draft by remember(owner) { mutableStateOf<XtreamContentPolicy?>(null) }
    var dirty by remember(owner) { mutableStateOf(false) }
    var busy by remember(owner) { mutableStateOf(false) }
    var message by remember(owner) { mutableStateOf<String?>(null) }
    var tab by rememberSaveable(owner) { mutableIntStateOf(0) }
    var moreLanguages by rememberSaveable(owner) { mutableStateOf(false) }
    var categoryKey by rememberSaveable(owner) { mutableStateOf("") }
    var categoryType by rememberSaveable(owner) { mutableStateOf(CatalogType.LIVE_TV) }
    val scope = rememberCoroutineScope()
    val xtream = remember(portals) { portals.filter { it.portalType == PortalType.XTREAM } }
    val available by produceState<Map<PortalProfile, List<Category>>>(emptyMap(), xtream) {
        value = withContext(Dispatchers.IO) {
            val store = ProfileStore(context)
            xtream.associateWith { portal ->
                listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES).flatMap { type ->
                    store.browseCatalogMetadata(type, portal.cacheKey()).first()?.categories.orEmpty()
                }
            }
        }
    }
    LaunchedEffect(stored) { if (!dirty) draft = stored }
    val policy = draft
    fun change(next: XtreamContentPolicy) { draft = next; dirty = true; message = null }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
    SettingsSection("${profile.name} · Xtream", page = SettingsPage.XTREAM_CONTENT) {
        val profilePicker: @Composable () -> Unit = { SettingsPickerRow("Profile", profiles.map { it.id to it.name }, profile.id, selectProfile) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val filtering: @Composable () -> Unit = {
                if (policy != null) SettingsControlRow("Filter Xtream content", checked = policy.enabled,
                    onToggle = { change(policy.copy(enabled = it)) })
            }
            if (maxWidth >= 600.dp) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { profilePicker() }
                Box(Modifier.weight(1f)) { filtering() }
            } else Column { profilePicker(); filtering() }
        }
        if (policy == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@SettingsSection }
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("General", "Parents", "Kids").forEach { preset ->
                    NikTvSecondaryActionButton({
                        val next = when (preset) { "Parents" -> XtreamContentPolicy.parents(); "Kids" -> XtreamContentPolicy.kids(); else -> XtreamContentPolicy(enabled = true) }
                        change(next.copy(knownCategories = policy.knownCategories, categoryOverrides = policy.categoryOverrides,
                            itemOverrides = policy.itemOverrides))
                    }, modifier = Modifier.widthIn(min = 80.dp).remoteFocusFrame(RoundedCornerShape(10.dp)), shape = RoundedCornerShape(10.dp)) { Text(preset, style = MaterialTheme.typography.labelMedium) }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 740.dp
                val editor: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ContentFilterTabs(listOf("Languages", "Content", "Sports", "Exceptions"), tab) { tab = it }
                        when (tab) {
                            0 -> {
                                Text("Languages you watch", style = MaterialTheme.typography.titleSmall)
                                Text("Keep content in any selected audio language. Subtitles alone don’t change its language.", style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
                                ContentFilterChoices(if (moreLanguages) contentLanguageOptions else contentLanguageOptions.take(12), policy.languages) {
                                    change(policy.copy(languages = it))
                                }
                                NikTvTextActionButton(onClick = { moreLanguages = !moreLanguages },
                                    modifier = Modifier.remoteFocusFrame(RoundedCornerShape(10.dp))) { Text(if (moreLanguages) "Fewer languages" else "More languages") }
                                SettingsControlRow("Include dubbed content", checked = policy.includeDubbed,
                                    description = "For example, English films dubbed in Hindi.", onToggle = { change(policy.copy(includeDubbed = it)) })
                            }
                            1 -> {
                                Text("Content you want to see", style = MaterialTheme.typography.titleSmall)
                                ContentFilterChoices(contentInterestOptions, policy.interests) { change(policy.copy(interests = it)) }
                                Text("Devotional preferences", style = MaterialTheme.typography.titleSmall)
                                ContentFilterChoices(listOf("hindu" to "Hindu / Jain", "islamic" to "Islamic", "christian" to "Christian"), policy.devotional) {
                                    change(policy.copy(devotional = it))
                                }
                                SettingsControlRow("Reviewed kids sources only", checked = policy.reviewedKidsOnly,
                                    description = "Use known kids sources or your explicit exceptions. Provider Kids and animation labels alone aren’t age checks.",
                                    onToggle = { change(policy.copy(reviewedKidsOnly = it)) })
                            }
                            2 -> {
                                Text("Follow your sports", style = MaterialTheme.typography.titleSmall)
                                ContentFilterChoices(contentSportOptions, policy.sports) { change(policy.copy(sports = it)) }
                                SettingsControlRow("Include replays and highlights", checked = policy.includeReplays,
                                    onToggle = { change(policy.copy(includeReplays = it)) })
                                Text("Channels in the Cricket folder can still broadcast other sports. Commentary language also applies.",
                                    style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
                            }
                            else -> {
                                SettingsPickerRow("Unclassified content", listOf(UnclassifiedContent.REVIEW to "Keep out until reviewed",
                                    UnclassifiedContent.SHOW to "Show for this profile", UnclassifiedContent.HIDE to "Always hide"),
                                    policy.unclassified, { change(policy.copy(unclassified = it)) })
                                SettingsControlRow("Review new provider categories", checked = policy.reviewNewCategories,
                                    description = "New categories stay hidden after saving a baseline for that media type.", onToggle = { change(policy.copy(reviewNewCategories = it)) })
                                ContentFilterTabs(listOf("Live TV", "Movies", "Series"), listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES).indexOf(categoryType)) {
                                    categoryType = listOf(CatalogType.LIVE_TV, CatalogType.MOVIES, CatalogType.SERIES)[it]; categoryKey = ""
                                }
                                val rows = available.flatMap { (portal, categories) ->
                                    categories.filter { it.type == categoryType &&
                                        (parental?.showSensitiveIptv == true || !it.isSensitiveIptvCategory()) }.map { portal to it }
                                }
                                if (rows.isEmpty()) Text("Open this portal’s ${categoryType.title} page once to load its categories.", style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
                                val choices = rows.map { (portal, category) -> XtreamContentPolicy.overrideKey(ProfilePortalAccess.key(portal), category.type, category.id) to category.title }
                                val selectedKey = categoryKey.takeIf { saved -> choices.any { it.first == saved } } ?: choices.firstOrNull()?.first
                                if (selectedKey != null) {
                                        SettingsPickerRow("Category", choices, selectedKey, { categoryKey = it })
                                        SettingsPickerRow("Category visibility", listOf<ContentOverride?>(null, ContentOverride.ALLOW, ContentOverride.HIDE).map {
                                            it to when (it) { ContentOverride.ALLOW -> "Allow explicitly"; ContentOverride.HIDE -> "Hide"; null -> "Use profile rules" }
                                        }, policy.categoryOverrides[selectedKey], { selected ->
                                            change(policy.copy(categoryOverrides = if (selected == null) policy.categoryOverrides - selectedKey else policy.categoryOverrides + (selectedKey to selected)))
                                        })
                                }
                                Text("Explicit category exceptions override language and interest rules. Kids profiles still exclude provider-marked adult content.", style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
                            }
                        }
                    }
                }
                if (wide) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(Modifier.weight(1f)) { editor() }
                    Box(Modifier.width(240.dp)) { ContentFilterSummary(profile.name, policy) }
                } else Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { editor(); ContentFilterSummary(profile.name, policy) }
            }
        }
    }
    }
    if (policy != null) {
            HorizontalDivider(color = SettingsOutline)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message ?: if (busy) "Saving…" else if (dirty) "Unsaved changes" else "${profile.name} · Xtream",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
                NikTvPrimaryActionButton(onClick = {
                    if (busy) return@NikTvPrimaryActionButton
                    busy = true
                    val captured = owner
                    val baseline = available.entries.associate { (portal, cats) ->
                        val connection = ProfilePortalAccess.key(portal)
                        val existing = policy.knownCategories[connection].orEmpty()
                        // Saving unrelated preferences must not approve newly discovered folders.
                        connection to (existing + cats.filter { category ->
                            existing.none { it.startsWith("${category.type.name}:") }
                        }.map { XtreamContentPolicy.categoryKey(it.type, it.id) })
                    }.filterValues { it.isNotEmpty() }
                    val next = policy.copy(knownCategories = policy.knownCategories + baseline)
                    scope.launch {
                        try { XtreamContentPreferences.save(context, captured, next)
                            if (draft == policy) { draft = next; dirty = false }
                            message = "Preferences saved for ${profile.name}." }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { message = "Could not save preferences. Please try again." }
                        finally { busy = false }
                    }
                }, modifier = Modifier.remoteFocusFrame(RoundedCornerShape(10.dp)), shape = RoundedCornerShape(10.dp)) {
                    Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Save preferences")
                }
            }
    }
    }
}

@Composable
private fun ContentFilterTabs(labels: List<String>, selected: Int, choose: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            Column(Modifier.weight(1f)) {
                Surface(onClick = { choose(index) }, modifier = Modifier.fillMaxWidth().settingsFocusEntry()
                    .semantics { role = Role.Tab; this.selected = index == selected }
                    .remoteFocusFrame(RoundedCornerShape(10.dp)), shape = RoundedCornerShape(10.dp), color = Color.Transparent) {
                    Box(Modifier.heightIn(min = 40.dp).padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelMedium, color = if (index == selected) Color.White else SettingsMuted)
                    }
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (index == selected) Color(0xFFE50930) else Color.Transparent))
            }
        }
    }
}

@Composable
private fun ContentFilterChoices(options: List<Pair<String, String>>, selected: Set<String>, change: (Set<String>) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 600.dp) 3 else 2
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (id, title) ->
                        val checked = id in selected
                        Surface(onClick = { change(if (checked) selected - id else selected + id) },
                            modifier = Modifier.weight(1f).settingsFocusEntry().semantics { role = Role.Checkbox; toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
                                .remoteFocusFrame(RoundedCornerShape(10.dp)),
                            shape = RoundedCornerShape(10.dp), color = if (checked) Color(0xFF35171C) else Color(0xFF1B1E24),
                            border = BorderStroke(1.dp, if (checked) Color(0xFFAC3945) else SettingsOutline)) {
                            Row(Modifier.heightIn(min = 46.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                                if (checked) Icon(Icons.Default.Check, null, Modifier.size(17.dp)) else Spacer(Modifier.size(17.dp))
                                Text(title, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ContentFilterSummary(name: String, policy: XtreamContentPolicy) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFF161A23), border = BorderStroke(1.dp, SettingsOutline)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Key, null, Modifier.size(18.dp)); Text("$name’s selection", style = MaterialTheme.typography.titleSmall)
            }
            if (!policy.enabled) Text("Filtering is off. Your full Xtream subscription remains visible.", style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
            listOf("Languages" to (contentLanguageOptions to policy.languages), "Content" to (contentInterestOptions to policy.interests),
                "Sports" to (contentSportOptions to policy.sports)).forEach { (heading, selection) ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(heading, style = MaterialTheme.typography.labelSmall, color = SettingsMuted)
                    Text(selection.first.filter { it.first in selection.second }.joinToString { it.second }.ifBlank { "None selected" }, style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider(color = SettingsOutline)
            Text("Mixed or missing metadata: ${if (policy.unclassified == UnclassifiedContent.SHOW) "shown" else "kept out"}.", style = MaterialTheme.typography.bodySmall)
            Text("Page-level category choices can narrow this selection further.", style = MaterialTheme.typography.bodySmall, color = SettingsMuted)
        }
    }
}
