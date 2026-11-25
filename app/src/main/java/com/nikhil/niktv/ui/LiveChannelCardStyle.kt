package com.nikhil.niktv.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow

/** Card density is a device preference: TV and phone screens need not match. */
internal object LiveChannelCardStyle {
    private var owner: String? = null
    val modern = MutableStateFlow(true)

    @Synchronized fun initialize(context: Context) {
        if (owner == context.packageName) return
        modern.value = context.getSharedPreferences("live_channel_design", Context.MODE_PRIVATE)
            .getBoolean("modern_cards", true)
        owner = context.packageName
    }

    fun set(context: Context, enabled: Boolean) {
        initialize(context)
        context.getSharedPreferences("live_channel_design", Context.MODE_PRIVATE).edit()
            .putBoolean("modern_cards", enabled).apply()
        modern.value = enabled
    }
}

@Composable internal fun rememberModernLiveChannelCards(): Boolean {
    LiveChannelCardStyle.initialize(LocalContext.current)
    val modern by LiveChannelCardStyle.modern.collectAsState()
    return modern
}
