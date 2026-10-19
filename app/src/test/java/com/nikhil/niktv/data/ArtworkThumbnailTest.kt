package com.nikhil.niktv.data

import org.junit.Assert.*
import org.junit.Test
import com.nikhil.niktv.model.MediaItem

class ArtworkThumbnailTest {
    @Test fun posterUsesThumbnailButHeroKeepsResolution() {
        assertEquals("https://image.tmdb.org/t/p/w342/poster.jpg",
            thumbnailArtworkUrl("https://image.tmdb.org/t/p/w500/poster.jpg", 0))
        assertEquals("https://image.tmdb.org/t/p/w185/poster.jpg",
            thumbnailArtworkUrl("https://image.tmdb.org/t/p/w500/poster.jpg", 180))
        assertEquals("https://image.tmdb.org/t/p/w1280/hero.jpg",
            thumbnailArtworkUrl("https://image.tmdb.org/t/p/w1280/hero.jpg", 0))
        assertEquals("https://provider.test/poster?token=abc",
            thumbnailArtworkUrl("https://provider.test/poster?token=abc", 180))
        assertNull(thumbnailArtworkUrl(null, 180))
    }

    @Test fun qualityCopiesShareTmdbPosterButLargerImagesHaveSeparateCacheEntries() {
        val a = MediaItem("one", "Movie HD", "https://image.tmdb.org/t/p/w500/poster.jpg", null)
        val b = a.copy(id = "two", title = "Movie 4K")
        assertEquals(a.artworkCacheKey(), b.artworkCacheKey())
        assertNotEquals(a.artworkCacheKey(185), a.artworkCacheKey(780))
        assertEquals(a.copy(logo = "https://provider/poster?token=old").artworkCacheKey(),
            a.copy(logo = "https://provider/poster?token=new").artworkCacheKey())
    }
}
