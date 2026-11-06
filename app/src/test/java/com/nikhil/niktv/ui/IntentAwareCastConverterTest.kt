package com.nikhil.niktv.ui

import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class, manifest = Config.NONE)
class IntentAwareCastConverterTest {
    @Test fun receiverLoadHonorsBothPauseAndPlayWithoutDiscardingMediaIdentity() {
        var requested = false
        val converter = IntentAwareCastConverter { requested }
        val media = MediaItem.Builder().setUri("https://fixture.invalid/movie.mp4").setMimeType(MimeTypes.VIDEO_MP4).build()
        val paused = converter.toMediaQueueItem(media)
        assertFalse(paused.autoplay)
        assertEquals(media.localConfiguration?.uri, converter.toMediaItem(paused).localConfiguration?.uri)
        requested = true
        assertTrue(converter.toMediaQueueItem(media).autoplay)
    }
}
