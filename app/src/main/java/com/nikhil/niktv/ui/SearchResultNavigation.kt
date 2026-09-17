package com.nikhil.niktv.ui

/** Result index, footer at count, or -1 for the controls above the results. */
internal fun searchVerticalTarget(index: Int, columns: Int, count: Int, down: Boolean, footer: Boolean): Int {
    if (index == count) return if (down) count else (count - 1).coerceAtLeast(-1)
    if (!down) return if (index < columns) -1 else index - columns
    val nextRow = (index / columns + 1) * columns
    return when {
        nextRow < count -> minOf(index + columns, count - 1)
        footer -> count
        else -> index
    }
}
