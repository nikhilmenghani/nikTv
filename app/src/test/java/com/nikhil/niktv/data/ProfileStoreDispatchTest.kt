package com.nikhil.niktv.data

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class ProfileStoreDispatchTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var scope: CoroutineScope
    private lateinit var preferences: DataStore<Preferences>
    private lateinit var file: File
    private lateinit var store: ProfileStore
    private val context get() = RuntimeEnvironment.getApplication()
    private val movie = MediaItem("same-id", "Fixture movie", null, null)
    private val series = MediaItem("series", "Fixture series", null, null)
    private val episode = MediaItem("episode", "Episode 1", null, null, seasonNumber = 1, episodeNumber = 1)

    @Before fun create() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        file = temporary.newFolder("preferences").resolve("fixture.preferences_pb")
        preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        store = ProfileStore(context, preferences)
    }

    @After fun stop() = runBlocking { scope.coroutineContext.job.cancelAndJoin() }

    @Test fun decoderRunsOffCollectorAndIgnoresUnrelatedPreferenceChanges() = runBlocking {
        val library = stringPreferencesKey("library")
        val scalar = intPreferencesKey("setting")
        val calls = AtomicInteger()
        val parserThread = AtomicReference<Thread>()
        val collectorThread = Thread.currentThread()
        val observed = flowOf(
            mutablePreferencesOf(library to "[1]", scalar to 1),
            mutablePreferencesOf(library to "[1]", scalar to 2),
            mutablePreferencesOf(library to " [1] ", scalar to 3),
            mutablePreferencesOf(library to "[2]", scalar to 3),
        ).decodedProfilePreference(select = { it[library] }, decode = { raw ->
            calls.incrementAndGet()
            parserThread.set(Thread.currentThread())
            Json.decodeFromString<List<Int>>(raw!!)
        }).toList()
        assertNotSame(collectorThread, parserThread.get())
        assertEquals(3, calls.get()) // No parse for the unrelated scalar write.
        assertEquals(listOf(listOf(1), listOf(2)), observed) // Equivalent decoded data does not fan out.
    }

    @Test fun editTransformsRunOffCallerAndConcurrentUpdatesRemainAtomic() = runBlocking {
        val key = intPreferencesKey("counter")
        val writerThread = AtomicReference<Thread>()
        val caller = Thread.currentThread()
        preferences.editProfileMetadata {
            writerThread.set(Thread.currentThread())
            it[key] = 0
        }
        assertNotSame(caller, writerThread.get())
        coroutineScope {
            repeat(24) { launch { preferences.editProfileMetadata { it[key] = (it[key] ?: 0) + 1 } } }
        }
        assertEquals(24, preferences.data.first()[key])
    }

    @Test fun metadataWritesEnqueueInCallerOrderBeforeDispatchingTransforms() = runBlocking {
        val caller = Thread.currentThread()
        val order = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val serial = Mutex()
        val values = MutableStateFlow<Preferences>(emptyPreferences())
        val queued = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = values
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                // This assertion makes the test independent of Default's scheduling:
                // an outer withContext dispatch loses the enqueue-thread guarantee.
                assertSame("Enqueue must happen before leaving the caller's thread", caller, Thread.currentThread())
                order += currentCoroutineContext()[CoroutineName]!!.name
                return serial.withLock {
                    gate.await()
                    transform(values.value).also { values.value = it }
                }
            }
        }
        val key = stringPreferencesKey("snapshot")
        val first = launch(CoroutineName("older"), start = CoroutineStart.UNDISPATCHED) {
            queued.editProfileMetadata { it[key] = "older" }
        }
        assertEquals(listOf("older"), order)
        val second = launch(CoroutineName("newer"), start = CoroutineStart.UNDISPATCHED) {
            queued.editProfileMetadata { it[key] = "newer" }
        }
        assertEquals(listOf("older", "newer"), order)
        gate.complete(Unit)
        joinAll(first, second)
        assertEquals("newer", values.value[key])
    }

    @Test fun canceledMetadataTransformCannotCommitPartialPreferencesOrBlockTheNextWrite() = runBlocking {
        val key = intPreferencesKey("atomic")
        preferences.editProfileMetadata { it[key] = 1 }
        val entered = CompletableDeferred<Unit>()
        val canceled = launch {
            preferences.editProfileMetadata {
                it[key] = 2
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        withTimeout(5_000) { entered.await() }
        withTimeout(5_000) { canceled.cancelAndJoin() }
        assertEquals(1, preferences.data.first()[key])
        withTimeout(5_000) { preferences.editProfileMetadata { it[key] = 3 } }
        assertEquals(3, preferences.data.first()[key])
    }

    @Test fun decodedLibraryEqualityAlsoRunsOffCollectorThread() = runBlocking {
        val library = stringPreferencesKey("library")
        val equalityThread = AtomicReference<Thread>()
        val collectorThread = Thread.currentThread()
        val values = flowOf(mutablePreferencesOf(library to "first"), mutablePreferencesOf(library to "equivalent"))
            .decodedProfilePreference(select = { it[library] }, decode = {
                object {
                    override fun equals(other: Any?): Boolean {
                        equalityThread.set(Thread.currentThread())
                        return other?.javaClass == javaClass
                    }
                    override fun hashCode() = 1
                }
            }).toList()
        assertEquals(1, values.size)
        assertNotNull(equalityThread.get())
        assertNotSame(collectorThread, equalityThread.get())
    }

    @Test fun libraryProgressMappingsAndSeasonCachesRoundTripWithoutCrossingProfiles() = runBlocking {
        val favorites = listOf("one", "two").map { FavoriteItem(FavoriteKind.MOVIE, movie, profileKey = it) }
        val recent = listOf("one", "two").map { RecentItem(FavoriteKind.MOVIE, movie, profileKey = it) }
        val progress = listOf(PlaybackProgress("one|same-id", 100, 1000, 1), PlaybackProgress("two|same-id", 700, 1000, 2))
        val seasons = listOf("one", "two").map { EpisodeSeasonCache(it, series.id, 1, listOf(1), listOf(episode), cachedAtMillis = 1) }
        val mappings = listOf("one", "two").map { TmdbIptvMapping(it, CatalogType.MOVIES, 10, movie, cachedAtMillis = 1) }
        store.saveFavorites(favorites)
        store.saveRecentlyPlayed(recent)
        store.savePlaybackProgress(progress)
        coroutineScope {
            seasons.forEach { launch { store.saveEpisodeSeasonCache(it) } }
            mappings.forEach { launch { store.saveTmdbMapping(it) } }
            listOf("one", "two").forEach { profile -> launch { store.rememberSeriesSeason(profile, series.id, if (profile == "one") 1 else 2) } }
        }
        scope.coroutineContext.job.cancelAndJoin()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        preferences = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        val recreated = ProfileStore(context, preferences)
        assertEquals(favorites, recreated.favorites.first())
        assertEquals(recent, recreated.recentlyPlayed.first())
        assertEquals(progress, recreated.playbackProgress.first())
        assertEquals(seasons.toSet(), recreated.episodeSeasonCaches.first().toSet())
        assertEquals(mappings.toSet(), recreated.tmdbMappings.first().toSet())
        assertEquals(mapOf("one|series" to 1, "two|series" to 2), recreated.rememberedSeriesSeasons.first())
        assertEquals(60, recreated.cacheIntervalMinutes.first())
        assertEquals(14, recreated.initialCatalogItems.first())
    }

    @Test fun profileSelectionStillHonorsLegacyFallbackAndActiveSessionIdentity() = runBlocking {
        val first = PortalProfile("First", "https://provider.invalid", "fixture-one")
        val second = first.copy(name = "Second", macAddress = "fixture-two")
        preferences.edit { it[stringPreferencesKey("active_profile")] = Json.encodeToString(first) }
        assertEquals(first, store.activeProfile.first())
        fun session(profile: PortalProfile) = PortalSession(profile, "fixture-session", "https://provider.invalid/api", "", "", "")
        val firstSession = session(first)
        val secondSession = session(second)
        store.save(firstSession)
        store.save(secondSession)
        assertEquals(second, store.activeProfile.first())
        assertEquals(secondSession, store.activeSession.first())
        store.activate(first)
        assertEquals(first, store.activeProfile.first())
        assertEquals(first, store.activeSession.first()!!.profile)
        assertEquals(second, store.sessionFor(second)!!.profile)
    }

    @Test fun episodeDiscoveryPreservesOtherProfilesAndCannotReviveAnUnsubscribedSeries() = runBlocking {
        val first = WatchedSeries("one", series, knownEpisodeIds = setOf("old"), checkedAtMillis = 1)
        val second = WatchedSeries("two", series, knownEpisodeIds = setOf("other"), checkedAtMillis = 1)
        store.saveWatchedSeries(listOf(first, second))
        assertEquals(listOf(episode), store.recordEpisodeDiscovery(first.key, listOf(episode), 2)!!.added)
        assertEquals(second, store.watchedSeries.first().single { it.profileKey == "two" })
        store.saveWatchedSeries(listOf(second))
        assertNull(store.recordEpisodeDiscovery(first.key, listOf(episode), 3))
        assertEquals(listOf(second), store.watchedSeries.first())
    }
}
