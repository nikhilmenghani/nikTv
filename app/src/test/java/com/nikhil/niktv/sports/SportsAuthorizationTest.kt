package com.nikhil.niktv.sports

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SportsAuthorizationTest {
    private val old = SportsStream("https://media.example.test/live.m3u8?token=old", "Selected")
    private val fresh = old.copy(link = "https://media.example.test/live.m3u8?token=fresh")
    private val other = old.copy(link = "https://media.example.test/other.m3u8", title = "Other")

    @Test fun authorizationRefreshRetainsSelectedSourceAcrossReorderingAndStopsAfterOneRenewal() = runTest {
        var refreshes = 0
        val controller = SportsPlaybackController(this, { refreshes++; listOf(other, fresh) }, { it })
        controller.start("match", listOf(old, other)); runCurrent()
        val rejected = controller.state.value.attemptId
        controller.authorizationFailed(rejected); runCurrent()
        assertEquals(1, refreshes)
        assertEquals(fresh, controller.state.value.current)
        assertEquals(1, controller.state.value.currentIndex)
        assertEquals(2, controller.state.value.attempts)
        controller.authorizationFailed(rejected); runCurrent()
        assertEquals(1, refreshes)
        controller.authorizationFailed(controller.state.value.attemptId); runCurrent()
        assertEquals(1, refreshes)
        assertEquals(fresh, controller.state.value.current)
        assertEquals(SPORTS_AUTHORIZATION_MESSAGE, controller.state.value.error)
        controller.retry(); runCurrent()
        assertEquals("Only explicit retry starts a new renewal budget", 2, refreshes)
        assertNull(controller.state.value.error)
        controller.stop()
    }

    @Test fun unchangedRejectedCredentialsStopWithoutChoosingAnotherBroadcastOrRestartingPlayer() = runTest {
        var refreshes = 0
        val controller = SportsPlaybackController(this, { refreshes++; listOf(old.copy(title = "Renamed"), other) }, { it })
        controller.start("match", listOf(old, other)); runCurrent()
        val attempt = controller.state.value.attemptId
        controller.authorizationFailed(attempt); runCurrent()
        assertEquals(1, refreshes)
        assertEquals(attempt, controller.state.value.attemptId)
        assertEquals(old, controller.state.value.current)
        assertEquals(0, controller.state.value.currentIndex)
        assertEquals(SPORTS_AUTHORIZATION_MESSAGE, controller.state.value.error)
        repeat(3) { controller.authorizationFailed(attempt); controller.failed(attempt); runCurrent() }
        assertEquals(1, refreshes)
        controller.stop()
    }

    @Test fun unchangedWebSourceIsResolvedAgainToObtainANewToken() = runTest {
        val page = old.copy(link = "https://player.example.test/selected", type = 4)
        var token = "old"
        val controller = SportsPlaybackController(this, { token = "fresh"; listOf(page) }, {
            it.copy(link = "https://media.example.test/live.m3u8?token=$token")
        })
        controller.start("match", listOf(page)); runCurrent()
        controller.authorizationFailed(controller.state.value.attemptId); runCurrent()
        assertTrue(controller.state.value.current!!.link.endsWith("token=fresh"))
        assertNull(controller.state.value.error)
        controller.stop()
    }

    @Test fun backgroundCancellationDiscardsLateAuthorizationMetadata() = runTest {
        val metadata = CompletableDeferred<List<SportsStream>>()
        val controller = SportsPlaybackController(this, { withContext(NonCancellable) { metadata.await() } }, { it })
        controller.start("match", listOf(old)); runCurrent()
        val attempt = controller.state.value.attemptId
        controller.authorizationFailed(attempt); runCurrent()
        controller.cancelPendingFallback()
        metadata.complete(listOf(fresh)); runCurrent()
        assertEquals(old, controller.state.value.current)
        assertEquals(attempt, controller.state.value.attemptId)
        assertFalse(controller.state.value.resolving)
        controller.stop()
    }

    @Test fun expiredProviderLinkRefreshesBeforeAnyPlayerCommitAndReportsPersistentExpiry() = runTest {
        val expired = old.copy(link = "https://media.example.test/live.m3u8?hdnea=exp%3D1~hmac%3Dfixture")
        var returned = fresh
        var refreshes = 0
        val resolver = SportsStreamResolver(SportsTransport { _, _ -> error("No media request during resolution") }) { error("No web resolver") }
        val controller = SportsPlaybackController(this, { refreshes++; listOf(returned) }, resolver::resolve)
        controller.start("match", listOf(expired)); runCurrent()
        assertEquals(fresh, controller.state.value.current)
        assertEquals(1, refreshes)
        controller.stop()
        returned = expired
        controller.start("match", listOf(expired)); runCurrent()
        assertNull(controller.state.value.current)
        assertEquals(SPORTS_EXPIRED_LINK_MESSAGE, controller.state.value.error)
        assertEquals(2, refreshes)
        assertFalse(controller.state.value.resolving)
        controller.stop()
    }

    @Test fun parsesOnlyRecognizedSignedExpiryAndKeepsTokenBytesUntouched() {
        val url = "https://media.example.test/live.m3u8?hdnea=st%3D10~exp%3D20~hmac%3Dfixture%2Bvalue"
        assertFalse(sportsSignedLinkExpired(url, 19_999))
        assertTrue(sportsSignedLinkExpired(url, 20_000))
        assertTrue(sportsSignedLinkExpired(url.replace("hdnea", "hdnts"), 21_000))
        assertFalse(sportsSignedLinkExpired("https://media.example.test/live.m3u8?exp=1", 21_000))
        assertFalse(sportsSignedLinkExpired(url.replace("exp%3D20", "exp%3Dinvalid"), 21_000))
    }
}
