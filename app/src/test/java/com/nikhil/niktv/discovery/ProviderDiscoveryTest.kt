package com.nikhil.niktv.discovery

import android.app.Application
import android.os.Looper
import com.nikhil.niktv.model.*
import com.nikhil.niktv.data.ProfileStore
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class ProviderDiscoveryTest {
    private fun hydration(vm: ProviderDiscoveryViewModel): Job = ProviderDiscoveryViewModel::class.java
        .getDeclaredField("diskJob").apply { isAccessible = true }.get(vm) as Job
    private fun bindWindow(vm: ProviderDiscoveryViewModel, window: Set<String>, profile: String = "window") {
        vm.bind(session(profile), mapOf(CatalogType.MOVIES to listOf("a", "b", "c").map(::category)), 60_000,
            preview = true, initialPreviewWindow = mapOf(CatalogType.MOVIES to window))
        waitFor { vm.state.value.ready }
    }
    @Test fun previewWindowRetainsRowsAndSelectionWithoutRebindingFreshCategories() {
        val reads = mutableListOf<List<String>>()
        val source = object : Source() {
            override suspend fun cached(profile: String, type: CatalogType, categories: List<Category>, preview: Boolean): Map<String, ProviderDiscoveryPage> {
                reads += categories.map { it.id }
                return categories.associate { it.id to ProviderDiscoveryPage(listOf(item(it.id, category = it.id)), 1, false, System.currentTimeMillis()) }
            }
        }
        val vm = ProviderDiscoveryViewModel(app, source)
        bindWindow(vm, setOf("a"))
        val identity = vm.state.value.identity
        val a = vm.state.value.pages.getValue(CatalogType.MOVIES).getValue("a")
        vm.updatePreviewWindow(mapOf(CatalogType.MOVIES to setOf("b")))
        waitFor { vm.state.value.pages[CatalogType.MOVIES]?.containsKey("b") == true }
        assertEquals(identity, vm.state.value.identity)
        assertEquals(listOf("a", "b", "c"), vm.state.value.categories.getValue(CatalogType.MOVIES).map { it.id })
        assertSame(a, vm.state.value.pages.getValue(CatalogType.MOVIES).getValue("a"))
        vm.updatePreviewWindow(mapOf(CatalogType.MOVIES to setOf("a")))
        waitFor { vm.state.value.loading.isEmpty() }
        assertEquals(listOf(listOf("a"), listOf("b")), reads)
        assertTrue(source.calls.isEmpty())
        vm.pause(release = true)
    }
    @Test fun failedWindowCacheReadPreservesOtherRowsAndAllowsNormalProviderAttempt() {
        val source = object : Source() {
            override suspend fun cached(profile: String, type: CatalogType, categories: List<Category>, preview: Boolean): Map<String, ProviderDiscoveryPage> {
                if (categories.any { it.id == "b" }) error("cache unavailable")
                return mapOf("a" to ProviderDiscoveryPage(listOf(item("kept")), 1, false, System.currentTimeMillis()))
            }
        }
        val vm = ProviderDiscoveryViewModel(app, source); bindWindow(vm, setOf("a"))
        vm.updatePreviewWindow(mapOf(CatalogType.MOVIES to setOf("b")))
        waitFor { hydration(vm).isCompleted }
        vm.load(CatalogType.MOVIES, categoryIds = setOf("b"))
        waitFor { source.saved.isNotEmpty() }
        assertEquals(listOf("b" to 1), source.calls)
        assertEquals("kept", vm.state.value.pages.getValue(CatalogType.MOVIES).getValue("a").items.single().id)
        assertTrue(vm.state.value.pages.getValue(CatalogType.MOVIES).containsKey("b"))
        assertTrue(vm.state.value.errors.isEmpty())
        vm.pause(release = true)
    }
    @Test fun failedObsoleteWindowDoesNotBlockDemandInTheNewWindow() {
        val source = object : Source() {
            override suspend fun page(session: PortalSession, category: Category, page: Int): PortalCatalogPage {
                if (category.id == "a") error("offline")
                return super.page(session, category, page)
            }
        }
        val vm = ProviderDiscoveryViewModel(app, source); bindWindow(vm, setOf("a"))
        vm.load(CatalogType.MOVIES, categoryIds = setOf("a"))
        waitFor { vm.state.value.errors.isNotEmpty() }
        vm.updatePreviewWindow(mapOf(CatalogType.MOVIES to setOf("b")))
        assertTrue(vm.state.value.errors.isEmpty())
        waitFor { hydration(vm).isCompleted }
        vm.load(CatalogType.MOVIES, categoryIds = setOf("b"))
        waitFor { source.saved.isNotEmpty() }
        assertEquals("b", source.calls.last().first)
        vm.pause(release = true)
    }
    @Test fun pausedWindowHydrationResumesCacheFirstWithoutClearingReadyRows() {
        val gate = CompletableDeferred<Unit>()
        val bReads = java.util.concurrent.atomic.AtomicInteger()
        val source = object : Source() {
            override suspend fun cached(profile: String, type: CatalogType, categories: List<Category>, preview: Boolean): Map<String, ProviderDiscoveryPage> {
                if (categories.any { it.id == "b" } && bReads.incrementAndGet() == 1) withContext(NonCancellable) { gate.await() }
                return categories.associate { it.id to ProviderDiscoveryPage(listOf(item(it.id)), 1, false, System.currentTimeMillis()) }
            }
        }
        val vm = ProviderDiscoveryViewModel(app, source); bindWindow(vm, setOf("a"))
        vm.updatePreviewWindow(mapOf(CatalogType.MOVIES to setOf("b")))
        waitFor { bReads.get() == 1 }; val old = hydration(vm)
        vm.pause(); bindWindow(vm, setOf("b"))
        waitFor { vm.state.value.pages[CatalogType.MOVIES]?.containsKey("b") == true }
        gate.complete(Unit); waitFor { old.isCompleted }
        assertEquals(2, bReads.get())
        assertTrue(vm.state.value.pages.getValue(CatalogType.MOVIES).containsKey("a"))
        assertTrue(source.calls.isEmpty())
        vm.pause(release = true)
    }
    @Test fun newWindowChecksItsCacheBeforeAutomaticNetworkDemand() {
        val gate = CompletableDeferred<Unit>(); val started = java.util.concurrent.atomic.AtomicBoolean()
        val source = object : Source() {
            override suspend fun cached(profile: String, type: CatalogType, categories: List<Category>, preview: Boolean): Map<String, ProviderDiscoveryPage> {
                if (categories.any { it.id == "b" }) { started.set(true); gate.await() }
                return categories.associate { it.id to ProviderDiscoveryPage(listOf(item("cached-${it.id}")), 1, false, System.currentTimeMillis()) }
            }
        }
        val vm = ProviderDiscoveryViewModel(app, source); bindWindow(vm, setOf("a"))
        vm.updatePreviewWindow(mapOf(CatalogType.MOVIES to setOf("b")))
        waitFor { started.get() }
        vm.load(CatalogType.MOVIES, categoryIds = setOf("b"))
        assertTrue(source.calls.isEmpty())
        assertFalse(vm.state.value.ready)
        assertEquals("cached-a", vm.state.value.pages.getValue(CatalogType.MOVIES).getValue("a").items.single().id)
        gate.complete(Unit); waitFor { vm.state.value.ready }
        vm.load(CatalogType.MOVIES, categoryIds = setOf("b"))
        waitFor { vm.state.value.loading.isEmpty() }
        assertTrue(source.calls.isEmpty())
        assertEquals("cached-b", vm.state.value.pages.getValue(CatalogType.MOVIES).getValue("b").items.single().id)
        vm.pause(release = true)
    }
    @Test fun slowWindowCacheCannotReplaceNewerNetworkData() {
        val gate = CompletableDeferred<Unit>()
        val started = java.util.concurrent.atomic.AtomicBoolean()
        val source = object : Source() {
            override suspend fun cached(profile: String, type: CatalogType, categories: List<Category>, preview: Boolean): Map<String, ProviderDiscoveryPage> {
                if (categories.any { it.id == "b" }) {
                    started.set(true); gate.await()
                    return mapOf("b" to ProviderDiscoveryPage(listOf(item("stale-b", category = "b")), 1, false, 1))
                }
                return mapOf("a" to ProviderDiscoveryPage(listOf(item("a")), 1, false, System.currentTimeMillis()))
            }
        }
        val vm = ProviderDiscoveryViewModel(app, source); bindWindow(vm, setOf("a"))
        vm.updatePreviewWindow(mapOf(CatalogType.MOVIES to setOf("b")))
        waitFor { started.get() }
        vm.load(CatalogType.MOVIES, refresh = true, categoryIds = setOf("b"))
        waitFor { source.saved.isNotEmpty() }
        val network = vm.state.value.pages.getValue(CatalogType.MOVIES).getValue("b")
        val disk = hydration(vm)
        gate.complete(Unit)
        waitFor { disk.isCompleted }
        assertEquals(network, vm.state.value.pages.getValue(CatalogType.MOVIES).getValue("b"))
        assertFalse(vm.state.value.items.getValue(CatalogType.MOVIES).any { it.id == "stale-b" })
        assertTrue(vm.state.value.pages.getValue(CatalogType.MOVIES).containsKey("a"))
        vm.pause(release = true)
    }
    @Test fun changingProfilesRejectsLateWindowHydration() {
        val gate = CompletableDeferred<Unit>(); val started = java.util.concurrent.atomic.AtomicBoolean()
        val oldProfile = session("old-window").profile.cacheKey()
        val source = object : Source() {
            override suspend fun cached(profile: String, type: CatalogType, categories: List<Category>, preview: Boolean): Map<String, ProviderDiscoveryPage> {
                if (profile == oldProfile) { started.set(true); withContext(NonCancellable) { gate.await() } }
                return mapOf("a" to ProviderDiscoveryPage(listOf(item(if (profile == oldProfile) "old" else "new")), 1, false, System.currentTimeMillis()))
            }
        }
        val vm = ProviderDiscoveryViewModel(app, source)
        vm.bind(session("old-window"), mapOf(CatalogType.MOVIES to listOf(category("a"))), 60_000, preview = true,
            initialPreviewWindow = mapOf(CatalogType.MOVIES to setOf("a")))
        waitFor { started.get() }; val oldDisk = hydration(vm)
        bindWindow(vm, setOf("a"), "new-window")
        gate.complete(Unit); waitFor { oldDisk.isCompleted }
        assertEquals("new", vm.state.value.items.getValue(CatalogType.MOVIES).single().id)
        assertEquals(session("new-window").profile.cacheKey(), vm.state.value.profile)
        vm.pause(release = true)
    }
    @Test fun returningToLandingKeepsReadyRowsWithoutReparsingTheDiskCache() {
        var cacheReads = 0
        val source = object : Source() {
            override suspend fun cached(profile: String, type: CatalogType, categories: List<Category>, preview: Boolean): Map<String, ProviderDiscoveryPage> {
                cacheReads++
                return mapOf("a" to ProviderDiscoveryPage(listOf(item("retained")), 1, false, System.currentTimeMillis()))
            }
        }
        val vm = ProviderDiscoveryViewModel(app, source)
        bind(vm, preview = true)
        val ready = vm.state.value.pages
        vm.pause(); bind(vm, preview = true)
        assertEquals(1, cacheReads)
        assertEquals(ready, vm.state.value.pages)
        assertEquals("retained", vm.state.value.items[CatalogType.MOVIES]?.single()?.id)
        vm.pause(release = true)
        assertTrue(vm.state.value.pages.isEmpty())
    }
    private val app get() = RuntimeEnvironment.getApplication()
    private fun item(id: String, title: String = id, category: String = "a") = MediaItem(id, title, null, null, portalCategoryId = category)
    private fun category(id: String) = Category(id, id, CatalogType.MOVIES)
    private fun session(name: String) = PortalSession(PortalProfile(name, "https://example.invalid/$name", "test"), "test", "test", "", "", "")
    private fun waitFor(condition: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(110))
            if (condition()) return
            Thread.sleep(5)
        }
        assertTrue("Asynchronous discovery state did not settle", condition())
    }
    private open class Source : ProviderDiscoverySource {
        val calls = mutableListOf<Pair<String, Int>>()
        val saved = mutableListOf<ProviderDiscoveryCache>()
        var initial = emptyMap<String, ProviderDiscoveryPage>()
        override suspend fun cached(profile: String, type: CatalogType, categories: List<Category>, preview: Boolean) = initial
        override suspend fun page(session: PortalSession, category: Category, page: Int): PortalCatalogPage {
            calls += category.id to page
            return PortalCatalogPage(listOf(MediaItem("${category.id}:$page", "Title", null, null)), page, false)
        }
        override suspend fun save(cache: ProviderDiscoveryCache) { saved += cache }
    }
    private fun bind(vm: ProviderDiscoveryViewModel, name: String = "p", ids: List<String> = listOf("a"), preview: Boolean = false) {
        vm.bind(session(name), mapOf(CatalogType.MOVIES to ids.map(::category)), 60_000, preview)
        waitFor { vm.state.value.ready }
    }
    private fun load(vm: ProviderDiscoveryViewModel, source: Source, refresh: Boolean = false) {
        val count = source.saved.size
        vm.load(CatalogType.MOVIES, refresh)
        waitFor { CatalogType.MOVIES !in vm.state.value.loading && (source.saved.size > count || vm.state.value.errors.isNotEmpty()) }
    }

    @Test fun selectedCollectionsFlattenMembershipAndExcludeSensitiveCategories() {
        val categories = listOf(category("a"), Category("custom", "All years", CatalogType.MOVIES, sourceCategoryIds = listOf("a", "b")),
            Category("secret", "Sensitive", CatalogType.MOVIES, isAdult = true))
        assertEquals(listOf("a", "b"), providerDiscoverySources(categories).map { it.id })
    }
    @Test fun defaultCategoryRowsUseRealCategoriesWhileExplicitAllSelectionStillWorks() {
        val all = Category("*", "All movies", CatalogType.MOVIES)
        assertEquals(listOf(category("a"), category("b")), providerDiscoveryCategoryRows(listOf(all, category("a"), category("b")), true))
        assertEquals(listOf(all), providerDiscoveryCategoryRows(listOf(all), false))
        assertEquals(listOf(category("b")), providerDiscoveryCategoryRows(listOf(category("b")), false))
    }
    @Test fun rowPreviewsAreBoundedAndOnlyTheVisibleCategoryIsRequested() {
        val source = Source().apply { initial = mapOf("a" to ProviderDiscoveryPage((1..100).map { item("$it") }, 8, true, System.currentTimeMillis())) }
        val vm = ProviderDiscoveryViewModel(app, source); bind(vm, ids = listOf("a", "b", "c"), preview = true)
        assertEquals(30, vm.state.value.pages[CatalogType.MOVIES]?.get("a")?.items?.size)
        assertTrue(vm.state.value.pages.getValue(CatalogType.MOVIES).getValue("a").truncated)
        vm.load(CatalogType.MOVIES, categoryIds = setOf("b"))
        waitFor { source.saved.isNotEmpty() }
        assertEquals(listOf("b" to 1), source.calls)
        assertTrue(source.saved.single().preview)
        assertEquals(30, vm.state.value.pages.getValue(CatalogType.MOVIES).getValue("a").items.size)
        val timestamp = vm.state.value.pages.getValue(CatalogType.MOVIES).getValue("a").cachedAt
        vm.load(CatalogType.MOVIES, refresh = true, categoryIds = setOf("b"))
        waitFor { source.saved.size == 2 }
        assertEquals(timestamp, vm.state.value.pages.getValue(CatalogType.MOVIES).getValue("a").cachedAt)
        assertEquals(listOf("b" to 1, "b" to 1), source.calls)
        vm.pause()
    }
    @Test fun realUiMetadataUsesSharedSessionLoaderAndGivesExplicitCollectionRequestsPriority() {
        val source = Source(); val vm = ProviderDiscoveryViewModel(app, source)
        val priorities = mutableListOf<Boolean>()
        val loader: ProviderDiscoveryPageLoader = { _, _, page, background ->
            priorities += background
            PortalCatalogPage(listOf(item("shared-session")), page, false)
        }
        val categories = mapOf(CatalogType.MOVIES to listOf(category("a")))
        vm.bind(session("shared"), categories, 60_000, preview = true, pageLoader = loader)
        waitFor { vm.state.value.ready }; load(vm, source)
        vm.bind(session("shared"), categories, 60_000, preview = false, pageLoader = loader)
        waitFor { vm.state.value.ready }; load(vm, source)
        assertEquals(listOf(true, false), priorities)
        assertTrue(source.calls.isEmpty())
        assertEquals("shared-session", vm.state.value.items[CatalogType.MOVIES]?.single()?.id)
        vm.pause()
    }
    @Test fun providerFailureMessagesDoNotCopyPrivateDiagnosticsIntoTiles() {
        val failure = java.io.IOException("Authorization: Bearer private-secret", java.net.UnknownHostException("private-host"))
        assertTrue(providerDiscoveryFailure(failure).contains("connection"))
        assertFalse(providerDiscoveryFailure(failure).contains("private"))
        assertFalse(providerDiscoveryFailure(IllegalStateException("private-secret")).contains("private-secret"))
    }
    @Test fun selectingMediaCancelsMetadataAndReleasesTheProviderGateWithoutARecoveryError() = runBlocking {
        val requests = DiscoveryMetadataRequests()
        val gate = Mutex()
        val pending = launch(start = CoroutineStart.UNDISPATCHED) { requests.run { gate.withLock { awaitCancellation() } } }
        assertTrue(gate.isLocked)
        requests.cancel()
        val foreground = async { gate.withLock { "user selection" } }
        assertEquals("user selection", withTimeout(1_000) { foreground.await() })
        assertTrue(pending.isCancelled)
    }
    @Test fun previewWritesDoNotReplaceFullCategoriesAndFullCategoriesSurviveSwitching() = runBlocking {
        val repo = ProviderDiscoveryRepository(app)
        val profile = session("rows-disk").profile.cacheKey()
        val full = ProviderDiscoveryPage((1..100).map { item("$it") }, 8, true, System.currentTimeMillis())
        repo.save(ProviderDiscoveryCache(profile, CatalogType.MOVIES, mapOf("a" to full)))
        repo.save(ProviderDiscoveryCache(profile, CatalogType.MOVIES, mapOf("b" to full.copy(items = listOf(item("b"))))))
        repo.save(ProviderDiscoveryCache(profile, CatalogType.MOVIES, mapOf("a" to full.copy(items = full.items.take(30), truncated = true)), preview = true))
        assertEquals(100, repo.cached(profile, CatalogType.MOVIES, listOf(category("a"))).getValue("a").items.size)
        assertEquals(30, repo.cached(profile, CatalogType.MOVIES, listOf(category("a")), preview = true).getValue("a").items.size)
        assertEquals("b", repo.cached(profile, CatalogType.MOVIES, listOf(category("b"))).getValue("b").items.single().id)
    }
    @Test fun truncatedPreviewCannotSkipUnloadedPagesInTheFullCollection() = runBlocking {
        val repo = ProviderDiscoveryRepository(app)
        val profile = session("preview-only").profile.cacheKey()
        repo.save(ProviderDiscoveryCache(profile, CatalogType.SERIES,
            mapOf("a" to ProviderDiscoveryPage(listOf(item("preview")), 9, false, System.currentTimeMillis(), truncated = true)), preview = true))
        val page = repo.cached(profile, CatalogType.SERIES, listOf(category("a"))).getValue("a")
        assertEquals(0, page.page)
        assertTrue(page.hasMore)
        assertTrue(page.items.isEmpty())
    }
    @Test fun collectionUsesOnlySelectedCategoriesAndDeduplicatesProviderIdentity() {
        val pages = mapOf("a" to ProviderDiscoveryPage(listOf(item("1"), item("2"))),
            "b" to ProviderDiscoveryPage(listOf(item("2"), item("3"))), "excluded" to ProviderDiscoveryPage(listOf(item("4"))))
        assertEquals(listOf("1", "2", "3"), providerDiscoveryItems(listOf(category("a"), category("b")), pages).map { it.id })
    }
    @Test fun localSearchHandlesAccentsPunctuationAndWordOrderWithoutProviderWork() {
        val items = listOf(item("1", "Drishyam - 2 (Hindi)"), item("2", "Amélie"))
        assertEquals(listOf("1"), searchProviderDiscovery(items, "Hindi drishyam").map { it.id })
        assertEquals(listOf("2"), searchProviderDiscovery(items, "amelie").map { it.id })
        assertEquals(items, searchProviderDiscovery(items, "  "))
    }
    @Test fun expiryRejectsUnknownFutureAndExpiredTimestamps() {
        assertFalse(providerDiscoveryFresh(null, 100, 10))
        assertFalse(providerDiscoveryFresh(ProviderDiscoveryPage(page = 1, cachedAt = 101), 100, 10))
        assertFalse(providerDiscoveryFresh(ProviderDiscoveryPage(page = 1, cachedAt = 90), 100, 10))
        assertTrue(providerDiscoveryFresh(ProviderDiscoveryPage(page = 1, cachedAt = 91), 100, 10))
    }
    @Test fun cachedTitlesPublishWithoutNetworkAndFreshCompletedCacheIsReused() {
        val source = Source().apply { initial = mapOf("a" to ProviderDiscoveryPage(listOf(item("cached")), 1, false, System.currentTimeMillis())) }
        val vm = ProviderDiscoveryViewModel(app, source)
        bind(vm)
        assertTrue(source.calls.isEmpty())
        assertEquals("cached", vm.state.value.items[CatalogType.MOVIES]?.single()?.id)
        vm.load(CatalogType.MOVIES)
        waitFor { vm.state.value.loading.isEmpty() }
        assertTrue(source.calls.isEmpty())
        assertTrue(source.saved.isEmpty())
        vm.pause(release = true)
        assertTrue(vm.state.value.items.isEmpty())
        assertTrue(vm.state.value.pages.isEmpty())
    }
    @Test fun staleCacheSurvivesNetworkFailure() {
        val source = object : Source() {
            override suspend fun page(session: PortalSession, category: Category, page: Int): PortalCatalogPage = error("offline")
        }.apply { initial = mapOf("a" to ProviderDiscoveryPage(listOf(item("cached")), 1, false, 1)) }
        val vm = ProviderDiscoveryViewModel(app, source); bind(vm)
        assertTrue(vm.state.value.hasMore(CatalogType.MOVIES))
        load(vm, source)
        assertEquals("cached", vm.state.value.items[CatalogType.MOVIES]?.single()?.id)
        assertTrue(vm.state.value.errors.containsKey(CatalogType.MOVIES))
        vm.pause()
    }
    @Test fun demandLoadsAtMostTwoCategoryRequestsAndResumesFromNextCategory() {
        val source = Source(); val vm = ProviderDiscoveryViewModel(app, source)
        bind(vm, ids = listOf("a", "b", "c", "d")); load(vm, source)
        assertEquals(listOf("a" to 1, "b" to 1), source.calls)
        load(vm, source)
        assertEquals(listOf("c" to 1, "d" to 1), source.calls.takeLast(2))
        vm.pause()
    }
    @Test fun pagingAppendsWithStableIdentityAndRefreshStartsAtPageOne() {
        val source = Source().apply { initial = mapOf("a" to ProviderDiscoveryPage(listOf(item("a:1")), 1, true, System.currentTimeMillis())) }
        val vm = ProviderDiscoveryViewModel(app, source); bind(vm); load(vm, source)
        assertEquals(listOf("a:1", "a:2"), vm.state.value.items[CatalogType.MOVIES]?.map { it.id })
        load(vm, source, refresh = true)
        assertEquals("a" to 1, source.calls.last())
        assertEquals(listOf("a:1"), vm.state.value.items[CatalogType.MOVIES]?.map { it.id })
        vm.pause()
    }
    @Test fun delayedOldProfileCannotPublishIntoNewProfile() {
        val delayed = CompletableDeferred<PortalCatalogPage>()
        val source = object : Source() {
            override suspend fun page(session: PortalSession, category: Category, page: Int) = withContext(NonCancellable) { delayed.await() }
        }
        val vm = ProviderDiscoveryViewModel(app, source); bind(vm, "old")
        vm.load(CatalogType.MOVIES)
        waitFor { vm.state.value.loading.isNotEmpty() }
        bind(vm, "new", listOf("b"))
        delayed.complete(PortalCatalogPage(listOf(item("obsolete")), 1, false))
        waitFor { vm.state.value.loading.isEmpty() }
        assertEquals(session("new").profile.cacheKey(), vm.state.value.profile)
        assertTrue(vm.state.value.items[CatalogType.MOVIES].orEmpty().isEmpty())
        assertTrue(source.saved.isEmpty())
        vm.pause()
    }
    @Test fun metadataMemoryIsBoundedAcrossSelectedCategories() {
        val pages = boundProviderDiscoveryPages(mapOf("a" to ProviderDiscoveryPage((0..12_001).map { item("$it") }),
            "b" to ProviderDiscoveryPage(listOf(item("extra")))))
        assertEquals(ProviderDiscoveryViewModel.MAX_ITEMS, pages.values.sumOf { it.items.size })
        assertTrue(pages.getValue("b").items.isEmpty())
    }
    @Test fun privateDiskCacheSeparatesProfilesTypesAndCategoriesAndRetainsProviderArtwork() = runBlocking {
        val store = ProfileStore(app)
        val profile = session("disk").profile.cacheKey()
        store.saveBrowseCatalog(BrowseCatalogCache(profile, CatalogType.MOVIES, 100, listOf(category("a")),
            mapOf("a" to listOf(item("one").copy(logo = "https://example.invalid/poster.jpg", command = "provider-command"))),
            mapOf("a" to 1), mapOf("a" to false), mapOf("a" to 100L)), scheduleMetadataSync = false)
        store.saveSearchCatalog(SearchCatalogCache(profile, CatalogType.MOVIES, 200,
            listOf(item("one"), item("two", category = "excluded")), setOf("*")), scheduleMetadataSync = false)
        val repo = ProviderDiscoveryRepository(app)
        val cached = repo.cached(profile, CatalogType.MOVIES, listOf(category("a"))).getValue("a")
        assertEquals(listOf("one"), cached.items.map { it.id })
        assertEquals("https://example.invalid/poster.jpg", cached.items.single().logo)
        assertEquals("provider-command", cached.items.single().command)
        assertTrue(repo.cached("other-profile", CatalogType.MOVIES, listOf(category("a"))).values.all { it.items.isEmpty() })
        assertTrue(repo.cached(profile, CatalogType.SERIES, listOf(category("a"))).values.all { it.items.isEmpty() })
    }
}
