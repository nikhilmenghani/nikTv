package com.nikhil.niktv.ui

import java.util.TimeZone
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Exercise the Java compatibility API shared by the app and its instrumentation APK. */
class AndroidRuntimeLinkageDeviceTest {
    @Test fun networkingAndTimeZoneUseTheSameCompatibilityRuntime() {
        // A test APK can otherwise ship a reduced DesugarTimeZone which shadows
        // the app copy on older Android versions and breaks OkHttp initialization.
        assertEquals("UTC", TimeZone.getTimeZone("UTC").id)
        val client = OkHttpClient.Builder().build()
        try {
            assertNotNull(client.dispatcher)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
