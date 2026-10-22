package com.nikhil.niktv.discovery

import android.content.Context
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.DashboardSurface

/** Modern discovery is the only user-facing experience; old stored opt-outs are ignored. */
internal object DiscoveryExperiencePreferences {
    private fun prefs(context: Context) = context.getSharedPreferences("discovery_experience", Context.MODE_PRIVATE)
    fun enabled(context: Context) = true
    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("enabled", enabled).apply()
}

internal fun modernCatalogSurface(type: CatalogType): DashboardSurface? = when (type) {
    CatalogType.MOVIES -> DashboardSurface.MOVIES
    CatalogType.SERIES -> DashboardSurface.SERIES
    else -> null
}
