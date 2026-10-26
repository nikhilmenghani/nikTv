package com.nikhil.niktv.ui

import android.content.SharedPreferences

/** Small local preferences, read/written on IO. No Room, network or playback-side polling. */
internal class IntroTimingStore(private val openPreferences: () -> SharedPreferences) {
    private val preferences get() = openPreferences()
    fun read(target: IntroTarget): IntroTimings = IntroTimings(readRange(target.episodeKey), target.seasonKey?.let(::readRange))

    private fun readRange(key: String): IntroRange? = decode(preferences.getString(key, null))

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
                edit.putString(key, "${range.startMillis},${range.endMillis},${System.currentTimeMillis()}")
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
            if (parts.size != 3) return null
            val start = parts[0].toLongOrNull() ?: return null
            val end = parts[1].toLongOrNull() ?: return null
            return IntroRange(start, end).takeIf { start >= 0 && end > start }
        }
    }
}
