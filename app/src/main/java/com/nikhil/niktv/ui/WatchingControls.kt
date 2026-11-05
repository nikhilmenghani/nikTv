package com.nikhil.niktv.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.PlayingMedia
import com.nikhil.niktv.data.UserProfilePreferences
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.data.UserSyncStore
import kotlinx.coroutines.delay
import java.util.WeakHashMap

internal object WatchingPreferences {
    fun save(context: Context, value: WatchingConfiguration) = save(preferences(context), value)
    fun preferences(context: Context, owner: UserScope? = UserSyncStore.get(context).selectedScope.value) =
        UserProfilePreferences.get(context, "niktv_playback_ui", owner = owner)
    fun read(prefs: SharedPreferences) = WatchingConfiguration(prefs.getBoolean("watching_check_enabled", true),
        prefs.getInt("watching_check_minutes", 120).coerceIn(15, 480), prefs.getInt("watching_check_episodes", 4).coerceIn(1, 12))
    fun save(prefs: SharedPreferences, value: WatchingConfiguration) { prefs.edit()
        .putBoolean("watching_check_enabled", value.enabled).putInt("watching_check_minutes", value.minutes.coerceIn(15, 480))
        .putInt("watching_check_episodes", value.episodes.coerceIn(1, 12)).apply() }
}

@Composable
internal fun rememberWatchingConfiguration(owner: UserScope? = LocalViewingOwner.current): WatchingConfiguration {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context, owner) { WatchingPreferences.preferences(context, owner) }
    var value by remember(prefs) { mutableStateOf(WatchingPreferences.read(prefs)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> value = WatchingPreferences.read(prefs) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

@Composable
internal fun WatchingSettingsContent() {
    val context = LocalContext.current
    val owner = LocalViewingOwner.current
    val prefs = remember(context, owner) { WatchingPreferences.preferences(context, owner) }
    val config = rememberWatchingConfiguration()
    SettingsControlRow("Are you still watching?", checked = config.enabled,
        description = "Pause and ask before continuing after either limit, without interaction.",
        onToggle = { WatchingPreferences.save(prefs, config.copy(enabled = it)) })
    if (config.enabled) {
        SettingsPickerRow("Without interaction: watch time", listOf(30, 60, 90, 120, 180, 240).map { it to "$it minutes" },
            config.minutes, { WatchingPreferences.save(prefs, config.copy(minutes = it)) }, "Counts time actually playing, across episodes.")
        SettingsPickerRow("Without interaction: episodes", (1..8).map { it to "$it episodes" }, config.episodes,
            { WatchingPreferences.save(prefs, config.copy(episodes = it)) }, "Whichever limit comes first. Using player controls restarts both counters.")
    }
}

internal val LocalWatchingSession = staticCompositionLocalOf<WatchingSession?> { null }

private val watchingSessions = WeakHashMap<Context, WatchingSession>()
internal class WatchingCheck(val session: WatchingSession, val eligible: Boolean) {
    var config = WatchingConfiguration()
    var awaiting by mutableStateOf(false); private set
    private var advancePending = false
    fun interact() { if (!awaiting) session.interact() }
    fun sample(now: Long, playing: Boolean) {
        if (!eligible) return
        session.sample(now, playing && !awaiting)
        if (!awaiting && playing && session.due(config)) awaiting = true
    }
    fun completeEpisode(advance: () -> Unit) {
        if (eligible) session.completeEpisode()
        if (eligible && session.due(config)) { advancePending = true; awaiting = true }
        else { session.expectNextEpisode(); advance() }
    }
    fun confirm(): Boolean {
        val advance = advancePending
        advancePending = false; awaiting = false; session.interact()
        if (advance) session.expectNextEpisode()
        return advance
    }
}

@Composable
internal fun rememberWatchingCheck(media: PlayingMedia): WatchingCheck {
    val context = LocalContext.current
    val owner = context.findActivity() ?: context.applicationContext
    val identity = "${media.userScope}:${media.profileKey}:${media.series?.id ?: media.media.id}"
    val suppliedSession = LocalWatchingSession.current
    val session = remember(owner, suppliedSession) { suppliedSession ?: watchingSessions.getOrPut(owner) { WatchingSession() } }
    val check = remember(session, media.progressKey, identity) {
        session.begin(identity, media.media.id)
        WatchingCheck(session, media.catalogType != CatalogType.LIVE_TV)
    }
    val config = rememberWatchingConfiguration(media.userScope)
    SideEffect { check.config = config }
    return check
}

@Composable
internal fun WatchPlayback(check: WatchingCheck, isPlaying: () -> Boolean, pause: () -> Unit) {
    val currentPlaying by rememberUpdatedState(isPlaying)
    val currentPause by rememberUpdatedState(pause)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(check, lifecycle) {
        try {
            while (true) {
                check.sample(SystemClock.elapsedRealtime(), lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && currentPlaying())
                if (check.awaiting) currentPause()
                delay(500)
            }
        } finally { check.session.sample(SystemClock.elapsedRealtime(), false) }
    }
}

/** Same bottom banner styling as the next-episode countdown, held until the viewer answers. */
@Composable
internal fun StillWatchingPrompt(check: WatchingCheck, onContinue: () -> Unit, onStop: () -> Unit) {
    if (!check.awaiting) return
    val resume = remember { FocusRequester() }
    val stop = remember { FocusRequester() }
    Dialog(onDismissRequest = onStop, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().initialFocusEntry(resume),
                shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .96f), contentColor = Color.White,
                tonalElevation = 6.dp, shadowElevation = 8.dp) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Are you still watching?", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Playback is paused.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        NikTvTextActionButton(onClick = onStop, modifier = Modifier.focusRequester(stop)
                            .playerDpadFocusRoutes(left = resume, right = resume, up = resume, down = resume)) { Text("Stop watching") }
                        NikTvPrimaryActionButton(onClick = onContinue, modifier = Modifier.focusRequester(resume)
                            .playerDpadFocusRoutes(left = stop, right = stop, up = stop, down = stop)) { Text("Continue") }
                    }
                }
            }
        }
    }
}
