package com.nikhil.niktv.torrent

import android.app.Application
import android.content.Context
import com.nikhil.niktv.data.*
import com.nikhil.niktv.discovery.DiscoveryCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class TorrentLibraryStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val users get() = UserSyncStore.get(context)
    private fun family(account: String = "701") = UserScope(account, UserSyncStore.defaultProfileId(account, UserProfileType.GENERAL))
    private fun kids() = UserScope(family().accountId, UserSyncStore.defaultProfileId(family().accountId, UserProfileType.KIDS))
    private fun movie(id: Int = 1) = DiscoveryCard(id, false, "Film $id", date = "2000-01-01",
        poster = "https://image.tmdb.org/t/p/w500/poster.jpg", backdrop = "https://image.tmdb.org/t/p/w1280/backdrop.jpg")
    private suspend fun library(owner: UserScope = family()) = TorrentLibraryStore.observe(context, owner).first()

    @Before fun reset() = runBlocking {
        withContext(Dispatchers.IO) { users.db.clearAllTables() }
        users.disconnect()
        context.getSharedPreferences("torrent_local_sources_v1", Context.MODE_PRIVATE).edit().clear().commit()
        users.selectAccount(family().accountId); users.initializeDefaults(family().accountId); users.selectProfile(family().profileId)
        UserProfilePreferences.warm(context, family())
    }

    @Test fun torrentLibrariesStaySeparateAcrossViewersAccountsAndIptvPreferences() = runBlocking {
        val entry = torrentLibraryEntry(movie(), positionMillis = 60_000, durationMillis = 600_000)
        TorrentLibraryStore.progress(context, family(), entry)
        TorrentLibraryStore.toggleSaved(context, family(), entry)
        assertEquals(entry.key, library().continueWatching.single().key)
        assertTrue(library(kids()).continueWatching.isEmpty())
        assertTrue(library(kids()).saved.isEmpty())
        users.selectAccount("702"); users.initializeDefaults("702")
        UserProfilePreferences.warm(context, family("702"))
        assertTrue(library(family("702")).saved.isEmpty())
        assertTrue(UserProfilePreferences.get(context, "iptv_pin_preferences", owner = family()).all.isEmpty())
        assertTrue(TorrentLibraryStore.observe(context, null).first().continueWatching.isEmpty())
    }

    @Test fun progressAndSavedStateHaveIndependentPortableKeys() = runBlocking {
        val entry = torrentLibraryEntry(movie(), positionMillis = 60_000, durationMillis = 600_000)
        TorrentLibraryStore.toggleSaved(context, family(), entry)
        TorrentLibraryStore.progress(context, family(), entry)
        TorrentLibraryStore.progress(context, family(), entry.copy(positionMillis = 90_000, updatedAt = entry.updatedAt + 1))
        assertTrue(library().isSaved(movie()))
        assertEquals(90_000L, library().continueWatching.single().positionMillis)
        TorrentLibraryStore.removeProgress(context, family(), entry)
        assertTrue(library().continueWatching.isEmpty())
        assertTrue(library().isSaved(movie()))
        TorrentLibraryStore.toggleSaved(context, family(), entry)
        assertTrue(library().saved.isEmpty())
    }

    @Test fun completedEpisodesStayRecordedAndOlderCallbacksCannotResurrectThem() = runBlocking {
        val show = movie().copy(series = true)
        val first = torrentLibraryEntry(show, TmdbEpisode(1, 5000, "Episode 5000", null, "2026-01-01", null),
            positionMillis = 60_000, durationMillis = 600_000).copy(updatedAt = 100)
        TorrentLibraryStore.toggleSaved(context, family(), first)
        TorrentLibraryStore.progress(context, family(), first)
        val complete = first.copy(positionMillis = 599_000, updatedAt = 200)
        TorrentLibraryStore.progress(context, family(), complete)
        TorrentLibraryStore.progress(context, family(), first.copy(positionMillis = 70_000, updatedAt = 150))
        assertTrue(library().continueWatching.isEmpty())
        assertTrue(library().saved.single().completed)
        val retained = UserProfilePreferences.get(context, "torrent_library_v1", owner = family())
            .getString("progress:${complete.key}", null)
        assertTrue(retained!!.contains("599000"))
        val next = first.copy(episode = 5001, episodeName = "Episode 5001", positionMillis = 15_000, updatedAt = 300)
        TorrentLibraryStore.progress(context, family(), next)
        assertEquals(5001, library().continueWatching.single().episode)
        assertEquals(1, library().continueWatching.size)
    }

    @Test fun syncMetadataRetainsArtworkYearAndEpisodeWithoutAnyPlaybackSource() = runBlocking {
        val raw = torrentLibraryEntry(movie().copy(series = true), TmdbEpisode(3, 8, "An episode", null, "2020-01-02", "https://image.tmdb.org/t/p/w500/still.jpg"),
            TorrentVideoFile(1, "Private folder/episode.mkv?secret=must-not-export", 800_000), "a".repeat(40), 60_000, 600_000)
        TorrentLibraryStore.progress(context, family(), raw)
        val records = users.snapshot(family().accountId).filter { "torrent_library_v1|progress:" in it.recordId }
        assertEquals(1, records.size)
        val payload = records.single().payload
        assertTrue(payload.contains("poster.jpg")); assertTrue(payload.contains("2000-01-01"))
        assertTrue(payload.contains("episodeStill")); assertTrue(payload.contains("An episode"))
        assertTrue(payload.contains("/t/p/w500/poster.jpg"))
        assertFalse("Portable artwork uses validated TMDB paths, not URL fields that sync redacts", payload.contains("://"))
        assertFalse(payload.contains("Private folder")); assertFalse(payload.contains("secret"))
        assertFalse(payload.contains("magnet:")); assertFalse(payload.contains("127.0.0.1"))
        val restored = library().continueWatching.single()
        assertEquals("2000", restored.year)
        assertEquals(3, restored.tmdbEpisode!!.seasonNumber)
        assertEquals(8, restored.tmdbEpisode!!.episodeNumber)
        assertEquals(movie().poster, restored.card!!.poster)
        assertEquals(movie().backdrop, restored.card!!.backdrop)
        assertEquals("https://image.tmdb.org/t/p/w500/still.jpg", restored.tmdbEpisode!!.stillUrl)
    }

    @Test fun newerRemoteMetadataUpdatesOneTitleWithoutReplacingTheOther() = runBlocking {
        val a = torrentLibraryEntry(movie(1), positionMillis = 60_000, durationMillis = 600_000)
        val b = torrentLibraryEntry(movie(2), positionMillis = 70_000, durationMillis = 600_000)
        TorrentLibraryStore.progress(context, family(), a)
        TorrentLibraryStore.progress(context, family(), b)
        val original = users.snapshot(family().accountId).single { "torrent_library_v1|progress:${a.key}" in it.recordId }
        val root = Json.parseToJsonElement(original.payload).jsonObject
        val wrapper = root.getValue("entry").jsonObject
        val value = wrapper.getValue("value").jsonObject
        val changed = JsonObject(value + ("positionMillis" to JsonPrimitive(180_000)))
        val remoteWrapper = JsonObject(wrapper + ("value" to changed))
        users.merge(family().accountId, listOf(original.copy(payload = JsonObject(root + ("entry" to remoteWrapper)).toString(),
            revision = original.revision + 100, deviceId = "other-device", mutationId = "torrent-remote-progress")))
        val rows = library().continueWatching.associateBy(TorrentLibraryEntry::key)
        assertEquals(180_000L, rows.getValue(a.key).positionMillis)
        assertEquals(70_000L, rows.getValue(b.key).positionMillis)
    }

    @Test fun newerIncompleteDeviceSnapshotRetainsCloudArtworkAndReleaseDate() = runBlocking {
        val entry = torrentLibraryEntry(movie(), positionMillis = 60_000, durationMillis = 600_000)
        TorrentLibraryStore.progress(context, family(), entry)
        val original = users.snapshot(family().accountId).single { "torrent_library_v1|progress:${entry.key}" in it.recordId }
        val root = Json.parseToJsonElement(original.payload).jsonObject
        val wrapper = root.getValue("entry").jsonObject
        val value = wrapper.getValue("value").jsonObject
        val incomplete = JsonObject(value + mapOf("poster" to JsonNull, "backdrop" to JsonNull, "date" to JsonNull,
            "positionMillis" to JsonPrimitive(180_000), "updatedAt" to JsonPrimitive(entry.updatedAt + 1)))
        val remoteWrapper = JsonObject(wrapper + ("value" to incomplete))
        users.merge(family().accountId, listOf(original.copy(payload = JsonObject(root + ("entry" to remoteWrapper)).toString(),
            revision = original.revision + 100, deviceId = "cold-device", mutationId = "torrent-incomplete-device")))
        val restored = library().continueWatching.single()
        assertEquals(180_000L, restored.positionMillis)
        assertEquals(movie().poster, restored.poster)
        assertEquals(movie().backdrop, restored.backdrop)
        assertEquals(movie().date, restored.date)
    }

    @Test fun devicePrivateManualSourcesNeverEnterTheGithubOutbox() = runBlocking {
        val entry = torrentLibraryEntry(file = TorrentVideoFile(0, "manual.mp4", 100_000), infoHash = "b".repeat(40),
            positionMillis = 6_000, durationMillis = 120_000, titleFallback = "Manual video")
        val source = "https://private.example/video.torrent?token=private-source-token"
        TorrentLibraryStore.progress(context, family(), entry)
        TorrentLibraryStore.rememberSource(context, family(), entry.key, source)
        assertEquals(source, TorrentLibraryStore.source(context, family(), entry.key))
        assertNull(TorrentLibraryStore.source(context, kids(), entry.key))
        assertFalse(users.snapshot(family().accountId).any { "private-source-token" in it.payload || "private.example" in it.payload })
        assertNull(library().continueWatching.single().card)
    }

    @Test fun staleMetadataCannotErasePreviouslySavedArtwork() = runBlocking {
        val entry = torrentLibraryEntry(movie(), positionMillis = 60_000, durationMillis = 600_000)
        TorrentLibraryStore.progress(context, family(), entry)
        TorrentLibraryStore.progress(context, family(), entry.copy(poster = null, backdrop = null, date = null,
            positionMillis = 90_000, updatedAt = entry.updatedAt + 1))
        val restored = library().continueWatching.single()
        assertEquals(movie().poster, restored.poster)
        assertEquals(movie().backdrop, restored.backdrop)
        assertEquals("2000-01-01", restored.date)
    }
}
