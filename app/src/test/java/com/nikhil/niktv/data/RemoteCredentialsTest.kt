package com.nikhil.niktv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RemoteCredentialsTest {
    @Test
    fun envFileKeepsOnlyNikTvValues() {
        val parsed = RemoteCredentials.parseKnownValues(
            """
            # Shared private environment
            NIKGAPPS_CLIENT_SECRET=unrelated-secret
            export NIKTV_TMDB_API_KEY="tmdb-value"
            NIKTV_XTREAM_PASSWORD='iptv-value'
            NIKTV_CRICKET_HIGHLIGHTS_URL=https://provider.example.test/featured
            NIKTV_CRICKET_HIGHLIGHTS_TOKEN=fixture-provider-token
            NIKTV_CRICBUZZ_AUTH_KEY=fixture-api-key
            NIKTV_CRICBUZZ_AUTH_MODE=AK
            NIKTV_CRICBUZZ_APP_VERSION=6.16.04
            """.trimIndent()
        )

        assertEquals("tmdb-value", parsed["NIKTV_TMDB_API_KEY"])
        assertEquals("iptv-value", parsed["NIKTV_XTREAM_PASSWORD"])
        assertEquals("https://provider.example.test/featured", parsed["NIKTV_CRICKET_HIGHLIGHTS_URL"])
        assertEquals("fixture-provider-token", parsed["NIKTV_CRICKET_HIGHLIGHTS_TOKEN"])
        assertEquals("fixture-api-key", parsed["NIKTV_CRICBUZZ_AUTH_KEY"])
        assertEquals("AK", parsed["NIKTV_CRICBUZZ_AUTH_MODE"])
        assertEquals("6.16.04", parsed["NIKTV_CRICBUZZ_APP_VERSION"])
        assertFalse(parsed.containsKey("NIKGAPPS_CLIENT_SECRET"))
    }
    @Test fun syncedCricketValuesReplaceOldImportAndEmbeddedValuesRemainFallbacks() {
        val key = "NIKTV_CRICBUZZ_AUTH_KEY"
        val mode = "NIKTV_CRICBUZZ_AUTH_MODE"
        val version = "NIKTV_CRICBUZZ_APP_VERSION"
        val embedded = mapOf(key to "local-build", mode to "AK", version to "16.11.01")
        val imported = mapOf(key to "old-import")
        assertEquals("github-key", RemoteCredentials.mergeCredentialValues(embedded, imported,
            mapOf(key to "github-key"))[key])
        assertEquals(embedded, RemoteCredentials.mergeCredentialValues(embedded, emptyMap(), emptyMap()))
        assertEquals("local-build", RemoteCredentials.mergeCredentialValues(embedded, emptyMap(), mapOf(key to ""))[key])
        assertEquals("AK", RemoteCredentials.mergeCredentialValues(embedded, imported, mapOf(key to "github-key"))[mode])
        assertEquals(emptyMap<String, String>(), RemoteCredentials.mergeCredentialValues(emptyMap(), emptyMap(), emptyMap()))
    }
}
