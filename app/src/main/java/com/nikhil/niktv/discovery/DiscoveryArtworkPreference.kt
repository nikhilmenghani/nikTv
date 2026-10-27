package com.nikhil.niktv.discovery

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.nikhil.niktv.ui.isTvLikeDevice

/** Device-local presentation preference; never changes feeds or provider lookups. */
internal object DiscoveryArtworkPreference {
    private const val KEY = "suggested_artwork"
    private fun prefs(context: Context) = context.getSharedPreferences("discovery_experience", Context.MODE_PRIVATE)
    fun enabled(context: Context, television: Boolean) = prefs(context).getBoolean(KEY, !television)
    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY, enabled).apply()
    fun observe(context: Context, changed: () -> Unit): () -> Unit {
        val prefs = prefs(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == KEY) changed() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

@Composable
internal fun rememberSuggestedArtwork(): State<Boolean> {
    val context = LocalContext.current.applicationContext
    val television = context.isTvLikeDevice(LocalConfiguration.current)
    val enabled = remember(context, television) { mutableStateOf(DiscoveryArtworkPreference.enabled(context, television)) }
    DisposableEffect(context, television) {
        val stop = DiscoveryArtworkPreference.observe(context) { enabled.value = DiscoveryArtworkPreference.enabled(context, television) }
        onDispose(stop)
    }
    return enabled
}
