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
            """.trimIndent()
        )

        assertEquals("tmdb-value", parsed["NIKTV_TMDB_API_KEY"])
        assertEquals("iptv-value", parsed["NIKTV_XTREAM_PASSWORD"])
        assertFalse(parsed.containsKey("NIKGAPPS_CLIENT_SECRET"))
    }
}
