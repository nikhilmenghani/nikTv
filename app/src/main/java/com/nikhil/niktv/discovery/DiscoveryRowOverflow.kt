package com.nikhil.niktv.discovery

/** A full preview alone is not evidence of additional content. */
internal fun discoveryRowHasMore(totalItems: Int, displayedItems: Int, nextPage: Boolean = false): Boolean =
    displayedItems > 0 && (totalItems > displayedItems || nextPage)
