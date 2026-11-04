package com.nikhil.niktv.sports

import android.content.Context

internal enum class SportsResolverMode(val title: String) {
    TOKEN_AWARE("7.4 token-aware"), LEGACY("7.3 legacy");
}

/** Sports-only, read once per source attempt. Does not alter an active player. */
internal class SportsResolverPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("sports_resolver_settings", Context.MODE_PRIVATE)
    fun read(): SportsResolverMode = runCatching {
        SportsResolverMode.valueOf(prefs.getString("mode", SportsResolverMode.TOKEN_AWARE.name)!!)
    }.getOrDefault(SportsResolverMode.TOKEN_AWARE)
    fun set(mode: SportsResolverMode) { prefs.edit().putString("mode", mode.name).apply() }
}
