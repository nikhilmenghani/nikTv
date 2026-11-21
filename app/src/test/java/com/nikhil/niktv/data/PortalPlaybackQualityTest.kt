package com.nikhil.niktv.data

import com.nikhil.niktv.model.PortalPlaybackVariant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PortalPlaybackQualityTest {
    private val files = listOf(
        PortalPlaybackVariant("720", "Hindi / 720p", 720, "Hindi", "720"),
        PortalPlaybackVariant("1080", "Hindi / 1080p", 1080, "Hindi", "1080"),
        PortalPlaybackVariant("english", "English / 2160p", 2160, "English", "english")
    )

    @Test fun bestRanksHighestWithinTheProvidersLanguage() {
        assertEquals(listOf("1080", "720", "english"), rankedPortalVariants(files, 0).map { it.id })
    }

    @Test fun manualResolutionAndExplicitFileHavePriority() {
        assertEquals("720", rankedPortalVariants(files, 720).first().id)
        assertEquals("720", rankedPortalVariants(files, 0, "720").first().id)
        assertEquals("english", rankedPortalVariants(files, 0, "english").first().id)
    }

    @Test fun unavailableResolutionUsesTheBestLowerQuality() {
        assertEquals("720", rankedPortalVariants(files, 900).first().id)
        assertEquals("720", rankedPortalVariants(files, 480).first().id)
    }

    @Test fun providerNamesAndUhdLabelsSupplyResolution() {
        val rows = listOf(
            """{"id":"low","name":"Hindi / Excellent quality (720)","quality":"2"}""",
            """{"id":"high","name":"Hindi / Excellent quality (1080)"}""",
            """{"id":"uhd","name":"Hindi / 4K"}"""
        ).map { Json.parseToJsonElement(it).jsonObject }
        val parsed = portalPlaybackVariants(rows)
        assertEquals(listOf(720, 1080, 2160), parsed.map { it.height })
        assertEquals("uhd", rankedPortalVariants(parsed, 0).first().id)
        assertEquals("/media/file_high.mpg", parsed[1].command)
    }
}
