package com.nikhil.niktv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class ProviderEpisodeTimeTest {
    private fun parse(value: String) = Json.parseToJsonElement(value).jsonObject.providerEpisodeAddedTime()

    @Test fun acceptsProviderSecondsAndMilliseconds() {
        assertEquals(1_700_000_000_000L, parse("""{"added":"1700000000"}"""))
        assertEquals(1_700_000_000_000L, parse("""{"date_added":1700000000000}"""))
    }
    @Test fun offsetTimestampsPreserveTheActualInstant() {
        assertEquals(1_700_000_000_000L, parse("""{"created_at":"2023-11-14T17:13:20-05:00"}"""))
    }
    @Test fun airDatesDurationsAndUnknownTimezonesNeverBecomeUploadTimes() {
        assertNull(parse("""{"air_date":"2023-11-14","duration":1320,"timestamp":1700000000}"""))
        assertNull(parse("""{"added":"2023-11-14"}"""))
        assertNull(parse("""{"date_add":"2023-11-14 10:30:00"}"""))
        assertNull(parse("""{"added":1320}"""))
        assertNull(parse("""{"added":null}"""))
    }
}
