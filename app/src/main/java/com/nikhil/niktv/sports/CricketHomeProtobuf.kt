package com.nikhil.niktv.sports

internal data class CricketVideoCollection(val id: Int, val title: String)
internal data class CricketVideoPage(val videos: List<CricketHighlight>, val nextCursor: String?)
internal data class CricketVideoSection(val id: Int?, val title: String, val videos: List<CricketHighlight>)

/** Bounded readers for Home, VideoList and VideoCollectionsDetail's declared wrapper fields. */
internal object CricketHomeProtobuf {
    fun parse(bytes: ByteArray): List<CricketHighlight> {
        val root = message(bytes)
        // HomePageStories.videoList (3), featuredVideoLists (10). Unknown news/ad fields are skipped.
        return (root.blobs(3) + root.blobs(10)).flatMap { list ->
            message(list).blobs(1).mapNotNull { wrapper ->
                message(wrapper).blobs(1).firstOrNull()?.let { video(message(it), highlightsOnly = true) }
            }
        }
    }

    fun latest(bytes: ByteArray): CricketVideoPage = page(message(bytes).blobs(1))

    fun collections(bytes: ByteArray): List<CricketVideoCollection> = collectionMessages(message(bytes)).mapNotNull { item ->
        val id = item.number(1)?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: return@mapNotNull null
        val title = item.text(2).trim().takeIf { it.isNotEmpty() && it.length <= 300 } ?: return@mapNotNull null
        CricketVideoCollection(id, title)
    }.distinctBy { it.id }.take(100)

    fun sections(bytes: ByteArray): List<CricketVideoSection> = collectionMessages(message(bytes)).mapNotNull { item ->
        val id = item.number(1)?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: return@mapNotNull null
        val title = item.text(2).trim().takeIf { it.isNotEmpty() && it.length <= 300 } ?: return@mapNotNull null
        CricketVideoSection(id, title, page(item.blobs(6)).videos)
    }.distinctBy { it.id }.take(100)

    fun collectionPage(bytes: ByteArray): CricketVideoPage {
        val root = message(bytes)
        return page(root.blobs(7) + collectionMessages(root).flatMap { it.blobs(6) })
    }

    private fun collectionMessages(root: Message): List<Message> = root.blobs(1).mapNotNull { wrapper ->
        message(wrapper).blobs(1).firstOrNull()?.let(::message)
    }

    private fun page(wrappers: List<ByteArray>): CricketVideoPage {
        val items = wrappers.mapNotNull { wrapper -> message(wrapper).blobs(1).firstOrNull()?.let(::message) }
        val rows = items.mapNotNull { video(it, highlightsOnly = false) }.distinctBy { it.id }.take(500)
        // pt is the publish timestamp of the last returned video, not its numeric video ID.
        val cursor = items.lastOrNull()?.number(4)?.takeIf { it > 0 }?.toString()
        return CricketVideoPage(rows, cursor.takeIf { items.isNotEmpty() })
    }

    private fun video(item: Message, highlightsOnly: Boolean): CricketHighlight? {
        val id = item.text(1)
        val title = item.text(2).trim()
        val type = item.text(18).trim()
        if (!validCricketId(id) || title.isEmpty() || title.length > 500 || item.number(8) == 1L ||
            type.equals("MatchStream", true) || (highlightsOnly && !type.equals("Highlights", true))) return null
        val image = item.text(22).takeIf(::cricketHttpsUrl) ?: item.text(6).takeIf { it.matches(Regex("[0-9]{1,20}")) }
            ?.let { "https://static.cricbuzz.com/a/img/v1/1280x720/i1/c$it/i.jpg" }.orEmpty()
        val premium = item.text(17).takeIf(::cricketHttpsUrl).orEmpty()
        val standard = item.text(14).takeIf(::cricketHttpsUrl).orEmpty()
        // Explicitly free clips use videoUrl as their actual asset; it is not a fallback for a missing premium URL.
        val freeAsset = premium.isBlank() && item.number(20) == 1L
        return CricketHighlight(id, title, item.text(10), image,
            premium.ifBlank { standard.takeIf { freeAsset }.orEmpty() },
            standard.takeUnless { freeAsset }.orEmpty(), type = type.ifBlank { "Video" })
    }

    private data class Message(val blobs: Map<Int, List<ByteArray>>, val numbers: Map<Int, Long>) {
        fun blobs(tag: Int) = blobs[tag].orEmpty()
        fun text(tag: Int) = blobs(tag).lastOrNull()?.toString(Charsets.UTF_8).orEmpty()
        fun number(tag: Int) = numbers[tag]
    }

    private fun message(bytes: ByteArray): Message {
        if (bytes.size > CricketHighlightsCatalogue.MAX_BYTES) invalid()
        var offset = 0
        var count = 0
        val blobs = mutableMapOf<Int, MutableList<ByteArray>>()
        val numbers = mutableMapOf<Int, Long>()
        fun varint(): Long {
            var value = 0L
            for (shift in 0..63 step 7) {
                if (offset >= bytes.size) invalid()
                val byte = bytes[offset++].toInt() and 255
                if (shift == 63 && byte > 1) invalid()
                value = value or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) return value
            }
            invalid()
        }
        fun skip(length: Long): ByteArray {
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
                1 -> skip(8)
                2 -> blobs.getOrPut(tag) { mutableListOf() }.add(skip(varint()))
                5 -> skip(4)
                else -> invalid()
            }
        }
        return Message(blobs, numbers)
    }

    private fun invalid(): Nothing = throw SportsException("The highlights provider returned an unreadable catalogue.")
}
