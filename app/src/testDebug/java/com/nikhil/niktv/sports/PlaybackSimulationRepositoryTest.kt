package com.nikhil.niktv.sports

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PlaybackSimulationRepositoryTest {
    private fun repository() = PlaybackSimulationRepository(RuntimeEnvironment.getApplication())

    @Test fun normalEntryLoadsBothFixturesAndResolvesPublicSamples() = runBlocking {
        val stages = mutableListOf<String>()
        val repository = repository()
        val events = repository.catalogue(stages::add)
        assertEquals(listOf("shaka-live", "angel-one"), events.map { it.id })
        val live = repository.resolve(events[0], events[0].sources.single(), stages::add)
        val video = repository.resolve(events[1], events[1].sources.single(), stages::add)
        assertEquals("https://storage.googleapis.com/shaka-live-assets/player-source.mpd", live.link)
        assertTrue(live.isLive)
        assertEquals("https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd", video.link)
        assertFalse(video.isLive)
        assertTrue(live.headers.isEmpty())
        assertEquals("", live.drmType)
        assertEquals(2, stages.count { it == "Local Base64 fixture decoded" })
    }

    @Test fun unknownEventAndSourceFailWithoutTrustingCallerData() = runBlocking {
        val repository = repository()
        val event = repository.catalogue {}.first()
        for ((selected, source) in listOf(
            event.copy(id = "missing") to event.sources.single(),
            event to PlaybackSimulationSource("missing", "Unknown source"),
        )) {
            try {
                repository.resolve(selected, source) {}
                fail("Expected an invalid fixture selection to fail")
            } catch (expected: SportsException) {
                assertTrue(expected.message.orEmpty().isNotBlank())
            }
        }
    }

    @Test fun retryReloadsTheFixtureAndPreservesItsIdentity() = runBlocking {
        val repository = repository()
        val event = repository.catalogue {}.last()
        val source = event.sources.single()
        val first = repository.resolve(event, source) {}
        val retry = repository.resolve(event.copy(isLive = true), source.copy(title = "Caller title")) {}
        assertEquals(first, retry)
        assertFalse(retry.isLive)
        assertEquals("DASH video sample", retry.title)
    }
}
