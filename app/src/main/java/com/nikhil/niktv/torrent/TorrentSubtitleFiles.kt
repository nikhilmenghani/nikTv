package com.nikhil.niktv.torrent

import java.util.Locale

internal data class TorrentSubtitleFile(
    val index: Int, val name: String, val sizeBytes: Long,
    val language: String?, val label: String, val url: String
)

internal const val MAX_TORRENT_SUBTITLE_BYTES = 2L * 1024 * 1024

/** Match the chosen episode, never another episode or a generic pack subtitle. */
internal fun matchingTorrentSubtitles(videoName: String, files: List<TorrentVideoFile>): List<TorrentSubtitleFile> {
    val base = videoName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').lowercase(Locale.ROOT)
    return files.asSequence().filter { it.sizeBytes in 1..MAX_TORRENT_SUBTITLE_BYTES }.mapNotNull { file ->
        val leaf = file.name.substringAfterLast('/').substringAfterLast('\\')
        if (leaf.substringAfterLast('.', "").lowercase(Locale.ROOT) !in setOf("srt", "vtt", "ass", "ssa")) return@mapNotNull null
        val stem = leaf.substringBeforeLast('.').lowercase(Locale.ROOT)
        val suffix = when {
            stem == base -> ""
            stem.startsWith(base) && stem.getOrNull(base.length) in listOf('.', '_', '-', ' ') -> stem.substring(base.length + 1)
            else -> return@mapNotNull null
        }
        if (suffix.length > 64) return@mapNotNull null
        val tokens = suffix.split(Regex("[._\\- ]+")).filter(String::isNotBlank)
        var language: String? = null
        val qualifiers = mutableListOf<String>()
        for (token in tokens) {
            val knownLanguage = SUBTITLE_LANGUAGES[token]
            when {
                token == "forced" -> qualifiers += "Forced"
                token in setOf("sdh", "cc") || token == "hi" && language != null -> qualifiers += "SDH"
                language == null && knownLanguage != null -> language = knownLanguage
                language != null && token in SUBTITLE_REGIONS -> language += "-${token.uppercase(Locale.ROOT)}"
                else -> return@mapNotNull null
            }
        }
        val title = language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).replaceFirstChar(Char::uppercaseChar) }
            ?.takeIf(String::isNotBlank) ?: "Subtitle"
        TorrentSubtitleFile(file.index, file.name, file.sizeBytes, language,
            (listOf(title) + qualifiers.distinct()).joinToString(" · "), "")
    }.distinctBy { it.index }.take(8).toList()
}

private val SUBTITLE_REGIONS = Locale.getISOCountries().map { it.lowercase(Locale.ROOT) }.toSet()
private val SUBTITLE_LANGUAGES: Map<String, String> = buildMap {
    Locale.getISOLanguages().forEach { code ->
        val locale = Locale.forLanguageTag(code)
        put(code, code)
        runCatching { locale.isO3Language }.getOrNull()?.let { put(it.lowercase(Locale.ROOT), code) }
        put(locale.getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT), code)
    }
    put("fre", "fr"); put("ger", "de"); put("chi", "zh"); put("dut", "nl")
}
