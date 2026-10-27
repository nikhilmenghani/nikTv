package com.nikhil.niktv.sports

internal fun sportsCatalogueNeedsRefresh(lastChecked: Long?, now: Long): Boolean =
    lastChecked == null || now < lastChecked || now - lastChecked >= 120_000
