package com.nikhil.niktv.sports

/** Bounded reader for the Home -> VideoList -> VideoAdWrapper -> VideoItem fields. */
internal object CricketHomeProtobuf {
    fun parse(bytes: ByteArray): List<CricketHighlight> {
        val root = message(bytes)
        // HomePageStories.videoList (3), featuredVideoLists (10). Unknown news/ad fields are skipped.
        return (root.blobs(3) + root.blobs(10)).flatMap { list ->
            message(list).blobs(1).mapNotNull { wrapper ->
                message(wrapper).blobs(1).firstOrNull()?.let { video(message(it)) }
            }
        }
    }

    private fun video(item: Message): CricketHighlight? {
        val id = item.text(1)
        val title = item.text(2).trim()
        if (!validCricketId(id) || title.isEmpty() || title.length > 500 || item.number(8) == 1L ||
            !item.text(18).equals("Highlights", true)) return null
        val image = item.text(22).takeIf(::cricketHttpsUrl) ?: item.text(6).takeIf { it.matches(Regex("[0-9]{1,20}")) }
            ?.let { "https://static.cricbuzz.com/a/img/v1/i1/c$it/i.jpg" }.orEmpty()
        return CricketHighlight(id, title, item.text(10), image,
            item.text(17).takeIf(::cricketHttpsUrl).orEmpty(), item.text(14).takeIf(::cricketHttpsUrl).orEmpty())
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
