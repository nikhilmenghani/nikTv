package com.nikhil.niktv.data

internal data class LanMediaRange(val start: Long, val end: Long) { val length get() = end - start + 1 }
internal fun lanMediaRange(header: String?, size: Long): LanMediaRange? {
    require(size >= 0)
    if (header == null) return null
    require(size > 0 && header.matches(Regex("bytes=[0-9]*-[0-9]*"))) { "Invalid range" }
    val parts = header.removePrefix("bytes=").split('-')
    val start: Long
    val end: Long
    if (parts[0].isBlank()) {
        val suffix = requireNotNull(parts[1].toLongOrNull()); require(suffix > 0)
        start = (size - suffix).coerceAtLeast(0); end = size - 1
    } else {
        start = requireNotNull(parts[0].toLongOrNull())
        end = if (parts[1].isBlank()) size - 1 else requireNotNull(parts[1].toLongOrNull()).coerceAtMost(size - 1)
    }
    require(start in 0 until size && end >= start)
    return LanMediaRange(start, end)
}
