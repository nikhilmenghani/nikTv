package com.nikhil.niktv.sports

import java.net.URLDecoder
import java.util.Locale
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class SportsStreamResolver(
    private val transport: SportsTransport,
    private val webResolve: suspend (SportsStream) -> SportsStream,
) {
    suspend fun resolve(input: SportsStream): SportsStream = withTimeout(30_000) {
        val stream = splitSportsHeaders(input)
        val resolved = when (stream.type) {
            0, 7 -> stream
            1, 3 -> throw SportsException("This stream requires provider-specific authorization. Choose another stream.")
            2 -> {
                val headers = stream.headers + mapOf("referer" to "rapidstreamz", "User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 13; Android Build/TP1A)")
                val suffix = transport.post(stream.api, headers, mapOf("referer" to "rapidstreamz")).body.trim()
                stream.copy(link = stream.link + suffix)
            }
            4 -> webResolve(stream)
            5 -> resolveVk(stream)
            6 -> {
                val id = stream.link.substringAfterLast('/')
                val body = transport.get(stream.api.replace("%s", id), stream.headers).body
                stream.copy(link = objectBody(body).text("playback_url"))
            }
            8 -> {
                val body = transport.get(stream.link, stream.headers).body
                val encoded = Regex("\"hlsManifestUrl\"\\s*:\\s*(\"(?:\\\\.|[^\"])*\")").find(body)?.groupValues?.get(1)
                    ?: throw SportsException("This source did not provide a media URL. Choose another stream.")
                stream.copy(link = Json.decodeFromString<String>(encoded))
            }
            9 -> {
                val lookup = "https://dokoplay.xyz/server_lookup.php".toHttpUrlOrNull()!!.newBuilder()
                    .addQueryParameter("channel_id", "premium${stream.link}").build()
                val key = objectBody(transport.get(lookup.toString(), stream.headers).body).text("server_key")
                if (!key.matches(Regex("[A-Za-z0-9-]{1,80}")) || !stream.link.matches(Regex("[A-Za-z0-9_-]{1,80}")))
                    throw SportsException("This source returned an invalid server. Choose another stream.")
                stream.copy(link = "https://${key}new.newkso.ru/$key/premium${stream.link}/mono.m3u8?",
                    headers = stream.headers + mapOf("Referer" to "https://newembedplay.xyz", "Origin" to "https://newembedplay.xyz",
                        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; 22101316I) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.6261.105 Mobile Safari/537.36"))
            }
            else -> throw SportsException("This source uses an unsupported stream format. Choose another stream.")
        }
        val parsed = splitSportsHeaders(resolved)
        val url = parsed.link.toHttpUrlOrNull() ?: throw SportsException("This source did not return a playable URL.")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw SportsException("This source returned an invalid URL.")
        val drmType = parsed.drmType.ifBlank { if (input.type == 7) "clearkey" else "" }
        parsed.copy(drmType = drmType, drmLicense = parsed.drmLicense.ifBlank { if (input.type == 7) input.api else "" })
    }

    private suspend fun resolveVk(stream: SportsStream): SportsStream {
        var url = stream.link.let { if (it.startsWith('/')) "https://vk.com$it" else it }
        val page = transport.get(url, stream.headers)
        val embed = Regex("itemprop=\"embedUrl\"\\s+href=\"([^\"]+)\"").find(page.body)?.groupValues?.get(1)
        val body = if (embed != null) { url = embed.replace("&amp;", "&"); transport.get(url, stream.headers).body } else page.body
        val encoded = Regex("\"(?:hls|hls_ondemand)\"\\s*:\\s*(\"(?:\\\\.|[^\"])*\")").find(body)?.groupValues?.get(1)
        return if (encoded != null) stream.copy(link = Json.decodeFromString<String>(encoded)) else webResolve(stream.copy(link = url))
    }
    private fun objectBody(body: String): JsonObject = SportsCatalogue.document(body) as? JsonObject
        ?: throw SportsException("This source returned an unreadable response.")
}

internal fun splitSportsHeaders(stream: SportsStream): SportsStream {
    val parts = stream.link.split('|', limit = 2)
    if (parts.size == 1) return stream
    val headers = stream.headers.toMutableMap()
    var drmType = stream.drmType
    var drmLicense = stream.drmLicense
    parts[1].split('&').forEach { field ->
        val name = field.substringBefore('=').trim().lowercase(Locale.ROOT)
        val raw = field.substringAfter('=', "")
        val value = if ('%' in raw) runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw) else raw
        when (name) {
            "user-agent", "referer", "referrer", "origin", "cookie", "authorization" -> {
                val key = when (name) { "user-agent" -> "User-Agent"; "referer", "referrer" -> "Referer"; else -> name.replaceFirstChar { it.uppercase() } }
                if ('\r' !in value && '\n' !in value) headers[key] = value
            }
            "drmscheme" -> drmType = value
            "drmlicense" -> drmLicense = value
        }
    }
    return stream.copy(link = parts[0], headers = headers, drmType = drmType, drmLicense = drmLicense)
}
