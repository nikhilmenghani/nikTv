package com.nikhil.niktv.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class XtreamInfoRedirectTest {
    private val source = "http://fixture.invalid:8080/player_api.php?username=test&password=secret&action=get_series_info&series_id=210".toHttpUrl()
    private val target = "http://fixture.invalid/info_api.php?action=get_series_info&series_id=210".toHttpUrl()
    @Test fun repairsOnlyDroppedPortAndPreservesSeriesAndCredentials() {
        val fixed = xtreamInfoPortFallback(source, target, 404)!!
        assertEquals(8080, fixed.port)
        assertEquals("210", fixed.queryParameter("series_id"))
        assertEquals("test", fixed.queryParameter("username"))
        assertEquals("secret", fixed.queryParameter("password"))
        assertEquals("/info_api.php", fixed.encodedPath)
    }
    @Test fun doesNotRetrySuccessOrAuthorizationErrors() {
        for (code in listOf(200, 401, 403, 429, 500)) assertNull(xtreamInfoPortFallback(source, target, code))
    }
    @Test fun neverSendsCredentialsToAnotherHostOrScheme() {
        assertNull(xtreamInfoPortFallback(source, target.newBuilder().host("other.invalid").build(), 404))
        assertNull(xtreamInfoPortFallback(source, target.newBuilder().scheme("https").build(), 404))
    }
    @Test fun leavesOtherPortsPathsAndIdentitiesAlone() {
        assertNull(xtreamInfoPortFallback(source, target.newBuilder().port(9000).build(), 404))
        assertNull(xtreamInfoPortFallback(source, target.newBuilder().encodedPath("/other.php").build(), 404))
        assertNull(xtreamInfoPortFallback(source, target.newBuilder().setQueryParameter("series_id", "1414").build(), 404))
        assertNull(xtreamInfoPortFallback(source.newBuilder().port(80).build(), target, 404))
    }
}
