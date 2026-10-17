package com.nikhil.niktv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class NikTubeVideo(val id: String, val title: String, val channel: String, val duration: String = "",
    val channelId: String = "", val avatar: String = "", val views: String = "", val published: String = "",
    val thumbnailUrl: String = "") {
    val thumbnail: String get() = thumbnailUrl.ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" }
}

data class NikTubePage(val videos: List<NikTubeVideo>, val continuation: String? = null)
data class NikTubeChapter(val title: String, val startMillis: Long)
data class NikTubeDetails(val description: String, val related: NikTubePage, val chapters: List<NikTubeChapter> = emptyList())
data class NikTubeCaption(val language: String, val label: String, val url: String)
data class NikTubeChannelTab(val title: String, val params: String)
data class NikTubeRemotePlaylist(val id: String, val title: String)
data class NikTubeChannel(val id: String, val title: String, val avatar: String = "", val description: String = "",
    val tabs: List<NikTubeChannelTab> = emptyList(), val page: NikTubePage = NikTubePage(emptyList()), val playlists: List<NikTubeRemotePlaylist> = emptyList())
data class NikTubeComment(val author: String, val text: String)

data class NikTubeStream(val videoUrl: String, val audioUrl: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(), val sabr: com.newtube.sabr.SabrStreamInfo? = null,
    val description: String = "", val captions: List<NikTubeCaption> = emptyList(), val channel: String = "", val channelId: String = "", val title: String = "")

class NikTubeClient(context: android.content.Context? = null) {
    private val proof = context?.let { NikTubeProof(it.applicationContext) }
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

    private fun browseContext() = JSONObject().put("client", JSONObject()
        .put("clientName", "WEB").put("clientVersion", "2.20261010.00.00").put("hl", "en").put("gl", "CA"))

    suspend fun page(query: String? = null, continuation: String? = null): NikTubePage = withContext(Dispatchers.IO) {
        val body = JSONObject().put("context", browseContext())
        if (continuation != null) body.put("continuation", continuation)
        else if (query != null) body.put("query", query.take(200)).put("params", "EgIQAQ==")
        else body.put("browseId", "FEwhat_to_watch")
        parsePage(post(if (query == null) "browse" else "search", body))
    }
    suspend fun shorts(continuation: String? = null): NikTubePage = withContext(Dispatchers.IO) {
        val body = JSONObject().put("context", browseContext())
        if (continuation == null) body.put("query", "popular shorts").put("params", "EgIQCQ==") else body.put("continuation", continuation)
        parsePage(post("search", body))
    }

    suspend fun details(id: String): NikTubeDetails = withContext(Dispatchers.IO) {
        val root = post("next", JSONObject().put("context", browseContext()).put("videoId", id))
        var description = ""
        val chapters = mutableListOf<NikTubeChapter>()
        visitJson(root) { value ->
            value.optJSONObject("videoSecondaryInfoRenderer")?.optJSONObject("description")?.let { description = jsonText(it) }
            value.optJSONObject("chapterRenderer")?.let { chapter ->
                val title = jsonText(chapter.optJSONObject("title"))
                val time = chapter.optString("timeRangeStartMillis").toLongOrNull()
                if (title.isNotBlank() && time != null) chapters.add(NikTubeChapter(title, time))
            }
        }
        NikTubeDetails(description, parsePage(root), chapters.distinctBy { it.startMillis }.sortedBy { it.startMillis })
    }

    suspend fun suggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val url = okhttp3.HttpUrl.Builder().scheme("https").host("suggestqueries.google.com").addPathSegment("complete")
            .addPathSegment("search").addQueryParameter("client", "firefox").addQueryParameter("ds", "yt")
            .addQueryParameter("q", query.take(200)).build()
        run {
            val list = JSONArray(requestText(Request.Builder().url(url).build())).optJSONArray(1) ?: JSONArray()
            (0 until list.length()).map { list.optString(it) }.filter(String::isNotBlank).take(8)
        }
    }

    suspend fun channel(id: String, params: String? = null, continuation: String? = null): NikTubeChannel = withContext(Dispatchers.IO) {
        require(id.startsWith("UC") && id.length <= 100)
        val body = JSONObject().put("context", browseContext())
        if (continuation != null) body.put("continuation", continuation) else {
            body.put("browseId", id); if (params != null) body.put("params", params)
        }
        val root = post("browse", body)
        val meta = root.optJSONObject("metadata")?.optJSONObject("channelMetadataRenderer")
        val header = root.optJSONObject("header")?.optJSONObject("c4TabbedHeaderRenderer")
        val title = meta?.optString("title").orEmpty().ifBlank { header?.optString("title").orEmpty() }
        val avatars = meta?.optJSONObject("avatar")?.optJSONArray("thumbnails") ?: header?.optJSONObject("avatar")?.optJSONArray("thumbnails")
        val tabs = mutableListOf<NikTubeChannelTab>()
        val playlists = mutableListOf<NikTubeRemotePlaylist>()
        visitJson(root) { value -> value.optJSONObject("tabRenderer")?.let { tab ->
            val browse = tab.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")
            if (browse?.optString("browseId") == id) tabs.add(NikTubeChannelTab(tab.optString("title"), browse.optString("params")))
        } }
        visitJson(root) { value ->
            val playlist = value.optJSONObject("gridPlaylistRenderer") ?: value.optJSONObject("playlistRenderer")
            playlist?.optString("playlistId")?.takeIf(String::isNotBlank)?.let { playlists.add(NikTubeRemotePlaylist(it, jsonText(playlist.optJSONObject("title")))) }
            value.optJSONObject("lockupViewModel")?.takeIf { it.optString("contentType") == "LOCKUP_CONTENT_TYPE_PLAYLIST" }?.let { lockup ->
                playlists.add(NikTubeRemotePlaylist(lockup.optString("contentId"), lockup.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")?.optJSONObject("title")?.optString("content").orEmpty()))
            }
        }
        NikTubeChannel(id, title, avatars?.optJSONObject(avatars.length() - 1)?.optString("url").orEmpty(), meta?.optString("description").orEmpty(), tabs.distinctBy { it.title }.filter { it.title in setOf("Home", "Videos", "Shorts", "Playlists") }, parsePage(root), playlists.distinctBy { it.id })
    }
    suspend fun playlist(id: String, continuation: String? = null): NikTubePage = withContext(Dispatchers.IO) {
        require(id.matches(Regex("[A-Za-z0-9_-]{10,100}")))
        parsePage(post("browse", JSONObject().put("context", browseContext()).apply { if (continuation == null) put("browseId", "VL$id") else put("continuation", continuation) }))
    }

    suspend fun comments(id: String, continuation: String? = null): Pair<List<NikTubeComment>, String?> = withContext(Dispatchers.IO) {
        var root = post("next", JSONObject().put("context", browseContext()).apply { if (continuation == null) put("videoId", id) else put("continuation", continuation) })
        if (continuation == null) {
            var token: String? = null
            visitJson(root) { value -> value.optJSONObject("itemSectionRenderer")?.takeIf { it.optString("sectionIdentifier") == "comment-item-section" }?.let { section ->
                visitJson(section) { node -> node.optJSONObject("continuationCommand")?.optString("token")?.takeIf(String::isNotBlank)?.let { token = it } }
            } }
            if (token == null) return@withContext emptyList<NikTubeComment>() to null
            root = post("next", JSONObject().put("context", browseContext()).put("continuation", token))
        }
        val result = mutableListOf<NikTubeComment>()
        visitJson(root) { value -> value.optJSONObject("commentRenderer")?.let { row ->
            val text = jsonText(row.optJSONObject("contentText"))
            if (text.isNotBlank()) result.add(NikTubeComment(jsonText(row.optJSONObject("authorText")), text))
        } }
        result.distinct() to parsePage(root).continuation
    }

    suspend fun search(query: String): List<NikTubeVideo> = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "WEB").put("clientVersion", "2.20261010.00.00")
            .put("hl", "en").put("gl", "CA")))
            .put("query", query.take(200)).put("params", "EgIQAQ==")
        parseVideos(post("search", payload))
    }

    suspend fun resolve(id: String): NikTubeStream {
        require(id.matches(Regex("[A-Za-z0-9_-]{11}")))
        val attestation = proof?.token(id)
        return withContext(Dispatchers.IO) {
        val payload = JSONObject().put("context", JSONObject().put("client", JSONObject()
            .put("clientName", "WEB").put("clientVersion", "2.20260907.06.00").put("userAgent", PLAYBACK_USER_AGENT)
            .put("platform", "DESKTOP").put("browserName", "Chrome").put("browserVersion", "140.0.0.0")
            .put("osName", "Macintosh").put("osVersion", "10_15_7").put("hl", "en").put("gl", "CA")))
            .put("videoId", id).put("contentCheckOk", true).put("racyCheckOk", true)
        if (attestation != null) {
            payload.getJSONObject("context").getJSONObject("client").put("visitorData", attestation.visitor)
                .put("clientVersion", attestation.clientVersion)
            payload.put("serviceIntegrityDimensions", JSONObject().put("poToken", attestation.token))
            payload.put("playbackContext", JSONObject().put("contentPlaybackContext", JSONObject()
                .put("signatureTimestamp", attestation.timestamp).put("html5Preference", "HTML5_PREF_WANTS")))
        }
        val response = post("player", payload)
        val streamData = response.optJSONObject("streamingData")
        val endpoint = streamData?.optString("serverAbrStreamingUrl")?.takeIf(String::isNotBlank)
        if (endpoint != null && attestation != null && proof != null) {
            val uri = android.net.Uri.parse(endpoint)
            uri.getQueryParameter("n")?.takeIf(String::isNotBlank)?.let { value ->
                val transformed = proof.decipherN(attestation.playerUrl, value)
                val query = uri.encodedQuery.orEmpty().split('&').filterNot { android.net.Uri.decode(it.substringBefore('=')) == "n" }.joinToString("&")
                streamData.put("serverAbrStreamingUrl", uri.buildUpon().encodedQuery(query).appendQueryParameter("n", transformed).build().toString())
            }
        }
        if (attestation != null) {
            response.put("niktubeProofToken", attestation.token)
            response.put("niktubeSessionToken", attestation.sessionToken)
            response.put("niktubeClientVersion", attestation.clientVersion)
        }
        val captions = response.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")?.optJSONArray("captionTracks") ?: JSONArray()
        parseStream(response).copy(description = response.optJSONObject("videoDetails")?.optString("shortDescription").orEmpty(),
            channel = response.optJSONObject("videoDetails")?.optString("author").orEmpty(), channelId = response.optJSONObject("videoDetails")?.optString("channelId").orEmpty(), title = response.optJSONObject("videoDetails")?.optString("title").orEmpty(),
            captions = (0 until captions.length()).mapNotNull { index ->
                val caption = captions.optJSONObject(index) ?: return@mapNotNull null
                val url = caption.optString("baseUrl").takeIf { it.startsWith("https://") } ?: return@mapNotNull null
                NikTubeCaption(caption.optString("languageCode"), jsonText(caption.optJSONObject("name")), android.net.Uri.parse(url).buildUpon().appendQueryParameter("fmt", "vtt").build().toString())
            }, requestHeaders = buildMap {
            put("User-Agent", PLAYBACK_USER_AGENT)
            put("Origin", "https://www.youtube.com")
            put("Referer", "https://www.youtube.com/")
            if (attestation != null) put("X-Goog-Visitor-Id", attestation.visitor)
        })
        }
    }

    private suspend fun post(endpoint: String, payload: JSONObject): JSONObject {
        var request = Request.Builder().url("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .header("User-Agent", if (endpoint == "search") "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36"
                else PLAYBACK_USER_AGENT)
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", payload.getJSONObject("context").getJSONObject("client").getString("clientVersion"))
            .header("Origin", "https://www.youtube.com")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        payload.optJSONObject("context")?.optJSONObject("client")?.optString("visitorData")?.takeIf(String::isNotBlank)?.let {
            request = request.newBuilder().header("X-Goog-Visitor-Id", it)
                .header("X-YouTube-Client-Version", payload.getJSONObject("context").getJSONObject("client").getString("clientVersion"))
                .build()
        }
        return JSONObject(requestText(request))
    }
    private suspend fun requestText(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, error: java.io.IOException) { if (continuation.isActive) continuation.resumeWithException(java.io.IOException("YouTube could not be reached")) }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    try {
                        check(response.isSuccessful) { "YouTube could not be reached (${response.code})" }
                        val body = response.body ?: error("Empty response")
                        check(body.contentLength() <= 8 * 1024 * 1024) { "Metadata response is too large" }
                        val source = body.source(); check(!source.request(8L * 1024 * 1024 + 1)) { "Metadata response is too large" }
                        val text = source.readUtf8()
                        if (continuation.isActive) continuation.resume(text)
                    } catch (_: Exception) { if (continuation.isActive) continuation.resumeWithException(java.io.IOException("Could not read YouTube metadata")) }
                }
            }
        })
    }

    companion object {
        private const val PLAYBACK_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36(KHTML, like Gecko)"
        internal fun jsonText(value: JSONObject?): String = value?.optString("simpleText").orEmpty().ifBlank {
            val runs = value?.optJSONArray("runs") ?: JSONArray()
            (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
        }
        internal fun visitJson(value: Any?, action: (JSONObject) -> Unit) {
            when (value) {
                is JSONObject -> { action(value); value.keys().forEach { visitJson(value.opt(it), action) } }
                is JSONArray -> (0 until value.length()).forEach { visitJson(value.opt(it), action) }
            }
        }
        internal fun parsePage(root: JSONObject): NikTubePage {
            var token: String? = null
            visitJson(root) { value ->
                value.optJSONObject("continuationItemRenderer")?.optJSONObject("continuationEndpoint")
                    ?.optJSONObject("continuationCommand")?.optString("token")?.takeIf(String::isNotBlank)?.let { if (token == null) token = it }
            }
            return NikTubePage(parseVideos(root), token)
        }
        internal fun parseVideos(root: JSONObject): List<NikTubeVideo> {
            val videos = linkedMapOf<String, NikTubeVideo>()
            fun text(value: JSONObject?): String = value?.optString("simpleText").orEmpty().ifBlank {
                val runs = value?.optJSONArray("runs") ?: JSONArray()
                (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
            }
            fun visit(value: Any?) {
                when (value) {
                    is JSONObject -> {
                        value.optJSONObject("reelItemRenderer")?.let { reel ->
                            val id = reel.optString("videoId")
                            if (id.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                                val thumbs = reel.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                                videos.putIfAbsent(id, NikTubeVideo(id, jsonText(reel.optJSONObject("headline")), "", views = jsonText(reel.optJSONObject("viewCountText")), thumbnailUrl = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()))
                            }
                        }
                        value.optJSONObject("shortsLockupViewModel")?.let { reel ->
                            val endpoint = reel.optJSONObject("onTap")?.optJSONObject("innertubeCommand")?.optJSONObject("reelWatchEndpoint")
                            val id = endpoint?.optString("videoId").orEmpty()
                            if (id.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                                val meta = reel.optJSONObject("overlayMetadata")
                                val sources = reel.optJSONObject("thumbnailViewModel")?.optJSONObject("thumbnailViewModel")?.optJSONObject("image")?.optJSONArray("sources")
                                videos.putIfAbsent(id, NikTubeVideo(id, meta?.optJSONObject("primaryText")?.optString("content").orEmpty(), "", views = meta?.optJSONObject("secondaryText")?.optString("content").orEmpty(), thumbnailUrl = sources?.optJSONObject(sources.length() - 1)?.optString("url").orEmpty()))
                            }
                        }
                        value.optJSONObject("lockupViewModel")?.let { lockup ->
                            val id = lockup.optString("contentId")
                            val meta = lockup.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
                            val title = meta?.optJSONObject("title")?.optString("content").orEmpty()
                            if (id.matches(Regex("[A-Za-z0-9_-]{11}")) && title.isNotBlank()) {
                                var owner = ""; var channelId = ""; var avatar = ""; var thumbnail = ""; var duration = ""
                                val parts = mutableListOf<String>()
                                visitJson(meta) { part ->
                                    part.optJSONObject("text")?.optString("content")?.takeIf(String::isNotBlank)?.let { label ->
                                        parts.add(label)
                                        var channel = ""
                                        visitJson(part) { node -> node.optJSONObject("browseEndpoint")?.optString("browseId")?.takeIf { it.startsWith("UC") }?.let { channel = it } }
                                        if (channel.isNotBlank() && owner.isBlank()) { owner = label; channelId = channel }
                                    }
                                    if (avatar.isBlank()) part.optJSONObject("avatarViewModel")?.optJSONObject("image")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")?.let { avatar = it }
                                }
                                val image = lockup.optJSONObject("contentImage")?.optJSONObject("thumbnailViewModel")
                                val sources = image?.optJSONObject("image")?.optJSONArray("sources")
                                thumbnail = sources?.optJSONObject(sources.length() - 1)?.optString("url").orEmpty()
                                visitJson(image) { badge -> badge.optString("text").takeIf { it.matches(Regex("\\d+:\\d{2}(:\\d{2})?")) }?.let { duration = it } }
                                videos.putIfAbsent(id, NikTubeVideo(id, title, owner, duration, channelId, avatar,
                                    parts.firstOrNull { it.contains("view", true) }.orEmpty(), parts.firstOrNull { it.contains("ago", true) }.orEmpty(), thumbnail))
                            }
                        }
                        (value.optJSONObject("videoRenderer") ?: value.optJSONObject("compactVideoRenderer")
                            ?: value.optJSONObject("gridVideoRenderer"))?.let { video ->
                            val id = video.optString("videoId")
                            val title = text(video.optJSONObject("title"))
                            if (id.matches(Regex("[A-Za-z0-9_-]{11}")) && title.isNotBlank()) {
                                val owner = video.optJSONObject("ownerText") ?: video.optJSONObject("shortBylineText") ?: video.optJSONObject("longBylineText")
                                val channelId = owner?.optJSONArray("runs")?.optJSONObject(0)?.optJSONObject("navigationEndpoint")
                                    ?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
                                val thumbs = video.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                                val avatar = video.optJSONObject("channelThumbnailSupportedRenderers")?.optJSONObject("channelThumbnailWithLinkRenderer")
                                    ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")?.optJSONObject(0)?.optString("url").orEmpty()
                                videos.putIfAbsent(id, NikTubeVideo(id, title,
                                    text(owner),
                                    text(video.optJSONObject("lengthText")), channelId, avatar,
                                    text(video.optJSONObject("viewCountText")), text(video.optJSONObject("publishedTimeText")),
                                    thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()))
                            }
                        }
                        value.keys().forEach { visit(value.opt(it)) }
                    }
                    is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it)) }
                }
            }
            visit(root)
            return videos.values.toList()
        }

        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        internal fun parseStream(root: JSONObject): NikTubeStream {
            val status = root.optJSONObject("playabilityStatus")
            check(status?.optString("status") == "OK") {
                status?.optString("reason")?.takeIf(String::isNotBlank)
                    ?: "YouTube cannot play this video without additional verification."
            }
            val data = root.optJSONObject("streamingData") ?: error("No playable stream was returned.")
            val sabrEndpoint = data.optString("serverAbrStreamingUrl")
            fun formats(name: String): List<JSONObject> {
                val list = data.optJSONArray(name) ?: JSONArray()
                return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
                    .filter { it.optString("url").startsWith("https://") }
            }
            val combined = formats("formats").filter { it.optString("mimeType").startsWith("video/mp4") }
                .maxByOrNull { it.optInt("height") }
            if (combined != null && !sabrEndpoint.startsWith("https://")) return NikTubeStream(combined.getString("url"))
            val adaptive = formats("adaptiveFormats")
            val video = adaptive.filter { it.optString("mimeType").startsWith("video/mp4") && it.optInt("height") <= 1080 }
                .maxByOrNull { it.optInt("height") }
            val audio = adaptive.filter { it.optString("mimeType").startsWith("audio/mp4") }
                .maxByOrNull { it.optInt("bitrate") }
            if (video != null && audio != null && !sabrEndpoint.startsWith("https://")) return NikTubeStream(video.getString("url"), audio.getString("url"))
            val hls = data.optString("hlsManifestUrl")
            if (hls.startsWith("https://") && !sabrEndpoint.startsWith("https://")) return NikTubeStream(hls)
            if (sabrEndpoint.startsWith("https://")) {
                val video = root.getJSONObject("videoDetails")
                val config = root.getJSONObject("playerConfig").getJSONObject("mediaCommonConfig")
                    .getJSONObject("mediaUstreamerRequestConfig").getString("videoPlaybackUstreamerConfig")
                val formats = data.optJSONArray("adaptiveFormats") ?: error("Missing SABR formats")
                val tracks = (0 until formats.length()).mapNotNull { index ->
                    val item = formats.getJSONObject(index)
                    val mime = item.optString("mimeType")
                    val codecs = Regex("codecs=\"([^\"]+)\"").find(mime)?.groupValues?.get(1) ?: return@mapNotNull null
                    val audio = mime.startsWith("audio/mp4") && codecs.startsWith("mp4a") && !item.optBoolean("isDrc")
                    val visual = mime.startsWith("video/mp4") && codecs.startsWith("avc1") && item.optInt("height") <= 1080
                    if ((!audio && !visual) || item.optString("type").contains("OTF")) return@mapNotNull null
                    val modified = item.optString("lastModified").toLongOrNull() ?: return@mapNotNull null
                    val audioId = item.optJSONObject("audioTrack")?.optString("id")?.takeIf(String::isNotBlank)
                    val format = androidx.media3.common.Format.Builder().setId(item.getInt("itag").toString())
                        .setContainerMimeType(mime.substringBefore(';')).setCodecs(codecs)
                        .setSampleMimeType(if (audio) "audio/mp4a-latm" else "video/avc")
                        .setAverageBitrate(item.optInt("bitrate", -1))
                    if (audio) format.setSampleRate(item.optString("audioSampleRate").toIntOrNull() ?: -1)
                        .setChannelCount(item.optInt("audioChannels", -1)).setLanguage(audioId?.substringBefore('.'))
                    else format.setWidth(item.optInt("width", -1)).setHeight(item.optInt("height", -1))
                        .setFrameRate(item.optInt("fps", -1).toFloat())
                    com.newtube.sabr.SabrStreamInfo.Track(format.build(), modified, item.optString("xtags").takeIf(String::isNotBlank), audioId, false)
                }
                check(tracks.any { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO } && tracks.any { it.type == androidx.media3.common.C.TRACK_TYPE_AUDIO }) {
                    "YouTube did not return both supported audio and video tracks."
                }
                val info = com.newtube.sabr.SabrStreamInfo(video.getString("videoId"), video.getString("lengthSeconds").toLong() * 1_000_000,
                    sabrEndpoint, config, "WEB", root.optString("niktubeClientVersion", "2.20260907.06.00"), "Macintosh", "10_15_7", tracks,
                    root.optString("niktubeProofToken").takeIf(String::isNotBlank),
                    root.optString("niktubeSessionToken").takeIf(String::isNotBlank))
                return NikTubeStream("sabr://vod/${video.getString("videoId")}", sabr = info)
            }
            error("This video needs a stream format NikTube's draft resolver does not support yet.")
        }
    }
}
