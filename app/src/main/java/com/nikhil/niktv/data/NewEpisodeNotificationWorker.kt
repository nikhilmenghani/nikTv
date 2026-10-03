package com.nikhil.niktv.data

import android.content.Context
import android.util.Log
import androidx.work.*
import com.nikhil.niktv.model.SeriesStartSeason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

internal object NewEpisodeNotificationScheduler {
    private const val WORK = "niktv-new-episodes"
    fun initialize(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (!NewEpisodeNotifications.supported(context)) {
            manager.cancelUniqueWork(WORK)
            return
        }
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<NewEpisodeNotificationWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build())
    }
}

class NewEpisodeNotificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!NewEpisodeNotifications.supported(applicationContext) || CatalogPlaybackActivity.playing) return Result.success()
        val store = ProfileStore(applicationContext)
        val portal = StalkerPortalClient(applicationContext)
        var failed = false
        NewEpisodeNotifications.refreshMutex.withLock {
            val profiles = store.profiles.first()
            for (profile in profiles) {
                val due = store.watchedSeries.first().filter {
                    it.profileKey == profile.cacheKey() &&
                        System.currentTimeMillis() - it.checkedAtMillis >= NewEpisodeNotifications.INTERVAL_MILLIS
                }
                if (due.isEmpty()) continue
                try {
                    val session = withTimeoutOrNull(20_000L) { portal.authenticate(profile) }
                        ?: error("Profile check timed out")
                    for (watched in due) {
                        try {
                            val latest = withTimeoutOrNull(30_000L) { portal.episodeSeason(session, watched.series, SeriesStartSeason.LAST) }
                                ?: error("Episode check timed out")
                            store.recordEpisodeDiscovery(watched.key, latest.episodes, System.currentTimeMillis())?.let {
                                NewEpisodeNotifications.post(applicationContext, profile.name, it)
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            failed = true
                            Log.w("NikTvNewEpisodes", "Episode check failed; subscription remains due for retry")
                        }
                        delay(1_200L)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    failed = true
                    Log.w("NikTvNewEpisodes", "Profile check failed; subscriptions remain due for retry")
                }
            }
        }
        return if (failed) Result.retry() else Result.success()
    }
}
