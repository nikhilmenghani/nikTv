package com.nikhil.niktv.ui

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class NikTubeMobileViewModel(app: Application) : AndroidViewModel(app) {
    private val client = NikTubeClient(app)
    private val repository = NikTubeRepository(app)
    var videos by mutableStateOf<List<NikTubeVideo>>(emptyList()); private set
    var history by mutableStateOf<List<NikTubeVideo>>(emptyList()); private set
    var positions by mutableStateOf<Map<String, Long>>(emptyMap()); private set
    var loading by mutableStateOf(false); private set
    var loadingMore by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var query by mutableStateOf<String?>(null); private set
    var pageQuery by mutableStateOf<String?>(null); private set
    var continuation by mutableStateOf<String?>(null); private set
    var suggestions by mutableStateOf<List<String>>(emptyList()); private set
    var details by mutableStateOf<NikTubeDetails?>(null); private set
    var opening by mutableStateOf<NikTubeVideo?>(null); private set
    var failedVideo by mutableStateOf<NikTubeVideo?>(null); private set
    var channel by mutableStateOf<NikTubeChannel?>(null); private set
    var subscriptions by mutableStateOf<List<NikTubeChannel>>(emptyList()); private set
    var playlists by mutableStateOf<Map<String, List<NikTubeVideo>>>(emptyMap()); private set
    var subscriptionVideos by mutableStateOf<List<NikTubeVideo>>(emptyList()); private set
    var comments by mutableStateOf<List<NikTubeComment>>(emptyList()); private set
    var commentsLoading by mutableStateOf(false); private set
    var commentsError by mutableStateOf<String?>(null); private set
    var channelLoading by mutableStateOf(false); private set
    var channelError by mutableStateOf<String?>(null); private set
    var remotePlaylist by mutableStateOf<NikTubeRemotePlaylist?>(null); private set
    var remotePlaylistPage by mutableStateOf(NikTubePage(emptyList())); private set
    var channelParams by mutableStateOf<String?>(null); private set
    private var channelJob: Job? = null
    private var commentToken: String? = null
    private var commentsVideo: String? = null
    private var subscriptionJob: Job? = null
    var shorts by mutableStateOf<List<NikTubeVideo>>(emptyList()); private set
    var shortsLoading by mutableStateOf(false); private set
    var shortsError by mutableStateOf<String?>(null); private set
    private var shortsToken: String? = null
    private var shortsJob: Job? = null
    var downloadVideo by mutableStateOf<NikTubeVideo?>(null); private set
    var downloadStream by mutableStateOf<NikTubeStream?>(null); private set
    var downloadError by mutableStateOf<String?>(null); private set
    var downloads by mutableStateOf<List<NikTubeDownloadState>>(emptyList()); private set
    private var downloadOptionsJob: Job? = null
    var watchVisible by mutableStateOf(NikTubeSession.video != null)
    var fullscreen by mutableStateOf(false)
    private var browseJob: Job? = null
    private var suggestionJob: Job? = null
    private var playJob: Job? = null
    private var detailsJob: Job? = null
    private var lastSaved = 0L
    init {
        NikTubeSession.initialize(app)
        viewModelScope.launch {
            repository.migrateHistory()
            val saved = repository.history()
            history = saved.map { it.first }; positions = saved.associate { it.first.id to it.second }
            subscriptions = repository.subscriptions(); playlists = repository.playlists()
            browse()
        }
    }
    fun browse(search: String? = null, refresh: Boolean = false) {
        browseJob?.cancel()
        if (query != search) { videos = emptyList(); continuation = null }
        query = search; error = null; loadingMore = false; loading = true
        browseJob = viewModelScope.launch {
            try {
                val key = search?.let { "search:$it" } ?: "home"
                val cached = if (refresh) null else repository.cached(key)
                if (cached != null) { videos = cached.videos; continuation = cached.continuation; pageQuery = search; return@launch }
                pageQuery = search
                var result = client.page(search)
                if (search == null && result.videos.isEmpty()) {
                    pageQuery = "popular videos"
                    result = client.page(pageQuery)
                }
                videos = result.videos; continuation = result.continuation
                if (pageQuery == search) repository.cache(key, result)
                if (videos.isEmpty()) error = "No videos were returned. Try a search or refresh."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Could not load videos. Check your connection and retry." }
            finally { if (currentCoroutineContext().isActive) loading = false }
        }
    }
    fun loadMore() {
        val token = continuation ?: return
        if (loading || loadingMore) return
        val search = pageQuery
        loadingMore = true
        browseJob = viewModelScope.launch {
            try {
                val page = client.page(search, token)
                videos = (videos + page.videos).distinctBy { it.id }
                continuation = page.continuation.takeUnless { it == token }
                if (pageQuery == query) repository.cache(query?.let { "search:$it" } ?: "home", NikTubePage(videos, continuation))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "Could not load more videos. Tap Retry to continue." }
            finally { if (currentCoroutineContext().isActive) loadingMore = false }
        }
    }
    fun suggest(value: String) {
        suggestionJob?.cancel()
        if (value.isBlank()) { suggestions = emptyList(); return }
        suggestionJob = viewModelScope.launch {
            delay(300)
            try { suggestions = client.suggestions(value) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { suggestions = emptyList() }
        }
    }
    fun openChannel(video: NikTubeVideo) { if (video.channelId.isNotBlank()) openChannel(video.channelId, video.channel) }
    fun openChannel(id: String, title: String = "", params: String? = null, more: Boolean = false) {
        if (more && channelLoading) return
        channelJob?.cancel(); channelError = null; channelLoading = true
        val previous = channel?.takeIf { it.id == id }
        if (!more) { channelParams = params; channel = previous?.copy(page = NikTubePage(emptyList())) ?: NikTubeChannel(id, title) }
        channelJob = viewModelScope.launch {
            try {
                val result = client.channel(id, params ?: channelParams, if (more) previous?.page?.continuation else null)
                channel = result.copy(title = result.title.ifBlank { previous?.title ?: title }, avatar = result.avatar.ifBlank { previous?.avatar.orEmpty() },
                    tabs = result.tabs.ifEmpty { previous?.tabs.orEmpty() }, description = result.description.ifBlank { previous?.description.orEmpty() },
                    page = if (more) result.page.copy(videos = (previous?.page?.videos.orEmpty() + result.page.videos).distinctBy { it.id }, continuation = result.page.continuation.takeUnless { it == previous?.page?.continuation }) else result.page)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { channelError = "Could not load this channel. Retry when connected." }
            finally { if (currentCoroutineContext().isActive) channelLoading = false }
        }
    }
    fun closeChannel() { channelJob?.cancel(); channel = null; remotePlaylist = null; channelLoading = false }
    fun openRemotePlaylist(playlist: NikTubeRemotePlaylist, more: Boolean = false) {
        if (more && channelLoading) return
        channelJob?.cancel(); channelLoading = true; channelError = null
        if (!more) { remotePlaylist = playlist; remotePlaylistPage = NikTubePage(emptyList()) }
        channelJob = viewModelScope.launch {
            try { val page = client.playlist(playlist.id, if (more) remotePlaylistPage.continuation else null); remotePlaylistPage = if (more) page.copy(videos = (remotePlaylistPage.videos + page.videos).distinctBy { it.id }) else page }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { channelError = "Could not load this playlist." }
            finally { if (currentCoroutineContext().isActive) channelLoading = false }
        }
    }
    fun closeRemotePlaylist() { channelJob?.cancel(); remotePlaylist = null; channelLoading = false }
    fun subscribe(item: NikTubeChannel) {
        subscriptions = if (subscriptions.any { it.id == item.id }) subscriptions.filterNot { it.id == item.id } else subscriptions + item.copy(page = NikTubePage(emptyList()), tabs = emptyList())
        val updated = subscriptions
        viewModelScope.launch { repository.saveSubscriptions(updated) }
    }
    fun refreshSubscriptions(force: Boolean = false) {
        subscriptionJob?.cancel()
        subscriptionJob = viewModelScope.launch {
            channelLoading = true; channelError = null
            try {
                val list = subscriptions
                val permits = Semaphore(3)
                val results = list.map { channel -> async { permits.withPermit {
                    val key = "subscription:${channel.id}"
                    val cached = if (force) null else repository.cached(key)
                    cached ?: client.channel(channel.id).let { landing ->
                        landing.tabs.firstOrNull { it.title.equals("Videos", true) }?.let { client.channel(channel.id, it.params).page } ?: landing.page
                    }.let { page -> page.copy(videos = page.videos.map { it.copy(channel = it.channel.ifBlank { channel.title }, channelId = it.channelId.ifBlank { channel.id }, avatar = it.avatar.ifBlank { channel.avatar }) }) }.also { repository.cache(key, it) }
                } } }.awaitAll()
                subscriptionVideos = results.flatMap { it.videos }.distinctBy { it.id }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { channelError = "Some subscriptions could not load. Refresh to try again." }
            finally { if (currentCoroutineContext().isActive) channelLoading = false }
        }
    }
    fun addToPlaylist(name: String, video: NikTubeVideo) {
        val clean = name.trim().take(80)
        if (clean.isBlank()) return
        playlists = playlists + (clean to (playlists[clean].orEmpty() + video).distinctBy { it.id })
        val updated = playlists
        viewModelScope.launch { repository.savePlaylists(updated) }
    }
    fun createPlaylist(name: String) {
        val clean = name.trim().take(80)
        if (clean.isBlank() || playlists.containsKey(clean)) return
        playlists = playlists + (clean to emptyList())
        val updated = playlists; viewModelScope.launch { repository.savePlaylists(updated) }
    }
    fun removeFromPlaylist(name: String, video: NikTubeVideo) {
        playlists = playlists + (name to playlists[name].orEmpty().filterNot { it.id == video.id })
        val updated = playlists; viewModelScope.launch { repository.savePlaylists(updated) }
    }
    fun moveInPlaylist(name: String, video: NikTubeVideo, delta: Int) {
        val list = playlists[name].orEmpty().toMutableList(); val index = list.indexOfFirst { it.id == video.id }
        val target = index + delta
        if (index < 0 || target !in list.indices) return
        java.util.Collections.swap(list, index, target); playlists = playlists + (name to list)
        val updated = playlists; viewModelScope.launch { repository.savePlaylists(updated) }
    }
    fun deletePlaylist(name: String) { playlists = playlists - name; val updated = playlists; viewModelScope.launch { repository.savePlaylists(updated) } }
    fun clearHistory() { history = emptyList(); positions = emptyMap(); viewModelScope.launch { repository.saveHistory(emptyList()) } }
    fun loadComments(more: Boolean = false) {
        val id = NikTubeSession.video?.id ?: return
        if (commentsLoading || more && commentToken == null) return
        commentsLoading = true; commentsError = null
        if (!more || commentsVideo != id) { comments = emptyList(); commentToken = null; commentsVideo = id }
        viewModelScope.launch {
            try { val result = client.comments(id, if (more) commentToken else null); comments = (comments + result.first).distinct(); commentToken = result.second }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { commentsError = "Could not load comments." }
            finally { commentsLoading = false }
        }
    }
    fun play(video: NikTubeVideo, resume: Long = positions[video.id] ?: 0, showWatch: Boolean = true) {
        saveProgress(force = true)
        playJob?.cancel(); opening = video; failedVideo = null; error = null; watchVisible = showWatch; details = null
        playJob = viewModelScope.launch {
            try {
                val stream = client.resolve(video.id)
                val media = PlayingMedia(MediaItem("niktube:${video.id}", video.title, video.thumbnail, null, video.channel),
                    stream.videoUrl, CatalogType.MOVIES, resumePositionMillis = resume, progressKey = "niktube:${video.id}",
                    separateAudioUrl = stream.audioUrl, streamRequestHeaders = stream.requestHeaders, nativeSabr = stream.sabr)
                val enriched = video.copy(title = video.title.ifBlank { stream.title }, channel = video.channel.ifBlank { stream.channel }, channelId = video.channelId.ifBlank { stream.channelId })
                NikTubeSession.start(getApplication(), enriched, media, stream.captions)
                if (!showWatch) NikTubeSession.player?.repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE
                details = NikTubeDetails(stream.description, NikTubePage(emptyList()))
                history = (listOf(enriched) + history.filterNot { it.id == video.id }).take(500)
                repository.saveHistory(history.map { it to (positions[it.id] ?: 0L) })
                detailsJob?.cancel()
                detailsJob = viewModelScope.launch { runCatching { client.details(video.id) }.onSuccess { details = it.copy(description = it.description.ifBlank { stream.description }) } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failedVideo = video; error = "Could not open this video. Retry or open it externally." }
            finally { if (currentCoroutineContext().isActive) opening = null }
        }
    }
    fun saveProgress(force: Boolean = false) {
        val video = NikTubeSession.video ?: return
        val engine = NikTubeSession.player ?: return
        if (!force && System.currentTimeMillis() - lastSaved < 5_000) return
        lastSaved = System.currentTimeMillis()
        val duration = engine.duration
        val position = if (duration > 0 && engine.currentPosition >= duration - 10_000) 0 else engine.currentPosition
        positions = positions + (video.id to position)
        viewModelScope.launch { repository.saveHistory(history.map { it to (positions[it.id] ?: 0L) }) }
    }
    fun close() { saveProgress(true); playJob?.cancel(); opening = null; NikTubeSession.close(); watchVisible = false; fullscreen = false }
    fun cancelOpening() { playJob?.cancel(); opening = null; watchVisible = NikTubeSession.video != null }
    fun dismissFailure() { failedVideo = null; error = null }
    fun loadShorts(more: Boolean = false) {
        if (shortsLoading || more && shortsToken == null) return
        if (!more && shorts.isNotEmpty()) return
        shortsLoading = true; shortsError = null
        shortsJob = viewModelScope.launch {
            try {
                val result = client.shorts(if (more) shortsToken else null)
                shorts = (if (more) shorts + result.videos else result.videos).distinctBy { it.id }
                shortsToken = result.continuation.takeUnless { it == shortsToken }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { shortsError = "Could not load Shorts. Retry when connected." }
            finally { shortsLoading = false }
        }
    }
    fun prepareDownload(video: NikTubeVideo) {
        downloadOptionsJob?.cancel()
        downloadVideo = video; downloadStream = null; downloadError = null
        downloadOptionsJob = viewModelScope.launch {
            try { val result = client.resolve(video.id); if (downloadVideo?.id == video.id) downloadStream = result }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (downloadVideo?.id == video.id) downloadError = "Could not load download options. Retry when connected." }
        }
    }
    fun cancelDownloadOptions() { downloadOptionsJob?.cancel(); downloadVideo = null; downloadStream = null; downloadError = null }
    fun enqueueDownload(height: Int, audio: String = "") {
        val video = downloadVideo ?: return
        NikTubeDownloads.enqueue(getApplication(), video, height, audio); cancelDownloadOptions(); refreshDownloads()
    }
    fun refreshDownloads() { viewModelScope.launch { downloads = NikTubeDownloads.states(getApplication()) } }
    fun cancelDownload(record: NikTubeDownloadRecord) { NikTubeDownloads.cancel(getApplication(), record); refreshDownloads() }
    fun removeDownload(record: NikTubeDownloadRecord) { NikTubeDownloads.remove(getApplication(), record); refreshDownloads() }
    fun retryDownload(record: NikTubeDownloadRecord) { NikTubeDownloads.enqueue(getApplication(), record.video, record.height, record.audio); refreshDownloads() }
    fun playDownload(state: NikTubeDownloadState) {
        val file = state.file ?: return
        saveProgress(true)
        val video = state.record.video
        NikTubeSession.start(getApplication(), video, PlayingMedia(MediaItem("niktube:${video.id}", video.title, video.thumbnail, null, video.channel),
            android.net.Uri.fromFile(file).toString(), CatalogType.MOVIES, offlinePlayback = true, resumePositionMillis = positions[video.id] ?: 0))
        watchVisible = true; details = NikTubeDetails("Downloaded video", NikTubePage(emptyList()))
    }
    override fun onCleared() { repository.close(); super.onCleared() }
}
