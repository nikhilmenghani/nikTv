package com.nikhil.niktv.sports

import com.nikhil.niktv.ui.CricketBadgePosition
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CricketLiveScoresTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/cricket-scores/$name")!!.use { it.readBytes() }
    private fun match() = CricketScoreProtobuf.matches(fixture("live-matches.pb")).first { it.id == 161234 }

    @Test fun decodesAppMatchListingAndSkipsAds() {
        val matches = CricketScoreProtobuf.matches(fixture("live-matches.pb"))
        assertTrue(matches.size > 2)
        val match = match()
        assertEquals("IND vs NZ", match.title)
        assertEquals(118, match.scores.first().runs)
        assertEquals(3, match.scores.first().wickets)
        assertEquals("11.1", match.scores.first().overs)
        assertEquals(matches.size, matches.map { it.id }.distinct().size)
    }

    @Test fun decodesCurrentScoreAndCombinedMilestoneBoundary() {
        val score = CricketScoreProtobuf.score(fixture("commentary.pb"), match())
        assertEquals("IND 123/4 (11.3)", score.innings.first().label)
        assertTrue(score.recentBalls.contains("W"))
        assertTrue(score.events.any { it.title == "WICKET" })
        assertTrue(score.events.any { it.title == "FIFTY! · FOUR!" })
        assertFalse(score.complete)
        assertEquals("W", score.lastDelivery?.value)
        assertEquals("11.3", score.lastDelivery?.over)
        assertTrue(score.lastDelivery?.description?.isNotBlank() == true)
        assertTrue(score.events.none { it.detail.contains("B0$") })
    }

    @Test fun fullWicketCommentaryIsNotCutOffAtFourHundredCharacters() {
        fun varint(value: Long): ByteArray {
            val out = java.io.ByteArrayOutputStream(); var n = value
            while (n > 127) { out.write(((n and 127) or 128).toInt()); n = n ushr 7 }; out.write(n.toInt()); return out.toByteArray()
        }
        fun blob(tag: Int, value: ByteArray) = varint(((tag shl 3) or 2).toLong()) + varint(value.size.toLong()) + value
        fun text(tag: Int, value: String) = blob(tag, value.toByteArray())
        val full = "Caught at long off. " + "The fielder completes the catch. ".repeat(40) + "Final sentence remains readable."
        val line = text(1, full) + varint(16) + varint(200) + varint(25) +
            java.nio.ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putDouble(12.1).array() + text(5, "WICKET")
        val root = blob(3, text(9, "W")) + blob(2, blob(1, line))
        val score = CricketScoreProtobuf.score(root, match())
        assertEquals(full, score.events.single().detail)
        assertEquals(full, score.lastDelivery!!.description)
    }

    @Test fun eventsDoNotReplayOnOpenRepeatedPollOrOutOfOrderHistory() {
        val tracker = CricketScoreEventTracker()
        val old = CricketScoreEvent("old", 100, "FOUR!", "Boundary")
        val wicket = CricketScoreEvent("wicket", 200, "WICKET", "Caught")
        assertTrue(tracker.accept(listOf(old)).isEmpty())
        assertEquals(listOf(wicket), tracker.accept(listOf(wicket, old)))
        assertTrue(tracker.accept(listOf(wicket, old)).isEmpty())
        assertTrue(tracker.accept(listOf(CricketScoreEvent("history", 99, "SIX!", "Six"))).isEmpty())
        val milestone = CricketScoreEvent("milestone", 200, "FIFTY!", "Fifty")
        assertEquals(listOf(milestone), tracker.accept(listOf(wicket, milestone)))
    }

    @Test fun badgePositionClampsAndScalesWithAvailableSpace() {
        assertEquals(CricketBadgePosition(1f, 0f), CricketBadgePosition(.5f, .5f).move(500f, -500f, 100f, 100f))
        assertEquals(CricketBadgePosition(.6f, .6f), CricketBadgePosition(.5f, .5f).move(10f, 20f, 100f, 200f))
        assertEquals(CricketBadgePosition(), CricketBadgePosition(Float.NaN, Float.POSITIVE_INFINITY).safe())
        assertEquals(CricketBadgePosition(.5f, .5f), CricketBadgePosition(.5f, .5f).move(100f, 100f, 0f, 0f))
    }

    @Test fun missingOrMalformedProtobufDoesNotBecomeFakeZeroScore() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(0x1A, 0x7F), byteArrayOf(0x80.toByte()))) {
            assertTrue(runCatching { CricketScoreProtobuf.score(bytes, match()) }.isFailure)
        }
    }

    @Test fun followsAppSuppliedWidgetCadence() {
        assertEquals(CricketScoreTiming(10_000L, 25_000L), CricketScoreProtobuf.timing(fixture("settings.pb")))
        assertEquals(CricketScoreTiming(), CricketScoreProtobuf.timing(byteArrayOf()))
    }

    @Test fun callsCricbuzzAppEndpointsWithExistingAuthentication() = runTest {
        val urls = mutableListOf<String>()
        val direct = CricbuzzDirectClient(CricketHighlightsTransport { url, headers ->
            urls.add(url)
            assertEquals("application/x-protobuf", headers["Accept"])
            assertTrue(headers["Authentication"]!!.startsWith("exp="))
            fixture(if (url.endsWith("/live")) "live-matches.pb" else "commentary.pb")
        }, { CricbuzzCredentials("ab".repeat(32)) })
        val repository = CricketLiveScoresRepository(direct)
        val match = repository.matches().first { it.id == 161234 }
        repository.score(match)
        repository.score(match.copy(hundred = true))
        assertEquals(listOf("https://api.cricbuzz.com/a/matches/v1/live", "https://api.cricbuzz.com/a/mcenter/v1/161234/comm",
            "https://api.cricbuzz.com/a/mcenter/v1/161234/hcomm"), urls)
    }
}
