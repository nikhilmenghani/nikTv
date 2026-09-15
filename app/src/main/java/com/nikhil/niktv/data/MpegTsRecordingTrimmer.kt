package com.nikhil.niktv.data

import java.io.InputStream

/** Finds a safe MPEG-TS packet boundary at the requested presentation time. */
internal object MpegTsRecordingTrimmer {
    fun cutoffBytes(input: InputStream, durationMillis: Long): Long? {
        if (durationMillis <= 0L) return null
        val packet = ByteArray(PACKET_SIZE)
        var offset = 0L
        var firstPts: Long? = null
        while (readPacket(input, packet)) {
            if (packet[0] != SYNC_BYTE) return null
            packetPts(packet)?.let { pts ->
                val origin = firstPts ?: pts.also { firstPts = it }
                val elapsedMillis = (((pts - origin) and TIMESTAMP_MASK) * 1_000L) / 90_000L
                if (elapsedMillis > durationMillis + FRAME_TOLERANCE_MILLIS) return offset
            }
            offset += PACKET_SIZE
        }
        return null
    }

    private fun readPacket(input: InputStream, packet: ByteArray): Boolean {
        var read = 0
        while (read < packet.size) {
            val count = input.read(packet, read, packet.size - read)
            if (count < 0) return false
            read += count
        }
        return true
    }

    private fun packetPts(data: ByteArray): Long? {
        if ((data[1].toInt() and 0x40) == 0) return null
        val adaptationControl = (data[3].toInt() ushr 4) and 0x03
        if (adaptationControl == 0 || adaptationControl == 2) return null
        val payload = if (adaptationControl == 3) 5 + (data[4].toInt() and 0xff) else 4
        if (payload + 14 >= PACKET_SIZE || data[payload].toInt() != 0 ||
            data[payload + 1].toInt() != 0 || data[payload + 2].toInt() != 1
        ) return null
        val flags = (data[payload + 7].toInt() ushr 6) and 0x03
        if (flags !in 2..3) return null
        val position = payload + 9
        return (((data[position].toLong() ushr 1) and 0x07) shl 30) or
            ((data[position + 1].toLong() and 0xff) shl 22) or
            (((data[position + 2].toLong() ushr 1) and 0x7f) shl 15) or
            ((data[position + 3].toLong() and 0xff) shl 7) or
            ((data[position + 4].toLong() ushr 1) and 0x7f)
    }

    private const val PACKET_SIZE = 188
    private const val SYNC_BYTE: Byte = 0x47
    private const val FRAME_TOLERANCE_MILLIS = 80L
    private const val TIMESTAMP_MASK = (1L shl 33) - 1L
}
