package com.nikhil.niktv.discovery

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf

/** Independent of the original catalog layout setting; reverting never clears trial data. */
internal object DiscoveryExperiencePreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("discovery_experience", Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean("enabled", true)
    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("enabled", enabled).apply()
}

internal val LocalSetDiscoveryExperience = staticCompositionLocalOf<(Boolean) -> Unit> { {} }
