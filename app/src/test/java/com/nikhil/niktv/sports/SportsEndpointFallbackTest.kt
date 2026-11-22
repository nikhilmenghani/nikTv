package com.nikhil.niktv.sports

import java.io.IOException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SportsEndpointFallbackTest {
    private val categories = """[{"id":"sports","title":"Sports"}]"""
    private val channels = """[{"slug":"willow","title":"Willow"}]"""
    private val builtIn = SportsConfiguration().primary
    private val working = "https://working.example.test/"
    private val broken = "https://broken.example.test/"

    private fun configuration(primary: String, secondary: String = primary, agent: String = "Fixture/1") =
        """{"cric_api2":"$primary","cric_api1":"$secondary","userAgent":"$agent"}"""

    private class Store : SportsEndpointStore {
        val regions = mutableMapOf<String, SportsConfiguration>()
        val writes = mutableListOf<Pair<String, SportsConfiguration>>()
        val history = mutableMapOf<String, List<SportsConfiguration>>()
        val preferences = mutableMapOf<String, String>()
        override fun load(country: String) = regions[country]
        override fun save(country: String, configuration: SportsConfiguration) {
            writes += country to configuration
            regions[country] = configuration
            history[country] = (listOf(configuration) + history[country].orEmpty()).distinctBy { it.primary }.take(8)
        }
        override fun known(country: String) = (listOfNotNull(load(country)) + history[country].orEmpty()).distinctBy { it.primary }
        override fun preferred(country: String) = preferences[country]
        override fun prefer(country: String, base: String?) { if (base == null) preferences.remove(country) else preferences[country] = base }
    }

    @Test fun freshInstallFallsBackFromDuplicateUnresolvableHostsAndReusesTheWorkingHost() = runBlocking {
        val calls = mutableListOf<String>()
        val store = Store()
        val repository = SportsRepository(SportsTransport { url, _ ->
            calls += url
            SportsResponse(url, when {
                url.endsWith("config.txt") -> configuration(broken)
                url.startsWith(broken) -> throw UnknownHostException("Fixture DNS failure")
                url == "${builtIn}cats.txt" -> categories
                url == "${builtIn}categories/sports.txt" -> channels
                else -> error("Unexpected request: $url")
            })
        }, country = { "CA" }, endpointStore = store)

        assertEquals("Sports", repository.categories().single().title)
        assertEquals(builtIn, store.regions["CA"]?.primary)
        assertEquals(SPORTS_USER_AGENT, store.regions["CA"]?.userAgent)
        assertEquals("willow", repository.channels().single().slug)
        assertEquals(1, calls.count { it.startsWith(broken) })
        assertEquals("${builtIn}categories/sports.txt", calls.last())
        assertEquals(1, store.writes.size)
    }

    @Test fun badConfigurationCannotReplaceTheWorkingEndpointAcrossRefreshAndRepositoryRestart() = runBlocking {
        val store = Store()
        var primary = working
        val calls = mutableListOf<String>()
        val transport = SportsTransport { url, headers ->
            calls += url
            SportsResponse(url, when {
                url.endsWith("config.txt") -> configuration(primary, agent = "Working/2")
                url.startsWith(broken) -> throw UnknownHostException("Fixture DNS failure")
                url.startsWith(working) -> {
                    assertEquals("Working/2", headers["User-Agent"])
                    categories
                }
                else -> error("The built-in endpoint must not replace the saved working endpoint")
            })
        }
        val first = SportsRepository(transport, country = { "CA" }, endpointStore = store)
        first.categories()
        primary = broken
        first.categories(refresh = true)
        assertEquals(working, store.regions["CA"]?.primary)

        val restarted = SportsRepository(transport, country = { "CA" }, endpointStore = store)
        assertEquals("Sports", restarted.categories().single().title)
        assertEquals(working + "cats.txt", calls.last())
        assertEquals(1, store.writes.size)
    }

    @Test fun validDownloadedRotationIsPromotedOnlyAfterItsCatalogueDecodes() = runBlocking {
        val store = Store().apply { regions["CA"] = SportsConfiguration(working, userAgent = "Old/1") }
        val rotated = "https://rotated.example.test/"
        val repository = SportsRepository(SportsTransport { url, headers ->
            if (url.endsWith("config.txt")) SportsResponse(url, configuration(rotated, agent = "New/2"))
            else {
                assertEquals(working, store.regions["CA"]?.primary)
                assertEquals(rotated + "cats.txt", url)
                assertEquals("New/2", headers["User-Agent"])
                assertEquals("no-cache", headers["Cache-Control"])
                SportsResponse(url, categories)
            }
        }, country = { "CA" }, endpointStore = store)
        repository.categories(refresh = true)
        assertEquals(SportsConfiguration(rotated, userAgent = "New/2"), store.regions["CA"])
    }

    @Test fun httpSuccessWithWrongCatalogueShapeFallsBackWithoutPoisoningTheSavedEndpoint() = runBlocking {
        val store = Store().apply { regions["CA"] = SportsConfiguration(working) }
        val repository = SportsRepository(SportsTransport { url, _ ->
            SportsResponse(url, when {
                url.endsWith("config.txt") -> configuration(broken)
                url.startsWith(broken) -> "{}"
                url.startsWith(working) -> categories
                else -> error("Unexpected request")
            })
        }, country = { "CA" }, endpointStore = store)
        assertEquals("Sports", repository.categories().single().title)
        assertEquals(working, store.regions["CA"]?.primary)
        assertTrue(store.writes.isEmpty())
    }

    @Test fun unavailableBootstrapStillUsesThePersistedEndpoint() = runBlocking {
        val store = Store().apply { regions["CA"] = SportsConfiguration(working) }
        val repository = SportsRepository(SportsTransport { url, _ ->
            if (url.endsWith("config.txt")) throw IOException("Fixture bootstrap outage")
            assertEquals(working + "cats.txt", url)
            SportsResponse(url, categories)
        }, country = { "CA" }, endpointStore = store)
        assertEquals("Sports", repository.categories().single().title)
    }

    @Test fun completeOutageIsBoundedAndKeepsTheSavedEndpoint() = runBlocking {
        val store = Store().apply { regions["CA"] = SportsConfiguration(working) }
        val calls = mutableListOf<String>()
        val repository = SportsRepository(SportsTransport { url, _ ->
            calls += url
            if (url.endsWith("config.txt")) SportsResponse(url, configuration(broken))
            else throw IOException("Fixture outage")
        }, country = { "CA" }, endpointStore = store)
        try { repository.categories(); fail("An outage should remain an error") } catch (_: IOException) { }
        assertEquals(2, calls.count { it.endsWith("config.txt") })
        assertEquals(listOf(broken + "cats.txt", working + "cats.txt", builtIn + "cats.txt"),
            calls.filterNot { it.endsWith("config.txt") })
        assertEquals(working, store.regions["CA"]?.primary)
        assertTrue(store.writes.isEmpty())
    }

    @Test fun cancellationDoesNotTryAnotherHostOrReplaceTheSavedEndpoint() = runBlocking {
        val store = Store().apply { regions["CA"] = SportsConfiguration(working) }
        val calls = mutableListOf<String>()
        val repository = SportsRepository(SportsTransport { url, _ ->
            calls += url
            if (url.endsWith("config.txt")) SportsResponse(url, configuration(broken))
            else throw CancellationException("Fixture navigation away")
        }, country = { "CA" }, endpointStore = store)
        try { repository.categories(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(2, calls.size)
        assertEquals(working, store.regions["CA"]?.primary)
        assertTrue(store.writes.isEmpty())
    }

    @Test fun changingRegionNeverReusesAnotherRegionsSavedConfiguration() = runBlocking {
        val india = "https://india.example.test/"
        val store = Store().apply {
            regions["CA"] = SportsConfiguration(working)
            regions["IN"] = SportsConfiguration(india)
        }
        var region = "CA"
        val calls = mutableListOf<String>()
        val repository = SportsRepository(SportsTransport { url, _ ->
            calls += url
            if (url.endsWith("config.txt")) throw IOException("Fixture bootstrap outage")
            SportsResponse(url, categories)
        }, country = { region }, endpointStore = store)
        repository.categories()
        region = "IN"
        repository.categories()
        assertEquals(india + "cats.txt", calls.last())
        assertEquals(1, calls.count { it.startsWith(working) })
        assertTrue(store.writes.isEmpty())
    }

    @Test fun checksAllKnownServersWithoutSelectingThemOrReplacingTheWorkingEndpoint() = runBlocking {
        val previous = "https://previous.example.test/"
        val store = Store().apply {
            regions["CA"] = SportsConfiguration(working)
            history["CA"] = listOf(SportsConfiguration(previous))
        }
        val repository = SportsRepository(SportsTransport { url, _ ->
            SportsResponse(url, when {
                url.endsWith("config.txt") -> configuration(broken, secondary = working)
                url.startsWith(broken) -> throw UnknownHostException("Fixture DNS")
                else -> categories
            })
        }, country = { "CA" }, endpointStore = store)
        repository.checkServers()
        val state = repository.servers.value
        assertFalse(state.checking)
        assertNull(state.active)
        assertNull(state.preferred)
        assertEquals(setOf(broken, working, previous, builtIn), state.servers.map { it.base }.toSet())
        assertEquals(SportsServerHealth.UNAVAILABLE, state.servers.single { it.base == broken }.health)
        assertTrue(state.servers.filter { it.base != broken }.all { it.health == SportsServerHealth.AVAILABLE })
        assertTrue(store.writes.isEmpty())
        assertEquals(working, store.regions["CA"]?.primary)
    }

    @Test fun preferredServerSurvivesRestartAndAutomaticFollowsTheNextServiceRotation() = runBlocking {
        val chosen = "https://chosen.example.test/"
        val next = "https://next.example.test/"
        val store = Store()
        var primary = working
        val transport = SportsTransport { url, _ -> SportsResponse(url, if (url.endsWith("config.txt"))
            configuration(primary, secondary = chosen) else categories) }
        val first = SportsRepository(transport, country = { "CA" }, endpointStore = store)
        first.categories()
        first.selectServer(chosen)
        first.categories(refresh = true)
        assertEquals(chosen, first.servers.value.active)
        primary = next
        val restarted = SportsRepository(transport, country = { "CA" }, endpointStore = store)
        restarted.categories()
        assertEquals(chosen, restarted.servers.value.preferred)
        assertEquals(chosen, restarted.servers.value.active)
        restarted.selectServer(null)
        restarted.categories(refresh = true)
        assertNull(restarted.servers.value.preferred)
        assertEquals(next, restarted.servers.value.active)
        assertNull(store.preferred("CA"))
        assertTrue(restarted.servers.value.servers.any { it.base == working })
    }

    @Test fun failedPreferredServerFallsBackWithoutOverwritingTheUsersPreference() = runBlocking {
        val store = Store().apply { regions["CA"] = SportsConfiguration(working); preferences["CA"] = broken }
        val repository = SportsRepository(SportsTransport { url, _ ->
            SportsResponse(url, when {
                url.endsWith("config.txt") -> configuration(broken)
                url.startsWith(broken) -> throw UnknownHostException("Fixture DNS")
                url.startsWith(working) -> categories
                else -> error("Unexpected request")
            })
        }, country = { "CA" }, endpointStore = store)
        assertEquals("Sports", repository.categories().single().title)
        assertEquals(broken, repository.servers.value.preferred)
        assertEquals(working, repository.servers.value.active)
        assertEquals(broken, store.preferred("CA"))
        assertTrue(store.writes.isEmpty())
    }

    @Test fun checkingMalformedResponsesDoesNotMakeThemAvailableOrPromoteThem() = runBlocking {
        val store = Store().apply { regions["CA"] = SportsConfiguration(working) }
        val repository = SportsRepository(SportsTransport { url, _ ->
            SportsResponse(url, when {
                url.endsWith("config.txt") -> configuration(broken)
                url.startsWith(broken) -> "{}"
                else -> categories
            })
        }, country = { "CA" }, endpointStore = store)
        repository.checkServers()
        assertEquals(SportsServerHealth.UNAVAILABLE, repository.servers.value.servers.single { it.base == broken }.health)
        assertTrue(store.writes.isEmpty())
        try { repository.selectServer("https://unlisted.example.test/"); fail("Only listed servers can be selected") }
        catch (_: IllegalArgumentException) { }
        assertNull(repository.servers.value.preferred)
    }

    @Test fun cancellingChecksResetsCheckingStateAndDoesNotChangeServerChoice() = runBlocking {
        val repository = SportsRepository(SportsTransport { url, _ ->
            if (url.endsWith("config.txt")) SportsResponse(url, configuration(working))
            else throw CancellationException("Fixture close")
        }, country = { "CA" })
        try { repository.checkServers(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertFalse(repository.servers.value.checking)
        assertTrue(repository.servers.value.servers.none { it.health == SportsServerHealth.CHECKING })
        assertNull(repository.servers.value.preferred)
    }
}
