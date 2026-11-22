package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VlcCaptionProjectionTest {
    @Test fun controlsReserveBothEdgesWithoutClippingAuthoredTopOrBottom() {
        val fit = fitVlcCaptionPlane(1920, 1080, 0f, 100f, 2400f, 680f)
        assertEquals(100f, fit.top, .001f)
        assertEquals(780f, fit.top + 1080f * fit.scale, .001f)
        assertEquals(1200f, fit.left + 960f * fit.scale, .001f)
        assertTrue(fit.scale > .6f)
    }

    @Test fun portraitPlaneIsFittedByWidthWithItsAspectIntact() {
        val fit = fitVlcCaptionPlane(1920, 1080, 20f, 50f, 680f, 900f)
        assertEquals(20f, fit.left, .001f)
        assertEquals(680f, 1920f * fit.scale, .001f)
        assertTrue(fit.top + 1080f * fit.scale <= 950f)
    }

    @Test fun hidingControlsRestoresTheFullPlane() {
        val fit = fitVlcCaptionPlane(1920, 1080, 0f, 0f, 1920f, 1080f)
        assertEquals(VlcCaptionProjection(0f, 0f, 1f), fit)
    }

    @Test fun shortViewportKeepsEveryAuthoredPositionInside() {
        val fit = fitVlcCaptionPlane(1280, 720, 0f, 48f, 1280f, 220f)
        for (y in listOf(0f, 36f, 360f, 684f, 720f)) {
            assertTrue(fit.top + y * fit.scale in 48f..268f)
        }
    }
}
