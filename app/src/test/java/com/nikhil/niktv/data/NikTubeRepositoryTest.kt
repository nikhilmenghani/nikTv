package com.nikhil.niktv.data

import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class NikTubeRepositoryTest {
    @Test fun playlistsAndSubscriptionsStaySeparateFromHistory() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("niktube-library.db")
        val repository = NikTubeRepository(context)
        try {
            val video = NikTubeVideo("jNQXAC9IVRw", "Video", "Channel")
            repository.savePlaylists(mapOf("Watch Later" to listOf(video), "My playlist" to emptyList()))
            repository.saveSubscriptions(listOf(NikTubeChannel("UCabc", "Channel", "https://example.org/avatar")))
            assertEquals(listOf(video), repository.playlists()["Watch Later"])
            assertTrue(repository.playlists().containsKey("My playlist"))
            assertEquals("UCabc", repository.subscriptions().single().id)
            assertTrue(repository.history().isEmpty())
        } finally { repository.close() }
    }
    @Test fun historyMigrationPreservesPositionAndDoesNotOverwriteNewLibrary() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("niktube-library.db")
        context.getSharedPreferences("niktube_draft", 0).edit().putString("history", """[{"id":"jNQXAC9IVRw","title":"Original","channel":"jawed","position":12000}]""").commit()
        val repository = NikTubeRepository(context)
        try {
            repository.migrateHistory()
            assertEquals(12000L, repository.history().single().second)
            val replacement = NikTubeVideo("jNQXAC9IVRw", "Updated", "jawed", "0:19", "UCabc", "https://example.org/avatar")
            repository.saveHistory(listOf(replacement to 15000L))
            repository.migrateHistory()
            assertEquals(listOf(replacement to 15000L), repository.history())
        } finally { repository.close() }
    }
    @Test fun pageCachePreservesContinuationAndSeparatesSearchFromHome() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("niktube-library.db")
        val repository = NikTubeRepository(context)
        try {
            val page = NikTubePage(listOf(NikTubeVideo("jNQXAC9IVRw", "Video", "Channel")), "next-page")
            repository.cache("home", page)
            assertEquals(page, repository.cached("home"))
            assertNull(repository.cached("search:home"))
        } finally { repository.close() }
    }
}
