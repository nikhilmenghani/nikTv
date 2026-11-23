package com.nikhil.niktv.discovery

/** Logical tile indices exclude the two non-focusable section headings. */
internal class ChannelSectionGrid(val pinnedCount: Int, val total: Int, val columns: Int) {
    val sectioned get() = pinnedCount > 0
    fun gridIndex(tile: Int): Int = tile + if (!sectioned) 0 else if (tile < pinnedCount) 1 else 2
    fun tileIndex(grid: Int): Int = if (!sectioned) grid else
        (if (grid == pinnedCount + 1) pinnedCount else grid - if (grid <= pinnedCount) 1 else 2)
            .coerceIn(0, (total - 1).coerceAtLeast(0))
    fun vertical(tile: Int, down: Boolean): Int {
        if (!sectioned) return tile + if (down) columns else -columns
        val start = if (tile < pinnedCount) 0 else pinnedCount
        val end = if (start == 0) pinnedCount else total
        val column = (tile - start) % columns
        val rowStart = start + (tile - start) / columns * columns
        val target = rowStart + if (down) columns else -columns
        return when {
            target in start until end -> (target + column).coerceAtMost(end - 1)
            down && start == 0 && pinnedCount < total -> (pinnedCount + column).coerceAtMost(total - 1)
            !down && start == pinnedCount -> ((pinnedCount - 1) / columns * columns + column).coerceAtMost(pinnedCount - 1)
            else -> if (down) total else -1
        }
    }
}
