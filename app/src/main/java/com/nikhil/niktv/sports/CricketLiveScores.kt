package com.nikhil.niktv.sports

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

internal data class CricketLiveMatch(
    val id: Int, val title: String, val description: String, val status: String,
    val state: String, val scores: List<CricketInnings> = emptyList(),
    val hundred: Boolean = false,
)

internal data class CricketInnings(
    val id: Int, val team: String, val runs: Int, val wickets: Int, val overs: String,
) {
    val label get() = "$team $runs/$wickets ($overs)"
}

internal data class CricketScoreEvent(val id: String, val timestamp: Long, val title: String, val detail: String)
internal data class CricketDelivery(val id: String, val value: String, val over: String, val description: String = "")

internal data class CricketScoreTiming(val widgetMillis: Long = 10_000L, val matchesMillis: Long = 25_000L)

internal data class CricketLiveScore(
    val matchId: Int, val innings: List<CricketInnings>, val status: String, val state: String,
    val recentBalls: String, val events: List<CricketScoreEvent>, val lastDelivery: CricketDelivery? = null,
) {
    val complete get() = state.lowercase(Locale.US) in setOf("complete", "completed", "result", "abandon", "abandoned", "cancelled", "canceled")
}

/** Only newly received commentary produces alerts; opening a match never replays its history. */
internal class CricketScoreEventTracker {
    private var initialized = false
    private var watermark = 0L
    private val seen = linkedSetOf<String>()

    fun accept(events: List<CricketScoreEvent>): List<CricketScoreEvent> {
        val fresh = if (!initialized) emptyList() else events.filter { it.timestamp >= watermark && it.id !in seen }
        initialized = true
        watermark = maxOf(watermark, events.maxOfOrNull { it.timestamp } ?: 0L)
        events.forEach { seen.add(it.id) }
        while (seen.size > 512) seen.remove(seen.first())
        return fresh.sortedBy { it.timestamp }
    }
}

internal class CricketLiveScoresRepository(
    private val direct: CricbuzzDirectClient = CricbuzzDirectClient(CricketHighlightsHttpTransport()),
) {
    private var timing = CricketScoreTiming()
    private var timingCheckedAt = 0L
    suspend fun timing(): CricketScoreTiming {
        val now = System.currentTimeMillis()
        if (now - timingCheckedAt in 0 until 900_000L) return timing
        timingCheckedAt = now
        try { timing = CricketScoreProtobuf.timing(direct.scoreRequest("/a/infra/v1/settings")) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { /* Use the app's last known widget cadence if settings is unavailable. */ }
        return timing
    }

    suspend fun matches(): List<CricketLiveMatch> = CricketScoreProtobuf.matches(direct.scoreRequest("/a/matches/v1/live"))

    // tms/iid are pagination cursors for older commentary. Always fetch the current snapshot here.
    suspend fun score(match: CricketLiveMatch): CricketLiveScore {
        require(match.id > 0)
        val route = if (match.hundred) "hcomm" else "comm"
        return CricketScoreProtobuf.score(direct.scoreRequest("/a/mcenter/v1/${match.id}/$route"), match)
    }
}

/** Wire tags are from the app's Match / CommentaryList schemas; ads and media are skipped. */
internal object CricketScoreProtobuf {
    fun timing(bytes: ByteArray): CricketScoreTiming {
        val rates = message(bytes).messages(1).associate { it.text(1) to it.int(2) }
        fun rate(key: String, fallback: Long) = rates[key]?.takeIf { it in 1..3600 }?.times(1_000L) ?: fallback
        return CricketScoreTiming(rate("floating_score_widget", 10_000L), rate("matches", 25_000L))
    }

    fun matches(bytes: ByteArray): List<CricketLiveMatch> = message(bytes).messages(1).flatMap { type ->
        type.messages(2).flatMap { wrapper -> wrapper.messages(1).flatMap { series ->
            series.messages(3).mapNotNull { match ->
                val info = match.child(1) ?: return@mapNotNull null
                val id = info.int(1).takeIf { it > 0 } ?: return@mapNotNull null
                val team1 = info.child(10)?.text(3).orEmpty()
                val team2 = info.child(11)?.text(3).orEmpty()
                if (team1.isBlank() || team2.isBlank()) return@mapNotNull null
                val scores = match.child(3)
                CricketLiveMatch(id, "$team1 vs $team2", listOf(info.text(4), info.text(3)).filter { it.isNotBlank() }.joinToString(" · "),
                    info.text(9), info.text(8),
                    teamScores(scores?.child(1), team1) + teamScores(scores?.child(2), team2),
                    info.text(5).equals("HUN", true) || info.text(5).contains("hundred", true))
            }
        } }
    }.distinctBy { it.id }.filterNot { it.state.lowercase(Locale.US) in setOf("complete", "completed", "result", "abandon", "abandoned", "cancelled", "canceled") }.take(200)

    fun score(bytes: ByteArray, match: CricketLiveMatch): CricketLiveScore {
        val root = message(bytes)
        val mini = root.child(3)
        val header = root.child(4)
        if (mini == null && header == null) invalid()
        val innings = mini?.child(10)?.messages(1).orEmpty().map { innings(it, it.text(3)) }.sortedBy { it.id }
        val lines = root.messages(2).mapNotNull { it.child(1) }
        val recentBalls = mini?.text(9).orEmpty()
        val latest = lines.filter { it.double(3) > 0 && it.text(5) != "over-break" }
            .maxWithOrNull(compareBy<Message> { it.int(4) }.thenBy { it.number(2) })
        val lastValue = recentBalls.split(Regex("[\\s|]+"))
            .lastOrNull { it.matches(Regex("(?i)([0-9]+|W|[0-9]*(wd|nb|lb|b)[0-9]*)")) }
        val delivery = if (latest != null && lastValue != null) CricketDelivery(
            "${latest.int(4)}:${latest.int(11)}:${latest.double(3)}:${latest.number(2)}", lastValue,
            String.format(Locale.US, "%.1f", latest.double(3)), commentaryText(latest)) else null
        val events = lines.mapNotNull { line ->
            val types = line.text(5).uppercase(Locale.US).split(',').map(String::trim)
            val labels = types.mapNotNull { type -> when (type) {
                "WICKET" -> "WICKET"
                "FOUR" -> "FOUR!"
                "SIX" -> "SIX!"
                "FIFTY" -> "FIFTY!"
                "HUNDRED", "CENTURY" -> "CENTURY!"
                "DOUBLE_HUNDRED", "DOUBLE_CENTURY" -> "DOUBLE CENTURY!"
                "TRIPLE_HUNDRED", "TRIPLE_CENTURY" -> "TRIPLE CENTURY!"
                else -> if (type.contains("MILESTONE")) "MILESTONE" else null
            } }.distinct()
            val timestamp = line.number(2)
            if (labels.isEmpty() || timestamp <= 0) return@mapNotNull null
            CricketScoreEvent("${line.int(4)}:${line.number(2)}:${line.int(11)}:${line.text(5)}", timestamp,
                labels.joinToString(" · "), commentaryText(line))
        }
        return CricketLiveScore(match.id, innings, header?.text(2).orEmpty().ifBlank { match.status },
            header?.text(1).orEmpty().ifBlank { match.state }, recentBalls, events, delivery)
    }

    private fun commentaryText(line: Message): String {
        var detail = line.text(1)
        line.messages(7).flatMap { it.messages(2) }.forEach { value ->
            val token = value.text(1)
            if (token.isNotBlank()) detail = detail.replace(token, value.text(2))
        }
        return detail.replace(Regex("<[^>]*>"), "").replace(Regex("[A-Z][0-9]+\\$"), "")
            .replace("&amp;", "&").replace("&nbsp;", " ").replace("&#39;", "'").replace("&quot;", "\"").trim()
    }

    private fun teamScores(score: Message?, team: String) = listOfNotNull(score?.child(1), score?.child(2)).map { innings(it, team) }
    private fun innings(value: Message, team: String) = CricketInnings(value.int(1), team.take(20), value.int(4), value.int(5),
        value.double(6).let { if (it % 1.0 == 0.0) it.toInt().toString() else String.format(Locale.US, "%.1f", it) })

    private class Message(val blobs: Map<Int, List<ByteArray>>, val numbers: Map<Int, Long>) {
        fun messages(tag: Int) = blobs[tag].orEmpty().map(::message)
        fun child(tag: Int) = blobs[tag]?.lastOrNull()?.let(::message)
        fun text(tag: Int) = blobs[tag]?.lastOrNull()?.toString(Charsets.UTF_8)?.take(16_000).orEmpty()
        fun number(tag: Int) = numbers[tag] ?: 0L
        fun int(tag: Int) = number(tag).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        fun double(tag: Int): Double = blobs[tag]?.lastOrNull()?.takeIf { it.size == 8 }?.let {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).double.takeIf(Double::isFinite)
        } ?: 0.0
    }

    private fun message(bytes: ByteArray): Message {
        if (bytes.size > 4_194_304) invalid()
        var offset = 0
        var count = 0
        val blobs = mutableMapOf<Int, MutableList<ByteArray>>()
        val numbers = mutableMapOf<Int, Long>()
        fun varint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                if (offset >= bytes.size) invalid()
                val b = bytes[offset++].toInt() and 255
                if (shift == 63 && b > 1) invalid()
                value = value or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) return value
            }
            invalid()
        }
        fun read(length: Long): ByteArray {
            if (length < 0 || length > bytes.size - offset) invalid()
            return bytes.copyOfRange(offset, offset + length.toInt()).also { offset += length.toInt() }
        }
        while (offset < bytes.size) {
            if (++count > 20_000) invalid()
            val key = varint()
            val tag = (key ushr 3).toInt()
            if (tag !in 1..536_870_911) invalid()
            when ((key and 7).toInt()) {
                0 -> numbers[tag] = varint()
                1 -> blobs.getOrPut(tag) { mutableListOf() }.add(read(8))
                2 -> blobs.getOrPut(tag) { mutableListOf() }.add(read(varint()))
                5 -> read(4)
                else -> invalid()
            }
        }
        return Message(blobs, numbers)
    }

    private fun invalid(): Nothing = throw SportsException("Cricbuzz returned an unreadable live score.")
}
