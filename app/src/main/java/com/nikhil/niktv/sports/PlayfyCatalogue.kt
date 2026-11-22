package com.nikhil.niktv.sports

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.*

/** Maps decoded Playfy documents; envelopes and network requests belong to its repository. */
internal object PlayfyCatalogue {
    private val numericId = Regex("[0-9]{1,20}")
    private val schedule = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss Z", Locale.ROOT)

    fun channelId(slug: String): String? = slug.removePrefix("playfy-")
        .takeIf { slug.startsWith("playfy-") && numericId.matches(it) }

    fun categoryPath(title: String): String {
        val category = title.trim().lowercase(Locale.ROOT).replace(' ', '-')
        if (category.isBlank() || category.length > 200 || category in setOf(".", "..") ||
            category.any { it == '/' || it == '\\' || it.code < 32 })
            throw SportsException("This Playfy category has an unsupported name.")
        return "cats/$category.json"
    }

    fun categories(document: JsonElement): List<SportsCategory> = rows(document, "categories").mapNotNull { row ->
        val item = row as? JsonObject ?: return@mapNotNull null
        val title = item.text("title").trim().takeIf(String::isNotEmpty) ?: return@mapNotNull null
        if (!published(item)) return@mapNotNull null
        SportsCategory("playfy-category-${item.text("id").ifBlank { title }}", title, item.text("image"),
            item.text("catLink").takeIf(::sportsNetworkUrl).orEmpty(), geo(item.text("geo")))
    }.distinctBy { it.id }

    fun channels(document: JsonElement, category: String = "Sports"): List<SportsChannel> = rows(document, "channels").mapNotNull { row ->
        val item = row as? JsonObject ?: return@mapNotNull null
        val id = id(item) ?: return@mapNotNull null
        if (!published(item)) return@mapNotNull null
        SportsChannel("playfy-$id", item.text("title").trim().ifBlank { "Channel $id" }, "playfy-$id", category,
            item.text("image"), formats(item), geo(item.text("geo")))
    }.distinctBy { it.key }

    fun events(document: JsonElement): List<SportsEvent> {
        val root = document as? JsonObject ?: throw SportsException("The Playfy events are unavailable.")
        return rows(root["channels"] ?: JsonNull, "events").mapNotNull { row ->
            val item = row as? JsonObject ?: return@mapNotNull null
            val id = id(item) ?: return@mapNotNull null
            if (!published(item)) return@mapNotNull null
            val event = item["eventInfo"] as? JsonObject ?: return@mapNotNull null
            val formats = formats(item)
            val sport = item.text("category").ifBlank { event.text("eventCat") }
            val competition = event.text("eventName")
            SportsEvent("playfy-$id", item.text("title").trim(), "playfy-$id", sport, competition,
                item.text("image").ifBlank { event.text("eventBanner") }, event.text("teamA"), event.text("teamB"),
                time(event.text("startTime")), time(event.text("endTime")), formats.map { it.title },
                geo(item.text("geo")), event.text("teamAFlag"), event.text("teamBFlag"),
                competition, event.text("isPinned") in setOf("1", "true"), formats)
        }.distinctBy { it.slug }
    }

    fun eventFilters(document: JsonElement): List<SportsEventFilter> {
        val root = document as? JsonObject ?: throw SportsException("The Playfy sport filters are unavailable.")
        val categories = root["cats"] as? JsonArray ?: throw SportsException("The Playfy sport filters are unavailable.")
        return categories.mapNotNull { row ->
            val item = row as? JsonObject
            if (item != null && !published(item)) return@mapNotNull null
            val title = (item?.text("title") ?: (row as? JsonPrimitive)?.contentOrNull)
                ?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
            SportsEventFilter("playfy-filter-${item?.text("id")?.takeIf(String::isNotBlank) ?: title}", title,
                item?.text("image").orEmpty())
        }.distinctBy { it.title }
    }

    fun detail(document: JsonElement): SportsChannelDetail = SportsChannelDetail(streams(document))

    fun streams(document: JsonElement): List<SportsStream> = rows(document, "stream choices").mapNotNull { row ->
        val item = row as? JsonObject ?: return@mapNotNull null
        val link = item.text("link").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val type = item.text("type").toIntOrNull() ?: 0
        val api = item.text("dl").ifBlank { item.text("api") }
        val explicitDrm = item.text("dt").ifBlank { item.text("drmType") }
        val headers = (item["headers"] as? JsonObject).orEmpty().mapNotNull header@{ (name, value) ->
            val content = (value as? JsonPrimitive)?.contentOrNull ?: return@header null
            if (name.any { it == '\r' || it == '\n' || it == '\u0000' } ||
                content.any { it == '\r' || it == '\n' || it == '\u0000' }) null else name to content
        }.toMap()
        val mappedType = when (type) {
            0, 1, 5, 7 -> 0
            2 -> 4
            3 -> 103
            4 -> 6
            // Keep the occurrence so the controller can reject just this source and try the next.
            else -> -1
        }
        val suffix = api.substringAfterLast('&', "").lowercase(Locale.ROOT)
            .takeIf { it in setOf("widevine", "playready", "clearkey") }
        SportsStream(link, item.text("title").ifBlank { "Stream" }, mappedType, api, headers,
            drmType = if (type == 1) explicitDrm.ifBlank { suffix ?: "clearkey" } else "",
            drmLicense = if (type == 1) { if (suffix == null) api else api.substringBeforeLast('&') } else "",
            geo = geo(item.text("geo")), webLink = item.text("webLink"),
            playbackFormat = if (type == 5) when (api.lowercase(Locale.ROOT)) {
                "mpd", "dash" -> "DASH"
                "m3u8", "hls" -> "HLS"
                "ism", "ismv", "ss" -> "SS"
                else -> ""
            } else "")
    }

    private fun formats(item: JsonObject): List<SportsFormat> = (item["formats"] as? JsonArray).orEmpty().mapIndexedNotNull { index, row ->
        when (row) {
            is JsonPrimitive -> row.contentOrNull?.trim()?.takeIf(String::isNotEmpty)?.let {
                val rawGeo = ((item["formatGeos"] as? JsonArray)?.getOrNull(index) as? JsonPrimitive)?.contentOrNull
                    ?.takeUnless { value -> value.isBlank() || value == "null" } ?: "ALL"
                SportsFormat(it, geo = geo(rawGeo))
            }
            is JsonObject -> row.text("title").trim().takeIf(String::isNotEmpty)?.let {
                SportsFormat(it, row.text("webLink"), geo(row.text("geo")))
            }
            else -> null
        }
    }

    private fun rows(document: JsonElement, label: String): JsonArray = document as? JsonArray
        ?: throw SportsException("The Playfy $label are unavailable.")
    // The Playfy app ignores this legacy flag; it is not a two-letter region restriction.
    private fun geo(value: String): String = value.takeUnless { it.isBlank() || it == "0" } ?: "ALL"
    private fun id(item: JsonObject): String? = item.text("id").takeIf(numericId::matches)
    private fun published(item: JsonObject): Boolean = item.text("publish") !in setOf("0", "false")
    private fun time(value: String): Instant? = runCatching { OffsetDateTime.parse(value, schedule).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(value) }.getOrNull()
}
