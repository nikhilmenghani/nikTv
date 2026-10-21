package com.nikhil.niktv.ui

internal enum class CategoryPickerDirection { UP, DOWN, LEFT, RIGHT }

/** Null leaves the grid for the search/footer; horizontal edges stay in the row. */
internal fun categoryPickerDestination(index: Int, count: Int, columns: Int, direction: CategoryPickerDirection): Int? {
    if (index !in 0 until count) return null
    val width = columns.coerceAtLeast(1)
    return when (direction) {
        CategoryPickerDirection.UP -> (index - width).takeIf { it >= 0 }
        CategoryPickerDirection.DOWN -> if (index / width == (count - 1) / width) null else minOf(index + width, count - 1)
        CategoryPickerDirection.LEFT -> if (index % width == 0) index else index - 1
        CategoryPickerDirection.RIGHT -> if ((index + 1) % width == 0 || index == count - 1) index else index + 1
    }
}

internal fun categoryPickerColumns(widthDp: Int, fontScale: Float): Int =
    when {
        widthDp / fontScale.coerceAtLeast(1f) >= 800 -> 4
        widthDp / fontScale.coerceAtLeast(1f) >= 560 -> 2
        else -> 1
    }

/** An explicitly empty selection stays empty; draft edits survive background category updates. */
internal fun categoryPickerSelection(draft: List<String>?, saved: List<String>?, defaults: List<String>): Set<String> =
    (draft ?: saved ?: defaults).toSet()
