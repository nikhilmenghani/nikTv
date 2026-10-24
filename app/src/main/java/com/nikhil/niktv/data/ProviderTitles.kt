package com.nikhil.niktv.data

import com.nikhil.niktv.model.CatalogType

/** Presentation/search only. Never rewrite provider identities, commands or persisted names. */
internal object ProviderTitles {
    private const val language = "english|eng|en|hindi|hin|hi|tamil|tam|ta|telugu|tel|te|kannada|kan|kn|malayalam|mal|ml|marathi|mar|mr|bengali|bangla|ben|bn|gujarati|guj|gu|punjabi|pan|pa|urdu|urd|ur|french|fra|fr|spanish|spa|es|german|ger|de|italian|ita|it|portuguese|por|pt|russian|rus|ru|arabic|ara|ar|japanese|jpn|jp|ja|korean|kor|ko|chinese|mandarin|cantonese|zho|chi|cn|zh|turkish|tur|tr|dutch|nld|nl|polish|pol|pl|ukrainian|ukr|uk"
    private const val quality = "4k|8k|uhd|fhd|hd|sd|2160p|1080p|720p|480p|cam|hdcam|hdr|hevc|x26[45]|h[ .-]?26[45]|blu[ -]?ray|web[ -]?(?:dl|rip)|dual[ -]?audio|multi(?:[ -]?(?:audio|language))?|dub(?:bed)?|sub(?:bed|title[sd]?)?|e[ -]?subs?"
    private const val platform = "netflix|amazon(?: prime)?|disney\\+?|paramount\\+?|comedy central|pbs kids|apple tv\\+?|hbo"
    private const val content = "tv[ -]?shows?|web[ -]?series|series|movies?"
    private val metadata = Regex("^(?:$language|$quality)(?:[ ,/|+&-]+(?:$language|$quality|$platform|$content))*$", RegexOption.IGNORE_CASE)
    private val edgeLabel = Regex("^(?:$language|$quality|vod|movies?|series)\\s*(?:[|]\\s*|\\s+-\\s+)", RegexOption.IGNORE_CASE)
    private val trailingLabel = Regex("(?:\\s*\\|\\s*|\\s+-\\s+)((?:$language|$quality)(?:[ ,/|+&-]+(?:$language|$quality|$platform|$content))*)$", RegexOption.IGNORE_CASE)
    private val firstGroup = Regex("^\\s*(?:\\[([^\\]]+)\\]|\\(([^)]+)\\))\\s*")
    private val lastGroup = Regex("\\s*(?:\\[([^\\]]+)\\]|\\(([^)]+)\\))\\s*$")
    private val channel = Regex("^(?:star\\s+(?:plus|bharat|pravah|jalsha|maa|vijay)|sony(?:\\s+(?:sab|set|pal|tv|entertainment))?|zee(?:\\s+(?:tv|marathi|bangla|kannada|telugu|tamil|punjabi))?|colors(?:\\s+(?:tv|marathi|bangla|kannada|tamil|gujarati))?|sab|sun\\s+(?:tv|neo)|dangal(?:\\s+tv)?|mtv|urdu one|hum\\s+tv|har pal geo|set entertainment)(?:[ -]+(?:$language))?\\s*[:|]\\s*(?=\\S)", RegexOption.IGNORE_CASE)
    private val year = Regex("\\s*[\\[(](?:19|20)\\d{2}[)\\]]\\s*$")
    // Visible items and searches share a bounded cache; no catalogue-wide pass or IO.
    private val cache = object : LinkedHashMap<Pair<String, Boolean>, String>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, Boolean>, String>?) = size > 1024
    }
    fun clean(raw: String, series: Boolean): String = synchronized(cache) {
        val key = raw to series
        cache[key] ?: parse(raw, series).also { cache[key] = it }
    }
    private fun parse(raw: String, series: Boolean): String {
        var value = raw.trim()
        repeat(8) {
            var next = value.replace(edgeLabel, "")
            if (series) next = next.replace(channel, "")
            for (regex in listOf(firstGroup, lastGroup)) {
                val match = regex.find(next)
                if (match != null && metadata.matches(match.groupValues.drop(1).first { it.isNotBlank() }.trim()))
                    next = next.removeRange(match.range).trim()
            }
            next = next.replace(trailingLabel, "").trim()
            if (next.isBlank() || next == value) return value.ifBlank { raw }
            value = next
        }
        return value.ifBlank { raw }
    }
    fun display(raw: String, type: CatalogType): String {
        if (type == CatalogType.LIVE_TV) return raw // Live cards have category-aware presentation.
        val clean = clean(raw, type == CatalogType.SERIES)
        return clean.replace(year, "").trim().ifBlank { clean }
    }
}
internal fun providerTmdbTitle(title: String): String = ProviderTitles.clean(title, false)
internal fun providerSeriesTmdbTitle(title: String): String = ProviderTitles.clean(title, true)
internal fun providerDisplayTitle(title: String, type: CatalogType): String = ProviderTitles.display(title, type)
