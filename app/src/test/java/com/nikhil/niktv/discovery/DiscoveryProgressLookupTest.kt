package com.nikhil.niktv.discovery

import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.NikTvState
import org.junit.Assert.*
import org.junit.Test

class DiscoveryProgressLookupTest {
    private fun media(id: String, title: String = id) = MediaItem(id, title, null, null)
    private fun movie(profile: String, id: String, title: String = id) = RecentItem(
        FavoriteKind.MOVIE, media(id, title), playedAtMillis = 1, profileKey = profile)
    private fun show(profile: String, id: String, season: Int?, number: Int?, alias: String? = null) = RecentItem(
        FavoriteKind.SERIES, media(id), series = alias?.let { media(it) },
        lastPlayed = MediaItem("episode", "Episode", null, null, seasonNumber = season, episodeNumber = number),
        playedAtMillis = 1, profileKey = profile)

    @Test fun unicodeMovieTitlesKeepExactProfilePrefixesAndOriginalOrderForTimestampTies() {
        val recent = movie("catalog|profile", "provider-id", "  ÉL NIÑO | RRR (2022) ")
        val prefix = "progress-v2|catalog|profile|MOVIES|movie:él-niño-rrr-2022|"
        val firstTie = PlaybackProgress(prefix + "z-url", 250, 1000, 10)
        val progress = listOf(
            PlaybackProgress("progress-v2|another|MOVIES|movie:él-niño-rrr-2022|url", 900, 1000, 99),
            firstTie,
            PlaybackProgress(prefix + "a-url", 750, 1000, 10),
            PlaybackProgress(prefix + "old", 100, 1000, 5),
            PlaybackProgress("legacy|movie:él-niño-rrr-2022", 950, 1000, 99),
        )
        val lookup = DiscoveryProgressLookup.build(recent.profileKey, listOf(recent), progress)
        assertSame(firstTie, lookup.recent(recent))
        assertSame(firstTie, discoveryRecentProgress(recent, progress))
        assertEquals(.25f, lookup.provider(CatalogType.MOVIES, recent.media.id)!!, .0001f)
    }

    @Test fun seriesIdentityKeepsSeasonEpisodeAndProviderAliasWithoutParsingPipeDelimitedIds() {
        val recent = show("profile|one", "raw|provider", 2, 3, "series-container")
        val correct = PlaybackProgress("progress-v2|profile|one|SERIES|series:raw|provider|s:2|e:3|url", 400, 1000, 4)
        val progress = listOf(
            correct,
            correct.copy(key = correct.key.replace("s:2", "s:1"), updatedAtMillis = 10),
            correct.copy(key = correct.key.replace("e:3", "e:30"), updatedAtMillis = 11),
            correct.copy(key = correct.key.replace("profile|one", "profile|two"), updatedAtMillis = 12),
        )
        val lookup = DiscoveryProgressLookup.build(recent.profileKey, listOf(recent), progress)
        assertSame(correct, lookup.recent(recent))
        assertEquals(.4f, lookup.provider(CatalogType.SERIES, recent.media.id)!!, .0001f)
        assertEquals(.4f, lookup.provider(CatalogType.SERIES, "series-container")!!, .0001f)
        assertNull(lookup.recent(recent.copy(profileKey = "profile|two")))
        assertNull(lookup.provider(CatalogType.MOVIES, recent.media.id))
    }

    @Test fun firstMatchingRecentWinsEvenWhenItsProgressOrLastPlayedEpisodeIsMissing() {
        val first = movie("p", "same", "Missing")
        val later = movie("p", "same", "Available").copy(playedAtMillis = 100)
        val missingEpisode = show("p", "series", 1, 1, "alias").copy(lastPlayed = null)
        val laterEpisode = show("p", "series", 1, 1, "alias")
        val progress = listOf(
            PlaybackProgress("progress-v2|p|MOVIES|movie:available|url", 200, 1000, 1),
            PlaybackProgress("progress-v2|p|SERIES|series:series|s:1|e:1|url", 400, 1000, 1),
        )
        val lookup = DiscoveryProgressLookup.build("p", listOf(first, later, missingEpisode, laterEpisode), progress)
        assertNull(lookup.provider(CatalogType.MOVIES, "same"))
        assertNull(lookup.provider(CatalogType.SERIES, "series"))
        assertNull(lookup.provider(CatalogType.SERIES, "alias"))
        assertNotNull(lookup.recent(later))
        assertNotNull(lookup.recent(laterEpisode))
    }

    @Test fun latestInvalidDurationDoesNotFallBackAndFractionsRetainTheirExistingClamping() {
        val recents = listOf("invalid", "negative", "complete").map { movie("p", it) }
        val progress = listOf(
            PlaybackProgress("progress-v2|p|MOVIES|movie:invalid|older", 200, 1000, 1),
            PlaybackProgress("progress-v2|p|MOVIES|movie:invalid|latest", 100, 0, 2),
            PlaybackProgress("progress-v2|p|MOVIES|movie:negative|url", -100, 1000, 2),
            PlaybackProgress("progress-v2|p|MOVIES|movie:complete|url", 1500, 1000, 2),
        )
        val lookup = DiscoveryProgressLookup.build("p", recents, progress)
        assertEquals(0L, lookup.recent(recents.first())!!.durationMillis)
        assertNull(lookup.provider(CatalogType.MOVIES, "invalid"))
        assertEquals(0f, lookup.provider(CatalogType.MOVIES, "negative")!!, 0f)
        assertEquals(1f, lookup.provider(CatalogType.MOVIES, "complete")!!, 0f)
        assertNull(DiscoveryProgressLookup.build(null, recents, progress).provider(CatalogType.MOVIES, "complete"))
    }

    @Test fun indexedSnapshotMatchesOriginalAlgorithmsAcrossDuplicateProfilesAliasesSeasonsAndTies() {
        val profile = PortalProfile("Fixture", "https://fixture.invalid", "fixture")
        val own = profile.cacheKey()
        val recents = buildList {
            add(movie("another-profile", "duplicate", "Other"))
            add(movie(own, "duplicate", "A Movie!"))
            add(movie(own, "duplicate", "Later Movie"))
            add(show(own, "series|id", 2, 3, "alias"))
            add(show(own, "series|id", 1, 1, "alias"))
            add(show(own, "unknown", null, null))
            add(show(own, "no-episode", 1, 1, "missing-alias").copy(lastPlayed = null))
            add(show(own, "no-episode", 1, 1, "missing-alias"))
            add(movie(own, "invalid", "Invalid"))
        }
        val progress = buildList {
            recents.forEachIndexed { index, recent ->
                originalPrefix(recent)?.let { prefix ->
                    add(PlaybackProgress(prefix + "z-url", index * 100L, 1000, 10))
                    add(PlaybackProgress(prefix + "a-url", 900, 1000, 10))
                    add(PlaybackProgress(prefix + "older", 100, 1000, 1))
                }
            }
            add(PlaybackProgress("progress-v2|$own|MOVIES|movie:invalid|newer", 100, -1, 100))
            add(PlaybackProgress("unscoped-legacy", 100, 1000, 100))
        }
        val app = NikTvState(savedProfile = profile, recentlyPlayed = recents, playbackProgress = progress)
        val lookup = DiscoveryProgressLookup.build(own, recents, progress)
        recents.filter { it.profileKey == own }.forEach {
            assertEquals(originalRecentProgress(it, progress), lookup.recent(it))
            assertEquals(originalRecentProgress(it, progress), discoveryRecentProgress(it, progress))
        }
        for (type in CatalogType.entries) for (id in listOf("duplicate", "series|id", "alias", "unknown", "no-episode", "missing-alias", "invalid", "absent")) {
            assertEquals("$type/$id", originalProviderProgress(app, type, media(id)), lookup.provider(type, id))
            assertEquals("$type/$id helper", originalProviderProgress(app, type, media(id)), providerDiscoveryProgress(app, type, media(id)))
        }
    }

    // Independent copies of the pre-optimization policies: guard exact behavior during indexing.
    private fun originalPrefix(recent: RecentItem): String? {
        val type = if (recent.kind == FavoriteKind.SERIES) CatalogType.SERIES else CatalogType.MOVIES
        val identity = if (type == CatalogType.SERIES) {
            val episode = recent.lastPlayed ?: return null
            "series:${recent.media.id}|s:${episode.seasonNumber ?: -1}|e:${episode.episodeNumber ?: -1}|"
        } else "movie:${recent.media.title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')}|"
        return "progress-v2|${recent.profileKey}|${type.name}|$identity"
    }
    private fun originalRecentProgress(recent: RecentItem, progress: List<PlaybackProgress>) =
        originalPrefix(recent)?.let { prefix -> progress.filter { it.key.startsWith(prefix) }.maxByOrNull { it.updatedAtMillis } }
    private fun originalProviderProgress(app: NikTvState, type: CatalogType, item: MediaItem): Float? {
        val profile = app.savedProfile?.cacheKey() ?: return null
        val recent = app.recentlyPlayed.firstOrNull { it.profileKey == profile &&
            it.kind == (if (type == CatalogType.SERIES) FavoriteKind.SERIES else FavoriteKind.MOVIE) &&
            (it.media.id == item.id || type == CatalogType.SERIES && it.series?.id == item.id) } ?: return null
        val progress = originalRecentProgress(recent, app.playbackProgress) ?: return null
        return if (progress.durationMillis > 0) (progress.positionMillis.toFloat() / progress.durationMillis).coerceIn(0f, 1f) else null
    }
}
