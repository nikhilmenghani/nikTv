package com.nikhil.niktv.discovery

import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class DiscoveryPhoneLayoutTest {
    @Test fun overlayCaptionsKeepBothExistingPhoneCardFootprintsIncludingLargeFonts() {
        listOf(100f, 120f, 260f).forEach { width ->
            listOf(1f, 1.5f).forEach { font ->
                assertEquals(width * 1.5f + 7f + 45f * font, phoneDiscoveryCardHeight(width, false, font), .01f)
                assertEquals(width * 9f / 16f + 7f + 68f * font, phoneDiscoveryCardHeight(width, true, font), .01f)
            }
        }
    }
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { context.getSharedPreferences("discovery_experience", 0).edit().clear().commit() }

    @Test fun phonePolicyDoesNotSelectTabletOrTvEvenWithCompactDisplay() {
        assertTrue(usesDiscoveryPhoneLayout(384, false))
        assertTrue(usesDiscoveryPhoneLayout(411, false))
        assertFalse(usesDiscoveryPhoneLayout(600, false))
        assertFalse(usesDiscoveryPhoneLayout(800, false))
        assertFalse(usesDiscoveryPhoneLayout(480, true))
    }

    @Test fun twoLargerPortraitPostersFitPhoneWidthsWithoutChangingTheirRatio() {
        listOf(280, 320, 360, 384, 411, 480).forEach { width ->
            val poster = phoneDiscoveryPosterWidth(width)
            assertTrue("Two posters with insets and gaps must fit $width", poster * 2 + 48 <= width + .01f)
            assertTrue(poster >= 100f)
            assertEquals(poster, nativePosterGeometry(width.toFloat(), 800f, 1f, phone = true).width, .01f)
        }
    }

    @Test fun phoneCollectionsUseTwoColumnsAndPreserveTabletAndTvColumns() {
        for (width in listOf(320, 360, 411, 480)) {
            val configuration = android.content.res.Configuration().apply {
                screenWidthDp = width; smallestScreenWidthDp = width
            }
            assertEquals(2, com.nikhil.niktv.discovery.components.modernCollectionPosterColumns(configuration, false))
        }
        val tablet = android.content.res.Configuration().apply { screenWidthDp = 800; smallestScreenWidthDp = 800 }
        assertEquals(4, com.nikhil.niktv.discovery.components.modernCollectionPosterColumns(tablet, false))
        val tv = android.content.res.Configuration().apply { screenWidthDp = 960; smallestScreenWidthDp = 540 }
        assertEquals(5, com.nikhil.niktv.discovery.components.modernCollectionPosterColumns(tv, true))
    }
}
