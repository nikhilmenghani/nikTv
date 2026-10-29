package com.nikhil.niktv.sports

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** URLs are short-lived provider state, never persisted as a library identity. */
internal data class CricketHighlight(
    val id: String,
    val title: String,
    val duration: String = "",
    val image: String = "",
    val fullUrl: String = "",
    val previewUrl: String = "",
    val headers: Map<String, String> = emptyMap(),
) {
    fun fullStream(): SportsStream {
        if (!cricketHttpsUrl(fullUrl) || cricketAsset(fullUrl) == cricketAsset(previewUrl))
            throw SportsException("The provider has not supplied the full highlight. A preview will not be played instead.")
        return SportsStream(fullUrl, title, headers = headers, geo = "CA", isLive = false, contentId = "cricket:$id")
    }
    override fun toString() = "CricketHighlight(id=$id, title=$title)"
}

internal fun cricketHttpsUrl(value: String): Boolean = value.toHttpUrlOrNull()?.let {
    it.isHttps && it.username.isEmpty() && it.password.isEmpty()
} == true

private fun cricketAsset(value: String): String? = value.toHttpUrlOrNull()?.let { url ->
    val entry = url.pathSegments.indexOf("entryId")
    if (url.host.endsWith(".kaltura.com") && entry >= 0) "kaltura:${url.pathSegments.getOrNull(entry + 1)}"
    else url.host + url.encodedPath
}

/** An authorized provider/relay can return normalized JSON or wrap the original Home protobuf. */
internal object CricketHighlightsCatalogue {
    const val MAX_BYTES = 4 * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(bytes: ByteArray): List<CricketHighlight> {
        if (bytes.size > MAX_BYTES) throw SportsException("The highlights catalogue is too large.")
        if (bytes.isEmpty()) throw SportsException("The highlights provider returned an empty catalogue.")
        return try {
            val text = bytes.toString(Charsets.UTF_8).trimStart()
            val rows = if (text.startsWith("{")) parseJson(json.parseToJsonElement(text).jsonObject)
                else CricketHomeProtobuf.parse(bytes)
            rows.distinctBy { it.id }.take(500)
        } catch (failure: SportsException) { throw failure }
        catch (_: Exception) { throw SportsException("The highlights provider returned an unreadable catalogue.") }
    }

    private fun parseJson(root: JsonObject): List<CricketHighlight> {
        if (root.text("country").uppercase(java.util.Locale.ROOT) != "CA")
            throw SportsException("The highlights provider must return the Canadian catalogue.")
        root.text("catalogueBase64").takeIf(String::isNotBlank)?.let {
            return CricketHomeProtobuf.parse(java.util.Base64.getDecoder().decode(it))
        }
        val videos = root["videos"] as? JsonArray ?: throw SportsException("The highlights provider did not supply featured videos.")
        if (videos.size > 500) throw SportsException("The highlights catalogue contains too many videos.")
        return videos.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val id = item.text("id")
            val title = item.text("title").trim()
            if (!validCricketId(id) || title.isEmpty() || title.length > 500 || item["isLive"]?.jsonPrimitive?.booleanOrNull == true)
                return@mapNotNull null
            val type = item.text("videoType")
            if (type.isNotEmpty() && !type.equals("Highlights", true)) return@mapNotNull null
            val headers = (item["headers"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
                val content = (value as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                if (key.lowercase(java.util.Locale.ROOT) !in setOf("user-agent", "referer", "origin", "authorization", "cookie") ||
                    content.length > 8192 || content.any { it == '\r' || it == '\n' }) null else key to content
            }.toMap()
            CricketHighlight(id, title, item.text("durationStr"), item.text("imageUrl").takeIf(::cricketHttpsUrl).orEmpty(),
                item.text("premiumVideoUrl").takeIf(::cricketHttpsUrl).orEmpty(),
                item.text("videoUrl").takeIf(::cricketHttpsUrl).orEmpty(),
                headers)
        }
    }
}

internal fun validCricketId(value: String) = value.matches(Regex("[A-Za-z0-9_-]{1,100}"))
