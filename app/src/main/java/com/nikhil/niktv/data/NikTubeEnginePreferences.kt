package com.nikhil.niktv.data

import android.content.Context
import android.content.pm.PackageManager

enum class NikTubeEngine { AUTO, FLOW, SMARTTUBE }

object NikTubeEnginePreferences {
    fun selected(context: Context): NikTubeEngine = runCatching {
        NikTubeEngine.valueOf(context.getSharedPreferences("niktube_engine", 0).getString("engine", "AUTO")!!)
    }.getOrDefault(NikTubeEngine.AUTO)

    fun set(context: Context, engine: NikTubeEngine) {
        context.getSharedPreferences("niktube_engine", 0).edit().putString("engine", engine.name).apply()
    }

    fun resolved(context: Context): NikTubeEngine = when (val engine = selected(context)) {
        NikTubeEngine.AUTO -> if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            !context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)) NikTubeEngine.SMARTTUBE else NikTubeEngine.FLOW
        else -> engine
    }
}
