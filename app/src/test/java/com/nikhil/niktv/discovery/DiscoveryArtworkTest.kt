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
    @Test fun glassSoftensHardEdgesWithoutMutatingArtworkOrKeepingAFullSizeBlur() = runBlocking {
        val source = Bitmap.createBitmap(192, 108, Bitmap.Config.ARGB_8888)
        for (y in 0 until 108) for (x in 0 until 192) source.setPixel(x, y, if (x < 96) Color.BLACK else Color.WHITE)
        val result = DiscoveryGlassTransformation().transform(source, Size(1280, 720))
        assertEquals(96, result.width); assertEquals(54, result.height)
        assertEquals(Color.BLACK, source.getPixel(95, 54)); assertEquals(Color.WHITE, source.getPixel(96, 54))
        assertTrue(Color.red(result.getPixel(48, 27)) in 1..254)
        assertEquals(0, Color.red(result.getPixel(10, 27)))
    }
    @Test fun portraitGlassTextureRetainsArtworkAspectRatio() = runBlocking {
        val source = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val result = DiscoveryGlassTransformation().transform(source, Size(192, 108))
        assertEquals(96, result.width)
        assertEquals(144, result.height)
        assertEquals(200, source.width)
        assertEquals(300, source.height)
    }
    @Test fun glassUsesSharedHeroCoordinatesForCropFitAndScrolledArtwork() {
        val image = androidx.compose.ui.geometry.Size(200f, 300f)
        val viewport = androidx.compose.ui.geometry.Rect(0f, 100f, 300f, 700f)
        assertEquals(androidx.compose.ui.geometry.Rect(-50f, 100f, 350f, 700f), discoveryGlassImageRect(viewport, image))
        assertEquals(androidx.compose.ui.geometry.Rect(0f, 100f, 300f, 550f), discoveryGlassImageRect(viewport, image, fit = true, topAligned = true))
        assertEquals(androidx.compose.ui.geometry.Rect(-50f, -200f, 350f, 400f),
            discoveryGlassImageRect(androidx.compose.ui.geometry.Rect(0f, -200f, 300f, 400f), image))
    }
    @Test fun unknownGlassBoundsNeverRefitTheEntireArtworkIntoAButton() {
        assertNull(discoveryGlassImageRect(androidx.compose.ui.geometry.Rect.Zero, androidx.compose.ui.geometry.Size(200f, 300f)))
    }
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
