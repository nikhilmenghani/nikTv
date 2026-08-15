package com.nikhil.niktv

import com.nikhil.niktv.model.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesEpisodeSortTest {

    private fun String.episodeNumberFromTitle(): Int? {
        val patterns = listOf(
            Regex("(?i)S\\d+[ ._-]*E(?:P(?:ISODE)?)?[ ._-]*(\\d+)"),
            Regex("(?i)\\bEP(?:ISODE)?[ ._:-]*(\\d+)"),
            Regex("(?i)\\bE[ ._:-]*(\\d+)"),
            Regex("\\b(\\d+)\\b")
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.findAll(this).lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
    }

    private fun String.seasonNumberFromTitle(): Int? {
        val patterns = listOf(
            Regex("(?i)S(?:EASON)?[ ._-]*(\\d+)"),
            Regex("(?i)S(\\d+)[ ._-]*E"),
            Regex("(?i)Season[ ._-]*(\\d+)")
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.findAll(this).firstOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
    }

    private fun naturalTitleCompare(first: String, second: String): Int {
        val tokenPattern = Regex("\\d+|\\D+")
        val firstTokens = tokenPattern.findAll(first.lowercase()).map { it.value }.toList()
        val secondTokens = tokenPattern.findAll(second.lowercase()).map { it.value }.toList()
        for (index in 0 until minOf(firstTokens.size, secondTokens.size)) {
            val firstToken = firstTokens[index]
            val secondToken = secondTokens[index]
            val comparison = if (firstToken.all(Char::isDigit) && secondToken.all(Char::isDigit))
                (firstToken.toLongOrNull() ?: Long.MAX_VALUE).compareTo(secondToken.toLongOrNull() ?: Long.MAX_VALUE)
            else firstToken.compareTo(secondToken)
            if (comparison != 0) return comparison
        }
        return firstTokens.size.compareTo(secondTokens.size)
    }

    private fun episodeComparator(descending: Boolean): Comparator<MediaItem> = Comparator { first, second ->
        val firstSeason = first.seasonNumber ?: first.title.seasonNumberFromTitle()
        val secondSeason = second.seasonNumber ?: second.title.seasonNumberFromTitle()
        val seasonComp = when {
            firstSeason != null && secondSeason != null && firstSeason != secondSeason -> firstSeason.compareTo(secondSeason)
            firstSeason != null && secondSeason == null -> 1
            firstSeason == null && secondSeason != null -> -1
            else -> 0
        }
        if (seasonComp != 0) {
            return@Comparator if (descending) -seasonComp else seasonComp
        }
        val firstEp = first.episodeNumber ?: first.title.episodeNumberFromTitle()
        val secondEp = second.episodeNumber ?: second.title.episodeNumberFromTitle()
        val epComp = when {
            firstEp != null && secondEp != null && firstEp != secondEp -> firstEp.compareTo(secondEp)
            firstEp != null && secondEp == null -> 1
            firstEp == null && secondEp != null -> -1
            else -> naturalTitleCompare(first.title, second.title)
        }
        if (descending) -epComp else epComp
    }

    @Test
    fun testDailyEpisodesDescendingSort() {
        val episodes = listOf(
            MediaItem("1", "TMKOC - Episode 100", null, null, episodeNumber = 100),
            MediaItem("2", "TMKOC - Episode 4250", null, null, episodeNumber = 4250),
            MediaItem("3", "TMKOC - Episode 4249", null, null, episodeNumber = 4249),
            MediaItem("4", "TMKOC - Episode 1", null, null, episodeNumber = 1)
        )

        val sortedDescending = episodes.sortedWith(episodeComparator(descending = true))
        assertEquals(listOf("4250", "4249", "100", "1"), sortedDescending.map { it.episodeNumber?.toString() })

        val sortedAscending = episodes.sortedWith(episodeComparator(descending = false))
        assertEquals(listOf("1", "100", "4249", "4250"), sortedAscending.map { it.episodeNumber?.toString() })
    }

    @Test
    fun testSeasonAndEpisodeDescendingSort() {
        val episodes = listOf(
            MediaItem("s1e1", "Pilot", null, null, seasonNumber = 1, episodeNumber = 1),
            MediaItem("s1e24", "Season 1 Finale", null, null, seasonNumber = 1, episodeNumber = 24),
            MediaItem("s2e1", "Season 2 Premiere", null, null, seasonNumber = 2, episodeNumber = 1),
            MediaItem("s2e10", "Season 2 Finale", null, null, seasonNumber = 2, episodeNumber = 10)
        )

        val sortedDescending = episodes.sortedWith(episodeComparator(descending = true))
        assertEquals(listOf("s2e10", "s2e1", "s1e24", "s1e1"), sortedDescending.map { it.id })

        val sortedAscending = episodes.sortedWith(episodeComparator(descending = false))
        assertEquals(listOf("s1e1", "s1e24", "s2e1", "s2e10"), sortedAscending.map { it.id })
    }

    @Test
    fun testEpisodeNumberExtractionFromTitle() {
        assertEquals(4250, "Taarak Mehta Ka Ooltah Chashmah Episode 4250".episodeNumberFromTitle())
        assertEquals(12, "S03E12 - The Reunion".episodeNumberFromTitle())
        assertEquals(5, "Special EP 5".episodeNumberFromTitle())
    }

    @Test
    fun testSeasonNumberExtractionFromTitle() {
        assertEquals(3, "S03E12 - The Reunion".seasonNumberFromTitle())
        assertEquals(2, "Season 2 Episode 5".seasonNumberFromTitle())
        assertEquals(1, "S1:E24".seasonNumberFromTitle())
    }
}
