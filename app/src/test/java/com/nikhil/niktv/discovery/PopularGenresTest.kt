package com.nikhil.niktv.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class PopularGenresTest {
    @Test fun browsingFiltersGenreAndReleaseDateWithoutProviderAvailability() {
        val (path, query) = genreTitleRequest(false, 35, 2, today = "2026-11-13")
        assertEquals("/discover/movie", path)
        assertEquals("35", query["with_genres"])
        assertEquals("2", query["page"])
        assertEquals("false", query["include_adult"])
        assertEquals("2026-11-13", query["primary_release_date.lte"])
        assertFalse(query.containsKey("with_watch_providers"))
    }
    @Test fun wallpaperUsesVoteCountAndMediaSpecificThreshold() {
        val movie = genreTitleRequest(false, 35, 1, wallpaper = true).second
        val (path, tv) = genreTitleRequest(true, 10759, 1, wallpaper = true)
        assertEquals("vote_count.desc", movie["sort_by"])
        assertEquals("800", movie["vote_count.gte"])
        assertEquals("en", movie["with_original_language"])
        assertEquals("/discover/tv", path)
        assertEquals("200", tv["vote_count.gte"])
        assertFalse(tv.containsKey("with_original_language"))
    }
    @Test fun artworkPrefersPrimaryGenreAndSkipsMissingOrAdultPosters() {
        val root = Json.parseToJsonElement("""{"results":[
          {"genre_ids":[35],"backdrop_path":"/wide-only.jpg"},
          {"genre_ids":[35],"adult":true,"poster_path":"/adult.jpg"},
          {"genre_ids":[18,35],"poster_path":"/secondary.jpg"},
          {"genre_ids":[35,18],"poster_path":"/primary.jpg"}] }""").jsonObject
        assertEquals("https://image.tmdb.org/t/p/w500/primary.jpg", genrePoster(root, 35))
    }
    @Test fun absentArtworkFallsBackWithoutBrokenImageUrl() {
        assertNull(genrePoster(Json.parseToJsonElement("""{"results":[{"genre_ids":[35],"poster_path":null}]}""").jsonObject, 35))
    }
}
