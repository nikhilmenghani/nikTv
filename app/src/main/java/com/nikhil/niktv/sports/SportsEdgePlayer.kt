package com.nikhil.niktv.sports

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class SportsEdgePlayer(val manifest: String, val tokenApi: String, val origin: String)

internal fun sportsEdgePlayer(html: String, page: String): SportsEdgePlayer? {
    if (!html.contains("/api/playlist-token")) return null
    val location = page.toHttpUrlOrNull() ?: return null
    fun variable(name: String): String? = Regex("\\b(?:const|let|var)\\s+$name\\s*=\\s*([\"'])(.*?)\\1")
        .find(html)?.groupValues?.get(2)?.replace("\\/", "/")?.takeIf(String::isNotBlank)
    val edge = variable("EDGE")?.trimEnd('/')?.toHttpUrlOrNull() ?: return null
    val channel = variable("CHANNEL") ?: return null
    val manifest = variable("SRC")?.toHttpUrlOrNull()
        ?: edge.newBuilder().addPathSegment(channel).addPathSegment("index.m3u8").build()
    if (!sportsSameOrigin(edge.toString(), manifest.toString()) ||
        listOf(edge, manifest, location).any { it.username.isNotEmpty() || it.password.isNotEmpty() }) return null
    val api = edge.newBuilder().addPathSegments("api/playlist-token").addQueryParameter("slug", channel).build()
    return SportsEdgePlayer(manifest.toString(), api.toString(), location.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/'))
}

internal fun sportsSameOrigin(a: String, b: String): Boolean {
    val left = a.toHttpUrlOrNull() ?: return false
    val right = b.toHttpUrlOrNull() ?: return false
    return left.scheme == right.scheme && left.host == right.host && left.port == right.port &&
        left.username.isEmpty() && left.password.isEmpty() && right.username.isEmpty() && right.password.isEmpty()
}
