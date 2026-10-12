package com.nikhil.niktv.ui

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.NikTubeActivity
import com.nikhil.niktv.data.NikTubeClient
import com.nikhil.niktv.data.NikTubeVideo
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.PlayingMedia
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

internal class NikTubeViewModel(application: Application) : AndroidViewModel(application) {
    private val client = NikTubeClient(application)
    private val preferences = application.getSharedPreferences("niktube_draft", 0)
    private val positions = mutableMapOf<String, Long>()
    var videoFrameRendered = false
        private set
    var decodedVideoFrames = 0
        private set
    var nativePlaybackPosition = 0L
        private set
    var nativePlaybackDuration = 0L
        private set
    var history by mutableStateOf<List<NikTubeVideo>>(emptyList())
        private set
    var rows by mutableStateOf<List<Pair<String, List<NikTubeVideo>>>>(emptyList())
        private set
    var searchRows by mutableStateOf<List<Pair<String, List<NikTubeVideo>>>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var resolving by mutableStateOf<NikTubeVideo?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf<PlayingMedia?>(null)
        private set
    var activeVideo by mutableStateOf<NikTubeVideo?>(null)
        private set
    var failedVideo by mutableStateOf<NikTubeVideo?>(null)
        private set
    var searchTitle by mutableStateOf<String?>(null)
        private set
    private var browseJob: Job? = null
    private var playbackJob: Job? = null
    private val topics = listOf("Discover" to "travel nature documentary", "Science & space" to "NASA Veritasium science",
        "Technology" to "Marques Brownlee technology", "Music" to "NPR Music Tiny Desk concert")

    init {
        runCatching {
            val saved = JSONArray(preferences.getString("history", "[]"))
            history = (0 until saved.length()).map { index ->
                val item = saved.getJSONObject(index)
                val video = NikTubeVideo(item.getString("id"), item.getString("title"), item.optString("channel"), item.optString("duration"))
                positions[video.id] = item.optLong("position")
                video
            }
        }
        browse()
    }

    fun browse(query: String = "") {
        browseJob?.cancel()
        browseJob = viewModelScope.launch {
            loading = true
            error = null
            if (query.isNotBlank()) searchTitle = query.trim()
            try {
                val sources = if (query.isBlank()) topics else listOf("Results for ${query.take(60)}" to query)
                val results = sources.map { (title, term) -> async {
                    try { title to client.search(term) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { title to emptyList<NikTubeVideo>() }
                } }.awaitAll()
                val populated = results.filter { it.second.isNotEmpty() }
                if (query.isBlank()) rows = populated else searchRows = populated
                if (populated.isEmpty()) error = "YouTube returned no videos. Try another search or refresh."
                else if (populated.size < sources.size) error = "Some shelves could not load. Refresh to try again."
            } finally { if (currentCoroutineContext().isActive) loading = false }
        }
    }

    fun play(video: NikTubeVideo, resume: Long = positions[video.id] ?: 0L,
        resolver: suspend (String) -> com.nikhil.niktv.data.NikTubeStream = client::resolve) {
        playbackJob?.cancel()
        playing = null
        videoFrameRendered = false
        decodedVideoFrames = 0
        nativePlaybackPosition = 0L
        nativePlaybackDuration = 0L
        playbackJob = viewModelScope.launch {
            resolving = video
            error = null
            failedVideo = null
            try {
                val stream = resolver(video.id)
                activeVideo = video
                playing = PlayingMedia(MediaItem("niktube:${video.id}", video.title, video.thumbnail, null, video.channel),
                    stream.videoUrl, CatalogType.MOVIES, resumePositionMillis = resume,
                    progressKey = "niktube:${video.id}", directFullscreen = true, separateAudioUrl = stream.audioUrl,
                    streamRequestHeaders = stream.requestHeaders, nativeSabr = stream.sabr)
                history = (listOf(video) + history.filterNot { it.id == video.id }).take(20)
                saveHistory()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                failedVideo = video
                error = failure.message ?: "Could not open this video. Try again."
            }
            finally { if (currentCoroutineContext().isActive) resolving = null }
        }
    }

    fun updateProgress(position: Long, duration: Long) {
        nativePlaybackPosition = position
        nativePlaybackDuration = duration
        val id = activeVideo?.id ?: return
        positions[id] = if (duration > 0 && duration - position < 10_000) 0L else position
        saveHistory()
    }

    private fun saveHistory() {
        val saved = JSONArray()
        history.forEach { video -> saved.put(JSONObject().put("id", video.id).put("title", video.title)
            .put("channel", video.channel).put("duration", video.duration).put("position", positions[video.id] ?: 0L)) }
        preferences.edit().putString("history", saved.toString()).apply()
    }

    fun closePlayer() { playbackJob?.cancel(); playing = null; resolving = null }
    fun onVideoFrameRendered() { videoFrameRendered = true }
    fun onVideoFramesRendered(count: Int) { decodedVideoFrames = count }
    fun resumeFraction(video: NikTubeVideo): Float? {
        val position = positions[video.id] ?: return null
        val parts = video.duration.split(':').map { it.toLongOrNull() ?: return null }
        val duration = parts.fold(0L) { seconds, value -> seconds * 60L + value } * 1000L
        return if (position > 0 && duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else null
    }
    fun streamRejected(position: Long) {
        positions[activeVideo?.id ?: return] = position
        failedVideo = activeVideo
        playing = null
        error = "YouTube refused the stream. Retry playback to request a fresh session."
    }
    fun dismissError() { error = null; failedVideo = null }
}

@Composable
internal fun NikTubeEntry(modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    val open = { context.startActivity(Intent(context, NikTubeActivity::class.java)) }
    if (compact) IconButton(onClick = open, modifier = modifier) { Icon(Icons.Default.PlayCircle, "NikTube") }
    else OutlinedButton(onClick = open, modifier = modifier) {
        Icon(Icons.Default.PlayCircle, null, Modifier.size(22.dp))
        Spacer(Modifier.width(8.dp))
        Text("NikTube")
    }
}
