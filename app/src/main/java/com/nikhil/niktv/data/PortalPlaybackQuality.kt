package com.nikhil.niktv.data

import android.content.Context
import com.nikhil.niktv.model.PortalPlaybackVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

internal fun portalPlaybackVariants(rows: List<JsonObject>): List<PortalPlaybackVariant> = rows.mapNotNull { row ->
    fun text(key: String) = (row[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val id = text("id").takeIf(String::isNotBlank) ?: return@mapNotNull null
    val name = text("name").ifBlank { text("title") }
    val description = "$name ${text("quality")}"
    val height = Regex("(?i)\\b(4320|2160|1440|1080|720|576|480|360|240)p?\\b").find(description)
        ?.groupValues?.get(1)?.toIntOrNull()
        ?: when {
            Regex("(?i)\\b(?:4k|uhd)\\b").containsMatchIn(description) -> 2160
            Regex("(?i)\\b(?:fhd|full hd)\\b").containsMatchIn(description) -> 1080
            Regex("(?i)\\bhd\\b").containsMatchIn(description) -> 720
            else -> 0
        }
    val language = name.substringBefore('/').trim().takeIf { '/' in name }.orEmpty()
    PortalPlaybackVariant(id, name.ifBlank { if (height > 0) "${height}p" else "Provider quality" },
        height, language, "/media/file_$id.mpg")
}.distinctBy { it.id }

/** Preserve the provider's default language while ranking resolution within it. */
internal fun rankedPortalVariants(variants: List<PortalPlaybackVariant>, preferredHeight: Int,
    preferredId: String? = null): List<PortalPlaybackVariant> {
    val selected = variants.firstOrNull { it.id == preferredId }
    val language = selected?.language ?: variants.firstOrNull()?.language.orEmpty()
    return variants.sortedWith(compareByDescending<PortalPlaybackVariant> { it.id == preferredId }
        .thenByDescending { it.language == language }
        .thenByDescending { preferredHeight == 0 || it.height in 1..preferredHeight }
        .thenByDescending { if (preferredHeight > 0 && it.height > preferredHeight) -it.height else it.height })
}

internal object PortalQualityPreferences {
    private const val DOMAIN = "iptv_playback_quality"
    fun height(context: Context, owner: UserScope?): Int = owner?.let {
        UserProfilePreferences.get(context, DOMAIN, owner = it).getInt("height", 0).coerceAtLeast(0)
    } ?: 0
    suspend fun save(context: Context, owner: UserScope, height: Int) = withContext(Dispatchers.IO) {
        UserProfilePreferences.warm(context, owner)
        check(UserProfilePreferences.get(context, DOMAIN, owner = owner).edit().putInt("height", height.coerceAtLeast(0)).commit())
    }
}
