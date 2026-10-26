package com.nikhil.niktv.ui

import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.model.PlayingMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Stable
internal class IntroSkipSettings(val target: IntroTarget?, private val store: IntroTimingStore,
    private val scope: CoroutineScope) {
    var timings by mutableStateOf(IntroTimings()); private set
    var loaded by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var editorOpen by mutableStateOf(false); private set
    var startText by mutableStateOf("0:00")
    var endText by mutableStateOf("0:00")
    var editScope by mutableStateOf(IntroScope.EPISODE)
    private var draftStarted = false
    private var writeJob: Job? = null
    fun dispose() { writeJob?.cancel() }

    suspend fun load() {
        try { target?.let { timings = withContext(Dispatchers.IO) { store.read(it) } } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { error = "Could not load saved intro timings." }
        loaded = true
    }
    fun open(position: Long) {
        val item = target ?: return
        if (!loaded) return
        if (!draftStarted) {
            val saved = timings.effective
            startText = introTime(saved?.startMillis ?: 0)
            endText = introTime(saved?.endMillis ?: position)
            editScope = if (timings.episode != null || item.seasonKey == null) IntroScope.EPISODE else IntroScope.SEASON
            draftStarted = true
        }
        error = null
        editorOpen = true
    }
    fun close() { if (!saving) editorOpen = false }
    fun write(range: IntroRange?, onSuccess: () -> Unit) {
        val item = target ?: return
        if (saving) return
        val selectedScope = editScope
        saving = true; error = null
        writeJob = scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    store.save(item, selectedScope, range)
                    store.read(item)
                }
                timings = updated
                draftStarted = false
                editorOpen = false
                onSuccess()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "Could not save intro timing. Please retry." }
            finally { saving = false }
        }
    }
}

@Composable
internal fun rememberIntroSkipSettings(media: PlayingMedia): IntroSkipSettings {
    val context = LocalContext.current.applicationContext
    val target = remember(media.profileKey, media.catalogType, media.media.id, media.media.seasonNumber,
        media.media.episodeNumber, media.series, media.nativeSabr, media.progressKey) { introTarget(media) }
    val scope = rememberCoroutineScope()
    val store = remember(context) { IntroTimingStore { context.getSharedPreferences("player_intro_timings", Context.MODE_PRIVATE) } }
    val settings = remember(target) {
        IntroSkipSettings(target, store, scope)
    }
    LaunchedEffect(settings) { settings.load() }
    DisposableEffect(settings) { onDispose { settings.dispose() } }
    return settings
}

@Composable
internal fun IntroTimingDialog(settings: IntroSkipSettings, durationMillis: Long,
    currentPosition: () -> Long, onDismiss: () -> Unit) {
    val target = settings.target ?: return
    val markStart = remember { FocusRequester() }
    val start = parseIntroTime(settings.startText)
    val end = parseIntroTime(settings.endText)
    val range = if (start != null && end != null) IntroRange(start, end) else null
    val valid = range?.validFor(durationMillis) == true
    val selectedSaved = if (settings.editScope == IntroScope.SEASON) settings.timings.season else settings.timings.episode
    AlertDialog(onDismissRequest = { if (!settings.saving) onDismiss() },
        shape = RoundedCornerShape(20.dp), containerColor = Color(0xF21A1A1A),
        titleContentColor = Color.White, textContentColor = Color.White,
        title = { Text("Intro timing", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.fillMaxWidth().playerChoiceFocusEntry(markStart).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Mark the current position, or enter m:ss / h:mm:ss. Close and reopen to mark the other end while watching.",
                    style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntroTimeField("Start", settings.startText, { settings.startText = it }, !settings.saving, Modifier.weight(1f))
                    NikTvActionButton("Mark start", enabled = !settings.saving,
                        modifier = Modifier.width(112.dp).height(48.dp).focusRequester(markStart),
                        onClick = { settings.startText = introTime(currentPosition()) })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntroTimeField("End", settings.endText, { settings.endText = it }, !settings.saving, Modifier.weight(1f))
                    NikTvActionButton("Mark end", enabled = !settings.saving,
                        modifier = Modifier.width(112.dp).height(48.dp),
                        onClick = { settings.endText = introTime(currentPosition()) })
                }
                if (target.seasonKey != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PlayerVisualChoiceButton(target.episodeLabel, settings.editScope == IntroScope.EPISODE,
                            Modifier.weight(1f), onClick = { settings.editScope = IntroScope.EPISODE })
                        PlayerVisualChoiceButton(target.seasonLabel.orEmpty(), settings.editScope == IntroScope.SEASON,
                            Modifier.weight(1f), onClick = { settings.editScope = IntroScope.SEASON })
                    }
                    Text("Episode overrides take priority over season timings.", style = MaterialTheme.typography.labelSmall)
                } else Text(target.episodeLabel, style = MaterialTheme.typography.labelMedium)
                if (!valid) Text("Enter an end after the start and at least one second before the video ends.",
                    style = MaterialTheme.typography.bodySmall)
                settings.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (selectedSaved != null) NikTvActionButton("Remove saved timing", enabled = !settings.saving,
                    modifier = Modifier.fillMaxWidth().height(44.dp), onClick = { settings.write(null, onDismiss) })
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NikTvActionButton("Close", enabled = !settings.saving, modifier = Modifier.weight(1f).height(44.dp), onClick = onDismiss)
                NikTvActionButton(if (settings.saving) "Saving…" else "Save", primary = true,
                    enabled = valid && !settings.saving, modifier = Modifier.weight(1f).height(44.dp),
                    onClick = { range?.takeIf { it.validFor(durationMillis) }?.let { settings.write(it, onDismiss) } })
            }
        })
}

@Composable
private fun IntroTimeField(label: String, value: String, change: (String) -> Unit, enabled: Boolean, modifier: Modifier) {
    var editing by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    fun activate() {
        if (enabled) { editing = true; scope.launch { withFrameNanos { }; requester.requestFocus(); keyboard?.show() } }
    }
    OutlinedTextField(value, { if (it.length <= 11 && it.all { c -> c.isDigit() || c == ':' }) change(it) },
        label = { Text(label) }, singleLine = true, enabled = enabled, readOnly = !editing,
        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White,
            focusedLabelColor = Color.White, unfocusedLabelColor = Color.LightGray),
        shape = RoundedCornerShape(10.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { editing = false; keyboard?.hide() }),
        modifier = modifier.focusRequester(requester).onFocusChanged { if (!it.isFocused) { editing = false; keyboard?.hide() } }
            .onPreviewKeyEvent {
                if (!editing && it.key in listOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)) {
                    if (it.type == KeyEventType.KeyUp) activate()
                    true
                } else false
            }.pointerInput(enabled) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) activate()
                }
            })
}

/** Remote users can press OK immediately; touch users keep their current input mode. */
@Composable
internal fun IntroSkipButton(requester: FocusRequester, onSkip: () -> Unit, onControls: () -> Unit,
    onReturnToVideo: () -> Unit, remainingSeconds: Long, modifier: Modifier = Modifier) {
    if (remainingSeconds <= 0L) return
    var focused by remember { mutableStateOf(false) }
    val directionalInput = playerUsesDirectionalInput()
    LaunchedEffect(requester, directionalInput) {
        if (directionalInput) {
            withFrameNanos { }
            requester.requestFocus()
        }
    }
    val returnToVideo by rememberUpdatedState(onReturnToVideo)
    DisposableEffect(Unit) { onDispose { if (focused) returnToVideo() } }
    NikTvActionButton("Skip intro · ${introTime(remainingSeconds * 1_000L)}", primary = false, modifier = modifier.width(164.dp)
        .height(if (rememberPlayerTvDevice()) 56.dp else 48.dp)
        .background(Color(0xD91A1A1A), RoundedCornerShape(12.dp)).focusRequester(requester)
        .onFocusChanged { focused = it.isFocused }
        .semantics {
            contentDescription = "Skip intro"
            stateDescription = "$remainingSeconds seconds remaining"
        }
        .onPreviewKeyEvent {
            if (it.type != KeyEventType.KeyDown) false else when (it.key) {
                Key.DirectionUp -> { onControls(); true }
                Key.DirectionLeft -> { onReturnToVideo(); true }
                else -> false
            }
        }, onClick = onSkip)
}
