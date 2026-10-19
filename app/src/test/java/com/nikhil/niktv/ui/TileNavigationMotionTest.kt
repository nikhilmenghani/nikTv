package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TileNavigationMotionTest {
    @Test fun visibleRowDoesNotMove() = assertEquals(0f, tileRowRevealDistance(110, 120, 0, 500))
    @Test fun clippedBottomRevealsOnlyTheMissingPart() = assertEquals(35f, tileRowRevealDistance(415, 120, 0, 500))
    @Test fun clippedTopRevealsOnlyTheMissingPart() = assertEquals(-25f, tileRowRevealDistance(-25, 120, 0, 500))
    @Test fun nextOffscreenRowDoesNotJumpToTop() = assertEquals(160f, tileRowRevealDistance(540, 120, 0, 500))
    @Test fun oversizedRowDoesNotOscillate() = assertEquals(0f, tileRowRevealDistance(-20, 600, 0, 500))
    @Test fun contentPaddingIsRespected() = assertEquals(-10f, tileRowRevealDistance(10, 120, 20, 500))
}
