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

data class NikTubeVideo(val id: String, val title: String, val channel: String, val duration: String = "") {
    val thumbnail: String get() = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
}

data class NikTubeStream(val videoUrl: String, val audioUrl: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(), val sabr: com.newtube.sabr.SabrStreamInfo? = null)

class NikTubeClient(context: android.content.Context? = null) {
    private val proof = context?.let { NikTubeProof(it.applicationContext) }
    private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

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
        parseStream(response).copy(requestHeaders = buildMap {
            put("User-Agent", PLAYBACK_USER_AGENT)
            put("Origin", "https://www.youtube.com")
            put("Referer", "https://www.youtube.com/")
            if (attestation != null) put("X-Goog-Visitor-Id", attestation.visitor)
        })
        }
    }

    private fun post(endpoint: String, payload: JSONObject): JSONObject {
        var request = Request.Builder().url("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .header("User-Agent", if (endpoint == "search") "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140.0.0.0 Safari/537.36"
                else PLAYBACK_USER_AGENT)
            .header("X-YouTube-Client-Name", "1")
            .header("X-YouTube-Client-Version", if (endpoint == "search") "2.20261010.00.00" else "2.20260907.06.00")
            .header("Origin", "https://www.youtube.com")
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        payload.optJSONObject("context")?.optJSONObject("client")?.optString("visitorData")?.takeIf(String::isNotBlank)?.let {
            request = request.newBuilder().header("X-Goog-Visitor-Id", it)
                .header("X-YouTube-Client-Version", payload.getJSONObject("context").getJSONObject("client").getString("clientVersion"))
                .build()
        }
        return http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "YouTube could not be reached (${response.code}). Try again." }
            JSONObject(response.body?.string() ?: error("YouTube returned an empty response."))
        }
    }

    companion object {
        private const val PLAYBACK_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36(KHTML, like Gecko)"
        internal fun parseVideos(root: JSONObject): List<NikTubeVideo> {
            val videos = linkedMapOf<String, NikTubeVideo>()
            fun text(value: JSONObject?): String = value?.optString("simpleText").orEmpty().ifBlank {
                val runs = value?.optJSONArray("runs") ?: JSONArray()
                (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
            }
            fun visit(value: Any?) {
                when (value) {
                    is JSONObject -> {
                        value.optJSONObject("videoRenderer")?.let { video ->
                            val id = video.optString("videoId")
                            val title = text(video.optJSONObject("title"))
                            if (id.matches(Regex("[A-Za-z0-9_-]{11}")) && title.isNotBlank()) {
                                videos.putIfAbsent(id, NikTubeVideo(id, title,
                                    text(video.optJSONObject("ownerText") ?: video.optJSONObject("shortBylineText")),
                                    text(video.optJSONObject("lengthText"))))
                            }
                        }
                        value.keys().forEach { visit(value.opt(it)) }
                    }
                    is JSONArray -> (0 until value.length()).forEach { visit(value.opt(it)) }
                }
            }
            visit(root)
            return videos.values.take(24)
        }

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
