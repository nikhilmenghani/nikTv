package com.nikhil.niktv.torrent

import android.app.Application
import com.nikhil.niktv.data.*
import com.nikhil.niktv.discovery.GenreMarket
import com.nikhil.niktv.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class TorrentDiscoveryPreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val users get() = UserSyncStore.get(context)
    private fun owner(account: String = "711", type: UserProfileType = UserProfileType.GENERAL) =
        UserScope(account, UserSyncStore.defaultProfileId(account, type))
    @Before fun reset() = runBlocking {
        withContext(Dispatchers.IO) { users.db.clearAllTables() }
        users.disconnect(); users.selectAccount("711"); users.initializeDefaults("711"); users.selectProfile(owner().profileId)
        UserProfilePreferences.warm(context, owner())
    }

    @Test fun defaultsMatchEachTorrentSurfaceWithoutWritingIptvSettings() {
        assertEquals(HOME_SECTIONS, TorrentDiscoveryPreferences.defaults(DashboardSurface.HOME).sections)
        assertTrue(TorrentDiscoveryPreferences.defaults(DashboardSurface.MOVIES).sections.all { !it.series })
        assertTrue(TorrentDiscoveryPreferences.defaults(DashboardSurface.SERIES).sections.all { it.series })
        assertEquals(TorrentDiscoveryPreferences.defaults(DashboardSurface.HOME), TorrentDiscoveryPreferences.read(context, null, DashboardSurface.HOME))
        assertTrue(UserProfilePreferences.get(context, "home2_presentation", owner = owner()).all.isEmpty())
    }

    @Test fun emptySelectionAndVisibilitySwitchesAreIndependentPerSurfaceAndViewer() = runBlocking {
        val hidden = TorrentDiscoveryConfiguration(emptyList(), genres = false, providers = false)
        TorrentDiscoveryPreferences.save(context, owner(), DashboardSurface.MOVIES, hidden)
        assertEquals(hidden, TorrentDiscoveryPreferences.observe(context, owner(), DashboardSurface.MOVIES).first())
        assertEquals(HOME_SECTIONS, TorrentDiscoveryPreferences.read(context, owner(), DashboardSurface.HOME).sections)
        assertTrue(TorrentDiscoveryPreferences.read(context, owner(type = UserProfileType.KIDS), DashboardSurface.MOVIES).genres)
        users.selectAccount("712"); users.initializeDefaults("712"); UserProfilePreferences.warm(context, owner("712"))
        assertFalse(TorrentDiscoveryPreferences.read(context, owner("712"), DashboardSurface.MOVIES).sections.isEmpty())
    }

    @Test fun allSupportedCategoriesArePortableButWrongMediaTypesCannotLeakToMovies() = runBlocking {
        val chosen = TorrentDiscoveryConfiguration(listOf(TmdbHomeSection.ACTION, TmdbHomeSection.ACTION_SERIES,
            TmdbHomeSection.BOLLYWOOD_COMEDY, TmdbHomeSection.ACTION), genres = true, providers = false)
        TorrentDiscoveryPreferences.save(context, owner(), DashboardSurface.MOVIES, chosen)
        assertEquals(listOf(TmdbHomeSection.ACTION, TmdbHomeSection.BOLLYWOOD_COMEDY),
            TorrentDiscoveryPreferences.read(context, owner(), DashboardSurface.MOVIES).sections)
        val records = users.snapshot("711").filter { "torrent_discovery_v1|" in it.recordId }
        assertEquals(4, records.size)
        assertTrue(records.all { it.profileId == owner().profileId })
        assertFalse(records.any { "password" in it.payload || "portalUrl" in it.payload })
    }

    @Test fun genreMarketRowsAreIndependentlyPortablePerSurfaceAndProfile() = runBlocking {
        assertEquals(GenreMarket.entries.toSet(), TorrentDiscoveryPreferences.defaults(DashboardSurface.HOME).genreMarkets)
        val chosen = TorrentDiscoveryPreferences.defaults(DashboardSurface.MOVIES)
            .copy(genreMarkets = setOf(GenreMarket.BOLLYWOOD))
        TorrentDiscoveryPreferences.save(context, owner(), DashboardSurface.MOVIES, chosen)
        assertEquals(setOf(GenreMarket.BOLLYWOOD), TorrentDiscoveryPreferences.observe(context, owner(), DashboardSurface.MOVIES).first().genreMarkets)
        assertEquals(GenreMarket.entries.toSet(), TorrentDiscoveryPreferences.read(context, owner(), DashboardSurface.HOME).genreMarkets)
        assertEquals(GenreMarket.entries.toSet(), TorrentDiscoveryPreferences.read(context, owner(type = UserProfileType.KIDS), DashboardSurface.MOVIES).genreMarkets)
        val wire = users.snapshot("711").single { "torrent_discovery_v1|MOVIES:markets" in it.recordId }
        assertTrue("The explicit market selection is included in portable profile data", "BOLLYWOOD" in wire.payload)
        TorrentDiscoveryPreferences.save(context, owner(), DashboardSurface.MOVIES, chosen.copy(genreMarkets = emptySet()))
        assertTrue(TorrentDiscoveryPreferences.read(context, owner(), DashboardSurface.MOVIES).genreMarkets.isEmpty())
    }

    @Test fun atomicOfflineRegistrationPreservesOtherPortalsAndRejectsStaleWorkerUpdates() = runBlocking {
        val store = ProfileStore(context).scoped(owner())
        val iptv = OfflineMediaDownload(requestId = "iptv-request", profileKey = "iptv-private-profile", catalogType = CatalogType.MOVIES,
            media = MediaItem("movie-1", "IPTV movie", null, null))
        val torrent = iptv.copy(requestId = "torrent-request", profileKey = "torrents")
        store.saveOfflineDownloads(listOf(iptv))
        assertTrue(store.upsertOfflineDownload(torrent))
        assertEquals(2, store.offlineDownloads.first().size)
        val newer = torrent.copy(requestId = "new-request")
        assertTrue(store.upsertOfflineDownload(newer))
        assertFalse(store.upsertOfflineDownload(torrent.copy(downloadId = 33), expectedRequestId = torrent.requestId))
        assertFalse(store.removeOfflineDownload(torrent.key, requestId = torrent.requestId))
        assertEquals(newer.requestId, store.offlineDownloads.first().single { it.profileKey == "torrents" }.requestId)
        assertTrue(store.removeOfflineDownload(newer.key, requestId = newer.requestId))
        assertEquals(listOf(iptv), store.offlineDownloads.first())
        assertFalse(users.snapshot("711").any { "iptv-private-profile" in it.payload || "torrent-request" in it.payload })
    }
}
