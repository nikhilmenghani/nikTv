package com.nikhil.niktv.data

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.nikhil.niktv.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class, manifest = Config.NONE)
class MediaIdentityRepositoryTest {
    @Test fun identitySurvivesRepositoryRecreationWithoutChangingProviderItem() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val title = "Example (2020)"
        val movie = TmdbMovie(123, "Example", "Example", null, "poster", null, "2020-01-01", null)
        val item = MediaItem("provider42", title, "provider-poster", "/play/42")
        MediaIdentityRepository(context, TmdbClient()).save(MediaIdentity("profile", CatalogType.MOVIES,
            item.id, title, movie = movie, method = "confirmed"))
        val restored = MediaIdentityRepository(context, TmdbClient()).resolve("profile", CatalogType.MOVIES, item)
        assertEquals(123, restored?.tmdbId)
        assertEquals("confirmed", restored?.method)
        assertEquals("/play/42", item.command)
        assertNull(item.externalTmdbId)
        // A colliding provider id on another profile cannot retrieve this mapping.
        val repository = MediaIdentityRepository(context, TmdbClient())
        val other = repository.cached("another", CatalogType.MOVIES, item)
        assertNull(other)
        assertNull(repository.cached("profile", CatalogType.SERIES, item))
        assertNull(repository.cached("profile", CatalogType.MOVIES, item.copy(title = "Reused id")))
        assertNull(repository.cached("profile", CatalogType.MOVIES, item.copy(externalTmdbId = 999)))
        repository.save(MediaIdentity("profile", CatalogType.MOVIES, item.id, item.title,
            movie = movie.copy(id = 456), method = "matched"))
        assertEquals(123, repository.cached("profile", CatalogType.MOVIES, item)?.tmdbId)
    }
}
