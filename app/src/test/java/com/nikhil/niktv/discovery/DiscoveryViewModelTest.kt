package com.nikhil.niktv.discovery

import android.app.Application
import android.os.Looper
import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class DiscoveryViewModelTest {
    @Test fun openingProviderRootReplacesPreviousDetailStack() {
        val vm = DiscoveryViewModel(app, Source())
        vm.bind("providers", DashboardSurface.MOVIES, emptyList()); pump()
        vm.openRootDetails(card(1)); pump()
        vm.openDetails(card(2)); pump()
        vm.openRootDetails(card(3)); pump()
        assertEquals(3, vm.state.value.detail?.id)
        vm.closeDetails(); pump()
        assertNull(vm.state.value.detail)
    }

    @Test fun clearingFocusedPresentationRejectsLateProviderMetadataAndPreservesFeedAndHero() {
        val old = CompletableDeferred<DiscoveryDetails>()
        val heroResult = CompletableDeferred<DiscoveryDetails>()
        val vm = DiscoveryViewModel(app, object : Source() {
            override suspend fun providerDetails(item: MediaItem, type: CatalogType, stale: (DiscoveryDetails) -> Unit) =
                if (item.id == "old") withContext(NonCancellable) { old.await() }
                else DiscoveryDetails(card(202))
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit) =
                heroResult.await()
        })
        vm.bind("clear-focus", DashboardSurface.MOVIES, emptyList()); vm.loadFeed(TmdbHomeSection.TRENDING_MOVIES); pump()
        vm.prepareHero(); pump()
        val feed = vm.state.value.feeds
        val hero = vm.state.value.hero
        assertNotNull(hero)
        vm.presentProvider("row:old", MediaItem("old", "Old", null, null), CatalogType.MOVIES); pump()
        assertNotNull(vm.state.value.focusedPresentation)

        vm.clearFocusedPresentation(); pump()
        assertNull(vm.state.value.focusedEntryKey)
        assertNull(vm.state.value.focusedPresentation)
        assertEquals(feed, vm.state.value.feeds)
        assertEquals(hero, vm.state.value.hero)
        heroResult.complete(DiscoveryDetails(card(1).copy(genres = listOf("Hero metadata")))); pump()
        assertEquals(listOf("Hero metadata"), vm.state.value.heroPresentation?.genres)
        old.complete(DiscoveryDetails(card(101))); pump()
        assertNull(vm.state.value.focusedPresentation)
        assertNull(vm.state.value.focusedEntryKey)
        assertTrue(vm.state.value.errors.isEmpty())

        // Re-enabling presentation on the same route is a normal new request.
        vm.presentProvider("row:new", MediaItem("new", "New", null, null), CatalogType.MOVIES); pump()
        assertEquals("row:new", vm.state.value.focusedEntryKey)
        assertEquals(202, vm.state.value.focusedPresentation?.id)
    }
    @Test fun providerFocusRejectsLateMetadataWithoutOpeningAProviderOrDetailRoute() {
        val old = CompletableDeferred<DiscoveryDetails>()
        val vm = DiscoveryViewModel(app, object : Source() {
            override suspend fun providerDetails(item: MediaItem, type: CatalogType, stale: (DiscoveryDetails) -> Unit) =
                if (item.id == "old") withContext(NonCancellable) { old.await() }
                else DiscoveryDetails(card(202).copy(title = "Selected provider title"))
        })
        vm.bind("provider-focus", DashboardSurface.SERIES, emptyList()); pump()
        vm.presentProvider("row:old", MediaItem("old", "Old", null, null), CatalogType.SERIES); pump()
        vm.presentProvider("row:new", MediaItem("new", "New", null, null), CatalogType.SERIES); pump()
        old.complete(DiscoveryDetails(card(101))); pump()
        assertEquals("row:new", vm.state.value.focusedEntryKey)
        assertEquals(202, vm.state.value.focusedPresentation?.id)
        assertNull(vm.state.value.detail)
        assertTrue(vm.state.value.errors.isEmpty())
    }
    @Test fun returningFromDetailsRetainsFocusedPresentationAndPreparedBackdrop() {
        val vm = DiscoveryViewModel(app, Source())
        vm.bind("return", DashboardSurface.SERIES, emptyList()); pump()
        vm.presentFocusedCard("continue:chosen", card(42)); vm.retainPreparedPresentation(card(42))
        vm.pause(); vm.bind("return", DashboardSurface.SERIES, emptyList()); vm.resume("return", DashboardSurface.SERIES)
        assertEquals("continue:chosen", vm.state.value.focusedEntryKey)
        assertEquals(42, vm.state.value.focusedPresentation?.id)
        assertEquals(42, vm.preparedPresentation?.id)
        vm.bind("different-profile", DashboardSurface.SERIES, emptyList())
        assertNull(vm.state.value.focusedPresentation)
        assertNull(vm.preparedPresentation)
    }
    @Test fun repeatedSettledProviderFocusReusesItsPresentation() {
        var requests = 0
        val vm = DiscoveryViewModel(app, object : Source() {
            override suspend fun providerDetails(item: MediaItem, type: CatalogType, stale: (DiscoveryDetails) -> Unit): DiscoveryDetails {
                requests++; return DiscoveryDetails(card(42))
            }
        })
        vm.bind("repeat-focus", DashboardSurface.MOVIES, emptyList()); pump()
        val provider = MediaItem("provider", "Title", null, null)
        vm.presentProvider("row:provider", provider, CatalogType.MOVIES); pump()
        vm.pause(); vm.bind("repeat-focus", DashboardSurface.MOVIES, emptyList())
        vm.presentProvider("row:provider", provider, CatalogType.MOVIES); pump()
        assertEquals(1, requests)
    }
    @Test fun heroPresentationEnrichesOnlyPublicPresentationWithoutOpeningDetails() {
        val vm = DiscoveryViewModel(app, object : Source() {
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit) =
                DiscoveryDetails(card.copy(titleLogo = "https://example.invalid/title.png", genres = listOf("Comedy")))
        })
        vm.bind("hero-public", DashboardSurface.MOVIES, emptyList()); vm.loadFeed(TmdbHomeSection.TRENDING_MOVIES); pump()
        vm.prepareHero(); pump()
        assertEquals(listOf("Comedy"), vm.state.value.heroPresentation?.genres)
        assertNull(vm.state.value.detail)
        assertTrue(vm.state.value.episodes.isEmpty())
        assertTrue(vm.state.value.errors.isEmpty())
    }

    @Test fun obsoleteHeroMetadataCannotReplaceTheNewFeaturedTitle() {
        val old = CompletableDeferred<DiscoveryDetails>()
        val vm = DiscoveryViewModel(app, object : Source() {
            override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit) = listOf(card(1), card(2))
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit) =
                if (card.id == 1) withContext(NonCancellable) { old.await() } else DiscoveryDetails(card.copy(genres = listOf("New")))
        })
        vm.bind("hero-race", DashboardSurface.MOVIES, emptyList()); vm.loadFeed(TmdbHomeSection.TRENDING_MOVIES); pump()
        vm.prepareHero(); pump(); vm.rotate(1); vm.prepareHero(); pump()
        old.complete(DiscoveryDetails(card(1))); pump()
        assertEquals(2, vm.state.value.hero?.id)
        assertEquals(2, vm.state.value.heroPresentation?.id)
    }

    @Test fun leavingProfileRejectsLateHeroMetadataEvenIfCancellationIsIgnored() {
        val old = CompletableDeferred<DiscoveryDetails>()
        val vm = DiscoveryViewModel(app, object : Source() {
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit) = withContext(NonCancellable) { old.await() }
        })
        vm.bind("hero-old", DashboardSurface.MOVIES, emptyList()); vm.loadFeed(TmdbHomeSection.TRENDING_MOVIES); pump(); vm.prepareHero(); pump()
        vm.pause(); vm.bind("hero-new", DashboardSurface.SERIES, emptyList()); pump()
        old.complete(DiscoveryDetails(card(1))); pump()
        assertEquals("hero-new", vm.state.value.profile)
        assertNull(vm.state.value.heroPresentation)
    }

    @Test fun optionalHeroFailureDoesNotHideFeedOrRaiseAnError() {
        val vm = DiscoveryViewModel(app, object : Source() {
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit): DiscoveryDetails = error("offline")
        })
        vm.bind("hero-error", DashboardSurface.MOVIES, emptyList()); vm.loadFeed(TmdbHomeSection.TRENDING_MOVIES); pump(); vm.prepareHero(); pump()
        assertNotNull(vm.state.value.hero)
        assertNull(vm.state.value.heroPresentation)
        assertTrue(vm.state.value.errors.isEmpty())
    }

    @Test fun refreshedFeedRetargetsHeroMetadataWithoutWaitingForObsoleteWork() {
        val old = CompletableDeferred<DiscoveryDetails>()
        var loads = 0
        val vm = DiscoveryViewModel(app, object : Source() {
            override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit) = listOf(card(++loads))
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit) =
                if (card.id == 1) withContext(NonCancellable) { old.await() } else DiscoveryDetails(card)
        })
        vm.bind("hero-refresh", DashboardSurface.MOVIES, emptyList()); vm.loadFeed(TmdbHomeSection.TRENDING_MOVIES); pump(); vm.prepareHero(); pump()
        vm.loadFeed(TmdbHomeSection.TRENDING_MOVIES, true); pump(); vm.prepareHero(); pump()
        assertEquals(2, vm.state.value.heroPresentation?.id)
        old.complete(DiscoveryDetails(card(1))); pump()
        assertEquals(2, vm.state.value.heroPresentation?.id)
    }
    @Test fun onlyHomeAutomaticallyFetchesTmdbDiscovery() {
        val requested = mutableListOf<TmdbHomeSection>()
        val vm = DiscoveryViewModel(app, object : Source() {
            override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit): List<DiscoveryCard> {
                requested += section
                return emptyList()
            }
        })
        vm.bind("split", DashboardSurface.MOVIES, emptyList()); pump()
        vm.resume("split", DashboardSurface.MOVIES); pump()
        vm.bind("split", DashboardSurface.SERIES, emptyList()); pump()
        vm.resume("split", DashboardSurface.SERIES); pump()
        assertTrue(requested.isEmpty())
        vm.bind("split", DashboardSurface.HOME, emptyList()); pump()
        assertEquals(setOf(TmdbHomeSection.TRENDING_MOVIES, TmdbHomeSection.TRENDING_SERIES), requested.toSet())
    }

    private val app get() = RuntimeEnvironment.getApplication()
    private fun pump() = shadowOf(Looper.getMainLooper()).idle()
    private fun card(id: Int, series: Boolean = false) = DiscoveryCard(id, series, "Movie $id", date = "2020-01-01")
    private open class Source : DiscoveryDataSource {
        override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit) = listOf(DiscoveryCard(1, section.series, "Cached movie", date = "2020-01-01"))
        override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit) = DiscoveryDetails(card, seasons = if (card.series) listOf(1, 2) else emptyList())
        override suspend fun related(card: DiscoveryCard) = emptyList<DiscoveryCard>()
        override suspend fun collection(id: Int) = emptyList<DiscoveryCard>()
        override suspend fun reviews(card: DiscoveryCard) = emptyList<DiscoveryReview>()
        override suspend fun episodes(card: DiscoveryCard, season: Int) = listOf(DiscoveryEpisode(season, 1, "Episode", date = "2020-01-01"))
    }
    @Test fun trialSettingsAreSeparateByProfileAndSurface() {
        val vm = DiscoveryViewModel(app, Source())
        val original = listOf(TmdbHomeSection.ACTION)
        vm.bind("profile-a", DashboardSurface.MOVIES, original); pump()
        vm.configure(listOf(TmdbHomeSection.COMEDY))
        vm.bind("profile-b", DashboardSurface.MOVIES, original); pump()
        assertEquals(original, vm.state.value.sections)
        vm.bind("profile-a", DashboardSurface.MOVIES, original); pump()
        assertEquals(listOf(TmdbHomeSection.COMEDY), vm.state.value.sections)
        assertEquals(listOf(TmdbHomeSection.ACTION), original)
        vm.bind("empty-profile", DashboardSurface.MOVIES, emptyList()); pump()
        assertTrue(vm.state.value.sections.isEmpty())
    }
    @Test fun oldDetailsCannotReplaceNewSelectionEvenIfSourceIgnoresCancellation() {
        val old = CompletableDeferred<DiscoveryDetails>()
        val source = object : Source() {
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit): DiscoveryDetails =
                if (card.id == 1) withContext(NonCancellable) { old.await() } else DiscoveryDetails(card)
        }
        val vm = DiscoveryViewModel(app, source)
        vm.bind("p", DashboardSurface.MOVIES, emptyList()); pump()
        vm.openDetails(card(1)); pump(); vm.openDetails(card(2)); pump()
        old.complete(DiscoveryDetails(card(1))); pump()
        assertEquals(2, vm.state.value.details?.card?.id)
        vm.closeDetails(); pump(); assertEquals(1, vm.state.value.detail?.id)
        vm.closeDetails(); pump(); assertNull(vm.state.value.detail)
    }
    @Test fun oldSeasonCannotReplaceNewSeasonAndFutureEpisodesAreHidden() {
        val old = CompletableDeferred<List<DiscoveryEpisode>>()
        val source = object : Source() {
            override suspend fun episodes(card: DiscoveryCard, season: Int): List<DiscoveryEpisode> =
                if (season == 1) withContext(NonCancellable) { old.await() } else super.episodes(card, season)
        }
        val vm = DiscoveryViewModel(app, source)
        vm.bind("p", DashboardSurface.SERIES, emptyList()); pump(); vm.openDetails(card(1, true)); pump()
        vm.loadSeason(1); pump(); vm.loadSeason(2); pump()
        old.complete(listOf(DiscoveryEpisode(1, 1, "Old", date = "2020-01-01"))); pump()
        assertEquals(2, vm.state.value.season)
        assertEquals(2, vm.state.value.episodes.single().season)
    }
    @Test fun supplementarySectionsReloadForNewTitlesAndRemainIndependentOnFailure() {
        val source = object : Source() {
            override suspend fun related(card: DiscoveryCard) = listOf(card.copy(id = card.id + 10))
            override suspend fun reviews(card: DiscoveryCard): List<DiscoveryReview> = error("offline")
        }
        val vm = DiscoveryViewModel(app, source)
        vm.bind("p", DashboardSurface.MOVIES, emptyList()); pump(); vm.openDetails(card(1)); pump()
        vm.loadSupplement("related"); vm.loadSupplement("reviews"); pump()
        assertEquals(11, vm.state.value.related.single().id)
        assertTrue("extra:reviews" in vm.state.value.errors)
        vm.openDetails(card(2)); pump(); vm.loadSupplement("related"); pump()
        assertEquals(12, vm.state.value.related.single().id)
        assertFalse("extra:reviews" in vm.state.value.errors)
    }
    @Test fun lateFeedFromPreviousProfileIsRejected() {
        val old = CompletableDeferred<List<DiscoveryCard>>(); var first = true
        val source = object : Source() {
            override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit): List<DiscoveryCard> =
                if (first) { first = false; withContext(NonCancellable) { old.await() } } else listOf(card(2))
        }
        val vm = DiscoveryViewModel(app, source)
        vm.bind("old", DashboardSurface.MOVIES, emptyList()); vm.loadFeed(TmdbHomeSection.TRENDING_MOVIES); pump()
        vm.bind("new", DashboardSurface.MOVIES, emptyList()); vm.loadFeed(TmdbHomeSection.TRENDING_MOVIES); pump()
        old.complete(listOf(card(1))); pump()
        assertEquals("new", vm.state.value.profile)
        assertEquals(2, vm.state.value.hero?.id)
    }
    @Test fun secondFeedDoesNotReplaceTheHeroTheUserAlreadySelected() {
        val shows = CompletableDeferred<List<DiscoveryCard>>()
        val source = object : Source() {
            override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit) =
                if (section.series) shows.await() else (1..5).map { card(it) }
        }
        val vm = DiscoveryViewModel(app, source)
        vm.bind("p", DashboardSurface.HOME, emptyList()); pump(); vm.rotate(1)
        assertEquals("movie:2", vm.state.value.hero?.key)
        shows.complete((1..5).map { card(it, true) }); pump()
        assertEquals("movie:2", vm.state.value.hero?.key)
    }
    @Test fun closingDetailsClearsBusyStateAndRejectsLateCompletion() {
        val delayed = CompletableDeferred<DiscoveryDetails>()
        val source = object : Source() {
            override suspend fun details(card: DiscoveryCard, stale: (DiscoveryDetails) -> Unit) = withContext(NonCancellable) { delayed.await() }
        }
        val vm = DiscoveryViewModel(app, source)
        vm.bind("p", DashboardSurface.MOVIES, emptyList()); pump(); vm.openDetails(card(1)); pump()
        assertTrue("detail" in vm.state.value.loading)
        vm.closeDetails(); pump(); assertFalse("detail" in vm.state.value.loading)
        delayed.complete(DiscoveryDetails(card(1))); pump()
        assertNull(vm.state.value.details); assertNull(vm.state.value.detail)
    }
    @Test fun leavingTheScreenCancelsWorkAndResumeReloadsOnlyTheSameRoute() {
        var calls = 0; var canceled = false
        val source = object : Source() {
            override suspend fun feed(section: TmdbHomeSection, stale: (List<DiscoveryCard>) -> Unit): List<DiscoveryCard> {
                calls++
                if (calls == 1) try { awaitCancellation() } finally { canceled = true }
                return listOf(card(9))
            }
        }
        val vm = DiscoveryViewModel(app, source)
        vm.bind("p", DashboardSurface.HOME, emptyList()); pump()
        vm.pause(); pump(); assertTrue(canceled); assertTrue(vm.state.value.loading.isEmpty())
        vm.resume("other", DashboardSurface.HOME); pump(); assertEquals(2, calls)
        vm.resume("p", DashboardSurface.HOME); pump(); assertEquals("movie:9", vm.state.value.hero?.key)
    }
}
