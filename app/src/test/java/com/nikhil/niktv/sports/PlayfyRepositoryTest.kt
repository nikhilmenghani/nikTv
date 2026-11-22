package com.nikhil.niktv.sports

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlayfyRepositoryTest {
    private val categories = """[{"id":"1","title":"Sports"}]"""
    private val channels = """[{"id":"380","title":"Synthetic channel"}]"""
    private val choices = """[{"id":1,"title":"Synthetic source","link":"https://media.example.test/live.m3u8","type":"0"}]"""
    private val events = """{"channels":[{"id":"5","title":"Synthetic event","eventInfo":{}}],"cats":[{"id":"1","title":"Cricket"}]}"""
    private val old = "https://retained.example.test/"
    private val next = "https://rotation.example.test/"
    private val broken = "https://unavailable.example.test/"

    private class Store : SportsEndpointStore {
        val working = mutableMapOf<String, SportsConfiguration>()
        val history = mutableMapOf<String, List<SportsConfiguration>>()
        val preferred = mutableMapOf<String, String>()
        val saves = mutableListOf<Pair<String, SportsConfiguration>>()
        override fun load(country: String) = working[country]
        override fun known(country: String) = history[country].orEmpty()
        override fun preferred(country: String) = preferred[country]
        override fun prefer(country: String, base: String?) { if (base == null) preferred.remove(country) else preferred[country] = base }
        override fun save(country: String, configuration: SportsConfiguration) {
            saves += country to configuration
            working[country] = configuration
            history[country] = (listOf(configuration) + history[country].orEmpty()).distinctBy { it.primary }.take(8)
        }
    }

    private suspend fun rejects(action: suspend () -> Unit) {
        try { action(); fail("Expected SportsException") } catch (_: SportsException) { }
    }

    @Test fun catalogueUsesVerifiedPlayfyRoutesWithoutCricfyBootstrap() = runBlocking {
        val calls = mutableListOf<String>()
        val repository = PlayfyRepository(SportsTransport { url, _ ->
            calls += url
            SportsResponse(url, when (url.removePrefix(PLAYFY_DEFAULT_BASE)) {
                "cc.json" -> categories
                "cats/sports.json" -> channels
                "channels/380.json" -> choices
                "cats/live-events.json" -> events
                "cats/highlights.json" -> events
                else -> error("Unexpected request $url")
            })
        }, country = { "CA" }, credential = { "" })
        val category = repository.categories(false).single()
        val channel = repository.channels(category, false).single()
        assertEquals("playfy-380", channel.slug)
        assertEquals("playfy-380", channel.key)
        assertEquals(1, repository.detail(channel.slug, false).streams.size)
        assertEquals("playfy-5", repository.events(false).single().slug)
        assertEquals("Cricket", repository.eventFilters(false).single().title)
        assertEquals(1, repository.highlights(false).size)
        assertEquals(setOf("cc.json", "cats/sports.json", "channels/380.json", "cats/live-events.json", "cats/highlights.json"),
            calls.map { it.removePrefix(PLAYFY_DEFAULT_BASE) }.toSet())
        assertTrue(calls.all { it.startsWith(PLAYFY_DEFAULT_BASE) && !it.endsWith(".txt") })
    }

    @Test fun unnamespacedOrNonnumericChannelIdsNeverReachTheNetwork() = runBlocking {
        var requests = 0
        val repository = PlayfyRepository(SportsTransport { _, _ -> requests++; error("No request expected") }, credential = { "" })
        for (slug in listOf("380", "willow", "playfy-willow", "playfy-../380", "playfy-380?x=1")) rejects { repository.detail(slug, false) }
        assertEquals(0, requests)
    }

    @Test fun regionalCategoriesChannelsFormatsAndStreamsAreFiltered() = runBlocking {
        val repository = PlayfyRepository(SportsTransport { url, _ -> SportsResponse(url, when {
            url.endsWith("cc.json") -> """[{"id":"1","title":"Sports","geo":"CA"},{"id":"2","title":"India","geo":"IN"}]"""
            url.endsWith("cats/sports.json") -> """[{"id":"1","title":"Canadian","geo":"CA","formats":["Local","India"],"formatGeos":["CA","IN"]},{"id":"2","title":"India","geo":"IN"}]"""
            else -> """[{"title":"Local","link":"https://media.example.test/ca.m3u8","geo":"CA"},{"title":"India","link":"https://media.example.test/in.m3u8","geo":"IN"}]"""
        }) }, country = { "CA" }, credential = { "" })
        val category = repository.categories(false).single()
        val channel = repository.channels(category, false).single()
        assertEquals(listOf("Local"), channel.formats.map { it.title })
        assertEquals(listOf("Local"), repository.detail(channel.slug, false).streams.map { it.title })
    }

    @Test fun malformedHttpSuccessCannotBecomeTheSavedWorkingEndpoint() = runBlocking {
        val store = Store()
        val calls = mutableListOf<String>()
        val repository = PlayfyRepository(SportsTransport { url, _ ->
            calls += url
            SportsResponse(url, if (url.startsWith(broken)) """{"error":"not a catalogue"}""" else categories)
        }, store, { "CA" }, { if (it == "NIKTV_PLAYFY_API_URL") broken else "" })
        assertEquals("Sports", repository.categories(false).single().title)
        assertEquals(listOf(broken + "cc.json", PLAYFY_DEFAULT_BASE + "cc.json"), calls)
        assertEquals(listOf(PLAYFY_DEFAULT_BASE), store.saves.map { it.second.primary })
        assertEquals(SportsServerHealth.UNAVAILABLE, repository.servers.value.servers.first { it.base == broken }.health)
    }

    @Test fun unavailablePreferredEndpointFallsBackAndRetainsThePreference() = runBlocking {
        val store = Store().apply { working["CA"] = SportsConfiguration(old); preferred["CA"] = broken }
        val calls = mutableListOf<String>()
        fun repository() = PlayfyRepository(SportsTransport { url, _ ->
            calls += url
            if (url.startsWith(broken)) throw IOException("Synthetic outage")
            SportsResponse(url, categories)
        }, store, { "CA" }, { "" })
        val first = repository()
        first.categories(false)
        assertEquals(broken, first.servers.value.preferred)
        assertEquals(old, first.servers.value.active)
        assertEquals(broken, store.preferred["CA"])
        repository().categories(false)
        assertEquals(listOf(broken + "cc.json", old + "cc.json", broken + "cc.json", old + "cc.json"), calls)
    }

    @Test fun refreshPromotesConfiguredRotationAndPersistsBothWorkingEndpoints() = runBlocking {
        val store = Store().apply { working["CA"] = SportsConfiguration(old); history["CA"] = listOf(SportsConfiguration(old)) }
        val calls = mutableListOf<String>()
        fun repository() = PlayfyRepository(SportsTransport { url, _ -> calls += url; SportsResponse(url, categories) },
            store, { "CA" }, { if (it == "NIKTV_PLAYFY_API_URL") next else "" })
        val first = repository()
        first.categories(false)
        first.categories(true)
        assertEquals(listOf(old + "cc.json", next + "cc.json"), calls)
        assertEquals(next, store.working["CA"]?.primary)
        assertEquals(listOf(next, old), store.history["CA"]?.map { it.primary })
        repository().categories(false)
        assertEquals(next + "cc.json", calls.last())
    }

    @Test fun healthChecksDoNotPromoteEndpointsOrPopulateCatalogueCache() = runBlocking {
        val store = Store().apply { working["CA"] = SportsConfiguration(old) }
        val calls = mutableListOf<String>()
        val repository = PlayfyRepository(SportsTransport { url, _ -> calls += url; SportsResponse(url, categories) }, store, { "CA" }, { "" })
        repository.checkServers()
        assertFalse(repository.servers.value.checking)
        assertNull(repository.servers.value.active)
        assertTrue(repository.servers.value.servers.all { it.health == SportsServerHealth.AVAILABLE })
        assertTrue(store.saves.isEmpty())
        val checked = calls.size
        repository.categories(false)
        assertEquals(checked + 1, calls.size)
        assertEquals(old + "cc.json", calls.last())
    }

    @Test fun cancelledHealthCheckStopsFallbackAndRestoresCheckingState() = runBlocking {
        val store = Store()
        var calls = 0
        val repository = PlayfyRepository(SportsTransport { _, _ -> calls++; throw CancellationException("Synthetic cancellation") }, store, { "CA" }, { "" })
        try { repository.checkServers(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(1, calls)
        assertTrue(store.saves.isEmpty())
        assertFalse(repository.servers.value.checking)
        assertTrue(repository.servers.value.servers.none { it.health == SportsServerHealth.CHECKING })
    }
}
