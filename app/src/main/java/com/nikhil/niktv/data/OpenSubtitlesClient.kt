package com.nikhil.niktv.data

import android.content.Context
import com.nikhil.niktv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class OnlineSubtitle(
    val id: String,
    val fileId: Int,
    val language: String,
    val release: String?,
    val fileName: String,
    val hearingImpaired: Boolean,
    val downloadCount: Int
) {
    val displayName: String
        get() = buildString {
            append(language.uppercase())
            release?.takeIf(String::isNotBlank)?.let { append(" · ").append(it) }
            if (hearingImpaired) append(" · SDH")
            if (downloadCount > 0) append(" · ").append(downloadCount).append(" downloads")
        }
}

data class SubtitleSearchRequest(
    val query: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val languages: String = "en"
)

object OpenSubtitlesClient {
    val configured: Boolean get() = BuildConfig.OPEN_SUBTITLES_KEY.trim().isNotEmpty()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun search(search: SubtitleSearchRequest): List<OnlineSubtitle> = withContext(Dispatchers.IO) {
        require(configured) { "OpenSubtitles API key is not embedded in this build." }
        require(search.query.isNotBlank()) { "Enter a movie or series title." }
        val url = "https://api.opensubtitles.com/api/v1/subtitles".toHttpUrl().newBuilder()
            .addQueryParameter("query", search.query.trim())
            .addQueryParameter("languages", search.languages.ifBlank { "en" })
            .apply {
                search.seasonNumber?.let { addQueryParameter("season_number", it.toString()) }
                search.episodeNumber?.let { addQueryParameter("episode_number", it.toString()) }
            }
            .build()
        val response = http.newCall(apiRequest(url.toString()).build()).execute()
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IOException(apiError("search", it.code, body))
            json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty().mapNotNull { item ->
                val root = item.jsonObject
                val attributes = root["attributes"]?.jsonObject ?: return@mapNotNull null
                val file = attributes["files"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@mapNotNull null
                val fileId = file["file_id"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                OnlineSubtitle(
                    id = root["id"]?.jsonPrimitive?.contentOrNull ?: fileId.toString(),
                    fileId = fileId,
                    language = attributes["language"]?.jsonPrimitive?.contentOrNull ?: "und",
                    release = attributes["release"]?.jsonPrimitive?.contentOrNull,
                    fileName = file["file_name"]?.jsonPrimitive?.contentOrNull ?: "subtitle-$fileId.srt",
                    hearingImpaired = attributes["hearing_impaired"]?.jsonPrimitive?.contentOrNull == "true",
                    downloadCount = attributes["download_count"]?.jsonPrimitive?.intOrNull ?: 0
                )
            }
        }
    }

    suspend fun download(context: Context, subtitle: OnlineSubtitle): File = withContext(Dispatchers.IO) {
        require(configured) { "OpenSubtitles API key is not embedded in this build." }
        val payload = buildJsonObject { put("file_id", subtitle.fileId) }.toString()
        val request = apiRequest("https://api.opensubtitles.com/api/v1/download")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val link = http.newCall(request).execute().use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw IOException(apiError("download", it.code, body))
            json.parseToJsonElement(body).jsonObject["link"]?.jsonPrimitive?.contentOrNull
                ?: throw IOException("OpenSubtitles did not return a download link.")
        }
        val bytes = http.newCall(Request.Builder().url(link).build()).execute().use {
            if (!it.isSuccessful) throw IOException("Subtitle file download returned HTTP ${it.code}.")
            it.body?.bytes() ?: throw IOException("The downloaded subtitle file was empty.")
        }
        val directory = File(context.cacheDir, "subtitles").apply { mkdirs() }
        val extension = subtitle.fileName.substringAfterLast('.', "srt")
            .lowercase()
            .takeIf { it in setOf("srt", "vtt", "ass", "ssa", "ttml", "xml") }
            ?: "srt"
        File(directory, "opensubtitles-${subtitle.fileId}.$extension").apply { writeBytes(bytes) }
    }

    private fun apiRequest(url: String) = Request.Builder()
        .url(url)
        .header("Api-Key", BuildConfig.OPEN_SUBTITLES_KEY.trim())
        .header("User-Agent", "NikTV v1")
        .header("Accept", "application/json")

    private fun apiError(action: String, code: Int, body: String): String {
        val message = runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            root["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return "OpenSubtitles $action failed (HTTP $code)${message?.let { ": $it" }.orEmpty()}"
    }
}
