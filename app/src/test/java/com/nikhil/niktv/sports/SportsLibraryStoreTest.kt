package com.nikhil.niktv.sports

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SportsLibraryStoreTest {
    @Test fun favouriteAndPlaylistPersistenceNeverWritesTheIptvPreferences() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("sports_library_v1", 0).edit().clear().commit()
        val iptv = context.getSharedPreferences("profile_store", 0)
        iptv.edit().putString("fixture", "IPTV unchanged").commit()
        val before = iptv.all.toMap()
        val store = SportsLibraryStore(context)
        val provider = store.addUrl("Fixture provider", "https://playlist.example.test/list?token=fixture")
        assertTrue(SportsLibraryStore(context).playlists().single().custom)
        val native = SportsChannel("1", "Native", "native-")
        val playlist = SportsChannel("1", "Playlist TV", "", playlist = provider,
            direct = SportsStream("https://cdn.example.test/live.mpd", "TV", headers = mapOf("Referer" to "https://origin.example.test/")))
        store.toggle(native); store.toggle(playlist)
        val reopened = SportsLibraryStore(context).favourites()
        assertEquals(setOf(native.key, playlist.key), reopened.map { it.key }.toSet())
        assertEquals("https://origin.example.test/", reopened.first { it.playlist != null }.direct!!.headers["Referer"])
        store.toggle(native)
        assertEquals(listOf(playlist.key), store.favourites().map { it.key })
        assertTrue(store.remove(provider).isEmpty())
        assertEquals(before, iptv.all)
    }
}
