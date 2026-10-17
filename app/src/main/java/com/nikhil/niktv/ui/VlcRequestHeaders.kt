package com.nikhil.niktv.ui

/** LibVLC 3 supports these media options. Arbitrary auth/cookie headers use Media3 instead. */
internal fun vlcHeaderOptions(headers: Map<String, String>): List<String>? {
    val names = mapOf("user-agent" to "http-user-agent", "referer" to "http-referrer", "referrer" to "http-referrer")
    if (headers.any { (key, value) -> key.lowercase() !in names || value.any { it == '\r' || it == '\n' || it == '\u0000' } }) return null
    return headers.map { (key, value) -> ":${names.getValue(key.lowercase())}=$value" }
}
