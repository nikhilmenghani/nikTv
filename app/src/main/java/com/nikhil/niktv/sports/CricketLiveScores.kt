package com.nikhil.niktv.sports

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

internal data class CricketScorecard(val innings: List<CricketInningsCard>, val status: String, val complete: Boolean)

internal data class CricketInningsCard(
    val score: CricketInnings,
    val balls: Int?,
    val declared: Boolean,
    val batters: List<CricketBatter>,
    val bowlers: List<CricketBowler>,
    val extras: CricketExtras?,
    val wickets: List<CricketWicket>,
)

internal data class CricketBatter(
    val name: String, val dismissal: String, val runs: Int, val balls: Int,
    val fours: Int, val sixes: Int, val strikeRate: String,
)

internal data class CricketBowler(
    val name: String, val overs: String, val balls: Int?, val maidens: Int,
    val runs: Int, val wickets: Int, val economy: String,
)

internal data class CricketExtras(
    val total: Int, val byes: Int, val legByes: Int, val wides: Int, val noBalls: Int, val penalty: Int,
)

internal data class CricketWicket(val batter: String, val runs: Int, val over: String, val ball: Int?)
internal data class CricketCommentaryCursor(val innings: Int, val timestamp: Long)
internal data class CricketCommentaryLine(
    val id: String, val timestamp: Long, val innings: Int, val delivery: String?, val event: String?, val text: String,
)
internal data class CricketCommentaryPage(
    val lines: List<CricketCommentaryLine>, val older: CricketCommentaryCursor?, val complete: Boolean,
)

internal data class CricketScoreTiming(val widgetMillis: Long = 10_000L, val matchesMillis: Long = 25_000L)

internal data class CricketLiveScore(
    val matchId: Int, val innings: List<CricketInnings>, val status: String, val state: String,
    val recentBalls: String, val events: List<CricketScoreEvent>, val lastDelivery: CricketDelivery? = null,
) {
    val complete get() = completeState(state)
}

private fun completeState(state: String) =
    state.lowercase(Locale.US) in setOf("complete", "completed", "result", "abandon", "abandoned", "cancelled", "canceled")

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
        return CricketScoreProtobuf.score(direct.scoreRequest(matchPath(match, "comm")), match)
    }

    suspend fun scorecard(match: CricketLiveMatch): CricketScorecard {
        val bytes = direct.scoreRequest(matchPath(match, "scard"))
        return withContext(Dispatchers.Default) { CricketScoreProtobuf.scorecard(bytes) }
    }

    suspend fun commentary(match: CricketLiveMatch, cursor: CricketCommentaryCursor? = null): CricketCommentaryPage {
        val query = cursor?.let { "?iid=${it.innings}&tms=${it.timestamp}" }.orEmpty()
        val bytes = direct.scoreRequest(matchPath(match, "comm") + query)
        return withContext(Dispatchers.Default) { CricketScoreProtobuf.commentary(bytes, match.hundred) }
    }

    private fun matchPath(match: CricketLiveMatch, endpoint: String): String {
        require(match.id > 0)
        return "/a/mcenter/v1/${match.id}/${if (match.hundred) "h" else ""}$endpoint"
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
    }.distinctBy { it.id }.filterNot { completeState(it.state) }.take(200)

    fun score(bytes: ByteArray, match: CricketLiveMatch): CricketLiveScore {
        val root = message(bytes)
        val mini = root.child(3)
        val header = root.child(4)
        if (mini == null && header == null) invalid()
        val innings = mini?.child(10)?.messages(1).orEmpty().map { innings(it, it.text(3)) }.sortedBy { it.id }
        val lines = commentaryMessages(root)
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

    fun scorecard(bytes: ByteArray): CricketScorecard {
        val root = message(bytes)
        val innings = root.messages(1).mapNotNull { card ->
            val id = card.int(1).takeIf { it > 0 } ?: return@mapNotNull null
            val team = card.text(11).ifBlank { card.text(12) }
            if (team.isBlank()) return@mapNotNull null
            CricketInningsCard(
                score = CricketInnings(id, team, card.int(7), card.int(8), oversLabel(card.double(9))),
                balls = card.int(15).takeIf { it > 0 },
                declared = card.int(13) != 0,
                batters = card.messages(2).mapNotNull { batter ->
                    val name = batter.text(7).ifBlank { batter.text(8) }
                    if (name.isBlank()) null else CricketBatter(name, cleanText(batter.text(11)), batter.int(3),
                        batter.int(2), batter.int(4), batter.int(5), batter.text(6))
                },
                bowlers = card.messages(3).mapNotNull { bowler ->
                    val name = bowler.text(7).ifBlank { bowler.text(8) }
                    if (name.isBlank()) null else CricketBowler(name, bowler.text(2), bowler.int(15).takeIf { it > 0 },
                        bowler.int(3), bowler.int(5), bowler.int(4), bowler.text(6))
                },
                extras = card.child(5)?.let {
                    CricketExtras(it.int(6), it.int(2), it.int(1), it.int(3), it.int(4), it.int(5))
                },
                wickets = card.child(4)?.messages(1).orEmpty().map {
                    CricketWicket(it.text(2), it.int(4), oversLabel(it.double(3)), it.int(5).takeIf { balls -> balls > 0 })
                },
            )
        }.distinctBy { it.score.id }.sortedBy { it.score.id }
        return CricketScorecard(innings, cleanText(root.text(4)), root.int(2) != 0)
    }

    fun commentary(bytes: ByteArray, hundred: Boolean): CricketCommentaryPage {
        val root = message(bytes)
        val entries = commentaryMessages(root)
        fun cursor(line: Message): CricketCommentaryCursor? {
            val innings = line.int(4).takeIf { it > 0 } ?: root.int(1)
            val timestamp = line.number(2)
            return if (innings > 0 && timestamp > 0) CricketCommentaryCursor(innings, timestamp) else null
        }
        val lines = entries.mapNotNull { line ->
            val position = cursor(line) ?: return@mapNotNull null
            val text = commentaryText(line).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val over = line.double(3)
            val ball = line.int(11)
            val events = line.text(5).uppercase(Locale.US).split(',').map(String::trim)
            val delivery = when {
                "OVER-BREAK" in events -> null
                hundred && ball > 0 -> "Ball $ball"
                over > 0 -> String.format(Locale.US, "%.1f", over)
                else -> null
            }
            val event = listOf("WICKET", "SIX", "FOUR").firstOrNull { it in events }
            CricketCommentaryLine("${position.innings}:${position.timestamp}:$over:$ball:${line.text(5)}",
                position.timestamp, position.innings, delivery, event, text)
        }.distinctBy { it.id }.sortedByDescending { it.timestamp }
        val oldest = entries.mapNotNull(::cursor).minByOrNull { it.timestamp }
        return CricketCommentaryPage(lines, oldest, completeState(root.child(4)?.text(1).orEmpty()))
    }

    private fun commentaryMessages(root: Message) = root.messages(2).mapNotNull { it.child(1) }

    private fun commentaryText(line: Message): String {
        var detail = line.text(1)
        line.messages(7).flatMap { it.messages(2) }.forEach { value ->
            val token = value.text(1)
            if (token.isNotBlank()) detail = detail.replace(token, value.text(2))
        }
        return cleanText(detail.replace(Regex("[A-Z][0-9]+\\$"), ""))
    }

    private fun cleanText(text: String): String =
        text.replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("<[^>]*>"), "")
            .replace("&amp;", "&").replace("&nbsp;", " ").replace("&#39;", "'").replace("&quot;", "\"").trim()

    private fun oversLabel(value: Double) =
        if (value % 1.0 == 0.0) value.toInt().toString() else String.format(Locale.US, "%.1f", value)

    private fun teamScores(score: Message?, team: String) = listOfNotNull(score?.child(1), score?.child(2)).map { innings(it, team) }
    private fun innings(value: Message, team: String) = CricketInnings(value.int(1), team.take(20), value.int(4), value.int(5),
        oversLabel(value.double(6)))

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
