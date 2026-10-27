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
) {
    fun status(now: Instant): SportsStatus = when {
        start == null || end == null || end < start -> SportsStatus.UNKNOWN
        now < start -> SportsStatus.UPCOMING
        now > end -> SportsStatus.FINISHED
        else -> SportsStatus.LIVE
    }
}

internal enum class SportsStatus(val label: String) { LIVE("Live"), UPCOMING("Upcoming"), FINISHED("Finished"), UNKNOWN("Schedule unavailable") }
internal data class SportsStream(
    val link: String,
    val title: String,
    val type: Int = 0,
    val api: String = "",
    val headers: Map<String, String> = emptyMap(),
    val drmType: String = "",
    val drmLicense: String = "",
    val geo: String = "ALL",
)

internal object SportsCatalogue {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss Z", Locale.ROOT)

    fun document(body: String): JsonElement {
        val text = body.trim().removePrefix("\uFEFF")
        runCatching { json.parseToJsonElement(text) }.getOrNull()?.let {
            if (it is JsonArray || it is JsonObject) return it
        }
        val bytes = try { Base64.getMimeDecoder().decode(text) } catch (_: IllegalArgumentException) {
            throw SportsException("The sports service returned an unreadable response.")
        }
        val decoders = listOf<() -> ByteArray>(
            { decodeSalted(bytes) },
            { aes(bytes, "3hHzxYAgSGI8/ham".toByteArray(), "UqCvkvjecEIz842V".toByteArray()) },
            { aes(bytes, "MqeYGCT4AYKoHKrT".toByteArray(), "oHKrTQuPx8zl8oJ+".toByteArray()) },
        )
        for (decode in decoders) {
            runCatching { json.parseToJsonElement(decode().toString(Charsets.UTF_8)) }.getOrNull()?.let {
                if (it is JsonArray || it is JsonObject) return it
            }
        }
        throw SportsException("The sports service response format has changed.")
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
                (o["formats"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.text("title")?.takeIf(String::isNotBlank) }, o.text("geo").ifBlank { "ALL" })
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
                }.toMap(), o.text("drmType"), o.text("drmLicense"), o.text("geo").ifBlank { "ALL" })
        }
    }

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
