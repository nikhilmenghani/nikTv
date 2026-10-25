package com.nikhil.niktv.ui

internal fun categoryMetadataExpired(fetchedAt: Long, now: Long, intervalMinutes: Int): Boolean =
    fetchedAt <= 0 || now < fetchedAt || now - fetchedAt >= intervalMinutes.coerceAtLeast(1) * 60_000L
