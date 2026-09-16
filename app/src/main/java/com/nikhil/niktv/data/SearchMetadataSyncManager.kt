package com.nikhil.niktv.data

import android.content.Context
import android.util.Base64
import com.nikhil.niktv.BuildConfig
import com.nikhil.niktv.model.BrowseCatalogCache
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PortalProfile
import com.nikhil.niktv.model.SearchCatalogCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

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
    val channelNumber: Int? = null
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
        val candidates = buildList {
            addAll(searchCache?.items.orEmpty())
            browseCache?.itemsByCategory?.values?.forEach(::addAll)
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
                        channelNumber = media.channelNumber
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
            .distinctBy { it.id }
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
            .digest(profile.cacheKey().toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(PROFILE_ID_LENGTH)

    private const val PROFILE_ID_LENGTH = 24
}

/**
 * Synchronizes only search metadata. Credentials, portal identity, artwork URLs,
 * playback commands, resolved stream URLs, schedules and descriptions are never
 * represented by [SearchMetadataIndex], so they cannot be uploaded accidentally.
 */
class SearchMetadataSyncManager(context: Context) {
    private val appContext = context.applicationContext
    private val backupManager = GitHubBackupManager(context.applicationContext)
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun buildIndex(
        profile: PortalProfile,
        type: CatalogType,
        searchCache: SearchCatalogCache?,
        browseCache: BrowseCatalogCache?,
        nowMillis: Long = System.currentTimeMillis()
    ): SearchMetadataIndex = SearchMetadataDocuments.build(
        profile, type, searchCache, browseCache, nowMillis
    )

    fun asLocalSearchCache(
        index: SearchMetadataIndex,
        localProfileKey: String
    ): SearchCatalogCache = SearchMetadataDocuments.asLocalCache(index, localProfileKey)

    fun merge(
        local: SearchMetadataIndex,
        remote: SearchMetadataIndex?
    ): SearchMetadataIndex = SearchMetadataDocuments.merge(local, remote)

    suspend fun download(
        profile: PortalProfile,
        type: CatalogType,
        config: GitHubBackupConfig = backupManager.loadConfig()
    ): SearchMetadataIndex? = withContext(Dispatchers.IO) {
        val cfg = validated(config)
        val profileId = anonymousProfileId(profile)
        val path = metadataPath(profileId, type)
        val branch = defaultBranch(cfg)
        val request = githubRequest(
            cfg,
            "https://api.github.com/repos/${cfg.username}/${cfg.repository}/contents/$path?ref=$branch",
            "application/vnd.github.raw+json"
        ).get().build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return@withContext null
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { githubError("download search metadata", response.code, body) }
            require(body.toByteArray().size <= MAX_INDEX_BYTES) { "Search metadata index is too large." }
            json.decodeFromString<SearchMetadataIndex>(body).also { index ->
                require(index.schemaVersion == SCHEMA_VERSION) { "Unsupported search metadata version." }
                require(index.anonymousProfileId == profileId && index.type == type) {
                    "Search metadata does not match this profile and media type."
                }
            }
        }
    }

    suspend fun upload(
        index: SearchMetadataIndex,
        config: GitHubBackupConfig = backupManager.loadConfig(),
        profileName: String? = null
    ): SearchMetadataUpload = withContext(Dispatchers.IO) {
        require(index.schemaVersion == SCHEMA_VERSION)
        require(PROFILE_ID.matches(index.anonymousProfileId))
        val content = json.encodeToString(index)
        require(content.toByteArray().size <= MAX_INDEX_BYTES) { "Search metadata index is too large." }
        val cfg = validated(config)
        val branch = defaultBranch(cfg)
        val path = metadataPath(index.anonymousProfileId, index.type)
        val contentsUrl =
            "https://api.github.com/repos/${cfg.username}/${cfg.repository}/contents/$path"
        val existingSha = readExistingSha(cfg, contentsUrl, branch)
        val payload = JSONObject()
            .put("message", SearchSyncCommitPreferences.message(appContext, index.type.title.lowercase(), profileName))
            .put("content", Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP))
            .put("branch", branch)
            .apply { existingSha?.let { put("sha", it) } }
        val request = githubRequest(cfg, contentsUrl)
            .put(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { githubError("upload search metadata", response.code, body) }
            SearchMetadataUpload(
                path = path,
                htmlUrl = JSONObject(body).optJSONObject("content")?.optString("html_url").orEmpty()
            )
        }
    }

    fun anonymousProfileId(profile: PortalProfile): String =
        SearchMetadataDocuments.anonymousProfileId(profile)

    private fun readExistingSha(
        config: GitHubBackupConfig,
        contentsUrl: String,
        branch: String
    ): String? {
        val request = githubRequest(config, "$contentsUrl?ref=$branch").get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 404) return null
            check(response.isSuccessful) { githubError("read search metadata", response.code, body) }
            return JSONObject(body).optString("sha").takeIf(String::isNotBlank)
        }
    }

    private fun defaultBranch(config: GitHubBackupConfig): String {
        val request = githubRequest(
            config,
            "https://api.github.com/repos/${config.username}/${config.repository}"
        ).get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) { githubError("read metadata repository", response.code, body) }
            return JSONObject(body).optString("default_branch", "main").ifBlank { "main" }
        }
    }

    private fun githubRequest(
        config: GitHubBackupConfig,
        url: String,
        accept: String = "application/vnd.github+json"
    ): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", accept)
        .header("Authorization", "Bearer ${config.token}")
        .header("X-GitHub-Api-Version", "2022-11-28")

    private fun validated(config: GitHubBackupConfig): GitHubBackupConfig {
        val username = config.username.trim()
        val repository = config.repository.trim().removeSuffix(".git")
        val token = config.token.trim().ifBlank { BuildConfig.G_TOKEN.trim() }
        require(GITHUB_NAME.matches(username) && GITHUB_NAME.matches(repository)) {
            "Invalid GitHub metadata repository."
        }
        require(token.isNotBlank()) { "GitHub token is required for metadata sync." }
        return config.copy(username = username, repository = repository, token = token)
    }

    private fun metadataPath(profileId: String, type: CatalogType): String =
        "metadata/search/v$SCHEMA_VERSION/$profileId/${type.name.lowercase(Locale.US)}.json"

    private fun githubError(action: String, status: Int, body: String): String {
        val message = runCatching { JSONObject(body).optString("message") }.getOrDefault("")
        return "Unable to $action (GitHub $status)${message.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val PROFILE_ID_LENGTH = 24
        private const val MAX_INDEX_BYTES = 5 * 1024 * 1024
        private val PROFILE_ID = Regex("[a-f0-9]{$PROFILE_ID_LENGTH}")
        private val GITHUB_NAME = Regex("[A-Za-z0-9_.-]+")
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
