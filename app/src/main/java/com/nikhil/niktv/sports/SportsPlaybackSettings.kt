package com.nikhil.niktv.sports

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.data.UserProfilePreferences
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.data.UserSyncStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal data class SportsPlaybackSettings(val timeout: Int = 3, val engine: PlaybackEngine = PlaybackEngine.MEDIA3)

/** Sports timing is portable per person; the engine remains a device setting. */
internal class SportsPlaybackPreferences(context: Context,
    private val owner: UserScope? = UserSyncStore.get(context).selectedScope.value) {
    private val prefs = context.applicationContext.getSharedPreferences("sports_playback_settings", Context.MODE_PRIVATE)
    private val timing = UserProfilePreferences.get(context, "niktv_playback_ui", owner = owner)
    private fun read() = SportsPlaybackSettings(normalizeTimeout(if (owner == null) prefs.getInt("controls_timeout", 3)
        else timing.getInt("sports_controls_timeout", 3)),
        runCatching { PlaybackEngine.valueOf(prefs.getString("engine", PlaybackEngine.MEDIA3.name)!!) }
            .getOrDefault(PlaybackEngine.MEDIA3).let { if (it == PlaybackEngine.EXOPLAYER) PlaybackEngine.MEDIA3 else it })
    val settings: Flow<SportsPlaybackSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        timing.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener); timing.unregisterOnSharedPreferenceChangeListener(listener) }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()
    val playbackEngine = settings.map { it.engine }
    val playerControlsTimeoutSeconds = settings.map { it.timeout }
    fun setPlayerControlsTimeoutSeconds(seconds: Int) = (if (owner == null) prefs else timing).edit()
        .putInt(if (owner == null) "controls_timeout" else "sports_controls_timeout", normalizeTimeout(seconds)).apply()
    fun setPlaybackEngine(engine: PlaybackEngine) = prefs.edit().putString("engine",
        (if (engine == PlaybackEngine.EXOPLAYER) PlaybackEngine.MEDIA3 else engine).name).apply()

    private fun normalizeTimeout(seconds: Int) = if (seconds <= 0) 0 else seconds.coerceIn(1, 30)
}

internal class SportsPlaybackSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val owner = UserSyncStore.get(application).selectedScope.value
    private val store = SportsPlaybackPreferences(application, owner)
    private val mutable = MutableStateFlow<SportsPlaybackSettings?>(null)
    val settings = mutable.asStateFlow()
    init {
        viewModelScope.launch { store.settings.collect { mutable.value = it } }
    }
    fun setTimeout(seconds: Int) {
        val value = if (seconds <= 0) 0 else seconds.coerceIn(1, 30)
        mutable.update { (it ?: SportsPlaybackSettings()).copy(timeout = value) }
        viewModelScope.launch(Dispatchers.IO) { store.setPlayerControlsTimeoutSeconds(value) }
    }
    fun setEngine(engine: PlaybackEngine) {
        val value = if (engine == PlaybackEngine.EXOPLAYER) PlaybackEngine.MEDIA3 else engine
        mutable.update { (it ?: SportsPlaybackSettings()).copy(engine = value) }
        viewModelScope.launch(Dispatchers.IO) { store.setPlaybackEngine(value) }
    }
}
