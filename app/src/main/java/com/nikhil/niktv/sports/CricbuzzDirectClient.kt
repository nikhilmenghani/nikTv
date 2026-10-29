package com.nikhil.niktv.sports

import com.nikhil.niktv.data.RemoteCredentials
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal class CricbuzzDirectClient(
    private val transport: CricketHighlightsTransport,
    private val credentials: () -> CricbuzzCredentials = {
        CricbuzzCredentials(RemoteCredentials.get("NIKTV_CRICBUZZ_AUTH_KEY"),
            RemoteCredentials.get("NIKTV_CRICBUZZ_AUTH_MODE").ifBlank { "AK" },
            RemoteCredentials.get("NIKTV_CRICBUZZ_APP_VERSION").ifBlank { "6.16.04" })
    },
    private val now: () -> Long = System::currentTimeMillis,
) {
    val configured get() = credentials().configured
    val cacheIdentity get() = credentials().cacheIdentity
    private var regionCheckedAt: Long? = null
    private var regionIdentity = ""
    private var clockOffset = 0L

    suspend fun featured(): List<CricketHighlight> {
        if (!configured) throw SportsException("Import your Cricbuzz API configuration to connect the Canadian catalogue.")
        if (regionIdentity != cacheIdentity || regionCheckedAt?.let { now() - it !in 0 until 900_000 } != false) {
            val geo = Json.parseToJsonElement(request("/a/infra/v1/geo-location", json = true).toString(Charsets.UTF_8)).jsonObject
            if (!geo.text("country").equals("CA", true)) throw SportsException("Cricbuzz has not made the Canadian catalogue available on this connection.")
            regionIdentity = cacheIdentity; regionCheckedAt = now()
        }
        // The catalogue's signed media URLs are preserved; a signed-out detail response is never substituted.
        return CricketHomeProtobuf.parse(request("/a/home/v1/index")).distinctBy { it.id }.take(500)
    }

    private suspend fun request(path: String, json: Boolean = false): ByteArray {
        val url = "https://api.cricbuzz.com$path"
        suspend fun send(): ByteArray {
            val config = credentials()
            val headers = mapOf("Accept" to if (json) "application/json" else "application/x-protobuf",
                "CB-Loc" to "CA", "CB-TZ" to SimpleDateFormat("Z", Locale.US).format(Date(now())),
                "CB-AppVer" to config.appVersion, "cb-src" to "playstore",
                "Authentication" to CricbuzzAuthentication.header(url, config, now(), clockOffset))
            return transport.get(url, headers)
        }
        try { return send() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (denied: CricketAccessDenied) {
            val offset = denied.serverTimeMillis?.let { (it - now()) / 1000 } ?: throw denied
            // Retry once only when the authenticated HTTPS server demonstrates meaningful clock skew.
            if (kotlin.math.abs(offset - clockOffset) < 30 || kotlin.math.abs(offset) > 86_400) throw denied
            clockOffset = offset
            return send()
        }
    }
}
