package com.nikhil.niktv.discovery

import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class WatchProvidersTest {
    private fun card(id: Int, date: String? = "2020-01-01") = DiscoveryCard(id, false, "Title $id", date = date)
    private class Source : WatchProvidersSource {
        var calls = 0
        var requests = mutableListOf<String>()
        var result: suspend (String, Boolean, Int, Int) -> WatchProviderPage = { _, _, _, p -> WatchProviderPage(listOf(DiscoveryCard(p, false, "Film", date = "2020-01-01")), p, 2) }
        override suspend fun regions() = listOf(WatchRegion("IN", "India"))
        override suspend fun providers(region: String, series: Boolean, refresh: Boolean) = listOf(WatchProvider(1, "Fixture"))
        override suspend fun titles(region: String, series: Boolean, provider: Int, page: Int, stale: (WatchProviderPage) -> Unit, refresh: Boolean): WatchProviderPage {
            calls++; requests += "$region:$series:$provider:$page"; return result(region, series, provider, page)
        }
    }
    @Test fun requestSeparatesRegionTypeAndIncludesSubscriptionsWithReleasedDates() {
        val (path, query) = watchProviderRequest("IN", true, 8, 2, "2026-10-28")
        assertEquals("/discover/tv", path); assertEquals("IN", query["watch_region"])
        assertEquals("8", query["with_watch_providers"]); assertEquals("flatrate|free|ads", query["with_watch_monetization_types"])
        assertEquals("2026-10-28", query["first_air_date.lte"]); assertEquals("false", query["include_adult"])
        assertEquals("/discover/movie", watchProviderRequest("CA", false, 9, 1).first)
    }
    @Test fun providersAreDeduplicatedAndMalformedRecordsSkipped() {
        val root = Json.parseToJsonElement("""{"results":[{"provider_id":8,"provider_name":"Netflix"},{"provider_id":8,"provider_name":"Netflix"},{"provider_id":9},{"provider_id":0,"provider_name":"Invalid"}]}""").jsonObject
        assertEquals(listOf(WatchProvider(8, "Netflix")), parseWatchProviders(root))
    }
    @Test fun openingTabDoesNotFetchAnyTitlesAndMatchingLoadsDeduplicate() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.result = { _, _, _, p -> gate.await(); WatchProviderPage(listOf(card(1)), p, 1) }
        val model = WatchProvidersModel(scope, source); model.select()
        assertEquals(0, source.calls)
        model.load(1); model.load(1); assertEquals(1, source.calls)
        gate.complete(Unit); model.load(1); assertEquals(1, source.calls)
        assertEquals(1, model.state.value.rows[1]?.cards?.size); scope.cancel()
    }
    @Test fun changingRegionRejectsLateOldResponseAndKeepsTypeIndependent() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.result = { region, _, _, p -> if (region == "IN") withContext(NonCancellable) { gate.await() }; WatchProviderPage(listOf(card(if (region == "IN") 1 else 2)), p, 1) }
        val model = WatchProvidersModel(scope, source); model.select(); model.load(1)
        model.select("CA", true); model.load(1); gate.complete(Unit)
        assertEquals("CA", model.state.value.region); assertTrue(model.state.value.series)
        assertEquals(2, model.state.value.rows[1]?.cards?.single()?.id); scope.cancel()
    }
    @Test fun paginationRetainsUniqueReleasedTitlesAndFailuresCanRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val source = Source()
        var fail = true
        source.result = { _, _, _, p -> if (fail) throw IllegalStateException("offline")
            WatchProviderPage(listOf(card(1), card(p + 1), card(90, "2999-01-01"), card(91, null)), p, 2) }
        val model = WatchProvidersModel(scope, source); model.select(); model.load(1)
        assertNotNull(model.state.value.rows[1]?.error)
        fail = false; model.load(1); model.load(1, true)
        assertEquals(listOf(1, 2, 3), model.state.value.rows[1]?.cards?.map { it.id })
        assertFalse(model.state.value.rows[1]!!.more); scope.cancel()
    }
    @Test fun offscreenLoadsCancelAndRetainedRowMetadataIsBounded() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val source = Source()
        val model = WatchProvidersModel(scope, source, retainedRows = 3); model.select()
        (1..20).forEach { model.load(it) }
        assertEquals(setOf(18, 19, 20), model.state.value.rows.keys)
        source.result = { _, _, _, _ -> awaitCancellation() }
        model.load(21); model.leave(21)
        assertFalse(model.state.value.rows[21]!!.loading)
        model.stop(); scope.cancel()
    }
    @Test fun canceledRowCannotOverwriteItsNewerRetry() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined); val source = Source()
        val old = CompletableDeferred<Unit>(); var attempt = 0
        source.result = { _, _, _, p -> val n = ++attempt
            if (n == 1) withContext(NonCancellable) { old.await() }
            WatchProviderPage(listOf(card(n)), p, 1)
        }
        val model = WatchProvidersModel(scope, source); model.select(); model.load(1)
        model.leave(1); model.load(1); old.complete(Unit)
        assertEquals(2, model.state.value.rows[1]!!.cards.single().id)
        scope.cancel()
    }

}
