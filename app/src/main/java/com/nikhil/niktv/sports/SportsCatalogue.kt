package com.nikhil.niktv.sports

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.*
import kotlinx.serialization.Serializable

internal data class SportsEvent(
    val id: String,
    val title: String,
    val slug: String,
    val sport: String,
    val competition: String,
    val image: String,
    val teamA: String,
    val teamB: String,
    val start: Instant?,
    val end: Instant?,
    val choices: List<String>,
    val geo: String = "ALL",
    val teamAFlag: String = "",
    val teamBFlag: String = "",
    val eventName: String = "",
    val isHot: Boolean = false,
    val formats: List<SportsFormat> = emptyList(),
) {
    fun status(now: Instant): SportsStatus = when {
        start == null || end == null || end < start -> SportsStatus.UNKNOWN
        now < start -> SportsStatus.UPCOMING
        now > end -> SportsStatus.FINISHED
        else -> SportsStatus.LIVE
    }
}

internal enum class SportsStatus(val label: String) { LIVE("Live"), UPCOMING("Upcoming"), FINISHED("Finished"), UNKNOWN("Schedule unavailable") }
@Serializable
internal data class SportsStream(
    val link: String,
    val title: String,
    val type: Int = 0,
    val api: String = "",
    val headers: Map<String, String> = emptyMap(),
    val drmType: String = "",
    val drmLicense: String = "",
    val geo: String = "ALL",
    val webLink: String = "",
    val isLive: Boolean = true,
    val contentId: String = "",
)

internal object SportsCatalogue {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss Z", Locale.ROOT)

    fun document(body: String): JsonElement = document(body, com.nikhil.niktv.data.RemoteCredentials::get)

    internal fun document(body: String, credential: (String) -> String): JsonElement {
        val text = body.trim().removePrefix("\uFEFF")
        runCatching { json.parseToJsonElement(text) }.getOrNull()?.let {
            if (it is JsonArray || it is JsonObject) return it
        }
        val bytes = try { Base64.getMimeDecoder().decode(text) } catch (_: IllegalArgumentException) {
            throw SportsException("The sports service returned an unreadable response.")
        }
        val decoders = mutableListOf<() -> ByteArray>(
            { decodeSalted(bytes) },
        )
        for (index in 1..2) {
            val key = credential("NIKTV_CRICFY_AES_KEY_$index").toByteArray(Charsets.UTF_8)
            val iv = credential("NIKTV_CRICFY_AES_IV_$index").toByteArray(Charsets.UTF_8)
            if (key.size in setOf(16, 24, 32) && iv.size == 16) decoders.add { aes(bytes, key, iv) }
        }
        for (decode in decoders) {
            runCatching { json.parseToJsonElement(decode().toString(Charsets.UTF_8)) }.getOrNull()?.let {
                if (it is JsonArray || it is JsonObject) return it
            }
        }
        if (decoders.size == 1) throw SportsException("Sports decryption configuration is missing. Sync private configuration in Settings, then retry.")
        throw SportsException("The sports response could not be decoded. Refresh private configuration and retry.")
    }

    fun events(body: String): List<SportsEvent> {
        val rows = document(body) as? JsonArray ?: throw SportsException("The event catalogue is unavailable.")
        return rows.mapNotNull { row ->
            val o = row as? JsonObject ?: return@mapNotNull null
            if (o.text("publish") in setOf("0", "false")) return@mapNotNull null
            val slug = o.text("slug").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,200}")) } ?: return@mapNotNull null
            val info = o["eventInfo"] as? JsonObject ?: JsonObject(emptyMap())
            SportsEvent(o.text("id").ifBlank { slug }, o.text("title"), slug, info.text("eventCat"),
                info.text("eventType").ifBlank { info.text("eventName") },
                info.text("eventLogo").ifBlank { o.text("image") }, info.text("teamA"), info.text("teamB"),
                time(info.text("startTime")), time(info.text("endTime")),
                formats(o).map { it.title }, o.text("geo").ifBlank { "ALL" },
                info.text("teamAFlag"), info.text("teamBFlag"), info.text("eventName"),
                info.text("isHot") in setOf("1", "true"), formats(o))
        }.distinctBy { it.slug }
    }

    fun streams(body: String): List<SportsStream> {
        val root = document(body)
        val rows = (root as? JsonObject)?.get("streamUrls") as? JsonArray ?: root as? JsonArray
            ?: throw SportsException("No stream choices are available for this event.")
        return rows.mapNotNull { row ->
            val o = row as? JsonObject ?: return@mapNotNull null
            val link = o.text("link").takeIf(String::isNotBlank) ?: return@mapNotNull null
            SportsStream(link, o.text("title").ifBlank { "Stream" }, o.text("type").toIntOrNull() ?: 0,
                o.text("api"), (o["headers"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
                    (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
                }.toMap(), o.text("drmType"), o.text("drmLicense"), o.text("geo").ifBlank { "ALL" }, o.text("webLink"))
        }
    }

    fun categories(body: String): List<SportsCategory> = array(body, "The categories are unavailable.").mapNotNull { row ->
        val o = row as? JsonObject ?: return@mapNotNull null
        val title = o.text("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val raw = o.text("catLink")
        val link = raw.takeIf(::sportsNetworkUrl).orEmpty()
        SportsCategory(o.text("id").ifBlank { title }, title, o.text("image"), link, o.text("geo").ifBlank { "ALL" })
    }.distinctBy { it.id }

    fun filters(body: String): List<SportsEventFilter> = array(body, "The sport filters are unavailable.").mapNotNull { row ->
        val o = row as? JsonObject ?: return@mapNotNull null
        val title = o.text("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
        SportsEventFilter(o.text("id").ifBlank { title }, title, o.text("image"))
    }.distinctBy { it.id }

    fun channels(body: String): List<SportsChannel> = channelRows(array(body, "The channels are unavailable."))

    private fun channelRows(rows: JsonArray): List<SportsChannel> = rows.mapNotNull { row ->
        val o = row as? JsonObject ?: return@mapNotNull null
        if (o.text("publish") in setOf("0", "false")) return@mapNotNull null
        val slug = o.text("slug").takeIf(::sportsSlug) ?: return@mapNotNull null
        SportsChannel(o.text("id").ifBlank { slug }, o.text("title").trim().ifBlank { slug }, slug,
            o.text("cat"), o.text("image"), formats(o), o.text("geo").ifBlank { "ALL" })
    }.distinctBy { it.key }

    fun detail(body: String): SportsChannelDetail {
        val root = document(body)
        val o = root as? JsonObject
        return SportsChannelDetail(streams(body),
            (o?.get("related") as? JsonArray)?.let(::channelRows).orEmpty(),
            o?.text("prevChannel")?.takeIf(::sportsSlug), o?.text("nextChannel")?.takeIf(::sportsSlug),
            o?.text("geo").orEmpty().ifBlank { "ALL" }, o?.text("header").orEmpty(), o?.text("body").orEmpty())
    }

    private fun formats(o: JsonObject): List<SportsFormat> = (o["formats"] as? JsonArray).orEmpty().mapNotNull { row ->
        val item = row as? JsonObject ?: return@mapNotNull null
        item.text("title").takeIf(String::isNotBlank)?.let { SportsFormat(it, item.text("webLink"), item.text("geo").ifBlank { "ALL" }) }
    }

    private fun array(body: String, error: String): JsonArray = document(body) as? JsonArray ?: throw SportsException(error)

    fun baseUrl(body: String): String {
        return sportsConfiguration(body, "").primary
    }

    private fun time(value: String): Instant? = runCatching { OffsetDateTime.parse(value, dateFormat).toInstant() }.getOrNull()
    private fun aes(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
        init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)); doFinal(data)
    }
    private fun decodeSalted(bytes: ByteArray): ByteArray {
        require(bytes.size > 16)
        val salt = bytes.copyOfRange(0, 16)
        val data = bytes.copyOfRange(16, bytes.size)
        val sha = MessageDigest.getInstance("SHA-256")
        var offset = 0
        var counter = 0
        while (offset < data.size) {
            sha.update(salt)
            val block = sha.digest(ByteBuffer.allocate(4).putInt(counter++).array())
            for (b in block) { if (offset == data.size) break; data[offset] = (data[offset].toInt() xor b.toInt()).toByte(); offset++ }
        }
        val keyIv = sha.digest(salt)
        return aes(data, keyIv.copyOfRange(0, 16), keyIv.copyOfRange(16, 32))
    }
}

internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty().takeUnless { it == "null" }.orEmpty()
internal class SportsException(message: String) : java.io.IOException(message)
