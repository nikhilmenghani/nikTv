package com.nikhil.niktv.discovery

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun discoveryReleaseLabel(card: DiscoveryCard, locale: Locale = Locale.getDefault(), today: LocalDate = LocalDate.now()): String? {
    val date = runCatching { LocalDate.parse(card.date) }.getOrNull() ?: return null
    val label = if (card.series) { if (date > today) "First airs" else "First aired" }
        else if (date > today) "Releases" else "Released"
    return "$label ${date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))}"
}
