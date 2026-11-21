package com.nikhil.niktv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ProfileSetupEditor(profile: UserProfile?, onDismiss: () -> Unit,
    onSave: suspend (ProfileSetupDraft) -> Unit, onDelete: (() -> Unit)?,
    load: suspend () -> ProfileSetupDraft = { error("A setup loader is required") }) {
    var initial by remember(profile?.id) { mutableStateOf<ProfileSetupDraft?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(profile?.id, attempt) {
        try { initial = load(); failed = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val ready = initial
    if (ready != null) ProfileSetupForm(ready, onDismiss, onSave, onDelete)
    else {
        BackHandler(onBack = onDismiss)
        Surface(Modifier.fillMaxSize(), color = Color(0xFF090B10), contentColor = Color.White) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                if (failed) {
                    Text("Could not load profile setup.")
                    NikTvPrimaryActionButton({ failed = false; attempt++ }) { Text("Retry") }
                    NikTvTextActionButton(onDismiss) { Text("Cancel") }
                } else { CircularProgressIndicator(); Text("Loading profile options…") }
            }
        }
    }
}

@Composable
internal fun ProfileSetupForm(initial: ProfileSetupDraft, onDismiss: () -> Unit,
    onSave: suspend (ProfileSetupDraft) -> Unit, onDelete: (() -> Unit)? = null) {
    key(initial.profile.id) { ProfileSetupFormContent(initial, onDismiss, onSave, onDelete) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileSetupFormContent(initial: ProfileSetupDraft, onDismiss: () -> Unit,
    onSave: suspend (ProfileSetupDraft) -> Unit, onDelete: (() -> Unit)?) {
    var draft by remember(initial.profile.id) { mutableStateOf(initial) }
    var step by remember { mutableIntStateOf(0) }
    var options by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editConnection by remember { mutableStateOf(false) }
    var connection by remember { mutableStateOf<PortalProfile?>(null) }
    var pendingSafety by remember { mutableStateOf<ProfileParentalPolicy?>(null) }
    var pin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val remote = context.usesRemoteNavigation(config) || LocalInputModeManager.current.inputMode == androidx.compose.ui.input.InputMode.Keyboard
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val nameFocus = remember { FocusRequester() }
    val portalFocus = remember { FocusRequester() }
    val connectionFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }
    var returningFromConnection by remember { mutableStateOf(false) }
    var pendingStepFocus by remember { mutableStateOf(false) }
    fun change(value: ProfileSetupDraft) { if (!saving) { draft = value; error = null } }
    fun leave() { if (!saving) onDismiss() }
    fun previous() { if (!saving) { keyboard?.hide(); if (step > 0) { pendingStepFocus = remote; step-- } else onDismiss() } }
    fun save(later: Boolean = false) {
        if (saving) return
        saving = true
        error = null
        keyboard?.hide()
        val captured = if (later) draft.copy(selected = emptySet(), startingPortal = null) else draft
        scope.launch {
            try { onSave(captured) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Could not save profile. Your choices are kept here. Please try again." }
            finally { saving = false }
        }
    }
    BackHandler(enabled = !WindowInsets.isImeVisible && !editConnection && pendingSafety == null) { previous() }
    ProfileSetupFocusScope {
    if (editConnection) {
        PortalConnectionEditor(connection, false, connect = { portal ->
            val choice = ProfilePortalChoice(portal, false)
            val old = connection?.let(ProfilePortalAccess::key)
            change(draft.copy(portals = (draft.portals.filterNot { it.key == old || it.key == choice.key ||
                (!it.ready && it.portal.portalType == portal.portalType) } + choice),
                selected = (draft.selected - listOfNotNull(old).toSet()) + choice.key,
                startingPortal = if (draft.startingPortal == old) choice.key else draft.startingPortal))
            returningFromConnection = true; pendingStepFocus = remote; editConnection = false
        }, cancelEditor = { returningFromConnection = true; pendingStepFocus = remote; editConnection = false }, importBackup = {},
            confirmLabel = "Use connection", showImport = false)
        return@ProfileSetupFocusScope
    }
    val target = if (step == 0) nameFocus else if (returningFromConnection) connectionFocus else portalFocus
    Surface(Modifier.fillMaxSize(), color = Color(0xFF090B10), contentColor = Color.White) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp)
            .initialFocusEntry(target, remote), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.widthIn(max = 1000.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (initial.existing) "Manage profile" else "Add profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (step == 0) "1 of 2 · Profile details" else "2 of 2 · Portals & preferences",
                        color = SettingsMuted, style = MaterialTheme.typography.bodySmall)
                }
                if (step > 0) NikTvTextActionButton(::previous, Modifier.focusRequester(backFocus)) { Text("Previous") }
                NikTvTextActionButton(::leave) { Text("Cancel") }
            }
            key(step) {
                Column(Modifier.widthIn(max = if (step == 0) 720.dp else 1000.dp).fillMaxWidth().weight(1f)
                    .onGloballyPositioned {
                        // Hand off in the placement that attaches the new step, before a
                        // changed footer or header can be painted as an interim target.
                        if (pendingStepFocus && runCatching { target.requestFocus() }.getOrDefault(false)) pendingStepFocus = false
                    }
                    .verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (step == 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(Modifier.size(64.dp).background(Color(draft.profile.color), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                Text(draft.profile.avatar.ifBlank { draft.profile.name.take(1).uppercase().ifBlank { "N" } }, fontSize = 28.sp)
                            }
                            ProfileSetupTextField("Display name", draft.profile.name,
                                { change(draft.copy(profile = draft.profile.copy(name = it.take(60)))) }, nameFocus, Modifier.weight(1f))
                        }
                        ProfileSetupTextField("Initials or emoji", draft.profile.avatar,
                            { change(draft.copy(profile = draft.profile.copy(avatar = it.take(8)))) }, remember { FocusRequester() })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("Red" to 0xFFE50914L, "Blue" to 0xFF1565C0L, "Green" to 0xFF2E7D32L, "Purple" to 0xFF7B1FA2L).forEach { (name, color) ->
                                NikTvSecondaryActionButton({ change(draft.copy(profile = draft.profile.copy(color = color))) }, Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)) { Text(if (draft.profile.color == color) "✓ $name" else name) }
                            }
                        }
                        if (!initial.existing) SettingsPickerRow("Profile type", listOf(UserProfileType.GENERAL to "General", UserProfileType.KIDS to "Kids"),
                            draft.profile.type, { type -> change(draft.copy(profile = draft.profile.copy(type = type),
                                content = if (type == UserProfileType.KIDS) XtreamContentPolicy.kids() else XtreamContentPolicy())) })
                        else SettingsControlRow("Profile type", if (draft.profile.type == UserProfileType.KIDS) "Kids profile" else "General profile")
                        SettingsPickerRow("App layout", ViewerLayout.entries.map { it to it.label }, draft.layout,
                            { change(draft.copy(layout = it)) }, "Easy offers simpler IPTV navigation. Standard includes discovery features.")
                        SettingsControlRow("Show and sync on this device", checked = draft.enabled,
                            description = "Show this profile on Who’s watching and sync its viewing data here.",
                            onToggle = { change(draft.copy(enabled = it)) })
                    } else {
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val portalColumn: @Composable () -> Unit = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Portals for ${draft.profile.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text("Turn on the portals this profile can use. Existing connections are reused.", color = SettingsMuted, style = MaterialTheme.typography.bodySmall)
                                    draft.portals.forEachIndexed { index, choice ->
                                        SettingsControlRow(choice.portal.displayName,
                                            description = if (choice.ready) "Ready" else "Needs connection setup",
                                            modifier = if (index == 0) Modifier.focusRequester(portalFocus) else Modifier,
                                            checked = choice.key in draft.selected,
                                            onToggle = { enabled ->
                                                if (enabled && !choice.ready) { connection = choice.portal; editConnection = true }
                                                else change(draft.copy(selected = if (enabled) draft.selected + choice.key else draft.selected - choice.key))
                                            })
                                    }
                                    SettingsControlRow("Sports", checked = ProfilePortalAccess.SPORTS in draft.selected,
                                        modifier = if (draft.portals.isEmpty()) Modifier.focusRequester(portalFocus) else Modifier,
                                        onToggle = { change(draft.copy(selected = if (it) draft.selected + ProfilePortalAccess.SPORTS else draft.selected - ProfilePortalAccess.SPORTS)) })
                                    NikTvSecondaryActionButton({ connection = null; editConnection = true }, Modifier.fillMaxWidth().focusRequester(connectionFocus)) { Text("Add connection") }
                                    val starts = draft.portals.filter { it.key in draft.selected }.map { it.key to it.portal.displayName } +
                                        if (ProfilePortalAccess.SPORTS in draft.selected) listOf(ProfilePortalAccess.SPORTS to "Sports") else emptyList()
                                    if (starts.size > 1) SettingsPickerRow("Open first", starts, draft.start ?: starts.first().first, { change(draft.copy(startingPortal = it)) })
                                    else Text(starts.firstOrNull()?.let { "Opens ${it.second}" } ?: "Choose a portal, or set it up later.", color = SettingsMuted, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            val preferences: @Composable () -> Unit = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf("Content", "Playback", "Safety").forEachIndexed { index, label ->
                                            NikTvSecondaryActionButton({ options = index }, Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                                                Text(if (options == index) "✓ $label" else label, style = MaterialTheme.typography.labelLarge)
                                            }
                                        }
                                    }
                                    when (options) {
                                        0 -> ProfileSetupContentOptions(draft.content) { change(draft.copy(content = it)) }
                                        1 -> {
                                            SettingsControlRow("Pinch to zoom", checked = draft.pinchToZoom,
                                                description = "Allow two-finger zoom in the player. Off by default.", onToggle = { change(draft.copy(pinchToZoom = it)) })
                                            SettingsPickerRow("Preferred IPTV quality", listOf(0 to "Best available", 2160 to "Up to 4K", 1080 to "Up to 1080p", 720 to "Up to 720p", 480 to "Up to 480p"),
                                                draft.quality, { change(draft.copy(quality = it)) }, "Used when the provider offers multiple playback qualities.")
                                        }
                                        else -> {
                                            fun authorize(next: ProfileParentalPolicy) { pendingSafety = next; pin = ""; pinError = null }
                                            SettingsControlRow("Protect sensitive content", checked = draft.parental.protectionEnabled,
                                                onToggle = { authorize(draft.parental.copy(protectionEnabled = it)) })
                                            SettingsControlRow("Show sensitive IPTV categories", checked = draft.parental.showSensitiveIptv,
                                                onToggle = { authorize(draft.parental.copy(showSensitiveIptv = it)) })
                                            SettingsPickerRow("Lock again after", ParentalControls.relockOptions, draft.parental.relockMinutes,
                                                { authorize(draft.parental.copy(relockMinutes = it)) })
                                            Text("Changes require the device’s parental PIN.", color = SettingsMuted, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                            if (maxWidth >= 760.dp) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                Box(Modifier.weight(1f)) { portalColumn() }; Box(Modifier.weight(1.25f)) { preferences() }
                            } else Column(verticalArrangement = Arrangement.spacedBy(20.dp)) { portalColumn(); preferences() }
                        }
                    }
                }
            }
            Column(Modifier.widthIn(max = 1000.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (step == 0) {
                        onDelete?.let { NikTvTextActionButton(it) { Text("Delete profile") } }
                        NikTvPrimaryActionButton({ if (!saving) { keyboard?.hide(); returningFromConnection = false; pendingStepFocus = remote; step = 1 } },
                            Modifier.weight(1f), enabled = draft.profile.name.isNotBlank()) { Text("Next") }
                    } else {
                        if (!initial.existing) NikTvTextActionButton({ save(later = true) }) { Text("Set up later") }
                        NikTvPrimaryActionButton({ save() }, Modifier.weight(1f),
                            enabled = initial.existing || draft.selected.isNotEmpty()) {
                            if (saving) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                            Text(if (saving) "Saving…" else if (initial.existing) "Save changes" else if (draft.canOpen) "Create & open" else "Create profile")
                        }
                    }
                }
            }
        }
    }
    if (pendingSafety != null) ParentalPinDialog("Confirm parental PIN", pin, { pin = it; pinError = null }, pinError,
        dismiss = { pendingSafety = null }, confirm = {
            if (ParentalControls.accepts(context, pin)) { change(draft.copy(parental = pendingSafety!!)); pendingSafety = null }
            else pinError = "Incorrect PIN"
        })
    }
}

@Composable
private fun ProfileSetupFocusScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSettingsRailFocus provides null, LocalSettingsFocusEntries provides null,
        LocalSettingsTv provides LocalContext.current.isTvLikeDevice(LocalConfiguration.current), content = content)
}

@Composable
private fun ProfileSetupContentOptions(policy: XtreamContentPolicy, change: (XtreamContentPolicy) -> Unit) {
    var moreLanguages by remember { mutableStateOf(false) }
    Text("Xtream content preferences", style = MaterialTheme.typography.titleMedium)
    SettingsControlRow("Filter Xtream content", checked = policy.enabled, onToggle = { change(policy.copy(enabled = it)) })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("General" to XtreamContentPolicy(enabled = true), "Parents" to XtreamContentPolicy.parents(), "Kids" to XtreamContentPolicy.kids()).forEach { (title, preset) ->
            NikTvSecondaryActionButton({ change(preset.copy(knownCategories = policy.knownCategories,
                categoryOverrides = policy.categoryOverrides, itemOverrides = policy.itemOverrides)) }, Modifier.weight(1f), contentPadding = PaddingValues(6.dp)) { Text(title) }
        }
    }
    if (policy.enabled) {
        Text("Languages", style = MaterialTheme.typography.labelLarge)
        ContentFilterChoices(if (moreLanguages) contentLanguageOptions else contentLanguageOptions.take(8), policy.languages) { change(policy.copy(languages = it)) }
        NikTvTextActionButton({ moreLanguages = !moreLanguages }) { Text(if (moreLanguages) "Fewer languages" else "More languages") }
        Text("Interests", style = MaterialTheme.typography.labelLarge)
        ContentFilterChoices(contentInterestOptions, policy.interests) { change(policy.copy(interests = it)) }
        Text("Sports", style = MaterialTheme.typography.labelLarge)
        ContentFilterChoices(contentSportOptions, policy.sports) { change(policy.copy(sports = it)) }
        Text("Devotional content", style = MaterialTheme.typography.labelLarge)
        ContentFilterChoices(listOf("hindu" to "Hindu / Jain", "islamic" to "Islamic", "christian" to "Christian"), policy.devotional) { change(policy.copy(devotional = it)) }
        SettingsControlRow("Include dubbed content", checked = policy.includeDubbed, onToggle = { change(policy.copy(includeDubbed = it)) })
        SettingsControlRow("Include replays and highlights", checked = policy.includeReplays, onToggle = { change(policy.copy(includeReplays = it)) })
        SettingsControlRow("Reviewed kids sources only", checked = policy.reviewedKidsOnly, onToggle = { change(policy.copy(reviewedKidsOnly = it)) })
        SettingsPickerRow("Unclassified content", UnclassifiedContent.entries.map { it to when (it) {
            UnclassifiedContent.REVIEW -> "Keep out until reviewed"; UnclassifiedContent.SHOW -> "Show"; UnclassifiedContent.HIDE -> "Hide"
        } }, policy.unclassified, { change(policy.copy(unclassified = it)) })
        SettingsControlRow("Review new categories", checked = policy.reviewNewCategories, onToggle = { change(policy.copy(reviewNewCategories = it)) })
    }
}

@Composable
private fun ProfileSetupTextField(label: String, value: String, change: (String) -> Unit, focus: FocusRequester,
    modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(value) { if (editor.text != value) editor = TextFieldValue(value, TextRange(value.length)) }
    fun activate() { editing = true; focus.requestFocus(); keyboard?.show() }
    OutlinedTextField(editor, { editor = it; if (it.text != value) change(it.text) }, readOnly = !editing,
        label = { Text(label) }, singleLine = true, shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth().focusRequester(focus)
            .onFocusChanged { if (!it.isFocused && editing) { editing = false; keyboard?.hide() } }
            .onPreInterceptKeyBeforeSoftKeyboard { event ->
                if (editing && event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyUp) { editing = false; keyboard?.hide() }
                    true
                } else false
            }
            .onPreviewKeyEvent { event ->
                if (!editing && event.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                    if (event.type == KeyEventType.KeyUp) activate()
                    true
                } else if (!editing && event.key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight)) {
                    if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(when (event.key) {
                        Key.DirectionUp -> FocusDirection.Up; Key.DirectionDown -> FocusDirection.Down
                        Key.DirectionLeft -> FocusDirection.Left; else -> FocusDirection.Right
                    })
                    true
                } else false
            }.pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); activate() }
            })
}
