package com.nikhil.niktv.data

import com.nikhil.niktv.model.BrowseCatalogCache
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PortalProfile
import com.nikhil.niktv.model.SearchCatalogCache
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Locale

/** Public-safe IPTV metadata shared between a user's NikTV devices. */
@Serializable
data class SearchMetadataIndex(
    val schemaVersion: Int = 1,
    val anonymousProfileId: String,
    val type: CatalogType,
    val updatedAtMillis: Long,
    val items: List<SearchMetadataItem>
)

@Serializable
data class SearchMetadataItem(
    val id: String,
    val title: String,
    val categoryId: String? = null,
    val categoryTitle: String? = null,
    val externalTmdbId: Int? = null,
    val channelNumber: Int? = null,
    val observedAtMillis: Long = 0
)

data class SearchMetadataUpload(
    val path: String,
    val htmlUrl: String
)

internal object SearchMetadataDocuments {
    fun build(
        profile: PortalProfile,
        type: CatalogType,
        searchCache: SearchCatalogCache?,
        browseCache: BrowseCatalogCache?,
        nowMillis: Long = System.currentTimeMillis()
    ): SearchMetadataIndex {
        val categoryTitles = browseCache?.categories.orEmpty().associate { it.id to it.title }
        val providerIds = browseCache?.itemsByCategory?.values.orEmpty()
            .asSequence().flatten().map { it.id }.toHashSet()
        val candidates = buildList {
            browseCache?.itemsByCategory?.values?.forEach(::addAll)
            addAll(searchCache?.items.orEmpty())
        }
        return SearchMetadataIndex(
            anonymousProfileId = anonymousProfileId(profile),
            type = type,
            updatedAtMillis = nowMillis,
            items = candidates.asSequence()
                .filter {
                    it.id.isNotBlank() && it.title.isNotBlank() &&
                        "://" !in it.id && it.id.length <= 256
                }
                .distinctBy { it.id }
                .map { media ->
                    val categoryId = media.portalCategoryId
                        ?.trim()
                        ?.takeIf { it.isNotBlank() && "://" !in it && it.length <= 128 }
                    SearchMetadataItem(
                        id = media.id,
                        title = media.title.trim().take(300),
                        categoryId = categoryId,
                        categoryTitle = categoryId?.let(categoryTitles::get)
                            ?.trim()?.take(200)?.takeIf(String::isNotBlank),
                        externalTmdbId = media.externalTmdbId,
                        channelNumber = media.channelNumber,
                        observedAtMillis = if (media.id in providerIds)
                            browseCache?.cachedAtMillis ?: 0L else 0L
                    )
                }
                .sortedWith(compareBy(SearchMetadataItem::title, SearchMetadataItem::id))
                .toList()
        )
    }

    fun asLocalCache(index: SearchMetadataIndex, localProfileKey: String) =
        SearchCatalogCache(
            profileKey = localProfileKey,
            type = index.type,
            cachedAtMillis = index.updatedAtMillis,
            items = index.items.map { item ->
                MediaItem(
                    id = item.id,
                    title = item.title,
                    logo = null,
                    command = null,
                    portalCategoryId = item.categoryId,
                    channelNumber = item.channelNumber,
                    externalTmdbId = item.externalTmdbId
                )
            }
        )

    fun merge(
        local: SearchMetadataIndex,
        remote: SearchMetadataIndex?
    ): SearchMetadataIndex {
        if (remote == null) return local
        require(local.anonymousProfileId == remote.anonymousProfileId && local.type == remote.type)
        val items = (local.items + remote.items)
            .groupBy { it.id }.values.map { versions ->
                // Deterministic ties prevent devices from alternating titles.
                val ordered = versions.sortedWith(compareByDescending<SearchMetadataItem> { it.observedAtMillis }
                    .thenBy { it.title }.thenBy { it.categoryId.orEmpty() }
                    .thenBy { it.categoryTitle.orEmpty() }.thenBy { it.externalTmdbId ?: 0 }
                    .thenBy { it.channelNumber ?: 0 })
                val category = ordered.firstNotNullOfOrNull { it.categoryId?.takeIf(String::isNotBlank) }
                ordered.first().copy(
                    categoryId = category,
                    categoryTitle = ordered.filter { it.categoryId == category }
                        .firstNotNullOfOrNull { it.categoryTitle?.takeIf(String::isNotBlank) },
                    externalTmdbId = ordered.firstNotNullOfOrNull { it.externalTmdbId },
                    channelNumber = ordered.firstNotNullOfOrNull { it.channelNumber }
                )
            }
            .sortedWith(compareBy(SearchMetadataItem::title, SearchMetadataItem::id))
        return local.copy(
            updatedAtMillis = if (items == remote.items) {
                remote.updatedAtMillis
            } else {
                maxOf(local.updatedAtMillis, remote.updatedAtMillis)
            },
            items = items
        )
    }

    fun anonymousProfileId(profile: PortalProfile): String =
        MessageDigest.getInstance("SHA-256")
            // Freeze the existing namespace independently of local cache versions.
            .digest(("catalog-v5|${profile.portalType}|${normalizedPortal(profile.portalUrl)}|${profile.username.ifBlank { profile.macAddress.trim().uppercase(Locale.US).replace('-', ':') }}").toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(PROFILE_ID_LENGTH)

    private const val PROFILE_ID_LENGTH = 24

    private fun normalizedPortal(value: String): String {
        val uri = java.net.URI(value.trim().trimEnd('/'))
        val scheme = uri.scheme.lowercase(Locale.US)
        val port = uri.port.takeUnless { (scheme == "http" && it == 80) || (scheme == "https" && it == 443) } ?: -1
        return java.net.URI(scheme, uri.userInfo, uri.host.lowercase(Locale.US), port,
            uri.path.trimEnd('/'), uri.query, null).toString()
    }

    fun legacyProfileId(profile: PortalProfile): String = MessageDigest.getInstance("SHA-256")
        .digest(profile.cacheKey().toByteArray()).joinToString("") { "%02x".format(it) }.take(PROFILE_ID_LENGTH)
}
