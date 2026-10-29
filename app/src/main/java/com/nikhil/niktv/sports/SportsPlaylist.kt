package com.nikhil.niktv.sports

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Catalogue parsing only. A browse or focus event never opens a media connection. */
internal object SportsPlaylist {
    private const val MAX_ITEMS = 20_000
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(body: String, category: SportsCategory): List<SportsChannel> {
        val text = body.trim().removePrefix("\uFEFF").trim()
        if (text.startsWith("CFP1.")) throw SportsException("This provider uses a playlist format that is not supported yet.")
        if (text.startsWith('<')) throw SportsException("This provider returned a web page instead of a playlist. Try refreshing later.")
        if (text.isEmpty()) return emptyList()
        val root = if (text.startsWith('[') || text.startsWith('{')) runCatching { json.parseToJsonElement(text) }.getOrNull() else null
        if (root != null) {
            val rows = when (root) {
                is JsonArray -> root
                is JsonObject -> {
                    if (root.containsKey("error") || root.text("code").let { it.isNotBlank() && it != "200" })
                        throw SportsException("This provider could not supply its playlist. Try refreshing later.")
                    root["channels"] as? JsonArray ?: throw SportsException("This provider returned an unsupported playlist response.")
                }
                else -> throw SportsException("This provider returned an unsupported playlist response.")
            }
            return fromJson(rows, category)
        }
        if (text.startsWith("#EXTM3U", true) || text.lineSequence().any { it.trim().startsWith("#EXTINF:", true) })
            return fromM3u(text, category)
        val decoded = legacy(text) ?: runCatching { SportsCatalogue.document(text).toString() }.getOrNull()
            ?: throw SportsException("This provider uses an unreadable playlist format. Try another category.")
        if (decoded == text) throw SportsException("This provider uses an unreadable playlist format.")
        return parse(decoded, category)
    }

    private fun fromJson(rows: JsonArray, category: SportsCategory): List<SportsChannel> {
        if (rows.size > MAX_ITEMS) throw SportsException("This playlist is too large to display.")
        val occurrences = mutableMapOf<String, Int>()
        return rows.mapNotNull { row ->
            val o = row as? JsonObject ?: return@mapNotNull null
            val name = o.text("name").ifBlank { o.text("title") }.trim()
            val link = o.text("link").ifBlank { o.text("url") }.trim()
            if (name.isBlank() || !sportsNetworkUrl(link.substringBefore('|'))) return@mapNotNull null
            val headers = (o["headers"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                (v as? JsonPrimitive)?.contentOrNull?.takeIf { '\r' !in it && '\n' !in it }?.let { k to it }
            }.toMap().toMutableMap()
            mapOf("userAgent" to "User-Agent", "referrer" to "Referer", "origin" to "Origin", "cookie" to "Cookie").forEach { (field, header) ->
                o.text(field).takeIf { it.isNotBlank() && '\r' !in it && '\n' !in it }?.let { headers[header] = it }
            }
            item(name, o.text("groupTitle").ifBlank { o.text("group") }, o.text("logo").ifBlank { o.text("image") },
                SportsStream(link, name, headers = headers, drmType = o.text("drmScheme").ifBlank { o.text("drmType") },
                    drmLicense = o.text("drmLicense"), geo = o.text("geo").ifBlank { "ALL" }), category, occurrences)
        }
    }

    private fun fromM3u(text: String, category: SportsCategory): List<SportsChannel> {
        val result = mutableListOf<SportsChannel>()
        val occurrences = mutableMapOf<String, Int>()
        var name = ""; var group = ""; var logo = ""
        var drm = ""; var license = ""
        val headers = mutableMapOf<String, String>()
        fun reset() { name = ""; group = ""; logo = ""; drm = ""; license = ""; headers.clear() }
        val attributes = Regex("([A-Za-z0-9_-]+)\\s*=\\s*\"([^\"]*)\"")
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF:", true) -> {
                    reset()
                    val attrs = attributes.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    logo = attrs["tvg-logo"].orEmpty(); group = attrs["group-title"].orEmpty()
                    var quoted = false
                    val comma = line.indexOfFirst { c -> if (c == '"') quoted = !quoted; c == ',' && !quoted }
                    name = if (comma >= 0) line.substring(comma + 1).trim() else attrs["tvg-name"].orEmpty()
                }
                line.startsWith("#EXTHTTP:", true) -> {
                    val o = runCatching { json.parseToJsonElement(line.substringAfter(':')).jsonObject }.getOrNull()
                    o?.text("cookie")?.takeIf(String::isNotBlank)?.let { headers["Cookie"] = it }
                }
                line.startsWith('#') && '=' in line -> {
                    val key = line.substringBefore('=').substringAfter(':').trim().lowercase()
                    val value = line.substringAfter('=').trim()
                    when (key) {
                        "http-user-agent" -> headers["User-Agent"] = value
                        "http-referrer", "http-referer" -> headers["Referer"] = value
                        "http-origin" -> headers["Origin"] = value
                        "http-cookie" -> headers["Cookie"] = value
                        "inputstream.adaptive.license_type" -> drm = value
                        "inputstream.adaptive.license_key" -> license = value
                    }
                }
                line.isNotEmpty() && !line.startsWith('#') -> {
                    if (name.isNotBlank() && sportsNetworkUrl(line.substringBefore('|'))) {
                        if (result.size == MAX_ITEMS) throw SportsException("This playlist is too large to display.")
                        result += item(name, group, logo, SportsStream(line, name, headers = headers.toMap(), drmType = drm, drmLicense = license), category, occurrences)
                    }
                    reset()
                }
            }
        }
        return result
    }

    private fun item(name: String, group: String, logo: String, stream: SportsStream, category: SportsCategory,
        occurrences: MutableMap<String, Int>): SportsChannel {
        // Stable across signed-link rotation; same-name occurrences remain separate.
        val identity = "$group\u0000$name"
        val ordinal = occurrences[identity] ?: 0
        occurrences[identity] = ordinal + 1
        val id = sportsDigest("$identity\u0000$ordinal")
        return SportsChannel(id, name, "", category.title, logo, geo = stream.geo, group = group,
            playlist = category, direct = splitSportsHeaders(stream))
    }

    private fun legacy(text: String): String? = runCatching {
        require(text.length > 78)
        val iv = Base64.getMimeDecoder().decode(text.substring(10, 34))
        val key = Base64.getMimeDecoder().decode(text.substring(text.length - 54, text.length - 10))
        require(iv.size == 16 && key.size in setOf(16, 24, 32))
        val data = Base64.getMimeDecoder().decode(text.take(10) + text.substring(34, text.length - 54) + text.takeLast(10))
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data).toString(Charsets.UTF_8)
        }.takeIf { it.trimStart().startsWith('#') || it.trimStart().startsWith('[') || it.trimStart().startsWith('{') }
    }.getOrNull()
}

internal fun sportsDigest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    .take(12).joinToString("") { "%02x".format(it) }
