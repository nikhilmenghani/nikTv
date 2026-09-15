package com.nikhil.niktv.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MpegTsTimestampRebaserTest {
    @Test
    fun resumedSegmentContinuesAfterLastRecordedFrame() {
        val rebaser = MpegTsTimestampRebaser()

        val first = rebaser.rebase(packetWithPts(90_000L), forceContinuity = false)
        val resumed = rebaser.rebase(packetWithPts(990_000L), forceContinuity = true)
        val following = rebaser.rebase(packetWithPts(993_600L), forceContinuity = false)

        assertEquals(90_000L, readPts(first))
        assertEquals(93_600L, readPts(resumed))
        assertEquals(97_200L, readPts(following))
    }

    private fun packetWithPts(pts: Long): ByteArray = ByteArray(188) { 0xff.toByte() }.apply {
        this[0] = 0x47
        this[1] = 0x40 // payload-unit start
        this[2] = 0
        this[3] = 0x10 // payload only
        this[4] = 0
        this[5] = 0
        this[6] = 1
        this[7] = 0xe0.toByte()
        this[8] = 0
        this[9] = 0
        this[10] = 0x80.toByte()
        this[11] = 0x80.toByte() // PTS only
        this[12] = 5
        writePts(this, 13, pts)
    }

    private fun writePts(data: ByteArray, position: Int, value: Long) {
        data[position] = (0x20 or (((value ushr 30).toInt() and 7) shl 1) or 1).toByte()
        data[position + 1] = (value ushr 22).toByte()
        data[position + 2] = ((((value ushr 15).toInt() and 0x7f) shl 1) or 1).toByte()
        data[position + 3] = (value ushr 7).toByte()
        data[position + 4] = (((value.toInt() and 0x7f) shl 1) or 1).toByte()
    }

    private fun readPts(data: ByteArray): Long {
        val position = 13
        return (((data[position].toLong() ushr 1) and 7) shl 30) or
            ((data[position + 1].toLong() and 0xff) shl 22) or
            (((data[position + 2].toLong() ushr 1) and 0x7f) shl 15) or
            ((data[position + 3].toLong() and 0xff) shl 7) or
            ((data[position + 4].toLong() ushr 1) and 0x7f)
    }
}
