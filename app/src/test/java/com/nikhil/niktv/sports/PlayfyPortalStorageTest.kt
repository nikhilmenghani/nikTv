package com.nikhil.niktv.sports

import android.app.Application
import android.net.Uri
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PlayfyPortalStorageTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun resetFixturePreferences() {
        for (namespace in listOf("sports", "playfy")) {
            for (suffix in listOf("library_v1", "favorites_v2", "availability_v1", "browse", "resolver_settings", "playback_settings")) {
                context.getSharedPreferences("${namespace}_$suffix", 0).edit().clear().commit()
            }
        }
    }

    @Test fun favouritesRemainIndependentEvenWhenBothProvidersUseTheSameChannelKey() {
        val cricfy = SportsLibraryStore(context)
        val original = SportsChannel("17", "Existing Cricfy favourite", "same-key")
        cricfy.toggle(original)
        val before = context.getSharedPreferences("sports_library_v1", 0).all.toMap()

        val playfy = SportsLibraryStore(context, "playfy")
        assertTrue(playfy.favourites().isEmpty())
        val replacement = original.copy(title = "Playfy favourite")
        playfy.toggle(replacement)
        assertEquals(listOf(replacement), SportsLibraryStore(context, "playfy").favourites())
        assertEquals(listOf(original), SportsLibraryStore(context).favourites())
        playfy.toggle(replacement)
        assertTrue(SportsLibraryStore(context, "playfy").favourites().isEmpty())
        assertEquals(listOf(original), SportsLibraryStore(context).favourites())
        assertEquals(before, context.getSharedPreferences("sports_library_v1", 0).all)
    }

    @Test fun playfyPlaybackEngineAndTimeoutDoNotChangeCricfySettings() = runBlocking {
        val cricfy = SportsPlaybackPreferences(context)
        cricfy.setPlaybackEngine(com.nikhil.niktv.model.PlaybackEngine.FLOW)
        cricfy.setPlayerControlsTimeoutSeconds(17)
        val playfy = SportsPlaybackPreferences(context, namespace = "playfy")
        assertEquals(SportsPlaybackSettings(), playfy.settings.first())
        playfy.setPlayerControlsTimeoutSeconds(8)
        assertEquals(SportsPlaybackSettings(17, com.nikhil.niktv.model.PlaybackEngine.FLOW), cricfy.settings.first())
        assertEquals(8, SportsPlaybackPreferences(context, namespace = "playfy").settings.first().timeout)
    }

    @Test fun addingOrRemovingAPlayfyUrlPlaylistDoesNotChangeExistingCricfyPlaylists() {
        val cricfy = SportsLibraryStore(context)
        val original = cricfy.addUrl("Existing list", "https://playlist.example.test/shared.m3u")
        val playfy = SportsLibraryStore(context, "playfy")
        assertTrue(playfy.playlists().isEmpty())
        val own = playfy.addUrl("Playfy list", original.link)
        assertEquals("The same URL deliberately produces the same ID", original.id, own.id)
        assertEquals(listOf(own), SportsLibraryStore(context, "playfy").playlists())
        assertEquals(listOf(original), SportsLibraryStore(context).playlists())
        playfy.remove(own)
        assertTrue(SportsLibraryStore(context, "playfy").playlists().isEmpty())
        assertEquals(listOf(original), SportsLibraryStore(context).playlists())
    }

    @Test fun removingAnIdenticalImportedPlayfyFileCannotDeleteTheCricfyCopy() {
        val body = "#EXTM3U\n#EXTINF:-1,Fixture TV\nhttps://media.example.test/live.m3u8\n"
        val source = File(context.cacheDir, "shared-playlist.m3u").apply { writeText(body) }
        val cricfy = SportsLibraryStore(context)
        val original = cricfy.importFile(Uri.fromFile(source), "Cricfy import")
        val playfy = SportsLibraryStore(context, "playfy")
        assertTrue(playfy.playlists().isEmpty())
        val own = playfy.importFile(Uri.fromFile(source), "Playfy import")
        assertEquals(original.id, own.id)
        assertEquals(body, playfy.localBody(own))
        playfy.remove(own)
        assertEquals(body, SportsLibraryStore(context).localBody(original))
        assertEquals(listOf(original), SportsLibraryStore(context).playlists())
    }

    @Test fun playfyAvailabilityDoesNotInheritOrOverwriteCricfyFlagsAndFilters() {
        val cricfy = SportsAvailabilityStore(context, now = { 10L })
        cricfy.flag("same-key", SportsFlagReason.MANUAL)
        cricfy.setFilter(SportsAvailabilityFilter.FLAGGED_ONLY)
        val before = context.getSharedPreferences("sports_availability_v1", 0).all.toMap()

        val playfy = SportsAvailabilityStore(context, "playfy", now = { 20L })
        assertTrue(playfy.flags().isEmpty())
        assertEquals(SportsAvailabilityFilter.ALL, playfy.filter())
        playfy.flag("same-key", SportsFlagReason.PLAYBACK_FAILED)
        playfy.setFilter(SportsAvailabilityFilter.HIDE_FLAGGED)
        val reopened = SportsAvailabilityStore(context, "playfy")
        assertEquals(SportsChannelFlag(SportsFlagReason.PLAYBACK_FAILED, 20L), reopened.flags()["same-key"])
        assertEquals(SportsAvailabilityFilter.HIDE_FLAGGED, reopened.filter())
        playfy.clear("same-key")
        assertEquals(SportsChannelFlag(SportsFlagReason.MANUAL, 10L), SportsAvailabilityStore(context).flags()["same-key"])
        assertEquals(SportsAvailabilityFilter.FLAGGED_ONLY, SportsAvailabilityStore(context).filter())
        assertEquals(before, context.getSharedPreferences("sports_availability_v1", 0).all)
    }

    @Test fun playfyBrowseAndResolverChoicesDoNotReadOrChangeCricfyDefaults() {
        val cricfyBrowse = SportsBrowsePreferences(context)
        cricfyBrowse.setSport("Cricket")
        cricfyBrowse.setStatus("All events")
        SportsResolverPreferences(context).set(SportsResolverMode.LEGACY)

        val playfyBrowse = SportsBrowsePreferences(context, "playfy")
        assertEquals(SportsBrowseDefaults(), playfyBrowse.read())
        assertEquals(SportsResolverMode.TOKEN_AWARE, SportsResolverPreferences(context, "playfy").read())
        playfyBrowse.setSport("Football")
        playfyBrowse.setStatus("Live")
        SportsResolverPreferences(context, "playfy").set(SportsResolverMode.TOKEN_AWARE)
        assertEquals(SportsBrowseDefaults("Football", "Live"), SportsBrowsePreferences(context, "playfy").read())
        assertEquals(SportsBrowseDefaults("Cricket", "All events"), SportsBrowsePreferences(context).read())
        assertEquals(SportsResolverMode.LEGACY, SportsResolverPreferences(context).read())
    }
}
