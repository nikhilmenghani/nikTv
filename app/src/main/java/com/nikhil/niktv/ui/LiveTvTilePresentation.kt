package com.nikhil.niktv.ui

import java.util.Locale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

internal data class LiveTvTilePresentation(
    val title: String,
    val context: String? = null,
    val quality: String? = null
)

private val liveLanguages = listOf("English", "Hindi", "Tamil", "Telugu", "Malayalam", "Punjabi",
    "Kannada", "Marathi", "Gujarati", "Bengali", "Odia", "Assamese", "Bhojpuri", "Arabic")
private val liveRegions = mapOf("IN" to "India", "INDIA" to "India", "US" to "USA", "USA" to "USA",
    "UK" to "UK", "CA" to "Canada", "CANADA" to "Canada", "PK" to "Pakistan", "PAKISTAN" to "Pakistan",
    "PT" to "Portugal", "DE" to "Germany", "RU" to "Russia", "ES" to "Spain", "FR" to "France",
    "AU" to "Australia", "NZ" to "New Zealand")
private val livePrefixLabels = liveRegions + liveLanguages.associateBy { it.uppercase(Locale.ROOT) } +
    mapOf("TM" to "Tamil", "CRIC" to "Cricket", "KIDS" to "Kids", "EN" to "English", "AR" to "Arabic")
private val continuousLabel = Regex("(?i)\\b24\\s*[/x]\\s*7\\b")
private val qualityLabel = Regex("(?i)(?<![\\p{L}\\p{N}])(?:\\((8K|4K|UHD|FHD|HD|SD)\\)|(8K|4K|UHD|FHD|HD|SD))(?![\\p{L}\\p{N}+]|\\s*\\+)")

private fun String.liveLabelCase(): String = split(' ').joinToString(" ") { word ->
    when (word.uppercase(Locale.ROOT)) {
        "TV", "UK", "USA", "PPV", "UHD", "FHD", "HD", "SD", "4K", "8K", "2160P", "ESPN+", "MLS", "F1", "NBA", "NFL", "EPL", "MLB", "UAE", "MBC", "DSTV" -> word.uppercase(Locale.ROOT)
        "24X7" -> "24x7"
        else -> word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
    }
}

private fun categoryContentLabel(value: String): String {
    val clean = continuousLabel.replace(value.trim(), "24x7")
    return (if (clean.endsWith("24x7", true) && clean.length > 4) {
        "24x7 ${clean.dropLast(4).trim()}"
    } else clean).liveLabelCase()
}

/** Also recognizes the provider's pipe-less "ENGLISH Movies 24/7" category. */
internal fun liveCategoryLabelParts(title: String): List<String>? {
    val parts = title.split('|', limit = 2).map(String::trim)
    if (parts.size == 2 && parts.all(String::isNotBlank)) {
        return listOf(parts[0].liveLabelCase(), categoryContentLabel(parts[1]))
    }
    val language = liveLanguages.firstOrNull { title.startsWith("$it ", true) }
    return language?.takeIf { continuousLabel.containsMatchIn(title) }?.let {
        listOf(it, categoryContentLabel(title.drop(it.length)))
    }
}

/** Presentation only: provider names, stream IDs, search and playback stay intact. */
internal fun liveTvTilePresentation(title: String, category: String, xtream: Boolean): LiveTvTilePresentation {
    if (!xtream) {
        val quality = Regex("(?i)\\s*\\((4K|8K|UHD|FHD|HD)\\)\\s*$")
        val group = category.substringBefore('|').trim()
        var name = title.replace(quality, "").trim()
        if ('|' in category && group.isNotBlank() && name.startsWith("$group ", true)) name = name.drop(group.length).trim()
        if (category.contains("MOVIES", true) && name.endsWith(" MOVIES", true) && name.length > 7) name = name.dropLast(7).trim()
        return LiveTvTilePresentation(name, quality = quality.find(title)?.groupValues?.get(1)?.uppercase(Locale.ROOT))
    }

    val parts = liveCategoryLabelParts(category)
    val tags = mutableListOf<String>()
    var name = title.trim().replace('_', ' ').replace(Regex("\\s+"), " ")
        .replace("ᶠᴴᴰ", "FHD").replace("ᴴᴰ", "HD").replace("⁴ᵏ", "4K")
    // Only consume recognized tags; a colon in a programme/event name is not a delimiter.
    repeat(3) {
        val prefix = Regex("^([\\p{L}]+)\\s*[:|\\-▎]+\\s*").find(name)
        val label = prefix?.groupValues?.get(1)?.uppercase(Locale.ROOT)?.let(livePrefixLabels::get)
        if (prefix != null && label != null) {
            tags += label
            name = name.drop(prefix.value.length).trim()
        }
    }
    if (parts?.first() == "Canada" && name.startsWith("CA ")) {
        tags += "Canada"
        name = name.drop(3).trim()
        if (name.startsWith("(Asian)", true)) {
            tags += "Asian"
            name = name.drop(7).trim()
        }
    }
    val countrySuffix = Regex("(?:\\[([A-Z]{2,3})]|\\|([A-Z]{2,3})\\|)\\s*$").find(name)
    val country = countrySuffix?.let { liveRegions[it.groupValues[1].ifBlank { it.groupValues[2] }] }
    if (country != null) {
        tags += country
        name = name.removeRange(countrySuffix.range).trim()
    }
    val qualities = qualityLabel.findAll(name).toList()
    val quality = qualities.firstOrNull()?.let { it.groupValues[1].ifBlank { it.groupValues[2] }.uppercase(Locale.ROOT) }
    name = qualityLabel.replace(name, " ").trim().trim(' ', '.', '-', '▎')
    val qualityPrefixedRegion = Regex("^([A-Z]{2,3})\\s*[:|]+\\s*").find(name)
    qualityPrefixedRegion?.groupValues?.get(1)?.let(liveRegions::get)?.let {
        tags += it
        name = name.drop(qualityPrefixedRegion.value.length).trim()
    }
    val languageSuffix = liveLanguages.firstOrNull { name.endsWith(" $it", true) }
    if (languageSuffix != null) {
        tags += languageSuffix
        name = name.dropLast(languageSuffix.length).trim()
    }
    var content = parts?.last()
    if (name.startsWith("WEBSERIES ", true)) {
        name = name.drop(10).trim()
        content = if (continuousLabel.containsMatchIn(category)) "24x7 Web Series" else "Web Series"
    }
    if (continuousLabel.containsMatchIn(name)) {
        // Preserve internal occurrences (e.g. a movie called "24/7"), strip edge labels only.
        val edge = Regex("(?i)^24\\s*[/x]\\s*7\\s+|\\s+24\\s*[/x]\\s*7$")
        if (edge.containsMatchIn(name)) {
            name = name.replace(edge, "").trim()
            if (content?.contains("24x7") != true) content = listOfNotNull("24x7", content?.takeUnless { it == "TV" }).joinToString(" ")
        }
    }
    if (content?.contains("24x7") == true && content.contains("Movies")) {
        if (name.startsWith("ACTOR ", true)) name = name.drop(6).trim()
        if (name.endsWith(" MOVIES", true) && name.length > 7) name = name.dropLast(7).trim()
    }
    val event = Regex("(?i)^PPV EVENT (\\d+):\\s*(?=\\S)").find(name)
    if (event != null) {
        tags += "Event ${event.groupValues[1]}"
        name = name.drop(event.value.length)
    }
    if (tags.none { it in liveRegions.values || it in liveLanguages }) {
        parts?.first()?.takeUnless { it in listOf("India", "Sports", "Real 4K", "PPV") }?.let(tags::add)
    }
    content?.takeUnless { it == "TV" || it in tags }?.let(tags::add)
    name = name.replace(Regex("\\s+"), " ").trim()
    return LiveTvTilePresentation(name.ifBlank { title.trim() }, tags.distinct().joinToString(" · ").ifBlank { null }, quality)
}

/** Some Xtream guides contain multi-day entries; time-only labels make these look like future shows. */
internal fun liveTileScheduleText(start: Long?, end: Long?, timeZone: TimeZone = TimeZone.getDefault()): String? {
    if (start == null) return null
    fun format(pattern: String, value: Long) = SimpleDateFormat(pattern, Locale.getDefault()).apply {
        this.timeZone = timeZone
    }.format(Date(value))
    if (end == null) return "From ${format("h:mm a", start)}"
    return if (format("yyyyMMdd", start) == format("yyyyMMdd", end)) {
        "${format("h:mm a", start)}–${format("h:mm a", end)}"
    } else {
        "${format("MMM d", start)}–${format("MMM d", end)}"
    }
}
