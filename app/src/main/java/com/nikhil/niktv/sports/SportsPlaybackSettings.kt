package com.nikhil.niktv.sports

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.model.PlaybackEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal data class SportsPlaybackSettings(val timeout: Int = 3, val engine: PlaybackEngine = PlaybackEngine.MEDIA3)

/** Separate from ProfileStore: choosing a Sports engine/timeout never changes IPTV. */
internal class SportsPlaybackPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sports_playback_settings", Context.MODE_PRIVATE)
    private fun read() = SportsPlaybackSettings(prefs.getInt("controls_timeout", 3),
        runCatching { PlaybackEngine.valueOf(prefs.getString("engine", PlaybackEngine.MEDIA3.name)!!) }
            .getOrDefault(PlaybackEngine.MEDIA3).let { if (it == PlaybackEngine.EXOPLAYER) PlaybackEngine.MEDIA3 else it })
    val settings: Flow<SportsPlaybackSettings> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.flowOn(Dispatchers.IO).distinctUntilChanged()
    val playbackEngine = settings.map { it.engine }
    val playerControlsTimeoutSeconds = settings.map { it.timeout }
    fun setPlayerControlsTimeoutSeconds(seconds: Int) = prefs.edit().putInt("controls_timeout",
        if (seconds <= 0) 0 else seconds.coerceIn(1, 30)).apply()
    fun setPlaybackEngine(engine: PlaybackEngine) = prefs.edit().putString("engine",
        (if (engine == PlaybackEngine.EXOPLAYER) PlaybackEngine.MEDIA3 else engine).name).apply()
}

internal class SportsPlaybackSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SportsPlaybackPreferences(application)
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
