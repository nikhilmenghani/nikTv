package com.nikhil.niktv.data

import android.app.Application
import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class CatalogScanNotificationTest {
    @Test fun backgroundScanDeclaresDataSyncAndOffersPause() {
        val context = RuntimeEnvironment.getApplication()
        val info = CatalogScanNotification.foreground(context, "profile-a", "Movies · page 12")
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
        assertTrue(info.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals("Pause", info.notification.actions.single().title)
        assertEquals("Movies · page 12", info.notification.extras.getCharSequence(Notification.EXTRA_TEXT))
        assertNotNull(info.notification.contentIntent)
    }
    @Test fun notificationPauseIsDurableAndScopedToItsProfile() {
        val context = RuntimeEnvironment.getApplication()
        CatalogScanActionReceiver().onReceive(context, Intent(CatalogScanNotification.PAUSE)
            .putExtra(CatalogScanNotification.PROFILE, "profile-a"))
        assertTrue(CatalogOperations.held(context, CatalogOperations.scan("profile-a")))
        assertFalse(CatalogOperations.held(context, CatalogOperations.scan("profile-b")))
        assertFalse(CatalogOperations.held(context, CatalogOperations.BACKUP))
    }
}
