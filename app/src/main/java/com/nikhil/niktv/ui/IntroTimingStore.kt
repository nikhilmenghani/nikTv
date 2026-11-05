package com.nikhil.niktv.ui

import android.content.SharedPreferences

/** Small local preferences, read/written on IO. No Room, network or playback-side polling. */
internal class IntroTimingStore(private val openPreferences: () -> SharedPreferences) {
    private val preferences get() = openPreferences()
    fun read(target: IntroTarget): IntroTimings = IntroTimings(readRange(target.episodeKey, target.legacyEpisodeKey),
        target.seasonKey?.let { readRange(it, target.legacySeasonKey) })

    fun observe(target: IntroTarget, changed: () -> Unit): () -> Unit {
        val captured = preferences
        val keys = listOfNotNull(target.episodeKey, target.seasonKey, target.legacyEpisodeKey, target.legacySeasonKey).toSet()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key in keys) changed()
        }
        captured.registerOnSharedPreferenceChangeListener(listener)
        return { captured.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun readRange(key: String, legacy: String? = null): IntroRange? {
        val current = preferences.getString(key, null)
        if (current != null) return decode(current)
        val old = legacy?.let { preferences.getString(it, null) } ?: return null
        // Upgrade only after an exact local provider identity is available.
        preferences.edit().putString(key, old).remove(legacy).commit()
        return decode(old)
    }

    fun save(target: IntroTarget, scope: IntroScope, range: IntroRange?) {
        val key = when (scope) {
            IntroScope.EPISODE -> target.episodeKey
            IntroScope.SEASON -> requireNotNull(target.seasonKey)
        }
        synchronized(lock) {
            val edit = preferences.edit()
            if (range == null) edit.remove(key)
            else {
                require(range.startMillis >= 0 && range.endMillis > range.startMillis)
                edit.putString(key, "${range.startMillis},${range.endMillis},${range.autoSkip},${System.currentTimeMillis()}")
                // Bound retained timing metadata across all profiles; never inspect the media catalogue.
                preferences.all.filterKeys { it != key }.entries.sortedBy { entry ->
                    (entry.value as? String)?.substringAfterLast(',')?.toLongOrNull() ?: 0L
                }.take((preferences.all.size - MAX_ENTRIES + 1).coerceAtLeast(0)).forEach { edit.remove(it.key) }
            }
            check(edit.commit()) { "Could not save intro timing. Please retry." }
        }
    }

    companion object {
        private val lock = Any()
        internal const val MAX_ENTRIES = 500
        internal fun decode(value: String?): IntroRange? {
            val parts = value?.split(',') ?: return null
            if (parts.size !in 3..4) return null
            val automatic = if (parts.size == 4) parts[2].toBooleanStrictOrNull() ?: return null else false
            val start = parts[0].toLongOrNull() ?: return null
            val end = parts[1].toLongOrNull() ?: return null
            return IntroRange(start, end, automatic).takeIf { start >= 0 && end > start }
        }
    }
}
