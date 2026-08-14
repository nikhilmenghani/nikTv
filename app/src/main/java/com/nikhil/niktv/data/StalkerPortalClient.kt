package com.nikhil.niktv.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.nikhil.niktv.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.math.BigInteger
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Clean-room Stalker/MAG portal client. The user must have permission to access the configured portal. */
class StalkerPortalClient(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cookies = PortalCookieJar()
    private val http = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()
    @Volatile private var authenticationTrace: String = ""
    private val authMutex = Mutex()
    private val requestMutex = Mutex()
    private val trafficPrefs = context.getSharedPreferences("portal_traffic_guard", Context.MODE_PRIVATE)
    private val requestTimes = ArrayDeque<Long>()
    private var lastRequestAt = 0L

    suspend fun authenticate(profile: PortalProfile): PortalSession = authMutex.withLock { withContext(Dispatchers.IO) {
        if (profile.portalType == PortalType.XTREAM) return@withContext authenticateXtream(profile)
        val normalized = normalizePortal(profile.portalUrl)
        val clean = profile.copy(portalUrl = normalized, macAddress = normalizeMac(profile.macAddress))
        val handshakeParams = mapOf("type" to "stb", "action" to "handshake", "token" to "", "JsHttpRequest" to "1")
        val attempts = mutableListOf<String>()
        val preferredEndpoint = trafficPrefs.getString(endpointKey(clean), null)
        val discovered = endpointCandidates(normalized, preferredEndpoint).firstNotNullOfOrNull { endpoint ->
            runCatching {
                val response = request(clean, endpoint, null, handshakeParams)
                if (response.payload().string("token").isNullOrBlank()) {
                    attempts += "${endpoint.pathForMessage()}: no token"
                    null
                } else endpoint to response
            }.getOrElse {
                if (it is PortalRateLimitCooldownException) {
                    error("Portal protection is active. Wait ${it.remainingSeconds} seconds before making another server request.")
                }
                if (it is PortalHttpException && it.statusCode == 429) {
                    error("The portal is temporarily rate-limiting requests. Wait ${it.retryAfterSeconds ?: 30} seconds, then try again once.")
                }
                attempts += "${endpoint.pathForMessage()}: ${it.message}"
                null
            }
        } ?: error("No compatible Stalker API endpoint was found. Tried: ${attempts.joinToString()}")
        val (endpointUrl, handshake) = discovered
        trafficPrefs.edit().putString(endpointKey(clean), endpointUrl).apply()
        val token = requireNotNull(handshake.payload().string("token"))
        val random = handshake.payload().string("random")
        val identity = createDeviceIdentity(clean)
        val session = PortalSession(clean, token, endpointUrl, identity.serial, identity.metrics, identity.hardwareVersion2, random)
        val profileResponse = request(clean, endpointUrl, session, mapOf(
            "type" to "stb", "action" to "get_profile", "sn" to identity.serial,
            "hd" to "1", "ver" to MAG_VER, "num_banks" to "2",
            "stb_type" to identity.stbType, "client_type" to identity.clientType,
            "image_version" to "220", "video_out" to "hdmi", "device_id" to "",
            "device_id2" to "", "signature" to "", "auth_second_step" to "1",
            "hw_version" to HW_VERSION, "not_valid_token" to "0", "metrics" to identity.metrics,
            "hw_version_2" to identity.hardwareVersion2, "timestamp" to epochSeconds(),
            "api_signature" to API_SIGNATURE, "JsHttpRequest" to "1-xml"
        ))
        val payload = profileResponse.payload()
        authenticationTrace = buildString {
            appendLine("Device serial sent: ${identity.serial}")
            appendLine("Authentication context (successful get_profile response):")
            appendLine(redact(profileResponse.toString(), clean, session))
        }
        // Ministra versions differ widely here: valid profiles may omit both id and name.
        // Only reject an explicit server-side denial; later catalog calls are the real authorization check.
        if (payload is JsonNull || payload is JsonPrimitive && payload.booleanOrNull == false) {
            error("The portal returned an empty profile for this MAC address")
        }
        if (payload is JsonObject) {
            val message = payload.string("block_msg") ?: payload.string("msg")
            val explicitlyBlocked = (payload["blocked"] as? JsonPrimitive)?.contentOrNull in setOf("1", "true")
            if (explicitlyBlocked) error(message?.takeIf(String::isNotBlank) ?: "The portal reports that this profile is blocked")
        }
        session
    } }

    suspend fun categories(session: PortalSession, type: CatalogType): List<Category> = withContext(Dispatchers.IO) {
        if (session.profile.portalType == PortalType.XTREAM) return@withContext xtreamCategories(session, type)
        val action = when (type) {
            CatalogType.LIVE_TV -> "get_genres"; CatalogType.MOVIES, CatalogType.SERIES -> "get_categories"; CatalogType.RADIO -> "get_genres"
        }
        val requestType = if (type == CatalogType.MOVIES || type == CatalogType.SERIES) "vod" else type.apiType
        val categoryNodes = request(session.profile, session.endpointUrl, session, authorizedParams(session, mapOf("type" to requestType, "action" to action)))
            .payload().array()
        val portalCategories = categoryNodes.mapNotNull { node ->
                val o = node as? JsonObject ?: return@mapNotNull null
                val id = o.string("id") ?: o.string("category_id") ?: return@mapNotNull null
                val title = o.string("title") ?: o.string("name") ?: "Untitled"
                val seriesCategory = isSeriesCategory("$title ${o.string("alias").orEmpty()}")
                if (type == CatalogType.SERIES && id != "*" && !seriesCategory) return@mapNotNull null
                if (type == CatalogType.MOVIES && id != "*" && seriesCategory) return@mapNotNull null
                Category(id, title, type)
            }
        // This portal shares one VOD category endpoint but uses different listing
        // request shapes. Keep the wildcard only for the Movies request; Series
        // must start from a concrete Series category.
        if (type == CatalogType.MOVIES) {
            listOf(Category("*", "All movies", type)) +
                portalCategories.filterNot { it.id == "*" }
        } else portalCategories
    }

    suspend fun catalog(session: PortalSession, category: Category): List<MediaItem> = withContext(Dispatchers.IO) {
        if (session.profile.portalType == PortalType.XTREAM) return@withContext xtreamCatalog(session, category)
        val action = "get_ordered_list"
        val categoryKey = if (category.type == CatalogType.LIVE_TV || category.type == CatalogType.RADIO) "genre" else "category"
        val requestType = if (category.type == CatalogType.MOVIES || category.type == CatalogType.SERIES) "vod" else category.type.apiType
        val listingParams = mutableMapOf("type" to requestType, "action" to action, categoryKey to category.id, "p" to "1")
        if (category.type == CatalogType.MOVIES) listingParams += mapOf(
            "movie_id" to "0", "season_id" to "0", "episode_id" to "0", "fav" to "0",
            "sortby" to "added", "hd" to "0", "ended" to "0", "search" to ""
        )
        val listingNodes = request(session.profile, session.endpointUrl, session, authorizedParams(session, listingParams))
            .payload().arrayFromData()
        listingNodes.mapNotNull { node ->
                val o = node as? JsonObject ?: return@mapNotNull null
                // Category selection and request shape are authoritative. This
                // provider reports both is_movie=true and is_series=1 for Series,
                // making row-level flags unsuitable for separating the two tabs.
                val id = o.string("id") ?: o.string("movie_id") ?: return@mapNotNull null
                MediaItem(
                    id,
                    o.string("name") ?: o.string("title") ?: "Untitled",
                    portalAssetUrl(session, o.string("logo") ?: o.string("screenshot_uri") ?: o.string("pic")),
                    o.string("cmd"),
                    o.string("description") ?: o.string("genres_str"),
                    portalCategoryId = category.id
                )
            }
    }

    suspend fun fullCatalog(session: PortalSession, type: CatalogType, categories: List<Category>): List<MediaItem> = withContext(Dispatchers.IO) {
        if (session.profile.portalType == PortalType.XTREAM) return@withContext xtreamCatalog(session, type, null)
        categories.flatMap { category -> catalog(session, category) }.distinctBy { it.id }
    }

    /** One bounded server operation used by the dedicated search screen. */
    suspend fun search(session: PortalSession, type: SearchContentType, query: String, page: Int, categoryId: String = "*"): PortalSearchPage = withContext(Dispatchers.IO) {
        if (session.profile.portalType == PortalType.XTREAM) {
            val catalogType = when (type) {
                SearchContentType.LIVE_TV -> CatalogType.LIVE_TV
                SearchContentType.SERIES -> CatalogType.SERIES
                else -> CatalogType.MOVIES
            }
            val matches = xtreamCatalog(session, catalogType, null).filter { it.title.matchesSearchKeywords(query) }
            return@withContext PortalSearchPage(matches, 1, false)
        }
        val live = type == SearchContentType.LIVE_TV
        val response = request(session.profile, session.endpointUrl, session, authorizedParams(session, mapOf(
            "type" to if (live) "itv" else "vod", "action" to "get_ordered_list",
            (if (live) "genre" else "category") to categoryId,
            "search" to query.trim(), "p" to page.coerceAtLeast(1).toString(), "fav" to "0", "sortby" to "added",
            "hd" to "0", "ended" to "0"
        )))
        val payload = response.payload()
        val data = payload.arrayFromData()
        val items = data.mapNotNull { node ->
            val item = node as? JsonObject ?: return@mapNotNull null
            val id = item.string("id") ?: item.string("movie_id") ?: return@mapNotNull null
            val series = item.boolish("is_series") || item.string("series") == "1" ||
                item.string("type").equals("series", ignoreCase = true) || item.string("name").orEmpty().contains("series", ignoreCase = true)
            val episode = item.boolish("is_episode") || item.string("episode_id") != null
            val matchesType = when (type) {
                SearchContentType.LIVE_TV -> true
                SearchContentType.SERIES -> series && !episode
                SearchContentType.MOVIES -> !series && !episode
                SearchContentType.EPISODES -> episode
            }
            if (!matchesType) return@mapNotNull null
            MediaItem(id, item.string("name") ?: item.string("title") ?: "Untitled",
                portalAssetUrl(session, item.string("logo") ?: item.string("screenshot_uri") ?: item.string("pic")),
                item.string("cmd"), item.string("description") ?: item.string("genres_str"),
                item.string("season_number")?.toIntOrNull(), item.string("episode")?.toIntOrNull(),
                item.string("season_id"), item.string("category_id") ?: item.string("genre_id") ?: categoryId, item.string("episode_id"))
        }.filter { it.title.matchesSearchKeywords(query) }.distinctBy { it.id }
        val metadata = payload as? JsonObject
        val maxPage = metadata?.string("max_page")?.toIntOrNull()
            ?: metadata?.string("total_pages")?.toIntOrNull()
        val total = metadata?.string("total_items")?.toIntOrNull()
            ?: metadata?.string("total")?.toIntOrNull()
        val pageSize = metadata?.string("items_per_page")?.toIntOrNull()
            ?: metadata?.string("per_page")?.toIntOrNull()
        val hasMore = when {
            maxPage != null -> page < maxPage
            total != null && pageSize != null -> page * pageSize < total
            else -> data.isNotEmpty()
        }
        PortalSearchPage(items, page, hasMore)
    }

    suspend fun playableUrl(session: PortalSession, item: MediaItem, type: CatalogType): String = withContext(Dispatchers.IO) {
        if (session.profile.portalType == PortalType.XTREAM) {
            return@withContext item.command ?: error("This item has no playback URL")
        }
        val cmd = item.command ?: error("This item has no playback command")
        val playbackCommand = if (type == CatalogType.MOVIES || type == CatalogType.SERIES) {
            val movieId = item.id.substringBefore(':')
            val detail = request(session.profile, session.endpointUrl, session, authorizedParams(session, mapOf(
                "type" to "vod", "action" to "get_ordered_list", "movie_id" to movieId,
                "season_id" to item.portalSeasonId.orEmpty(), "episode_id" to item.portalEpisodeId.orEmpty(),
                "category" to (item.portalCategoryId ?: movieId), "fav" to "0", "sortby" to "added",
                "hd" to "0", "ended" to "0", "p" to "1"
            ))).payload().arrayFromData().firstOrNull() as? JsonObject
            detail?.string("id")?.let { "/media/file_$it.mpg" } ?: cmd
        } else cmd
        val response = request(session.profile, session.endpointUrl, session, authorizedParams(session, mapOf(
            "type" to if (type == CatalogType.SERIES) "vod" else type.apiType,
            "action" to "create_link",
            "cmd" to playbackCommand,
            "series" to (item.episodeNumber?.toString() ?: "0"),
            "download" to "0",
            "disable_ad" to "0",
            "force_ch_link_check" to "0"
        )))
        val payload = response.payload()
        val playbackUrl = payload.string("cmd")
            ?.removePrefix("ffmpeg ")
            ?.removePrefix("ffrt ")
            ?.trim()
        if (playbackUrl.isNullOrBlank()) {
            val portalError = payload.string("error")?.takeIf { it.isNotBlank() && it != "0" }
            error(
                when (portalError) {
                    "nothing_to_play" -> "The IPTV portal could not reach a VOD storage server for this video. Please try again shortly."
                    null -> "The IPTV portal returned an empty playback link"
                    else -> "The IPTV portal could not create a playback link: $portalError"
                }
            )
        }
        playbackUrl
    }

    suspend fun episodes(session: PortalSession, series: MediaItem): List<MediaItem> = withContext(Dispatchers.IO) {
        if (session.profile.portalType == PortalType.XTREAM) return@withContext xtreamEpisodes(session, series)
        val baseParams = mapOf(
            "type" to "vod", "action" to "get_ordered_list", "movie_id" to series.id,
            "category" to series.id, "season_id" to "0", "episode_id" to "0", "p" to "1"
        )
        val initial = request(session.profile, session.endpointUrl, session, authorizedParams(session, baseParams))
            .payload().arrayFromData()
        val direct = stalkerEpisodeItems(initial, series)
        if (direct.isNotEmpty()) return@withContext direct

        initial.mapNotNull { node ->
            val season = node as? JsonObject ?: return@mapNotNull null
            val seasonId = season.string("season_id") ?: season.string("id") ?: return@mapNotNull null
            seasonId to (season.string("season_number")?.toIntOrNull() ?: season.string("season")?.toIntOrNull())
        }.distinctBy { it.first }
            .flatMap { (seasonId, seasonNumber) ->
                val response = request(session.profile, session.endpointUrl, session, authorizedParams(session, baseParams + ("season_id" to seasonId)))
                stalkerEpisodeItems(response.payload().arrayFromData(), series, seasonNumber, seasonId)
            }
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }, { it.title }))
    }

    private fun stalkerEpisodeItems(nodes: List<JsonElement>, series: MediaItem, fallbackSeason: Int? = null, portalSeasonId: String? = null): List<MediaItem> =
        nodes.flatMap { node ->
            val item = node as? JsonObject ?: return@flatMap emptyList()
            if (item.boolish("is_season")) return@flatMap emptyList()
            val season = item.string("season_number")?.toIntOrNull() ?: item.string("season")?.toIntOrNull() ?: fallbackSeason
            val command = item.string("cmd") ?: series.command
            val numbered = (item["series"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }.orEmpty()
            val explicitEpisode = item.string("series_number")?.toIntOrNull() ?: item.string("episode")?.toIntOrNull() ?: item.string("episode_id")?.toIntOrNull()
            if (item.boolish("is_episode") && command != null && explicitEpisode != null) {
                listOf(MediaItem(
                    "${series.id}:$season:$explicitEpisode", item.string("name") ?: episodeTitle(season, explicitEpisode),
                    series.logo, command, series.description, season, explicitEpisode, portalSeasonId ?: item.string("season_id"),
                    series.portalCategoryId, item.string("id") ?: item.string("episode_id")
                ))
            } else if (numbered.isNotEmpty() && command != null) numbered.map { episode ->
                MediaItem("${series.id}:$season:$episode", episodeTitle(season, episode), series.logo, command, series.description, season, episode,
                    portalSeasonId ?: item.string("season_id"), series.portalCategoryId, item.string("id") ?: item.string("episode_id"))
            } else {
                val episode = explicitEpisode
                val id = item.string("id") ?: item.string("episode_id")
                if (id != null && command != null && (episode != null || season != null)) listOf(
                    MediaItem(id, item.string("name") ?: item.string("title") ?: episodeTitle(season, episode), item.string("screenshot_uri") ?: series.logo, command, item.string("description"), season, episode,
                        portalSeasonId ?: item.string("season_id"), series.portalCategoryId, id)
                ) else emptyList()
            }
        }.distinctBy { it.id }

    private fun episodeTitle(season: Int?, episode: Int?): String = when {
        season != null && episode != null -> "Season $season · Episode $episode"
        episode != null -> "Episode $episode"
        else -> "Episode"
    }

    private fun authenticateXtream(profile: PortalProfile): PortalSession {
        require(profile.username.isNotBlank()) { "Xtream username is required" }
        require(profile.password.isNotBlank()) { "Xtream password is required" }
        val clean = profile.copy(portalUrl = normalizeXtreamUrl(profile.portalUrl))
        val response = xtreamRequest(clean)
        val user = (response as? JsonObject)?.get("user_info") as? JsonObject
            ?: error("Xtream server did not return user information")
        val authenticated = (user["auth"] as? JsonPrimitive)?.contentOrNull in setOf("1", "true")
        if (!authenticated) error(user.string("message") ?: "Xtream username or password was rejected")
        val status = user.string("status")
        if (status != null && status !in setOf("Active", "active")) error("Xtream account status: $status")
        return PortalSession(clean, "xtream", "${clean.portalUrl}/player_api.php", "", "", "")
    }

    private fun xtreamCategories(session: PortalSession, type: CatalogType): List<Category> {
        val action = when (type) {
            CatalogType.LIVE_TV -> "get_live_categories"
            CatalogType.MOVIES -> "get_vod_categories"
            CatalogType.SERIES -> "get_series_categories"
            CatalogType.RADIO -> return emptyList()
        }
        return xtreamRequest(session.profile, action).array().mapNotNull { node ->
            val o = node as? JsonObject ?: return@mapNotNull null
            val id = o.string("category_id") ?: return@mapNotNull null
            Category(id, o.string("category_name") ?: "Untitled", type)
        }
    }

    private fun xtreamCatalog(session: PortalSession, category: Category): List<MediaItem> =
        xtreamCatalog(session, category.type, category.id)

    private fun xtreamCatalog(session: PortalSession, type: CatalogType, categoryId: String?): List<MediaItem> {
        val action = when (type) {
            CatalogType.LIVE_TV -> "get_live_streams"
            CatalogType.MOVIES -> "get_vod_streams"
            CatalogType.SERIES -> "get_series"
            CatalogType.RADIO -> return emptyList()
        }
        val profile = session.profile
        return xtreamRequest(profile, action, categoryId).array().mapNotNull { node ->
            val o = node as? JsonObject ?: return@mapNotNull null
            val id = o.string("stream_id") ?: o.string("series_id") ?: return@mapNotNull null
            val extension = o.string("container_extension") ?: "mp4"
            val url = when (type) {
                CatalogType.LIVE_TV -> "${profile.portalUrl}/live/${encode(profile.username)}/${encode(profile.password)}/$id.ts"
                CatalogType.MOVIES -> "${profile.portalUrl}/movie/${encode(profile.username)}/${encode(profile.password)}/$id.$extension"
                else -> null
            }
            MediaItem(id, o.string("name") ?: o.string("title") ?: "Untitled", o.string("stream_icon") ?: o.string("cover"), url, o.string("plot"))
        }
    }

    private fun xtreamEpisodes(session: PortalSession, series: MediaItem): List<MediaItem> {
        val profile = session.profile
        val response = xtreamRequest(profile, action = "get_series_info", seriesId = series.id) as? JsonObject
            ?: error("Xtream server returned invalid series information")
        val episodes = response["episodes"] ?: error("This series has no episode information")
        return flattenXtreamEpisodes(episodes).mapNotNull { (seasonHint, node) ->
            val id = node.string("id") ?: node.string("episode_id") ?: return@mapNotNull null
            val season = node.string("season")?.toIntOrNull() ?: node.string("season_number")?.toIntOrNull() ?: seasonHint
            val episode = node.string("episode_num")?.toIntOrNull() ?: node.string("episode_number")?.toIntOrNull()
            val extension = node.string("container_extension") ?: "mp4"
            val info = node["info"] as? JsonObject
            MediaItem(
                id = id,
                title = node.string("title") ?: episodeTitle(season, episode),
                logo = info?.string("movie_image") ?: series.logo,
                command = "${profile.portalUrl}/series/${encode(profile.username)}/${encode(profile.password)}/$id.$extension",
                description = info?.string("plot") ?: info?.string("releasedate"),
                seasonNumber = season,
                episodeNumber = episode
            )
        }.distinctBy { it.id }.sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }, { it.title }))
    }

    private fun flattenXtreamEpisodes(element: JsonElement, seasonHint: Int? = null): List<Pair<Int?, JsonObject>> = when (element) {
        is JsonArray -> element.flatMap { flattenXtreamEpisodes(it, seasonHint) }
        is JsonObject -> {
            if (element.string("id") != null || element.string("episode_id") != null) listOf(seasonHint to element)
            else element.flatMap { (key, value) -> flattenXtreamEpisodes(value, key.toIntOrNull() ?: seasonHint) }
        }
        else -> emptyList()
    }

    private fun xtreamRequest(profile: PortalProfile, action: String? = null, categoryId: String? = null, seriesId: String? = null): JsonElement {
        val url = "${profile.portalUrl}/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", profile.username)
            .addQueryParameter("password", profile.password)
            .apply { action?.let { addQueryParameter("action", it) }; categoryId?.let { addQueryParameter("category_id", it) }; seriesId?.let { addQueryParameter("series_id", it) } }
            .build()
        val request = Request.Builder().url(url).header("User-Agent", "NikTV/0.1 Android").header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Xtream server returned HTTP ${response.code}")
            if (body.isBlank()) error("Xtream server returned an empty response")
            return parsePortalResponse(body)
        }
    }

    private fun normalizeXtreamUrl(value: String): String = value.trim().trimEnd('/').let {
        (if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it")
            .removeSuffix("/player_api.php")
    }
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private suspend fun request(profile: PortalProfile, endpointUrl: String, session: PortalSession?, params: Map<String, String>): JsonElement = requestMutex.withLock {
        awaitTrafficPermit()
        val endpoint = endpointUrl.toHttpUrl().newBuilder().apply {
            params.forEach { (key, value) ->
                // Cast4K's Retrofit declaration marks the Stalker `cmd` value as
                // encoded. Preserve command paths such as /media/123.mpg exactly.
                if (key == "cmd") addEncodedQueryParameter(key, value)
                else addQueryParameter(key, value)
            }
        }.build()
        cookies.seedPortalCookies(endpoint, profile.macAddress)
        val builder = Request.Builder()
            .url(endpoint)
            .addHeader("User-Agent", "Mozilla/5.0 (QtEmbedded; U; Linux armv7l; en-US)")
            .addHeader("User-Agent", USER_AGENT)
            .header("X-User-Agent", "Model: MAG424; Link: WiFi")
            .header("Language", "en-US")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", portalReferer(endpoint))
            .header("Connection", "keep-alive")
        session?.let {
            builder.header("Authorization", "Bearer ${it.token}")
            builder.header("X-Token", it.token)
        }
        http.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val diagnostic = buildDiagnostic(endpointUrl, params, response.code, response.header("Content-Type"), body, profile, session)
            if (!response.isSuccessful) {
                val retryAfter = response.header("Retry-After")?.toLongOrNull()
                    ?: runCatching { parsePortalResponse(body).payload().string("retry_after")?.toLongOrNull() }.getOrNull()
                if (response.code == 429) recordRateLimit(retryAfter)
                throw PortalHttpException(response.code, retryAfter, diagnostic)
            }
            if (body.isBlank()) error(diagnostic)
            return try {
                parsePortalResponse(body).also { clearRateLimitStrikes() }
            } catch (_: Throwable) {
                error(diagnostic)
            }
        }
    }

    private fun buildDiagnostic(
        endpointUrl: String,
        params: Map<String, String>,
        status: Int,
        contentType: String?,
        body: String,
        profile: PortalProfile,
        session: PortalSession?
    ): String {
        val safeBody = redact(body, profile, session)
        return buildString {
            appendLine("NikTV portal diagnostics")
            appendLine("Endpoint: $endpointUrl")
            appendLine("Request: type=${params["type"] ?: "?"}, action=${params["action"] ?: "?"}")
            appendLine("HTTP status: $status")
            appendLine("Content-Type: ${contentType ?: "not supplied"}")
            appendLine("Response length: ${body.length}")
            appendLine("Response body:")
            append(if (safeBody.isBlank()) "<empty>" else safeBody)
            if (params["action"] != "handshake" && params["action"] != "get_profile" && authenticationTrace.isNotBlank()) {
                appendLine()
                appendLine()
                append(authenticationTrace)
            }
        }
    }

    private fun redact(value: String, profile: PortalProfile, session: PortalSession?): String {
        var safe = value.replace(profile.macAddress, "<redacted-mac>", ignoreCase = true)
            .replace(URLEncoder.encode(profile.macAddress, "UTF-8"), "<redacted-mac>", ignoreCase = true)
        session?.token?.takeIf(String::isNotBlank)?.let { safe = safe.replace(it, "<redacted-token>") }
        return safe
    }

    /** Older portals sometimes append debug output or wrap JSON in a JavaScript callback. */
    private fun parsePortalResponse(raw: String): JsonElement {
        val cleaned = raw.trim().removePrefix("\uFEFF")
        runCatching { return json.parseToJsonElement(cleaned) }

        val objectStart = cleaned.indexOf('{')
        val arrayStart = cleaned.indexOf('[')
        val start = listOf(objectStart, arrayStart).filter { it >= 0 }.minOrNull()
        if (start != null) {
            val candidate = extractBalancedJson(cleaned, start)
            if (candidate != null) runCatching { return json.parseToJsonElement(candidate) }
        }

        val preview = cleaned.replace(Regex("[\\r\\n\\t]+"), " ").take(160)
        error("Portal returned an unsupported response: $preview")
    }

    private fun extractBalancedJson(text: String, start: Int): String? {
        val opening = text[start]
        val closing = if (opening == '{') '}' else ']'
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    opening -> depth++
                    closing -> if (--depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }

    private fun normalizePortal(value: String): String {
        val url = value.trim().trimEnd('/').let { if (it.startsWith("http://") || it.startsWith("https://")) it else "http://$it" }
        // Keep the path supplied by the user. Discovery handles /c, /stalker_portal and direct PHP URLs.
        return url
    }
    private fun endpointCandidates(portalUrl: String, preferred: String? = null): List<String> {
        val direct = portalUrl.takeIf { it.endsWith(".php", ignoreCase = true) }
        val withoutC = portalUrl.removeSuffix("/c")
        val root = withoutC.removeSuffix("/stalker_portal")
        return buildList {
            preferred?.let(::add)
            direct?.let(::add)
            // Most modern Stalker portals use this endpoint. Probe it first so a
            // normal login does not generate multiple avoidable Cloudflare hits.
            add("$root/stalker_portal/server/load.php")
            add("$root/stalker_portal/portal.php")
            add("$root/server/load.php")
            add("$root/portal.php")
        }.distinct()
    }

    private suspend fun awaitTrafficPermit() {
        val now = System.currentTimeMillis()
        val blockedUntil = trafficPrefs.getLong("blocked_until", 0L)
        if (blockedUntil > now) {
            val seconds = ((blockedUntil - now + 999L) / 1000L).coerceAtLeast(1L)
            throw PortalRateLimitCooldownException(seconds)
        }
        while (requestTimes.isNotEmpty() && now - requestTimes.first() >= REQUEST_WINDOW_MS) requestTimes.removeFirst()
        val spacingWait = (lastRequestAt + MIN_REQUEST_SPACING_MS - now).coerceAtLeast(0L)
        val budgetWait = if (requestTimes.size >= MAX_REQUESTS_PER_WINDOW)
            (requestTimes.first() + REQUEST_WINDOW_MS - now).coerceAtLeast(0L) else 0L
        val wait = maxOf(spacingWait, budgetWait)
        if (wait > 0L) delay(wait)
        val grantedAt = System.currentTimeMillis()
        while (requestTimes.isNotEmpty() && grantedAt - requestTimes.first() >= REQUEST_WINDOW_MS) requestTimes.removeFirst()
        requestTimes.addLast(grantedAt)
        lastRequestAt = grantedAt
    }

    private fun recordRateLimit(retryAfter: Long?) {
        val strikes = (trafficPrefs.getInt("rate_limit_strikes", 0) + 1).coerceAtMost(5)
        val exponential = 30L * (1L shl (strikes - 1))
        val seconds = maxOf(retryAfter ?: 30L, exponential).coerceAtMost(30L * 60L)
        trafficPrefs.edit().putInt("rate_limit_strikes", strikes)
            .putLong("blocked_until", System.currentTimeMillis() + seconds * 1000L).apply()
    }

    private fun clearRateLimitStrikes() {
        // A successful request clears an expired block, but retain a decreasing strike
        // count so repeated bursts do not immediately return to the shortest cooldown.
        val strikes = trafficPrefs.getInt("rate_limit_strikes", 0)
        trafficPrefs.edit().putLong("blocked_until", 0L).putInt("rate_limit_strikes", (strikes - 1).coerceAtLeast(0)).apply()
    }

    private fun endpointKey(profile: PortalProfile): String = "endpoint_" + sha1(profile.portalUrl + "|" + profile.macAddress).take(20)
    private fun String.pathForMessage(): String = runCatching { toHttpUrl().encodedPath }.getOrDefault(this)
    private fun portalReferer(endpoint: HttpUrl): String = endpoint.newBuilder()
        .encodedPath(endpoint.encodedPath.substringBefore("server/load.php") + "c/")
        .query(null)
        .build()
        .toString()
    private fun normalizeMac(value: String): String {
        val hex = value.filter(Char::isLetterOrDigit).uppercase()
        require(hex.length == 12) { "MAC address must contain 12 hexadecimal characters" }
        return hex.chunked(2).joinToString(":")
    }
    private fun isSeriesCategory(title: String): Boolean {
        val normalized = title.uppercase()
        return listOf("SERIES", "TV SHOW", "TV SERIAL", "WEB SERIES", "NATOK").any(normalized::contains)
    }
    private fun portalAssetUrl(session: PortalSession, value: String?): String? {
        val asset = value?.trim()?.takeIf(String::isNotBlank) ?: return null
        if (asset.startsWith("http://") || asset.startsWith("https://")) return asset
        return runCatching { session.profile.portalUrl.toHttpUrl().resolve(asset)?.toString() }.getOrNull() ?: asset
    }
    private fun authorizedParams(session: PortalSession, specific: Map<String, String>) = specific + mapOf(
        "auth_second_step" to "1", "not_valid_token" to "0", "metrics" to session.metrics,
        "hw_version" to HW_VERSION, "hw_version_2" to session.hardwareVersion2,
        "timestamp" to epochSeconds(), "api_signature" to API_SIGNATURE, "JsHttpRequest" to "1-xml"
    )

    private fun createDeviceIdentity(profile: PortalProfile): DeviceIdentity {
        val mac = profile.macAddress
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val serialPrefix = when (Build.VERSION.SDK_INT) {
            24 -> "032016J0"; 25 -> "022017J0"; 26 -> "012018J0"; 27 -> "022018J0"
            28 -> "032019J0"; 29 -> "042020J0"; 30 -> "052021J0"; 31 -> "062021J0"
            32 -> "072022J0"; 33 -> "082023J0"; 34 -> "092024J0"; 35 -> "102025J0"
            else -> "999999J0"
        }
        val decimalId = runCatching { BigInteger(androidId, 16).toString(10).take(5) }.getOrDefault("00000")
        val serial = profile.serialNumber.trim().takeIf(String::isNotBlank) ?: (serialPrefix + decimalId)
        val isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val stbType = if (isTv) "MAG424" else "ANDROID"
        val clientType = if (isTv) "STB" else "ANDROID"
        val metrics = buildJsonObject {
            put("mac", mac); put("sn", serial); put("model", if (isTv) "MAG424" else Build.MODEL)
            put("type", clientType); put("uid", ""); put("random", sha1(UUID.randomUUID().toString()))
        }.toString()
        return DeviceIdentity(serial, stbType, clientType, metrics, sha1(Build.MANUFACTURER + Build.MODEL + Build.DEVICE))
    }
    private fun sha1(value: String): String = MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun epochSeconds() = (System.currentTimeMillis() / 1000).toString()
    private fun JsonElement.payload(): JsonElement = (this as? JsonObject)?.get("js") ?: this
    private fun JsonElement.array(): List<JsonElement> = when (this) { is JsonArray -> this; is JsonObject -> values.firstOrNull { it is JsonArray } as? JsonArray ?: emptyList(); else -> emptyList() }
    private fun JsonElement.arrayFromData(): List<JsonElement> = ((this as? JsonObject)?.get("data") ?: this).array()
    private fun JsonElement.string(key: String): String? = (this as? JsonObject)?.string(key)
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.boolish(key: String): Boolean = string(key)?.lowercase() in setOf("1", "true", "yes")
    private fun String.matchesSearchKeywords(query: String): Boolean {
        val titleWords = lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotBlank)
        val keys = query.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter(String::isNotBlank)
        return keys.isNotEmpty() && keys.all { key -> titleWords.any { word -> word.contains(key) } }
    }
    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Mobile Safari/533.3"
        private const val MAG_VER = "ImageDescription: 2.20.02-pub-424; ImageDate: Fri May 8 15:39:55 UTC 2020; PORTAL version: 5.6.2; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x588"
        private const val HW_VERSION = "1.7-BD-00"
        private const val API_SIGNATURE = "262"
        private const val MIN_REQUEST_SPACING_MS = 1_000L
        private const val REQUEST_WINDOW_MS = 60_000L
        private const val MAX_REQUESTS_PER_WINDOW = 20
    }

    private data class DeviceIdentity(val serial: String, val stbType: String, val clientType: String, val metrics: String, val hardwareVersion2: String)

    private class PortalCookieJar : CookieJar {
        private val stored = mutableMapOf<String, Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie -> stored[cookieKey(cookie)] = cookie }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            stored.entries.removeAll { it.value.expiresAt < now }
            return stored.values.filter { it.matches(url) }
        }

        @Synchronized
        fun seedPortalCookies(url: HttpUrl, mac: String) {
            put(url, "mac", mac)
            put(url, "stb_lang", "en")
            put(url, "timezone", "America/Toronto")
        }

        private fun put(url: HttpUrl, name: String, value: String) {
            val cookie = Cookie.Builder().name(name).value(value).domain(url.host).path("/").build()
            stored[cookieKey(cookie)] = cookie
        }

        private fun cookieKey(cookie: Cookie) = "${cookie.domain}|${cookie.path}|${cookie.name}"
    }
}

private class PortalHttpException(
    val statusCode: Int,
    val retryAfterSeconds: Long?,
    message: String
) : IllegalStateException(message)

private class PortalRateLimitCooldownException(val remainingSeconds: Long) :
    IllegalStateException("Portal requests are paused for $remainingSeconds seconds")
