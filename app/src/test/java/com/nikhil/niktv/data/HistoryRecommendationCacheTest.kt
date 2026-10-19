package com.nikhil.niktv.data

import com.nikhil.niktv.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class HistoryRecommendationCacheTest {
    private fun movie(id: Int) = TmdbMovie(id, "Movie $id", "Movie $id", null, null, null, "2020-01-01", null)

    @Test fun cachedRowsExcludeWatchedAndDuplicatesAndRemainProfileScoped() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val tmdb = TmdbClient()
        val identities = MediaIdentityRepository(context, tmdb)
        val item = MediaItem("seed", "Movie 42", null, null)
        val seen = MediaItem("seen", "Movie 21", null, null)
        identities.save(MediaIdentity("a", CatalogType.MOVIES, item.id, item.title, movie = movie(42)))
        identities.save(MediaIdentity("a", CatalogType.MOVIES, seen.id, seen.title, movie = movie(21)))
        identities.save(MediaIdentity("b", CatalogType.MOVIES, item.id, item.title, movie = movie(42)))
        val seed = RecentItem(FavoriteKind.MOVIE, item, profileKey = "a")
        val watched = listOf(seed, RecentItem(FavoriteKind.MOVIE, seen, profileKey = "a"))
        var calls = 0
        fun repository() = HistoryRecommendations(context, tmdb, identities,
            moviesFor = { calls++; listOf(movie(42), movie(21), movie(10), movie(10), movie(11)) },
            configured = { true })
        val rows = repository().load("a", listOf(seed), watched)
        assertEquals(listOf(10, 11), rows.single().movies.map { it.id })
        assertEquals("a", rows.single().profile)
        assertEquals(rows, repository().load("a", listOf(seed), watched))
        assertEquals(1, calls)
        val other = repository().load("b", listOf(seed.copy(profileKey = "b")))
        assertEquals("b", other.single().profile)
        assertEquals(2, calls)
        assertTrue(repository().load("a", listOf(seed), watched, setOf("MOVIES:42")).isEmpty())
        assertEquals(2, calls) // A dismissed seed must not fetch suggestions or reappear from cache.
        assertEquals(rows, repository().load("a", listOf(seed), watched))
        val chosen = repository().load("a", listOf(seed), watched, requested = setOf("MOVIES:42"))
        assertEquals("Because you chose Movie 42", chosen.single().title)
        assertTrue(repository().load("a", emptyList()).isEmpty())
    }
}
