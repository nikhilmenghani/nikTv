package com.nikhil.niktv.torrent

import com.nikhil.niktv.data.TmdbEpisode
import com.nikhil.niktv.discovery.DiscoveryCard
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class TorrentSearchRepositoryTest {
    private val movie = DiscoveryCard(10378, false, "Big Buck Bunny", date = "2008-04-10")
    private val series = DiscoveryCard(1, true, "Example Show", date = "2019-01-01")
    private val episode = TmdbEpisode(2, 3, "An episode", null, "2026-01-02", null)
    private val hash = "1234567890abcdef1234567890abcdef12345678"
    private fun listing(title: String = "Big.Buck.Bunny.2008.1080p.WEB-DL", seeds: Int = 12,
        magnet: String? = null) = RawTorrentListing(title, hash, 600_000_000L, seeds, magnet)

    @Test fun movieRequiresTheSelectedTitleAndYear() {
        assertTrue(torrentListingMatches("Big.Buck.Bunny.2008.1080p.WEB-DL", movie))
        assertFalse(torrentListingMatches("Big Buck Bunny 2024 1080p", movie))
        assertFalse(torrentListingMatches("Big Buck Bunny Returns 2008 1080p", movie))
        assertFalse(torrentListingMatches("Big Buck Bunny 1080p", movie))
        assertFalse(torrentListingMatches("A Big Buck Bunny 2008 1080p", movie))
    }

    @Test fun aYearInTheActualTitleIsNotMistakenForTheReleaseYear() {
        val film = DiscoveryCard(2, false, "2001: A Space Odyssey", date = "1968-04-02")
        assertTrue(torrentListingMatches("2001.A.Space.Odyssey.1968.1080p", film))
        assertFalse(torrentListingMatches("2001.A.Space.Odyssey.2001.1080p", film))
    }

    @Test fun pre1900MoviesStillRequireTheirExactTmdbReleaseYear() {
        val film = DiscoveryCard(127, false, "Roundhay Garden Scene", date = "1888-10-14")
        assertTrue(torrentListingMatches("Roundhay.Garden.Scene.1888.1080p", film))
        assertFalse(torrentListingMatches("Roundhay.Garden.Scene.1988.1080p", film))
        assertFalse(torrentListingMatches("Roundhay.Garden.Scene.1080p", film))
    }

    @Test fun previewsAndCamReleasesAreExcluded() {
        for (word in listOf("sample", "trailer", "preview", "HDCAM", "CAMRip", "telesync"))
            assertNull(sourceFromListing(listing("Big Buck Bunny 2008 $word 1080p"), movie, null, "Fixture"))
    }

    @Test fun episodeMatchingNeverUsesAnotherSeasonOrEpisode() {
        assertTrue(torrentListingMatches("Example.Show.S02E03.1080p", series, episode))
        assertTrue(torrentListingMatches("Example Show 2x03 720p", series, episode))
        assertFalse(torrentListingMatches("Example.Show.S02E30.1080p", series, episode))
        assertFalse(torrentListingMatches("Example.Show.S01E03.1080p", series, episode))
        assertFalse(torrentListingMatches("Example.Show.S02E03.1080p", series))
    }

    @Test fun tvTitlePrefixCannotSelectAnotherShowWithTheSameEpisodeNumbers() {
        val friends = DiscoveryCard(1668, true, "Friends", date = "1994-09-22")
        val target = episode.copy(seasonNumber = 1, episodeNumber = 1)
        for (name in listOf("Friends From College S01E01 1080p", "Friends With Benefits S01E01",
                "Friends The Reunion S01E01", "Friends From College S01 COMPLETE")) {
            assertFalse(name, torrentListingMatches(name, friends, target))
        }
    }

    @Test fun tvTitleBoundaryStillAllowsYearsResolutionSeasonPacksAndExplicitAliases() {
        val friends = DiscoveryCard(1668, true, "Friends", date = "1994-09-22")
        val target = episode.copy(seasonNumber = 1, episodeNumber = 1)
        for (name in listOf("Friends [1994] S01E01", "Friends.S01.COMPLETE", "Friends Season1",
                "Friends Season 1", "Friends 1080p S01E01", "Friends 1994 2160p S01E01")) {
            assertTrue(name, torrentListingMatches(name, friends, target))
        }
        assertTrue(torrentListingMatches("Amigos S01E01 1080p", friends, target, aliases = listOf("Amigos")))
        assertFalse(torrentListingMatches("Friends [2004] S01E01", friends, target))
    }

    @Test fun seasonPacksMatchOnlyTheirOwnSeason() {
        val pack = sourceFromListing(listing("Example.Show.S02.Complete.1080p"), series, episode, "Fixture")
        assertNotNull(pack)
        assertTrue(pack!!.seasonPack)
        assertNull(sourceFromListing(listing("Example.Show.Season.1.Complete.1080p"), series, episode, "Fixture"))
    }

    @Test fun episodeFileSelectionHasNumericBoundaries() {
        assertTrue(torrentEpisodeFileMatches("Show/Show.S02E003.mkv", 2, 3))
        assertFalse(torrentEpisodeFileMatches("Show/Show.S02E030.mkv", 2, 3))
        assertFalse(torrentEpisodeFileMatches("Show/Show.12x03.mkv", 2, 3))
        assertFalse(torrentEpisodeFileMatches("Show.S02E03/Show.S02E04.mkv", 2, 3))
        assertTrue(torrentEpisodeFileMatches("Show/Show_S02E03.mkv", 2, 3))
    }

    @Test fun longRunningSeriesKeepFullEpisodeNumbersAndExactBoundaries() {
        val target = episode.copy(seasonNumber = 1, episodeNumber = 5000)
        assertTrue(torrentListingMatches("Example.Show.S01E5000.1080p", series, target))
        assertTrue(torrentListingMatches("Example.Show.S0001E005000.1080p", series, target))
        assertFalse(torrentListingMatches("Example.Show.S01E50001.1080p", series, target))
        assertTrue(torrentEpisodeFileMatches("Show/Show.S01E5000.mkv", 1, 5000))
        assertFalse(torrentEpisodeFileMatches("Show/Show.S01E50001.mkv", 1, 5000))
        assertFalse(torrentEpisodeFileMatches("Show.S01E5000/Show.S01E5001.mkv", 1, 5000))
    }

    @Test fun fourDigitSeasonsAndSixDigitEpisodesDoNotOverflowOrTruncate() {
        val target = episode.copy(seasonNumber = 1234, episodeNumber = 500000)
        assertTrue(torrentListingMatches("Example.Show.S1234E500000.1080p", series, target))
        assertTrue(torrentEpisodeFileMatches("Show/Show.1234x500000.mkv", 1234, 500000))
        assertFalse(torrentEpisodeFileMatches("Show/Show.1234x5000001.mkv", 1234, 500000))
        assertFalse(torrentEpisodeFileMatches("Show/Show.S12345E500000.mkv", 1234, 500000))
        assertFalse(torrentEpisodeFileMatches("Show/Show.S01E2147483648.mkv", 1, 214748364))
        assertTrue(torrentSeasonPack("Example.Show.Season.1234.Complete", 1234))
        assertFalse(torrentSeasonPack("Example.Show.Season.12345.Complete", 1234))
    }

    @Test fun oversizedEpisodeMarkersNeverFallBackToSeasonPackSelection() {
        val target = episode.copy(seasonNumber = 1, episodeNumber = 5000)
        for (code in listOf("S01E2147483648", "S01 E2147483648", "1x2147483648", "S01E5000001")) {
            assertFalse(code, torrentListingMatches("Example.Show.$code.1080p", series, target))
            assertFalse(code, torrentSeasonPack("Example.Show.$code.1080p", 1))
        }
    }

    @Test fun zeroSeedListingsAndInvalidHashesCannotBecomePlayableResults() {
        assertNull(sourceFromListing(listing(seeds = 0), movie, null, "Fixture"))
        assertNull(sourceFromListing(listing(seeds = -1), movie, null, "Fixture"))
        assertNull(sourceFromListing(listing().copy(hash = "0".repeat(40)), movie, null, "Fixture"))
        assertNull(sourceFromListing(listing().copy(size = 0), movie, null, "Fixture"))
    }

    @Test fun aConflictingMagnetCannotOverrideTheSearchResultHash() {
        val other = "a".repeat(40)
        assertNull(sourceFromListing(listing(magnet = "magnet:?xt=urn:btih:$other"), movie, null, "Fixture"))
        assertNull(matchingTorrentMagnet("magnet:?xt=urn:btih:$hash&xt=urn:btih:$other", hash))
        assertNotNull(sourceFromListing(listing(magnet = "magnet:?xt=urn%3Abtih%3A$hash"), movie, null, "Fixture"))
    }

    @Test fun duplicateSourcesMergeByHashAndPreserveTheBestPeerCount() {
        val a = requireNotNull(sourceFromListing(listing(seeds = 3), movie, null, "A"))
        val b = a.copy(seeders = 27, provider = "B", torrentUrl = "https://example.com/legal.torrent")
        val rows = mergeTorrentSources(listOf(a, b))
        assertEquals(1, rows.size)
        assertEquals(27, rows.single().seeders)
        assertEquals("A · B", rows.single().provider)
        assertEquals(b.torrentUrl, rows.single().torrentUrl)
    }

    @Test fun apiBayParsesStringCountsAndItsNoResultSentinelIsRejected() {
        val body = Json.parseToJsonElement("""[{"name":"Big Buck Bunny 2008 1080p","info_hash":"$hash","size":"600000000","seeders":"20"},{"name":"No results returned","info_hash":"0000000000000000000000000000000000000000","size":"0","seeders":"0"}]""")
        val results = parseApiBay(body).mapNotNull { sourceFromListing(it, movie, null, "The Pirate Bay") }
        assertEquals(1, results.size)
        assertEquals(20, results.single().seeders)
    }

    @Test fun csvAcceptsItsDifferentFieldNames() {
        val body = Json.parseToJsonElement("""{"torrents":[{"name":"Big Buck Bunny 2008 720p","infohash":"$hash","size_bytes":1000,"seeders":2}]}""")
        val row = parseTorrentsCsv(body).single()
        assertEquals(hash, row.hash)
        assertEquals(1000L, row.size)
    }

    @Test fun torrentClawMustAgreeWithTheTmdbIdentity() {
        fun body(id: Int) = Json.parseToJsonElement("""{"results":[{"tmdbId":$id,"title":"Big Buck Bunny","year":2008,"torrents":[{"rawTitle":"Big Buck Bunny 2008 1080p","infoHash":"$hash","sizeBytes":1000,"seeders":3}]}]}""")
        assertEquals(1, parseTorrentClaw(body(movie.id), movie).size)
        assertTrue(parseTorrentClaw(body(99), movie).isEmpty())
    }

    @Test fun metadataOnlyTorrentClawResponseExplainsItsMissingPlaybackLinks() {
        val body = Json.parseToJsonElement("""{"_notice":"Magnet links, info hashes, and torrent downloads require a free API key.","results":[{"tmdbId":10378,"title":"Big Buck Bunny","year":2008,"torrents":[{"rawTitle":"Big Buck Bunny 2008 1080p","sizeBytes":1000,"seeders":3}]}]}""")
        assertThrows(TorrentSourceAccessRequired::class.java) { parseTorrentClaw(body, movie) }
    }

    @Test fun bitsearchExtractsDescriptorsWithoutAMagnetAndDecodesHtml() {
        val html = """<div class="torrent-result-card"><a href="/torrent/legal">Big Buck Bunny 2008 &amp; 1080p</a><span>1.25 GB</span><span>1,024</span><span>seeders</span><a href="/download/torrent/$hash">Download</a></div>"""
        val row = parseBitsearch(html).single()
        assertEquals(1_342_177_280L, row.size)
        assertEquals(1024, row.seeds)
        assertEquals(hash, row.hash)
        assertEquals("https://bitsearch.eu/download/torrent/$hash", row.torrent)
        assertTrue(row.title.contains('&'))
    }

    @Test fun failuresAreNotReportedAsSuccessfulEmptySearches() {
        assertThrows(IllegalArgumentException::class.java) { parseApiBay(Json.parseToJsonElement("{}")) }
        assertThrows(IllegalStateException::class.java) { parseTorrentsCsv(Json.parseToJsonElement("{}")) }
        val snapshot = TorrentSearchSnapshot(errors = mapOf("Fixture" to "Timed out"))
        assertTrue(snapshot.finished)
        assertTrue(snapshot.results.isEmpty())
        assertEquals("Timed out", snapshot.errors["Fixture"])
    }
}
