package com.nikhil.niktv.data

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.WatchedSeries
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class, manifest = Config.NONE)
class NewEpisodeNotificationsTest {
    private lateinit var context: Context
    private val series = MediaItem("show", "Daily Show", null, null)
    private val episode = MediaItem("ep", "Episode 2", null, null)
    private fun discovery(profile: String) = EpisodeDiscovery(WatchedSeries(profile, series), listOf(episode))

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, false)
    }

    @Test fun createsChannelAndIndependentProfileNotificationsWithAnOpenAction() {
        NewEpisodeNotifications.post(context, "First", discovery("first"))
        NewEpisodeNotifications.post(context, "Second", discovery("second"))
        val manager = context.getSystemService(NotificationManager::class.java)
        assertNotNull(manager.getNotificationChannel("new_episodes"))
        val notifications = manager.activeNotifications
        assertEquals(2, notifications.size)
        assertTrue(notifications.all { it.notification.contentIntent != null })
        assertEquals(setOf("First · Daily Show", "Second · Daily Show"), notifications.map { it.notification.extras.getString("android.title") }.toSet())
    }

    @Test fun tvDoesNotPostEpisodeNotifications() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        NewEpisodeNotifications.post(context, "TV", discovery("tv"))
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }

    @Test fun noNewEpisodesDoesNotPost() {
        NewEpisodeNotifications.post(context, "First", discovery("first").copy(added = emptyList()))
        assertTrue(context.getSystemService(NotificationManager::class.java).activeNotifications.isEmpty())
    }
}
