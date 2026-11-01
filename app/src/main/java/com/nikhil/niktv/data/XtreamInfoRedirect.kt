package com.nikhil.niktv.data

import okhttp3.HttpUrl

/** Some Xtream proxies emit an absolute info redirect that omits their listening port. */
internal fun xtreamInfoPortFallback(original: HttpUrl, redirected: HttpUrl, status: Int): HttpUrl? {
    if (status != 404 || original.host != redirected.host || original.scheme != redirected.scheme ||
        original.port == redirected.port || redirected.port != HttpUrl.defaultPort(redirected.scheme) ||
        original.port == HttpUrl.defaultPort(original.scheme) ||
        original.encodedPath != "/player_api.php" || redirected.encodedPath != "/info_api.php") return null
    val action = original.queryParameter("action")
    if (action !in setOf("get_series_info", "get_vod_info") || redirected.queryParameter("action") != action) return null
    for (key in listOf("series_id", "vod_id", "stream_id")) {
        if (original.queryParameter(key) != redirected.queryParameter(key)) return null
    }
    // Reuse credentials only on the original host, scheme and configured port.
    return redirected.newBuilder().port(original.port).apply {
        for (key in listOf("username", "password")) {
            original.queryParameter(key)?.let { setQueryParameter(key, it) }
        }
    }.build()
}
