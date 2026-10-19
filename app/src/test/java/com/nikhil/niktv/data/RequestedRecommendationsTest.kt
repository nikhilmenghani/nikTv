package com.nikhil.niktv.data

import android.app.Application
import com.nikhil.niktv.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class RequestedRecommendationsTest {
    @Test fun explicitSourcesPersistWithoutPlaybackAndRestoreOnlyTheirProfile() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val store = ProfileStore(context)
        val beforeProgress = store.recommendationProgress.first()
        val beforeRecent = store.recentlyPlayed.first()
        val seed = RecentItem(FavoriteKind.MOVIE, MediaItem("tmdb-movie:42", "Movie", null, null), profileKey = "a")
        store.dismissRecommendation("a", "MOVIES:42")
        store.dismissRecommendation("b", "MOVIES:42")
        store.requestRecommendations(seed, "MOVIES:42")
        store.requestRecommendations(seed, "MOVIES:42")
        val reopened = ProfileStore(context)
        assertEquals(listOf(seed), reopened.requestedRecommendations.first())
        assertFalse("MOVIES:42" in reopened.dismissedRecommendations.first()["a"].orEmpty())
        assertTrue("MOVIES:42" in reopened.dismissedRecommendations.first()["b"].orEmpty())
        assertEquals(beforeProgress, reopened.recommendationProgress.first())
        assertEquals(beforeRecent, reopened.recentlyPlayed.first())
        store.dismissRecommendation("a", "MOVIES:42")
        assertTrue("MOVIES:42" in reopened.dismissedRecommendations.first()["a"].orEmpty())
    }
}
