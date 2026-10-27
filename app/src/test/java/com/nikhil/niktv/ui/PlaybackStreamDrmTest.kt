package com.nikhil.niktv.ui

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import com.nikhil.niktv.model.PlaybackStreamDrm
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackStreamDrmTest {
    @Test fun protectedOpaqueManifestRetainsLicenseAndHeadersAfterMimeFallback() {
        val drm = PlaybackStreamDrm(C.WIDEVINE_UUID, "https://license.example.test/fixture")
        val headers = mapOf("Authorization" to "Bearer fixture", "Referer" to "https://sports.example.test/")
        for (mime in listOf(null, "application/dash+xml")) {
            val item = MediaItem.Builder().setUri("https://media.example.test/watch?id=fixture").setMimeType(mime)
                .withStreamDrm(drm, headers).build()
            val configured = item.localConfiguration!!.drmConfiguration!!
            assertEquals(drm.scheme, configured.scheme)
            assertEquals(drm.licenseUri, configured.licenseUri.toString())
            assertEquals(headers, configured.licenseRequestHeaders)
        }
    }

    @Test fun normalIptvMediaRemainsUnprotected() {
        val item = MediaItem.Builder().setUri("https://media.example.test/live.m3u8")
            .withStreamDrm(null, emptyMap()).build()
        assertNull(item.localConfiguration!!.drmConfiguration)
    }
}
