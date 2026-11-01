package com.nikhil.niktv.ui

import com.nikhil.niktv.sports.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], application = android.app.Application::class)
class CricketScoreReadingTest {
    private lateinit var controller: CricketScoreController
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("cricket_score_overlay", 0).edit().clear().commit()
        controller = CricketScoreController(context)
        controller.select(CricketLiveMatch(1, "IND vs AUS", "", "", "In Progress"))
    }
    private fun score(id: String, runs: Int, text: String) = CricketLiveScore(1,
        listOf(CricketInnings(1, "IND", runs, 2, "12.1")), "", "In Progress", "W", emptyList(),
        CricketDelivery(id, "W", "12.1", text))

    @Test fun highlightReadingSurvivesNewDeliveriesUntilExplicitRefreshOrDismissal() {
        val old = score("old", 100, "Wicket: " + "Full description. ".repeat(80))
        controller.receivedScore(old, listOf(CricketScoreEvent("wicket", 100, "WICKET", old.lastDelivery!!.description)))
        val reading = controller.reading!!
        val newer = score("next", 101, "The next delivery is a single.")
        controller.receivedScore(newer, listOf(CricketScoreEvent("new-event", 200, "FOUR!", "Newer highlight")))
        assertEquals(newer, controller.score)
        assertEquals(reading, controller.reading)
        assertTrue(controller.reading!!.text.length > 400)
        controller.refreshTile()
        assertEquals(reading, controller.reading)
        controller.receivedScore(newer, emptyList())
        assertEquals(newer.lastDelivery!!.description, controller.reading!!.text)
        assertFalse(controller.readingRefreshRequested)
        controller.dismissReading()
        assertNull(controller.reading)
    }

    @Test fun lastBallCanBeOpenedWithoutHighlightAndFailedRefreshKeepsTheReading() {
        val current = score("ball", 100, "A dot ball outside off stump.")
        controller.receivedScore(current, emptyList())
        assertNull(controller.reading)
        controller.showCommentary()
        val reading = controller.reading
        controller.refreshTile(); controller.refreshFailed()
        assertEquals(reading, controller.reading)
        assertFalse(controller.readingRefreshRequested)
        controller.stop()
        assertNull(controller.reading)
    }
}
