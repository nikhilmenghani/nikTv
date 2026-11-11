package com.nikhil.niktv.ui

import android.content.res.Configuration

/** Presentation follows the current window; rotating a phone opens the wide touch layout. */
internal fun Configuration.usesPhoneLayout(television: Boolean = false): Boolean {
    if (television) return false
    val width = screenWidthDp.takeIf { it > 0 } ?: smallestScreenWidthDp
    val landscape = screenHeightDp.takeIf { it > 0 }?.let { width > it }
        ?: (orientation == Configuration.ORIENTATION_LANDSCAPE)
    return width < 600 && !landscape
}

/** Wide composition on a short touch window needs compact spacing, not TV input behavior. */
internal fun Configuration.usesShortWideLayout(television: Boolean = false): Boolean =
    !television && !usesPhoneLayout() && screenHeightDp in 1..479
