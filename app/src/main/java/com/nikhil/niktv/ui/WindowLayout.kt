package com.nikhil.niktv.ui

import android.content.res.Configuration

/** Wide touch chrome needs 600dp of actual window width, including in split screen. */
internal fun Configuration.usesPhoneLayout(television: Boolean = false): Boolean {
    if (television) return false
    val width = screenWidthDp.takeIf { it > 0 } ?: smallestScreenWidthDp
    return width < 600
}

/** Wide composition on a short touch window needs compact spacing, not TV input behavior. */
internal fun Configuration.usesShortWideLayout(television: Boolean = false): Boolean =
    !television && !usesPhoneLayout() && screenHeightDp in 1..479
