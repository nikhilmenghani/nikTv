package com.nikhil.niktv.sports

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SportsRegionalTest {
    private val config = """{
        "conditions":[{"name":"unsupported","expression":"device.country in ['CA'] && app.version > 5"},{"name":"north","expression":"device.country in ['CA', 'US']"},{"name":"other","expression":"device.country not in ['CA','US']"}],
        "parameters":{
            "cric_api2":{"defaultValue":{"value":"https://default.example.test/"},"conditionalValues":{"unsupported":{"value":"https://wrong.example.test/"},"north":{"value":"https://north.example.test/"},"other":{"value":"https://other.example.test/"}}},
            "cric_api1":{"defaultValue":{"value":"https://backup.example.test/"}},
            "userAgent":{"defaultValue":{"value":"SportsDefault/1"},"conditionalValues":{"north":{"value":"SportsCanada/2"}}}
        }}"""

    @Test fun selectsRegionalValuesInConditionOrderAndIgnoresUnknownExpressions() {
        val canada = sportsConfiguration(config, "ca")
        assertEquals("https://north.example.test/", canada.primary)
        assertEquals("https://backup.example.test/", canada.secondary)
        assertEquals("SportsCanada/2", canada.userAgent)
        assertEquals("https://other.example.test/", sportsConfiguration(config, "IN").primary)
        assertEquals("https://default.example.test/", sportsConfiguration(config, "").primary)
        assertEquals("", sportsCountry("Canada"))
    }

    @Test fun rejectsUnsafeConfigurationAndHeaderValues() {
        try { sportsConfiguration(config.replace("https://default.example.test/", "https://user:pass@example.test/"), ""); fail("Credentials in a base URL must be rejected") }
        catch (_: SportsException) { }
        val sanitized = sportsConfiguration(config.replace("SportsDefault/1", "Bad\\r\\nHeader"), "")
        assertEquals(SPORTS_USER_AGENT, sanitized.userAgent)
    }

    @Test fun geoFilteringUsesWholeCountryCodesAndKeepsWorldwideStreams() {
        assertTrue(sportsGeoAvailable("ALL", "CA"))
        assertTrue(sportsGeoAvailable("in, ca | us", "CA"))
        assertFalse(sportsGeoAvailable("US", "CA"))
        assertFalse(sportsGeoAvailable("CAN", "CA"))
        assertTrue(sportsGeoAvailable("CA", ""))
        assertTrue(sportsGeoAvailable("CA", "Unknown"))
    }

    @Test fun fallsBackToSecondaryUsesRegionalAgentAndRebindsAfterCountryChange() = runBlocking {
        var region = "CA"
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val transport = SportsTransport { url, headers ->
            requests += url to headers
            val body = when {
                url.endsWith("config.txt") -> config
                url.startsWith("https://north.example.test/") -> throw SportsException("Fixture primary outage")
                url.endsWith("live-events.txt") -> "[]"
                url.endsWith("channels/test.txt") -> """{"streamUrls":[
                    {"link":"https://media.example.test/all.m3u8","title":"All","geo":"ALL"},
                    {"link":"https://media.example.test/ca.m3u8","title":"Canada","geo":"CA"},
                    {"link":"https://media.example.test/us.m3u8","title":"USA","geo":"US"},
                    {"link":"https://media.example.test/custom.m3u8","title":"Custom","headers":{"user-agent":"Explicit/3"}}
                ]}"""
                else -> error("Unexpected fixture request")
            }
            SportsResponse(url, body)
        }
        val repo = SportsRepository(transport, country = { region })
        repo.events()
        assertTrue(requests.last().first.startsWith("https://backup.example.test/"))
        assertEquals("SportsCanada/2", requests.last().second["User-Agent"])
        val choices = repo.streams("test")
        assertEquals(listOf("All", "Canada", "Custom"), choices.map { it.title })
        assertEquals("SportsCanada/2", choices.first().headers["User-Agent"])
        assertEquals("Explicit/3", choices.last().headers["user-agent"])
        val count = requests.size
        region = "IN"
        repo.events()
        assertTrue(requests.size > count)
        assertTrue(requests.last().first.startsWith("https://other.example.test/"))
        assertEquals("SportsDefault/1", requests.last().second["User-Agent"])
    }

    @Test fun unknownDeviceCountryRetainsRegionalEventsAndSources() = runBlocking {
        val transport = SportsTransport { url, _ -> SportsResponse(url, when {
            url.endsWith("config.txt") -> config
            url.endsWith("live-events.txt") -> """[{"slug":"event","title":"Fixture","geo":"CA"}]"""
            url.endsWith("channels/event.txt") -> """{"streamUrls":[
                {"link":"https://media.example.test/ca.m3u8","title":"Canada","geo":"CA"},
                {"link":"https://media.example.test/us.m3u8","title":"USA","geo":"US"}
            ]}"""
            else -> error("Unexpected fixture request")
        }) }
        val repo = SportsRepository(transport, country = { "" })
        assertEquals(listOf("event"), repo.events().map { it.slug })
        assertEquals(listOf("Canada", "USA"), repo.streams("event").map { it.title })
    }
}
