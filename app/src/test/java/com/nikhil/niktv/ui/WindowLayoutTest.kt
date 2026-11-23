package com.nikhil.niktv.ui

import android.app.Application
import android.content.res.Configuration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class WindowLayoutTest {
    private fun window(width: Int, height: Int, smallest: Int = minOf(width, height)) = Configuration().apply {
        screenWidthDp = width; screenHeightDp = height; smallestScreenWidthDp = smallest
        orientation = if (width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
    }

    @Test fun portraitPhoneUsesPhonePresentationAndLandscapeUsesWideWithoutChangingDeviceIdentity() {
        val portrait = window(360, 800)
        val landscape = window(800, 360)
        assertTrue(portrait.usesPhoneLayout())
        assertFalse(landscape.usesPhoneLayout())
        assertTrue(landscape.usesShortWideLayout())
        assertEquals(portrait.smallestScreenWidthDp, landscape.smallestScreenWidthDp)
        assertEquals(portrait.fontScale, landscape.fontScale, 0f)
        assertTrue(window(592, 360).usesPhoneLayout())
        assertEquals(2, com.nikhil.niktv.discovery.components.modernCollectionPosterColumns(portrait, false))
        assertEquals(4, com.nikhil.niktv.discovery.components.modernCollectionPosterColumns(landscape, false))
    }

    @Test fun tabletsAndTelevisionKeepWidePresentationWhileNarrowPortraitWindowsStayUsable() {
        assertFalse(window(800, 1200).usesPhoneLayout())
        assertFalse(window(1200, 800).usesPhoneLayout())
        assertFalse(window(360, 800).usesPhoneLayout(television = true))
        assertFalse(window(960, 540).usesShortWideLayout(television = true))
        assertTrue(window(450, 700, smallest = 800).usesPhoneLayout())
        assertFalse(window(800, 480).usesShortWideLayout())
    }

    @Test fun measuredWindowWinsOverStaleOrientationAndMissingDimensionsHaveDeterministicFallback() {
        assertFalse(window(800, 360).apply { orientation = Configuration.ORIENTATION_PORTRAIT }.usesPhoneLayout())
        assertTrue(window(360, 800).apply { orientation = Configuration.ORIENTATION_LANDSCAPE }.usesPhoneLayout())
        val missing = Configuration().apply { smallestScreenWidthDp = 360 }
        assertTrue(missing.usesPhoneLayout())
        missing.orientation = Configuration.ORIENTATION_LANDSCAPE
        assertTrue(missing.usesPhoneLayout())
    }
    @Test fun splitScreenAndRotationAlwaysReevaluateCurrentWidth() {
        val current = window(412, 900)
        for ((width, height, phone) in listOf(Triple(900, 412, false), Triple(480, 280, true),
            Triple(412, 900, true), Triple(320, 220, true), Triple(800, 360, false))) {
            current.screenWidthDp = width; current.screenHeightDp = height
            assertEquals("$width x $height", phone, current.usesPhoneLayout())
        }
    }
}
