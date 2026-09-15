package com.nikhil.niktv.data

/**
 * Makes separately downloaded MPEG-TS runs form one continuous timeline.
 * HLS resumes at the live edge, whose timestamps include time spent paused.
 */
internal class MpegTsTimestampRebaser {
    private var offset90Khz = 0L
    private var lastOutputPts: Long? = null
    private var previousOutputPts: Long? = null

    fun rebase(segment: ByteArray, forceContinuity: Boolean): ByteArray {
        val firstInputPts = findFirstPts(segment)
        val last = lastOutputPts
        if (forceContinuity && firstInputPts != null && last != null) {
            val step = previousOutputPts
                ?.let { positiveDelta(last, it) }
                ?.takeIf { it in 1..9_000 }
                ?: DEFAULT_FRAME_STEP
            offset90Khz = normalize(last + step - firstInputPts)
        }

        forEachPacket(segment) { packet ->
            adjustPcr(segment, packet, offset90Khz)
            val ptsPositions = timestampPositions(segment, packet)
            ptsPositions.forEachIndexed { index, position ->
                val adjusted = normalize(readTimestamp(segment, position) + offset90Khz)
                writeTimestamp(segment, position, adjusted)
                // PTS is first; DTS, when present, must be shifted but must not
                // become the continuity anchor for the following segment.
                if (index == 0) {
                    previousOutputPts = lastOutputPts
                    lastOutputPts = adjusted
                }
            }
        }
        return segment
    }

    private fun findFirstPts(data: ByteArray): Long? {
        var result: Long? = null
        forEachPacket(data) { packet ->
            if (result == null) {
                timestampPositions(data, packet).firstOrNull()?.let {
                    result = readTimestamp(data, it)
                }
            }
        }
        return result
    }

    private inline fun forEachPacket(data: ByteArray, block: (Int) -> Unit) {
        val firstSync = (0 until minOf(PACKET_SIZE, data.size)).firstOrNull { data[it] == SYNC_BYTE }
            ?: return
        var packet = firstSync
        while (packet + PACKET_SIZE <= data.size) {
            if (data[packet] == SYNC_BYTE) block(packet)
            packet += PACKET_SIZE
        }
    }

    private fun timestampPositions(data: ByteArray, packet: Int): List<Int> {
        if ((data[packet + 1].toInt() and 0x40) == 0) return emptyList()
        val adaptationControl = (data[packet + 3].toInt() ushr 4) and 0x03
        if (adaptationControl == 0 || adaptationControl == 2) return emptyList()
        val payload = if (adaptationControl == 3) {
            packet + 5 + (data[packet + 4].toInt() and 0xff)
        } else packet + 4
        if (payload + 14 >= packet + PACKET_SIZE ||
            data[payload].toInt() != 0 || data[payload + 1].toInt() != 0 ||
            data[payload + 2].toInt() != 1
        ) return emptyList()
        val flags = (data[payload + 7].toInt() ushr 6) and 0x03
        val pts = payload + 9
        return when {
            flags == 2 && pts + 4 < packet + PACKET_SIZE -> listOf(pts)
            flags == 3 && pts + 9 < packet + PACKET_SIZE -> listOf(pts, pts + 5)
            else -> emptyList()
        }
    }

    private fun adjustPcr(data: ByteArray, packet: Int, offset: Long) {
        val adaptationControl = (data[packet + 3].toInt() ushr 4) and 0x03
        if (adaptationControl != 2 && adaptationControl != 3) return
        val length = data[packet + 4].toInt() and 0xff
        if (length < 7 || packet + 11 >= data.size || (data[packet + 5].toInt() and 0x10) == 0) return
        val position = packet + 6
        val base = ((data[position].toLong() and 0xff) shl 25) or
            ((data[position + 1].toLong() and 0xff) shl 17) or
            ((data[position + 2].toLong() and 0xff) shl 9) or
            ((data[position + 3].toLong() and 0xff) shl 1) or
            ((data[position + 4].toLong() and 0x80) ushr 7)
        val adjusted = normalize(base + offset)
        data[position] = (adjusted ushr 25).toByte()
        data[position + 1] = (adjusted ushr 17).toByte()
        data[position + 2] = (adjusted ushr 9).toByte()
        data[position + 3] = (adjusted ushr 1).toByte()
        data[position + 4] = ((data[position + 4].toInt() and 0x7f) or
            (((adjusted and 1L).toInt()) shl 7)).toByte()
    }

    private fun readTimestamp(data: ByteArray, position: Int): Long =
        (((data[position].toLong() ushr 1) and 0x07) shl 30) or
            ((data[position + 1].toLong() and 0xff) shl 22) or
            (((data[position + 2].toLong() ushr 1) and 0x7f) shl 15) or
            ((data[position + 3].toLong() and 0xff) shl 7) or
            ((data[position + 4].toLong() ushr 1) and 0x7f)

    private fun writeTimestamp(data: ByteArray, position: Int, value: Long) {
        data[position] = ((data[position].toInt() and 0xf0) or
            (((value ushr 30).toInt() and 0x07) shl 1) or 1).toByte()
        data[position + 1] = (value ushr 22).toByte()
        data[position + 2] = ((((value ushr 15).toInt() and 0x7f) shl 1) or 1).toByte()
        data[position + 3] = (value ushr 7).toByte()
        data[position + 4] = ((((value.toInt()) and 0x7f) shl 1) or 1).toByte()
    }

    private fun positiveDelta(newer: Long, older: Long): Long = normalize(newer - older)
    private fun normalize(value: Long): Long = value and TIMESTAMP_MASK

    private companion object {
        const val PACKET_SIZE = 188
        const val SYNC_BYTE: Byte = 0x47
        const val DEFAULT_FRAME_STEP = 3_600L
        const val TIMESTAMP_MASK = (1L shl 33) - 1L
    }
}
