package com.nikhil.niktv.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** One episode-view choice per viewer, shared by IPTV and TMDB series. */
internal object SeriesEpisodeViewPreferences {
    private const val DOMAIN = "discovery_experience"
    private const val KEY = "series_episode_list_view"
    private class Change(val context: Context, val owner: UserScope?, val prefs: SharedPreferences, val list: Boolean) {
        val saved = CompletableDeferred<Unit>()
    }
    private val pending = ConcurrentHashMap<SharedPreferences, Change>()
    private val writes = Channel<Change>(Channel.UNLIMITED)
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        worker.launch {
            for (change in writes) {
                try {
                    change.owner?.let { UserProfilePreferences.warm(change.context, it) }
                    check(change.prefs.edit().putBoolean(KEY, change.list).commit())
                    pending.remove(change.prefs, change)
                    change.saved.complete(Unit)
                } catch (error: Exception) {
                    Log.w("SeriesEpisodeView", "Could not save episode view", error)
                    change.saved.completeExceptionally(error)
                }
            }
        }
    }
    fun listView(context: Context, owner: UserScope?, defaultListView: Boolean): Boolean {
        val prefs = UserProfilePreferences.get(context, DOMAIN, owner = owner)
        // A new series may open before the previous screen's disk write finishes.
        return pending[prefs]?.list ?: prefs.getBoolean(KEY, defaultListView)
    }
    fun setListView(context: Context, owner: UserScope?, list: Boolean): Deferred<Unit> {
        val app = context.applicationContext
        val prefs = UserProfilePreferences.get(app, DOMAIN, owner = owner)
        val change = Change(app, owner, prefs, list)
        pending[prefs] = change
        // The ordered worker survives screen disposal and retains the captured viewer.
        check(writes.trySend(change).isSuccess)
        return change.saved
    }
}
