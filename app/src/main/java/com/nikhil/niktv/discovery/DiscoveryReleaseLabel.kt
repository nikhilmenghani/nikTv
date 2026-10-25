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

/** Separate broadcast facts from genres so long metadata can wrap without truncation. */
internal fun discoveryDetailMetadata(card: DiscoveryCard, locale: Locale = Locale.getDefault()): List<String> = listOf(
    listOfNotNull(discoveryReleaseLabel(card, locale), card.runtime?.takeIf { it > 0 }?.let { "$it min" }).joinToString(" · "),
    (card.genres.filter(String::isNotBlank).distinct() + listOfNotNull(
        card.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(locale, it) }, card.certification?.takeIf(String::isNotBlank)
    )).joinToString(" · ")
).filter(String::isNotBlank)
