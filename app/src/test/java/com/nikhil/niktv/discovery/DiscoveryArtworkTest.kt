package com.nikhil.niktv.discovery

import android.graphics.Bitmap
import android.graphics.Color
import coil3.size.Size
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = android.app.Application::class)
class DiscoveryArtworkTest {
    @Test fun backdropShadingPreservesSourceAndProducesOpaqueCachedOutput() = runBlocking {
        val source = Bitmap.createBitmap(128, 72, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val result = DiscoveryBackdropTransformation().transform(source, Size(128, 72))
        assertNotSame(source, result)
        assertEquals(Color.WHITE, source.getPixel(64, 36))
        assertEquals(128, result.width); assertEquals(72, result.height)
        assertFalse(result.hasAlpha())
        assertEquals(DiscoveryBackdropTransformation().cacheKey, DiscoveryBackdropTransformation().cacheKey)
    }
}
