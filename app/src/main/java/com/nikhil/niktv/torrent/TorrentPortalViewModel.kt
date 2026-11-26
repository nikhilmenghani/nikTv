package com.nikhil.niktv.torrent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.data.TmdbEpisode
import com.nikhil.niktv.data.UserSyncStore
import com.nikhil.niktv.data.AccountSyncManager
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.data.OfflineDownloadInfo
import com.nikhil.niktv.data.OfflineDownloadStatus
import com.nikhil.niktv.data.OfflineMediaDownloads
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.PlaybackDownloadedData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal enum class TorrentLibraryPage { CONTINUE_WATCHING, MY_LIST }
internal enum class TorrentTransferPurpose { STREAM, DOWNLOAD }

internal data class TorrentPortalState(
    val query: String = "", val series: Boolean = false,
    val rootSurface: DashboardSurface = DashboardSurface.HOME,
    val home: Boolean = true, val homeData: TorrentHomeState = TorrentHomeState(),
    val homeCollection: TorrentHomeCollection? = null,
    val collectionPage: Int = 0, val collectionHasMore: Boolean = false,
    val library: TorrentLibraryState = TorrentLibraryState(), val libraryPage: TorrentLibraryPage? = null,
    val discoveryConfiguration: TorrentDiscoveryConfiguration = TorrentDiscoveryPreferences.defaults(DashboardSurface.HOME),
    val downloadsOpen: Boolean = false, val downloads: List<OfflineMediaDownload> = emptyList(),
    val downloadInfo: Map<String, OfflineDownloadInfo> = emptyMap(), val notice: String? = null,
    val transferPurpose: TorrentTransferPurpose = TorrentTransferPurpose.STREAM,
    val titles: List<DiscoveryCard> = emptyList(), val title: DiscoveryCard? = null,
    val seasons: List<Int> = emptyList(), val season: Int? = null,
    val episodes: List<TmdbEpisode> = emptyList(), val episode: TmdbEpisode? = null,
    val sources: TorrentSearchSnapshot? = null, val selectedSource: TorrentSource? = null,
    val advancedSearch: Boolean = false, val directSources: TorrentSearchSnapshot? = null,
    val directSearchRevision: Long = 0,
    val fileDownloads: List<TorrentDownloadJob> = emptyList(),
    val files: List<TorrentVideoFile> = emptyList(), val transfer: TorrentStreamStatus? = null,
    val loading: String? = null, val error: String? = null, val playing: PlayingMedia? = null,
    val playbackEngine: PlaybackEngine = PlaybackEngine.AUTO,
    val returnFocus: String? = null,
    val prefetchEnabled: Boolean = false, val nextTarget: TorrentEpisodeTarget? = null,
    val nextStatus: String? = null, val nextReady: Boolean = false, val nextFailed: Boolean = false,
    val advanceRequested: Boolean = false
) {
    val surface: DashboardSurface get() = rootSurface
    val browsingFeeds: Boolean get() = query.isBlank() && directSources == null && homeCollection == null && libraryPage == null && !downloadsOpen
}

private data class TorrentDownloadTarget(val card: DiscoveryCard?, val episode: TmdbEpisode?, val source: TorrentSource)

/** Torrent URLs live only in this playback owner; IPTV mappings and sync stay untouched. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class TorrentPortalViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = TorrentSearchRepository()
    private val homeRepository = TorrentHomeRepository(app)
    private val mutable = MutableStateFlow(TorrentPortalState())
    val state = mutable.asStateFlow()
    private val downloaded = MutableStateFlow<PlaybackDownloadedData?>(null)
    val downloadProgress = downloaded.asStateFlow()
    private var job: Job? = null
    private var transferJob: Job? = null
    private var engine: TorrentStreamEngine? = null
    private var lastClosingEngine: TorrentStreamEngine? = null
    private var lastSource: String? = null
    private var lastFile: TorrentVideoFile? = null
    private var generation = 0L
    private val homeJobs = mutableMapOf<String, Job>()
    private var titleOrigin: String? = null
    private val detailParents = ArrayDeque<Pair<TorrentPortalState, String?>>()
    private var collectionOrigin: String? = null
    private var collectionOriginSurface = DashboardSurface.HOME
    private var downloadsOriginSurface = DashboardSurface.HOME
    private var libraryOrigin: String? = null
    private var pendingResume: TorrentLibraryEntry? = null
    private var libraryPlayback = false
    private var downloadTarget: TorrentDownloadTarget? = null
    private var offlineMetadata: TorrentLibraryEntry? = null
    private val shelfCache = java.util.concurrent.ConcurrentHashMap<TmdbHomeSection, TorrentHomeShelf>()
    private var offlineEpisodeReturn = false
    private var explicitSourceChoice = false
    private var homeCatalogSeries = false
    private val discoveryConfigurations = listOf(DashboardSurface.HOME, DashboardSurface.MOVIES, DashboardSurface.SERIES)
        .associateWith { TorrentDiscoveryPreferences.defaults(it) }.toMutableMap()
    private var progressIdentity: String? = null
    private var progressPosition = 0L
    private var progressDuration = 0L
    private var progressSavedAt = 0L
    private var libraryMetadataJob: Job? = null
    private var fileDownloadsJob: Job? = null
    private val owner = UserSyncStore.get(app).selectedScope.value
    private var nextPlanJob: Job? = null
    private var nextPrepareJob: Job? = null
    private var preparedNext: PreparedTorrentEpisode? = null
    private val nextForeground = MutableStateFlow(false)
    private var videoStarted = false
    private val episodeCache = mutableMapOf<Pair<Int, Int>, List<TmdbEpisode>>()
    private suspend fun episodesFor(card: DiscoveryCard, season: Int): List<TmdbEpisode> =
        episodeCache[card.id to season] ?: repository.episodes(card, season).also { rows ->
            episodeCache[card.id to season] = rows
            owner?.let { captured ->
                val seasons = mutable.value.seasons.takeIf { mutable.value.title?.key == card.key } ?: listOf(season)
                persistenceScope.launch { runCatching { TorrentOfflineLibrary.rememberSequence(getApplication(), captured, card.id, seasons, mapOf(season to rows)) } }
            }
        }

    private fun streamOptions() = TorrentEngineOptions(streamCacheOwner = owner?.let { "${it.accountId}|${it.profileId}" })

    private fun observeTransfer(selected: TorrentStreamEngine) {
        fun publish(transfer: TorrentStreamStatus) {
            if (engine !== selected) return
            mutable.update { it.copy(transfer = transfer) }
            downloaded.value = transfer.takeIf { it.totalBytes > 0 }?.let {
                PlaybackDownloadedData(it.totalBytes, it.downloadedBytes,
                    it.verifiedRanges.filter { range -> range.endExclusiveBytes > range.startBytes }
                        .map { range -> range.startBytes until range.endExclusiveBytes }, it.storageWarning)
            }
        }
        publish(selected.status.value)
        transferJob = viewModelScope.launch { selected.status.collect(::publish) }
    }

    init {
        for (surface in discoveryConfigurations.keys.toList()) viewModelScope.launch {
            TorrentDiscoveryPreferences.observe(app, owner, surface).collect { configuration ->
                discoveryConfigurations[surface] = configuration
                if (mutable.value.surface == surface) applyDiscoveryConfiguration(surface)
            }
        }
        if (owner != null) viewModelScope.launch {
            ProfileStore(app).scoped(owner).offlineDownloads.collectLatest { entries ->
                val torrents = entries.filter { it.profileKey == TORRENT_OFFLINE_PROFILE }
                mutable.update { it.copy(downloads = torrents) }
                do {
                    val information = withContext(Dispatchers.IO) { torrents.associate { entry ->
                        entry.key to OfflineMediaDownloads.info(app, entry.requestId, entry.downloadId)
                    } }
                    mutable.update { it.copy(downloadInfo = information) }
                    val pending = information.values.any { it.status in setOf(OfflineDownloadStatus.QUEUED, OfflineDownloadStatus.DOWNLOADING, OfflineDownloadStatus.PAUSED) }
                    if (!pending) break
                    delay(1_000)
                } while (isActive)
            }
        }
        viewModelScope.launch { TorrentLibraryStore.observe(app, owner).collect { library ->
            mutable.update { it.copy(library = library) }
        } }
        viewModelScope.launch { ProfileStore(app).playbackEngine.collect { value -> mutable.update { it.copy(playbackEngine = value) } } }
        viewModelScope.launch {
            TorrentPlaybackPreferences.observe(app, owner).collect { enabled ->
                mutable.update { it.copy(prefetchEnabled = enabled) }
                if (enabled && videoStarted) startNextPreparation(background = true)
                else if (!enabled && !mutable.value.advanceRequested) stopNextPreparation()
            }
        }
        homeShelf(TmdbHomeSection.TRENDING_MOVIES)
        homeShelf(TmdbHomeSection.TRENDING_SERIES)
    }

    fun query(value: String) { mutable.update { it.copy(query = value) } }
    fun advancedSearch(enabled: Boolean) {
        job?.cancel(); generation++
        mutable.update { it.copy(advancedSearch = enabled, directSources = null, titles = emptyList(), loading = null, error = null) }
    }
    fun type(series: Boolean) {
        if (mutable.value.surface == (if (series) DashboardSurface.SERIES else DashboardSurface.MOVIES) &&
            mutable.value.browsingFeeds && mutable.value.title == null) return
        cancelLibraryMetadata()
        job?.cancel(); generation++
        pauseHomeLoading()
        pendingResume = null; libraryPlayback = false
        mutable.update { it.copy(home = false, rootSurface = if (series) DashboardSurface.SERIES else DashboardSurface.MOVIES,
            homeCollection = null, libraryPage = null, downloadsOpen = false, series = series, title = null,
            sources = null, directSources = null, query = "", error = null, titles = emptyList(), loading = null, returnFocus = null) }
        applyDiscoveryConfiguration(mutable.value.surface)
    }

    fun home() {
        cancelLibraryMetadata()
        job?.cancel(); generation++
        pendingResume = null; libraryPlayback = false
        mutable.update { it.copy(home = true, rootSurface = DashboardSurface.HOME, series = false,
            homeCollection = null, libraryPage = null, downloadsOpen = false, title = null, sources = null,
            titles = emptyList(), directSources = null, query = "", loading = null, error = null, returnFocus = null) }
        applyDiscoveryConfiguration(DashboardSurface.HOME)
    }

    fun cancelSearch(surface: DashboardSurface) {
        require(surface in discoveryConfigurations)
        cancelLibraryMetadata(); job?.cancel(); generation++; pauseHomeLoading()
        pendingResume = null; libraryPlayback = false
        mutable.update { it.copy(rootSurface = surface, home = surface == DashboardSurface.HOME,
            series = surface == DashboardSurface.SERIES, query = "", titles = emptyList(), title = null,
            homeCollection = null, libraryPage = null, downloadsOpen = false, sources = null, directSources = null,
            loading = null, error = null, returnFocus = "search") }
        applyDiscoveryConfiguration(surface)
    }

    fun configureDiscovery(configuration: TorrentDiscoveryConfiguration) {
        val captured = owner ?: return
        val surface = mutable.value.surface
        viewModelScope.launch {
            runCatching { TorrentDiscoveryPreferences.save(getApplication(), captured, surface, configuration) }
                .onFailure { mutable.update { it.copy(error = "Could not save these categories. Please try again.") } }
        }
    }

    fun dismissNotice() { mutable.update { it.copy(notice = null) } }

    fun openDownloads() {
        downloadsOriginSurface = mutable.value.surface
        cancelLibraryMetadata(); job?.cancel(); generation++; pauseHomeLoading()
        pendingResume = null; libraryPlayback = false
        mutable.update { it.copy(downloadsOpen = true, title = null, sources = null, loading = null, error = null, returnFocus = "downloads-back") }
        refreshDownloads()
        refreshFileDownloads()
    }
    private fun refreshFileDownloads() {
        val captured = owner ?: return
        fileDownloadsJob?.cancel()
        fileDownloadsJob = viewModelScope.launch {
            do {
                val files = withContext(Dispatchers.IO) { TorrentOfflineDownloads.files(getApplication(), captured) }
                mutable.update { it.copy(fileDownloads = files) }
                if (files.none { it.state in setOf("queued", "downloading") }) break
                delay(1_000)
            } while (isActive)
        }
    }
    fun removeFileDownload(file: TorrentDownloadJob) {
        if (file.owner != owner || mutable.value.fileDownloads.none { it.id == file.id }) return
        TorrentOfflineDownloads.remove(getApplication(), TorrentOfflineDownloads.PREFIX + file.id) {
            viewModelScope.launch { refreshFileDownloads() }
        }
    }
    fun openFileDownload(file: TorrentDownloadJob) {
        if (file.owner != owner || mutable.value.fileDownloads.none { it.id == file.id }) return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val uri = withContext(Dispatchers.IO) { TorrentOfflineDownloads.fileUri(app, file) }
            if (uri == null) {
                mutable.update { it.copy(notice = "This file has not finished downloading.") }
                return@launch
            }
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "application/octet-stream"
            runCatching {
                app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }.onFailure { mutable.update { it.copy(notice = "Install an app that can open ${file.extension.uppercase()} files.") } }
        }
    }

    private fun refreshDownloads() {
        val entries = mutable.value.downloads
        viewModelScope.launch {
            val information = withContext(Dispatchers.IO) { entries.associate { entry ->
                entry.key to OfflineMediaDownloads.info(getApplication(), entry.requestId, entry.downloadId)
            } }
            mutable.update { it.copy(downloadInfo = information) }
        }
    }

    fun removeDownload(entry: OfflineMediaDownload) {
        val captured = owner ?: return
        if (mutable.value.downloads.none { it.key == entry.key && it.requestId == entry.requestId }) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { OfflineMediaDownloads.remove(getApplication(), entry.requestId, entry.downloadId) }
                TorrentOfflineLibrary.forget(getApplication(), captured, entry)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutable.update { it.copy(error = "Could not remove this download. Please try again.") } }
        }
    }

    fun playDownload(entry: OfflineMediaDownload) {
        if (mutable.value.downloads.none { it.key == entry.key && it.requestId == entry.requestId }) return
        cancelLibraryMetadata(); pauseHomeLoading()
        libraryPlayback = true
        titleOrigin = "download:${entry.key}"
        launch("Opening offline video…") { token ->
            val uri = withContext(Dispatchers.IO) { OfflineMediaDownloads.playableUri(getApplication(), entry.requestId, entry.sourceUrl, entry.downloadId) }
            if (token != generation) return@launch
            if (uri == null) { mutable.update { it.copy(error = "This download is not available yet.") }; refreshDownloads(); return@launch }
            playDownloadInternal(entry, uri, token)
        }
    }

    fun openDownload(key: String) {
        val captured = owner ?: run { mutable.update { it.copy(error = "Open your profile to access this download.") }; return }
        viewModelScope.launch {
            val entries = ProfileStore(getApplication()).scoped(captured).offlineDownloads.first()
                .filter { it.profileKey == TORRENT_OFFLINE_PROFILE }
            mutable.update { it.copy(downloads = entries) }
            val entry = entries.firstOrNull { it.key == key }
            if (entry == null) mutable.update { it.copy(error = "This download is not available for this profile.") }
            else playDownload(entry)
        }
    }

    /** Lookup and file validation precede any TMDB/provider network request. */
    private suspend fun completedDownload(card: DiscoveryCard, episode: TmdbEpisode?): Pair<OfflineMediaDownload, String>? {
        val captured = owner ?: return null
        val entries = ProfileStore(getApplication()).scoped(captured).offlineDownloads.first()
            .filter { torrentOfflineMatches(it, card, episode) }
        return withContext(Dispatchers.IO) {
            entries.firstNotNullOfOrNull { entry -> OfflineMediaDownloads.playableUri(getApplication(), entry.requestId, entry.sourceUrl, entry.downloadId)?.let { entry to it } }
        }
    }

    private suspend fun playDownloadInternal(entry: OfflineMediaDownload, uri: String, token: Long, requestedPosition: Long = 0) {
        val captured = owner ?: return
        val metadata = TorrentOfflineLibrary.metadata(getApplication(), captured, entry)
            ?: entry.media.externalTmdbId?.let { id -> TorrentLibraryEntry(
                torrentLibraryKey(id, entry.catalogType == CatalogType.SERIES), id,
                entry.series?.title ?: entry.media.title, series = entry.catalogType == CatalogType.SERIES,
                poster = entry.series?.logo ?: entry.media.logo, overview = entry.series?.description ?: entry.media.description,
                season = entry.media.seasonNumber, episode = entry.media.episodeNumber,
                episodeName = entry.media.title, episodeAirDate = entry.media.episodeAirDate, episodeStill = entry.media.logo) }
            ?: throw IllegalStateException("This download has no movie or episode identity.")
        if (token != generation) return
        saveProgress(force = true)
        nextPlanJob?.cancel(); stopNextPreparation(); stopTransfer()
        offlineMetadata = metadata
        lastSource = null
        lastFile = TorrentVideoFile(-1, metadata.fileName ?: entry.media.title + "." + entry.fileType.lowercase(), metadata.sizeBytes ?: 0L)
        val card = metadata.card
        if (card == null) {
            val known = mutable.value.library.continueWatching.firstOrNull { it.key == metadata.key }
            val resume = requestedPosition.takeIf { it > 0 } ?: known?.positionMillis ?: 0L
            pendingResume = null
            mutable.update { it.copy(title = null, episode = null, sources = null, files = emptyList(), transfer = null,
                playing = makePlaying(entry.media, uri, lastFile!!, resume, null, null).copy(offlinePlayback = true),
                error = null, nextTarget = null, nextStatus = null, nextReady = false, advanceRequested = false) }
            downloaded.value = null
            videoStarted = false
            return
        }
        val episode = metadata.tmdbEpisode
        val existing = mutable.value
        if (!libraryPlayback) {
            if (episode == null) libraryPlayback = true else offlineEpisodeReturn = true
        }
        val sequence = if (card.series) TorrentOfflineLibrary.sequence(getApplication(), captured, card.id) else TorrentOfflineSequence()
        if (token != generation) return
        val localEpisodes = if (episode != null && episode.seasonNumber in sequence.completeSeasons)
            sequence.episodes.map(TorrentOfflineEpisode::episode) else listOfNotNull(episode)
        val known = mutable.value.library.continueWatching.firstOrNull { it.key == metadata.key &&
            (!metadata.series || it.season == metadata.season && it.episode == metadata.episode) }
        val resume = requestedPosition.takeIf { it > 0 } ?: known?.positionMillis ?: 0L
        pendingResume = null
        val keepEpisodes = existing.title?.key == card.key && episode != null && episodeCache.containsKey(card.id to episode.seasonNumber)
        mutable.update { it.copy(title = card, episode = episode, season = episode?.seasonNumber,
            seasons = if (keepEpisodes) it.seasons else sequence.seasons.ifEmpty { localEpisodes.map { ep -> ep.seasonNumber }.distinct() },
            episodes = if (keepEpisodes) it.episodes else localEpisodes,
            sources = null, selectedSource = null, files = emptyList(), transfer = null, returnFocus = null,
            playing = makePlaying(entry.media, uri, lastFile!!, resume, card, episode).copy(offlinePlayback = true,
                episodeQueue = (if (keepEpisodes) it.episodes else localEpisodes).map { ep -> episodeMedia(TorrentEpisodeTarget(card, ep)) }),
            nextTarget = null, nextReady = false, nextStatus = null, advanceRequested = false, error = null) }
        videoStarted = false
        downloaded.value = null
        if (keepEpisodes || episode?.seasonNumber in sequence.completeSeasons) planNextEpisode()
        if (card.series && episode != null) {
            // Refresh the actual sequence beside local playback. A sparse set of
            // downloaded files must never make Next silently skip missing episodes.
            val active = mutable.value.playing?.progressKey
            libraryMetadataJob = viewModelScope.launch {
                try {
                    val seasons = repository.seasons(card)
                    val episodes = episodesFor(card, episode.seasonNumber)
                    if (mutable.value.playing?.progressKey != active) return@launch
                    mutable.update { it.copy(seasons = seasons, episodes = episodes,
                        playing = it.playing?.copy(episodeQueue = episodes.map { ep -> episodeMedia(TorrentEpisodeTarget(card, ep)) })) }
                    planNextEpisode()
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Completed local playback remains fully usable offline. */ }
            }
        }
    }

    private fun applyDiscoveryConfiguration(surface: DashboardSurface) {
        val configuration = discoveryConfigurations.getValue(surface)
        mutable.value.homeData.shelves.forEach { shelfCache[it.section] = it }
        pauseHomeLoading()
        val differentCatalog = homeCatalogSeries != (surface == DashboardSurface.SERIES)
        homeCatalogSeries = surface == DashboardSurface.SERIES
        mutable.update { it.copy(discoveryConfiguration = configuration,
            homeData = it.homeData.copy(shelves = configuration.sections.map { section -> shelfCache[section] ?: TorrentHomeShelf(section) }, heroIndex = 0,
                genres = if (differentCatalog) emptyList() else it.homeData.genres,
                genresLoaded = !differentCatalog && it.homeData.genresLoaded,
                providers = if (differentCatalog) emptyList() else it.homeData.providers,
                providersLoaded = !differentCatalog && it.homeData.providersLoaded,
                genreArtwork = if (differentCatalog) emptyMap() else it.homeData.genreArtwork)) }
        if (mutable.value.browsingFeeds && mutable.value.title == null) configuration.sections.take(2).forEach(::homeShelf)
    }

    private fun updateHome(change: (TorrentHomeState) -> TorrentHomeState) {
        mutable.update { it.copy(homeData = change(it.homeData)) }
        mutable.value.homeData.shelves.forEach { shelfCache[it.section] = it }
    }

    private fun pauseHomeLoading() {
        homeJobs.values.toList().forEach { it.cancel() }
        homeJobs.clear()
    }

    private fun cancelLibraryMetadata() {
        libraryMetadataJob?.cancel()
        libraryMetadataJob = null
        explicitSourceChoice = false
    }

    fun homeHero(index: Int) = updateHome { home ->
        home.copy(heroIndex = index.coerceIn(0, (home.heroes.size - 1).coerceAtLeast(0)))
    }

    fun homeShelf(section: TmdbHomeSection) {
        if (section !in mutable.value.discoveryConfiguration.sections) return
        val key = "feed:${section.name}"
        val shelf = mutable.value.homeData.shelves.firstOrNull { it.section == section } ?: return
        if (homeJobs[key]?.isActive == true || (shelf.loaded && shelf.error == null)) return
        fun publish(page: DiscoveryFeedPage) = updateHome { home -> home.copy(shelves = home.shelves.map {
            if (it.section == section) it.copy(cards = page.cards, hasMore = page.hasMore, loaded = true) else it
        }) }
        updateHome { home -> home.copy(shelves = home.shelves.map {
            if (it.section == section) it.copy(loading = true, error = null) else it
        }) }
        homeJobs[key] = viewModelScope.launch {
            try { publish(homeRepository.feed(section, ::publish)) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { updateHome { home -> home.copy(shelves = home.shelves.map {
                if (it.section == section) it.copy(error = error.message ?: "Could not load these titles.") else it
            }) } }
            finally { updateHome { home -> home.copy(shelves = home.shelves.map {
                if (it.section == section) it.copy(loading = false) else it
            }) } }
        }
    }

    fun homeGenres() {
        val current = mutable.value.homeData
        if (homeJobs["genres"]?.isActive == true || (current.genresLoaded && current.genresError == null)) return
        updateHome { it.copy(genresLoading = true, genresError = null) }
        homeJobs["genres"] = viewModelScope.launch {
            try { val genres = homeRepository.genres(mutable.value.surface == DashboardSurface.SERIES); updateHome { it.copy(genres = genres, genresLoaded = true) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { updateHome { it.copy(genresError = error.message ?: "Could not load genres.") } }
            finally { updateHome { it.copy(genresLoading = false) } }
        }
    }

    fun homeProviders() {
        val current = mutable.value.homeData
        if (homeJobs["providers"]?.isActive == true || (current.providersLoaded && current.providersError == null)) return
        updateHome { it.copy(providersLoading = true, providersError = null) }
        homeJobs["providers"] = viewModelScope.launch {
            try { val providers = homeRepository.providers(mutable.value.surface == DashboardSurface.SERIES); updateHome { it.copy(providers = providers, providersLoaded = true) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { updateHome { it.copy(providersError = error.message ?: "Could not load providers.") } }
            finally { updateHome { it.copy(providersLoading = false) } }
        }
    }

    fun homeGenreArtwork(key: GenreArtworkKey) {
        if (key in mutable.value.homeData.genreArtwork || homeJobs["art:$key"]?.isActive == true) return
        homeJobs["art:$key"] = viewModelScope.launch {
            try { val artwork = homeRepository.genreArtwork(key); updateHome { it.copy(genreArtwork = it.genreArtwork + (key to artwork)) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { updateHome { it.copy(genreArtwork = it.genreArtwork + (key to null)) } }
        }
    }

    fun homeCollection(target: TorrentHomeCollection, originKey: String) {
        runCatching { target.validate() }.getOrElse { error ->
            mutable.update { it.copy(error = error.message ?: "Choose another collection.") }; return
        }
        cancelLibraryMetadata()
        pendingResume = null; libraryPlayback = false
        collectionOrigin = originKey
        collectionOriginSurface = mutable.value.surface
        pauseHomeLoading()
        mutable.update { it.copy(directSources = null, home = false, homeCollection = target, libraryPage = null, downloadsOpen = false, series = target.series, title = null,
            titles = emptyList(), sources = null, query = "", collectionPage = 0, collectionHasMore = false,
            returnFocus = null, error = null) }
        loadHomeCollection(target, 1)
    }

    fun loadMoreHome() {
        val current = mutable.value
        val target = current.homeCollection ?: return
        if (current.loading != null || !current.collectionHasMore) return
        loadHomeCollection(target, current.collectionPage + 1)
    }

    fun retryHomeCollection() {
        val target = mutable.value.homeCollection ?: return
        loadHomeCollection(target, 1)
    }

    private fun loadHomeCollection(target: TorrentHomeCollection, page: Int) {
        val previous = if (page == 1) emptyList() else mutable.value.titles
        launch(if (page == 1) "Loading ${target.title}…" else "Loading more titles…") { token ->
            fun publish(result: WatchProviderPage) {
                if (token == generation && mutable.value.homeCollection == target) mutable.update {
                    it.copy(titles = (previous + result.cards).distinctBy(DiscoveryCard::key), collectionPage = page,
                        collectionHasMore = page < result.totalPages && page < 500)
                }
            }
            publish(homeRepository.collection(target, page, ::publish))
        }
    }

    fun toggleSaved(card: DiscoveryCard) {
        toggleSaved(torrentLibraryEntry(card = card))
    }

    fun toggleSaved(entry: TorrentLibraryEntry) = changeLibrary { app, captured ->
        TorrentLibraryStore.toggleSaved(app, captured, entry)
    }

    fun removeContinueWatching(entry: TorrentLibraryEntry) {
        changeLibrary { app, captured -> TorrentLibraryStore.removeProgress(app, captured, entry) }
    }

    fun removeSaved(entry: TorrentLibraryEntry) {
        changeLibrary { app, captured -> TorrentLibraryStore.removeSaved(app, captured, entry) }
    }

    private fun changeLibrary(action: suspend (Application, UserScope) -> Unit) {
        val captured = owner ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            try { action(app, captured); AccountSyncManager.get(app).requestProfileSync(captured.accountId) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutable.update { it.copy(error = "Could not save the Torrent library. Please try again.") } }
        }
    }

    fun openLibraryPage(page: TorrentLibraryPage, originKey: String) {
        if (mutable.value.libraryPage == page && !mutable.value.downloadsOpen && mutable.value.title == null) return
        cancelLibraryMetadata()
        job?.cancel(); generation++; pauseHomeLoading()
        libraryOrigin = originKey
        pendingResume = null; libraryPlayback = false
        mutable.update { it.copy(directSources = null, home = false, homeCollection = null, libraryPage = page, downloadsOpen = false, title = null,
            sources = null, query = "", titles = emptyList(), loading = null, error = null, returnFocus = null) }
    }

    /** Rebuild a session from identity. Device-local descriptor URLs never enter sync. */
    fun resumeLibrary(entry: TorrentLibraryEntry, originKey: String? = null) {
        cancelLibraryMetadata()
        pauseHomeLoading()
        pendingResume = entry; libraryPlayback = true
        titleOrigin = originKey ?: "home:continue:${entry.key}"
        val card = entry.card
        mutable.update { it.copy(title = card, seasons = emptyList(), episodes = emptyList(), season = entry.season,
            episode = entry.tmdbEpisode, sources = null, selectedSource = null, error = null, returnFocus = null) }
        if (card == null) {
            launch("Reopening torrent…") { token ->
                val captured = owner
                val local = if (captured == null) null else mutable.value.downloads.firstOrNull {
                    it.media.id.startsWith("torrent:raw:${entry.infoHash}:") &&
                        TorrentOfflineLibrary.metadata(getApplication(), captured, it)?.fileName == entry.fileName
                }
                val localUri = local?.let { withContext(Dispatchers.IO) {
                    OfflineMediaDownloads.playableUri(getApplication(), it.requestId, it.sourceUrl, it.downloadId)
                } }
                if (token != generation) return@launch
                if (local != null && localUri != null) { playDownloadInternal(local, localUri, token, entry.positionMillis); return@launch }
                val stored = owner?.let { TorrentLibraryStore.source(getApplication(), it, entry.key) }
                val source = stored ?: validatedTorrentHash(entry.infoHash)?.let { torrentMagnet(it, entry.title) }
                if (token != generation) return@launch
                if (source != null) prepare(source)
                else mutable.update { it.copy(error = "Open the torrent link again to resume this video.", returnFocus = "manual") }
            }
        } else if (!card.series) sources()
        else {
            val savedEpisode = entry.tmdbEpisode
            mutable.update { it.copy(seasons = listOfNotNull(entry.season), episodes = listOfNotNull(savedEpisode)) }
            // Saved identity is sufficient for source lookup. Refresh the full
            // season list alongside it instead of delaying resume on metadata.
            if (savedEpisode != null) sources()
            libraryMetadataJob?.cancel()
            libraryMetadataJob = viewModelScope.launch {
                try {
                    val seasons = repository.seasons(card)
                    val number = entry.season?.takeIf { it in seasons } ?: seasons.firstOrNull()
                    val episodes = number?.let { episodesFor(card, it) }.orEmpty()
                    if (mutable.value.title?.key != card.key) return@launch
                    mutable.update { it.copy(seasons = seasons, season = number, episodes = episodes) }
                    if (savedEpisode == null) mutable.update { it.copy(error = "Choose an episode to continue.") }
                    else if (mutable.value.playing != null && mutable.value.nextTarget == null) planNextEpisode()
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Keep the usable saved episode when metadata is unavailable. */ }
            }
        }
    }

    fun progress(identity: String, position: Long, duration: Long) {
        val current = mutable.value.playing ?: return
        if (identity != current.progressKey) return
        if (position > 0) playbackStarted()
        if (progressIdentity != current.progressKey) {
            progressIdentity = current.progressKey; progressSavedAt = 0L
            progressPosition = 0L; progressDuration = 0L
        }
        progressPosition = position.coerceAtLeast(0)
        if (duration > 0) progressDuration = duration
        saveProgress()
    }

    private fun progressEntry(): TorrentLibraryEntry? {
        val current = mutable.value
        val playing = current.playing ?: return null
        if (progressIdentity != playing.progressKey || progressPosition < 5_000) return null
        return torrentLibraryEntry(current.title, current.episode, lastFile, engine?.sourceInfoHash ?: offlineMetadata?.infoHash,
            progressPosition, progressDuration, playing.media.title)
    }

    private fun saveProgress(force: Boolean = false) {
        val captured = owner ?: return
        val entry = progressEntry() ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && progressSavedAt != 0L && now - progressSavedAt < 30_000) return
        progressSavedAt = now
        val app = getApplication<Application>()
        val localSource = lastSource.takeIf { entry.card == null }
        // These writes cannot delay Next or teardown, and survive this VM's
        // disposal. They retain their captured viewing owner throughout.
        persistenceScope.launch {
            runCatching {
                if (localSource != null) TorrentLibraryStore.rememberSource(app, captured, entry.key, localSource)
                TorrentLibraryStore.progress(app, captured, entry)
                AccountSyncManager.get(app).requestProfileSync(captured.accountId)
            }
        }
    }

    private fun safeLibraryOrigin(key: String?, exclude: String? = null): String? {
        key ?: return null
        val library = mutable.value.library
        fun homeFallback() = if (mutable.value.homeData.hero != null) "home:hero" else "search"
        val prefix = "home:continue:"
        if (key.startsWith(prefix)) {
            val eligible = library.continueWatching.filterNot { it.key == exclude }
            val id = key.removePrefix(prefix)
            if (id == "all" && eligible.size > 10 || eligible.take(10).any { it.key == id }) return key
            return eligible.firstOrNull()?.let { "$prefix${it.key}" } ?: homeFallback()
        }
        if (key.startsWith("download:")) return key.takeIf { origin -> mutable.value.downloads.any { origin == "download:${it.key}" } } ?: "downloads-back"
        if (key.startsWith("library:")) {
            val rows = if (mutable.value.libraryPage == TorrentLibraryPage.CONTINUE_WATCHING) library.continueWatching else library.saved
            return key.takeIf { origin -> rows.any {
                (mutable.value.libraryPage != TorrentLibraryPage.CONTINUE_WATCHING || it.key != exclude) && origin.endsWith(":${it.key}")
            } } ?: "library-back"
        }
        return key
    }

    private fun launch(label: String, action: suspend (Long) -> Unit) {
        job?.cancel()
        val token = ++generation
        mutable.update { it.copy(loading = label, error = null) }
        job = viewModelScope.launch {
            try { action(token) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (token == generation) mutable.update { it.copy(error = error.message ?: "Please try again.") } }
            finally { if (token == generation) mutable.update { it.copy(loading = null) } }
        }
    }
    fun search() {
        cancelLibraryMetadata()
        val current = mutable.value
        pauseHomeLoading()
        pendingResume = null; libraryPlayback = false
        mutable.update { it.copy(home = false, homeCollection = null, libraryPage = null, downloadsOpen = false) }
        if (current.query.isBlank()) {
            job?.cancel(); generation++
            mutable.update { it.copy(title = null, sources = null, directSources = null, titles = emptyList(), loading = null, error = null) }
            applyDiscoveryConfiguration(mutable.value.surface)
            return
        }
        if (current.advancedSearch) {
            mutable.update { it.copy(directSources = TorrentSearchSnapshot(), directSearchRevision = it.directSearchRevision + 1,
                title = null, sources = null, titles = emptyList()) }
            launch("Searching torrent websites…") { token ->
                val result = repository.searchDirect(current.query, torrentSearchScope(current.surface)) { update ->
                    if (token == generation) mutable.update { it.copy(directSources = update) }
                }
                if (token == generation) mutable.update { it.copy(directSources = result, returnFocus = null) }
            }
            return
        }
        launch(if (current.query.isBlank()) "Loading popular titles…" else "Searching TMDB…") { token ->
            val titles = if (current.surface == DashboardSurface.HOME) coroutineScope {
                val movies = async { repository.titles(current.query.trim(), false) }
                val series = async { repository.titles(current.query.trim(), true) }
                val films = movies.await()
                val shows = series.await()
                (0 until maxOf(films.size, shows.size)).flatMap { index ->
                    listOfNotNull(films.getOrNull(index), shows.getOrNull(index))
                }.distinctBy(DiscoveryCard::key)
            } else repository.titles(current.query.trim(), current.surface == DashboardSurface.SERIES)
            if (token == generation) mutable.update { it.copy(titles = titles, title = null, sources = null, returnFocus = null) }
        }
    }
    fun open(card: DiscoveryCard, originKey: String? = null) {
        detailParents.clear()
        cancelLibraryMetadata()
        pauseHomeLoading()
        pendingResume = null; libraryPlayback = false
        titleOrigin = originKey ?: "title:${card.key}"
        mutable.update { it.copy(title = card, seasons = emptyList(), season = null, episodes = emptyList(), episode = null,
            sources = null, error = null, returnFocus = null) }
        if (!card.series) sources() else launch("Loading seasons…") { token ->
            val seasons = repository.seasons(card)
            if (token == generation) mutable.update { it.copy(seasons = seasons, season = seasons.firstOrNull()) }
            seasons.firstOrNull()?.let { number ->
                val episodes = episodesFor(card, number)
                if (token == generation) mutable.update { it.copy(episodes = episodes) }
            }
        }
    }
    fun openRelated(card: DiscoveryCard) {
        val current = mutable.value
        if (current.title?.key == card.key || !card.torrentPublished()) return
        val parents = detailParents.toList() + (current to titleOrigin)
        open(card)
        detailParents.addAll(parents.takeLast(32))
    }
    fun season(number: Int) {
        val card = mutable.value.title ?: return
        cancelLibraryMetadata()
        pendingResume = null
        mutable.update { it.copy(season = number, episodes = emptyList(), sources = null, episode = null) }
        launch("Loading season $number…") { token ->
            val episodes = episodesFor(card, number)
            if (token == generation) mutable.update { it.copy(episodes = episodes) }
        }
    }
    fun episode(value: TmdbEpisode) {
        cancelLibraryMetadata()
        pendingResume = null
        mutable.update { it.copy(episode = value) }
        sources()
    }
    fun sources() {
        val card = mutable.value.title ?: return
        val episode = mutable.value.episode
        mutable.update { it.copy(sources = TorrentSearchSnapshot(emptyList(), emptySet(), emptyMap()), files = emptyList()) }
        launch("Checking offline copy…") { token ->
            val offline = if (explicitSourceChoice) null else completedDownload(card, episode)
            if (token != generation) return@launch
            if (offline != null) {
                playDownloadInternal(offline.first, offline.second, token, pendingResume?.positionMillis ?: 0L)
                return@launch
            }
            mutable.update { it.copy(loading = "Searching torrent sources…") }
            val results = repository.searchSources(card, episode) { update ->
                if (token == generation) mutable.update { it.copy(sources = update) }
            }
            if (token == generation) {
                mutable.update { it.copy(sources = results) }
                pendingResume?.infoHash?.takeUnless { explicitSourceChoice }?.let { preferred -> results.results.firstOrNull { it.infoHash == preferred } }
                    ?.let(::source)
            }
        }
    }
    fun source(source: TorrentSource) {
        if (!source.kind.media) { download(source); return }
        downloadTarget = null
        mutable.update { it.copy(selectedSource = source, transferPurpose = TorrentTransferPurpose.STREAM, returnFocus = "source:${source.infoHash}") }
        val current = mutable.value
        val card = current.title
        if (card == null) {
            launch("Checking offline copy…") { token ->
                val offline = mutable.value.downloads.firstOrNull { it.media.id.startsWith("torrent:raw:${source.infoHash}:") }
                val uri = offline?.let { withContext(Dispatchers.IO) {
                    OfflineMediaDownloads.playableUri(getApplication(), it.requestId, it.sourceUrl, it.downloadId)
                } }
                if (token != generation) return@launch
                if (offline != null && uri != null) playDownloadInternal(offline, uri, token)
                else prepare(source.source)
            }
            return
        }
        launch("Checking offline copy…") { token ->
            val offline = if (explicitSourceChoice) null else completedDownload(card, current.episode)
            if (token != generation) return@launch
            if (offline != null) playDownloadInternal(offline.first, offline.second, token, pendingResume?.positionMillis ?: 0L)
            else prepare(source.source)
        }
    }
    fun download(source: TorrentSource) {
        val card = mutable.value.title
        if (owner == null) { mutable.update { it.copy(error = "Open a profile before downloading.") }; return }
        val episode = mutable.value.episode
        if (card?.series == true && episode == null) return
        cancelLibraryMetadata()
        pendingResume = null
        downloadTarget = TorrentDownloadTarget(card, episode, source)
        mutable.update { it.copy(selectedSource = source, transferPurpose = TorrentTransferPurpose.DOWNLOAD, returnFocus = "source:${source.infoHash}") }
        prepare(source.source)
    }
    fun chooseSource(position: Long) {
        val current = mutable.value
        val card = current.title ?: return
        val saved = (progressEntry() ?: current.library.continueWatching.firstOrNull {
            it.tmdbId == card.id && it.series == card.series &&
                (!card.series || it.season == current.episode?.seasonNumber && it.episode == current.episode?.episodeNumber)
        })?.let { if (position > 0) it.copy(positionMillis = position) else it }
        val fromLibrary = libraryPlayback
        exitPlayer()
        libraryPlayback = fromLibrary
        pendingResume = saved
        explicitSourceChoice = true
        mutable.update { it.copy(title = card, episode = current.episode, seasons = current.seasons,
            season = current.season, episodes = current.episodes, sources = current.sources,
            returnFocus = current.selectedSource?.let { source -> "source:${source.infoHash}" }, transferPurpose = TorrentTransferPurpose.STREAM) }
        if (current.sources?.results.isNullOrEmpty()) sources()
    }
    fun manual(source: String) {
        cancelLibraryMetadata()
        pendingResume = null; libraryPlayback = false
        downloadTarget = null
        // The generic link entry has no verified TMDB association. Never attach
        // its file/progress to a title or episode left behind by navigation.
        mutable.update { it.copy(title = null, episode = null, season = null, seasons = emptyList(), episodes = emptyList(),
            sources = null, selectedSource = null, transferPurpose = TorrentTransferPurpose.STREAM, returnFocus = "manual") }
        prepare(source)
    }
    fun prepare(source: String) {
        pauseHomeLoading()
        stopNextPreparation(); nextPlanJob?.cancel()
        videoStarted = false
        offlineMetadata = null
        stopTransfer()
        val closing = lastClosingEngine
        mutable.update { it.copy(transfer = TorrentStreamStatus(stage = "Connecting to torrent…"), files = emptyList(), error = null) }
        lastSource = source.trim()
        val selected = TorrentStreamEngine(getApplication(), streamOptions().copy(
            allowDownloadFiles = mutable.value.transferPurpose == TorrentTransferPurpose.DOWNLOAD && downloadTarget?.card == null))
        engine = selected
        observeTransfer(selected)
        launch("Connecting to torrent…") { token ->
            closing?.closeAndAwait()
            val files = selected.prepare(source.trim())
            if (token != generation || engine !== selected) return@launch
            mutable.update { it.copy(files = files) }
            val episode = mutable.value.episode
            val matching = episode?.let { ep -> files.filter { torrentEpisodeFileMatches(it.name, ep.seasonNumber, ep.episodeNumber) } }
            val remembered = pendingResume?.let { saved -> files.singleOrNull { file ->
                file.name.substringAfterLast('/').substringAfterLast('\\') == saved.fileName && file.sizeBytes == saved.sizeBytes
            } }
            val automatic = if (episode != null) matching?.singleOrNull() else remembered ?: files.singleOrNull()
            if (automatic != null) selectFile(selected, automatic, token)
        }
    }
    fun file(value: TorrentVideoFile) {
        val selected = engine ?: return
        launch(if (mutable.value.transferPurpose == TorrentTransferPurpose.DOWNLOAD) "Queuing download…" else "Buffering video…") { token -> selectFile(selected, value, token) }
    }
    private suspend fun selectFile(selected: TorrentStreamEngine, file: TorrentVideoFile, token: Long) {
        if (mutable.value.transferPurpose == TorrentTransferPurpose.STREAM) { startFile(selected, file, token); return }
        val target = downloadTarget ?: return
        val captured = owner ?: return
        val app = getApplication<Application>()
        val card = target.card
        val metadataHash = selected.sourceInfoHash ?: target.source.infoHash
        val identity = if (card == null) "torrent:raw:$metadataHash:${file.index}" else torrentOfflineMediaId(card, target.episode)
        val existing = mutable.value.downloads.firstOrNull { it.media.id == identity && it.profileKey == TORRENT_OFFLINE_PROFILE }
        val priorFile = if (!file.media) withContext(Dispatchers.IO) {
            TorrentOfflineDownloads.files(app, captured).firstOrNull { it.identity == identity }
        } else null
        if (priorFile != null && priorFile.state !in setOf("failed", "cancelled")) {
            stopTransfer()
            mutable.update { it.copy(files = emptyList(), transfer = null, notice = "This file is already in Torrent downloads.") }
            refreshFileDownloads()
            return
        }
        if (existing != null) {
            val info = withContext(Dispatchers.IO) { OfflineMediaDownloads.info(app, existing.requestId, existing.downloadId) }
            if (token != generation || engine !== selected) return
            if (info.status !in setOf(OfflineDownloadStatus.FAILED, OfflineDownloadStatus.MISSING)) {
                stopTransfer()
                mutable.update { it.copy(files = emptyList(), transfer = null, notice = if (info.status == OfflineDownloadStatus.COMPLETE) "Already available in Offline downloads." else "This video is already downloading.") }
                return
            }
        }
        var handle: com.nikhil.niktv.data.OfflineDownloadHandle? = null
        var registration: OfflineMediaDownload? = null
        var committed = false
        val cachedSeasons = episodeCache.filterKeys { it.first == card?.id }.mapKeys { it.key.second }
        val seasons = mutable.value.seasons
        try {
            // Cancellation during the enqueue/metadata boundary must still own
            // the new request and clean it up, rather than abandon a hidden worker.
            withContext(NonCancellable + Dispatchers.IO) {
                val title = if (card == null) file.name.substringAfterLast('/')
                    else if (target.episode == null) card.title else "${card.title} · S${target.episode.seasonNumber}:E${target.episode.episodeNumber}"
                handle = TorrentOfflineDownloads.enqueue(app, identity, title,
                    target.source.source, file.index, file.name, file.sizeBytes, captured, genericFile = !file.media)
                registration = when {
                    !file.media -> null
                    card == null -> TorrentOfflineLibrary.registerRaw(app, captured, handle!!, file, metadataHash)
                    else -> TorrentOfflineLibrary.register(app, captured, handle!!, card, target.episode, file, metadataHash)
                }
                if (card?.series == true) TorrentOfflineLibrary.rememberSequence(app, captured, card.id, seasons, cachedSeasons)
            }
            currentCoroutineContext().ensureActive()
            if (token != generation || engine !== selected) return
            committed = true
        } finally {
            if (!committed) withContext(NonCancellable + Dispatchers.IO) {
                handle?.let { OfflineMediaDownloads.remove(app, it.requestId) }
                registration?.let { TorrentOfflineLibrary.forget(app, captured, it) }
            }
        }
        stopTransfer()
        downloadTarget = null
        mutable.update { it.copy(files = emptyList(), transfer = null, notice = if (file.media) "Download queued. Find it in Offline downloads." else "File queued. Find it in Torrent downloads.") }
        refreshFileDownloads()
        if (priorFile != null) TorrentOfflineDownloads.remove(app, TorrentOfflineDownloads.PREFIX + priorFile.id)
        if (existing != null) persistenceScope.launch { OfflineMediaDownloads.remove(app, existing.requestId, existing.downloadId) }
    }
    private suspend fun startFile(selected: TorrentStreamEngine, file: TorrentVideoFile, token: Long, position: Long = 0) {
        val url = selected.play(file.index)
        if (token != generation || engine !== selected) return
        lastFile = file
        val current = mutable.value
        val saved = pendingResume
        val sameManualFile = saved?.let { torrentCanResume(it, current.title, current.episode, file, selected.sourceInfoHash) } == true
        val resumePosition = if (position > 0) position else saved?.positionMillis?.takeIf { sameManualFile } ?: 0L
        pendingResume = null
        explicitSourceChoice = false
        val title = current.title
        val episode = current.episode
        val display = if (episode != null) "${title?.title.orEmpty()} · S${episode.seasonNumber}:E${episode.episodeNumber} · ${episode.name.orEmpty()}"
            else title?.title ?: file.name.substringAfterLast('/')
        val media = if (title != null && episode != null) episodeMedia(TorrentEpisodeTarget(title, episode)).copy(title = display)
            else title?.media?.copy(id = "torrent:tmdb:movie:${title.id}", title = display)
                ?: MediaItem("torrent:manual:${lastSource.hashCode()}:${file.index}", display, null, null)
        videoStarted = false
        mutable.update { it.copy(playing = makePlaying(media, url, file, resumePosition, title, episode),
            nextTarget = null, nextStatus = null, nextReady = false, advanceRequested = false) }
        planNextEpisode()
    }
    fun retry(position: Long) {
        val current = mutable.value
        val media = current.playing ?: return
        val file = lastFile ?: return
        // A healthy torrent lease already owns its loopback route. Reopening the
        // player's connection must not try to select/start the torrent twice.
        if (media.offlinePlayback || engine != null && current.transfer?.error == null) {
            mutable.update { it.copy(playing = media.copy(resumePositionMillis = position.coerceAtLeast(0),
                playbackAttemptId = java.util.UUID.randomUUID().toString(), decoderSessionId = java.util.UUID.randomUUID().toString())) }
            return
        }
        val source = lastSource ?: return
        stopNextPreparation(); nextPlanJob?.cancel()
        stopTransfer()
        val closing = lastClosingEngine
        val selected = TorrentStreamEngine(getApplication(), streamOptions())
        engine = selected
        mutable.update { it.copy(playing = null) }
        observeTransfer(selected)
        launch("Reconnecting to torrent…") { token ->
            closing?.closeAndAwait()
            val files = selected.prepare(source)
            if (token != generation || engine !== selected) return@launch
            val sameFile = files.singleOrNull { it.index == file.index && it.name == file.name && it.sizeBytes == file.sizeBytes }
            if (sameFile != null) startFile(selected, sameFile, token, position)
            else mutable.update { it.copy(files = files, error = "Choose the video again to continue.") }
        }
    }
    fun playbackEngine(value: PlaybackEngine) { mutable.update { it.copy(playbackEngine = value) }; viewModelScope.launch { ProfileStore(getApplication()).setPlaybackEngine(value) } }
    fun retryDecoder(position: Long) {
        // Match the shared player's bounded decoder recovery. It excludes the
        // failed codec internally; changing a global engine preference here
        // would bypass that policy and affect other portals.
        mutable.update { state -> state.copy(playing = state.playing?.let {
            it.copy(decoderRecoveryCount = (it.decoderRecoveryCount + 1).coerceAtMost(3))
        }) }
        retry(position)
    }
    fun exitPlayer() {
        cancelLibraryMetadata()
        val wasPlaying = mutable.value.playing != null
        val completedKey = progressEntry()?.takeIf { it.completed }?.key
        saveProgress(force = true)
        job?.cancel(); generation++
        nextPlanJob?.cancel(); stopNextPreparation(); videoStarted = false
        stopTransfer()
        val returning = if (wasPlaying && offlineEpisodeReturn) mutable.value.episode?.let { "episode:${it.seasonNumber}:${it.episodeNumber}" }
            else if (wasPlaying && libraryPlayback) safeLibraryOrigin(titleOrigin, completedKey) else mutable.value.returnFocus
        mutable.update { it.copy(playing = null, loading = null, files = emptyList(), transfer = null, error = null,
            title = if (wasPlaying && libraryPlayback) null else it.title,
            sources = if (wasPlaying && libraryPlayback) null else it.sources, returnFocus = returning,
            nextTarget = null, nextStatus = null, nextReady = false, advanceRequested = false) }
        if (wasPlaying) { libraryPlayback = false; pendingResume = null; offlineMetadata = null; offlineEpisodeReturn = false }
        downloaded.value = null
    }
    fun cancelTransfer() { exitPlayer(); explicitSourceChoice = true }
    fun retryTransfer() { lastSource?.let(::prepare) }
    private fun stopTransfer() {
        transferJob?.cancel(); transferJob = null
        engine?.let { lastClosingEngine = it; it.close() }; engine = null
    }
    fun back(): Boolean {
        cancelLibraryMetadata()
        val current = mutable.value
        if (current.playing != null) { exitPlayer(); return true }
        if (current.transfer != null || current.files.isNotEmpty()) { cancelTransfer(); return true }
        job?.cancel(); generation++
        if (current.downloadsOpen) {
            mutable.update { it.copy(downloadsOpen = false, rootSurface = downloadsOriginSurface, home = downloadsOriginSurface == DashboardSurface.HOME,
                series = downloadsOriginSurface == DashboardSurface.SERIES, title = null, sources = null, loading = null, error = null, returnFocus = "downloads") }
            applyDiscoveryConfiguration(downloadsOriginSurface)
            return true
        }
        if (current.sources != null && current.title?.series == true) {
            mutable.update { it.copy(sources = null, episode = null, loading = null, error = null, returnFocus = current.episode?.let { ep -> "episode:${ep.seasonNumber}:${ep.episodeNumber}" }) }
            return true
        }
        if (current.title != null) {
            if (detailParents.isNotEmpty()) {
                val (parent, origin) = detailParents.removeLast()
                titleOrigin = origin
                mutable.update { it.copy(title = parent.title, seasons = parent.seasons, season = parent.season,
                    episodes = parent.episodes, episode = parent.episode, sources = parent.sources,
                    loading = null, error = parent.error, returnFocus = "movie-sources") }
                if (parent.sources?.finished == false) sources()
                return true
            }
            mutable.update { it.copy(title = null, sources = null, loading = null, error = null,
                returnFocus = safeLibraryOrigin(titleOrigin) ?: "title:${current.title.key}") }
            return true
        }
        if (current.homeCollection != null) {
            mutable.update { it.copy(rootSurface = collectionOriginSurface, home = collectionOriginSurface == DashboardSurface.HOME, series = collectionOriginSurface == DashboardSurface.SERIES,
                homeCollection = null, titles = emptyList(), loading = null,
                error = null, returnFocus = collectionOrigin ?: "home:hero") }
            applyDiscoveryConfiguration(collectionOriginSurface)
            return true
        }
        if (current.libraryPage != null) {
            val returning = safeLibraryOrigin(libraryOrigin)
            mutable.update { it.copy(home = it.surface == DashboardSurface.HOME, series = it.surface == DashboardSurface.SERIES,
                libraryPage = null, returnFocus = returning,
                loading = null, error = null) }
            return true
        }
        if (!current.home) { home(); return true }
        return false
    }
    private fun episodeMedia(target: TorrentEpisodeTarget): MediaItem {
        val ep = target.episode
        return MediaItem("torrent:tmdb:${target.card.id}:${ep.seasonNumber}:${ep.episodeNumber}",
            "S${ep.seasonNumber}:E${ep.episodeNumber} · ${ep.name.orEmpty()}", ep.stillUrl ?: target.card.poster, null,
            description = ep.overview, seasonNumber = ep.seasonNumber, episodeNumber = ep.episodeNumber,
            episodeAirDate = ep.airDate)
    }
    private fun makePlaying(media: MediaItem, url: String, file: TorrentVideoFile, position: Long,
        card: DiscoveryCard?, episode: TmdbEpisode?): PlayingMedia = PlayingMedia(
        media.copy(seasonNumber = episode?.seasonNumber, episodeNumber = episode?.episodeNumber), url,
        if (episode != null) CatalogType.SERIES else CatalogType.MOVIES,
        series = card?.takeIf { it.series }?.media,
        episodeQueue = if (card?.series == true) mutable.value.episodes.map { episodeMedia(TorrentEpisodeTarget(card, it)) } else emptyList(),
        resumePositionMillis = position, progressKey = media.id, playbackFormat = file.name.substringAfterLast('.', "mp4"),
        directFullscreen = true, externalPlayback = true, userScope = owner,
        sidecarSubtitles = engine?.subtitles.orEmpty().map {
            PlaybackSidecarSubtitle(id = "torrent-subtitle:${it.index}:${it.name.hashCode()}", label = it.label,
                language = it.language, url = it.url, fileName = it.name.substringAfterLast('/').substringAfterLast('\\'))
        })

    private fun planNextEpisode() {
        nextPlanJob?.cancel(); stopNextPreparation()
        val current = mutable.value
        val card = current.title?.takeIf { it.series } ?: return
        val episode = current.episode ?: return
        val activeMedia = current.playing?.progressKey ?: return
        nextPlanJob = viewModelScope.launch {
            try {
                val target = findNextTorrentEpisode(card, episode, current.seasons, current.episodes,
                    { repository.seasons(card) }, { episodesFor(card, it) })
                if (mutable.value.playing?.progressKey != activeMedia) return@launch
                val offline = target?.let { completedDownload(it.card, it.episode) }
                if (mutable.value.playing?.progressKey != activeMedia) return@launch
                mutable.update { it.copy(nextTarget = target,
                    nextReady = offline != null, nextStatus = if (offline != null) "Next episode is available offline" else null,
                    playing = it.playing?.copy(nextEpisode = target?.let(::episodeMedia))) }
                if (target != null && offline == null && mutable.value.prefetchEnabled && videoStarted) startNextPreparation(background = true)
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { mutable.update { it.copy(nextStatus = "Next episode information is unavailable. Open the episode list to try again.") } }
        }
    }
    fun playbackStarted() {
        if (videoStarted) return
        videoStarted = true
        if (mutable.value.prefetchEnabled) startNextPreparation(background = true)
    }
    fun prefetch(enabled: Boolean) {
        val captured = owner ?: return
        viewModelScope.launch { TorrentPlaybackPreferences.set(getApplication(), captured, enabled) }
    }
    private fun startNextPreparation(background: Boolean) {
        if (mutable.value.nextReady && preparedNext == null) return
        val target = mutable.value.nextTarget ?: return
        val currentEngine = engine ?: return
        if (preparedNext != null || nextPrepareJob?.isActive == true) return
        nextForeground.value = !background
        mutable.update { it.copy(nextStatus = "Looking for the next episode…", nextReady = false, nextFailed = false) }
        nextPrepareJob = viewModelScope.launch {
            try {
                val prepared = prepareTorrentEpisode(getApplication(), repository, target, currentEngine.status,
                    background, mutable.value.selectedSource?.quality, { message ->
                        if (engine === currentEngine) mutable.update { it.copy(nextStatus = message) }
                    }, foreground = nextForeground, streamCacheOwner = streamOptions().streamCacheOwner)
                if (engine !== currentEngine || mutable.value.nextTarget != target) { prepared.close(); return@launch }
                preparedNext = prepared
                mutable.update { it.copy(nextReady = true, nextFailed = false, nextStatus = "Next episode is buffered") }
                if (mutable.value.advanceRequested) switchToPreparedNext()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (engine === currentEngine) mutable.update { it.copy(nextReady = false, nextFailed = true,
                    nextStatus = "Next episode needs another source. ${error.message.orEmpty()}") }
            }
        }
    }
    fun nextEpisode() {
        val target = mutable.value.nextTarget ?: return
        if (mutable.value.advanceRequested) return
        mutable.update { it.copy(advanceRequested = true) }
        val playingKey = mutable.value.playing?.progressKey
        viewModelScope.launch {
            val local = completedDownload(target.card, target.episode)
            if (mutable.value.playing?.progressKey != playingKey) return@launch
            if (local != null) {
                playDownloadInternal(local.first, local.second, generation)
            } else if (mutable.value.playing?.offlinePlayback == true) chooseNextSource()
            else {
                nextForeground.value = true
                if (preparedNext != null) switchToPreparedNext() else startNextPreparation(background = false)
            }
        }
    }
    fun retryNext() {
        stopNextPreparation()
        mutable.update { it.copy(advanceRequested = true) }
        nextForeground.value = true
        startNextPreparation(background = false)
    }
    fun chooseNextSource() {
        val target = mutable.value.nextTarget ?: return
        val fromLibrary = libraryPlayback
        exitPlayer()
        libraryPlayback = fromLibrary
        mutable.update { it.copy(title = target.card, episode = target.episode, season = target.episode.seasonNumber, returnFocus = null) }
        sources()
    }
    fun queueEpisode(item: MediaItem) {
        val current = mutable.value
        val episode = current.episodes.firstOrNull { it.seasonNumber == item.seasonNumber && it.episodeNumber == item.episodeNumber } ?: return
        val fromLibrary = libraryPlayback
        exitPlayer()
        libraryPlayback = fromLibrary
        mutable.update { it.copy(title = current.title, episode = episode, season = episode.seasonNumber, returnFocus = null) }
        sources()
    }
    private suspend fun switchToPreparedNext() {
        saveProgress(force = true)
        val prepared = preparedNext ?: return
        preparedNext = null
        try { prepared.adopt() }
        catch (cancel: CancellationException) { prepared.close(); throw cancel }
        catch (error: Exception) {
            prepared.close()
            mutable.update { it.copy(nextReady = false, nextFailed = true, nextStatus = "Next episode could not start. ${error.message.orEmpty()}") }
            return
        }
        // Promote the prepared torrent before closing its predecessor, so its
        // bandwidth gate cannot mistake normal handoff for abandoned playback.
        stopTransfer()
        engine = prepared.engine
        lastFile = prepared.file; lastSource = prepared.source.source
        val episode = prepared.target.episode
        mutable.update { it.copy(title = prepared.target.card, episode = episode, season = episode.seasonNumber,
            episodes = episodeCache[prepared.target.card.id to episode.seasonNumber].orEmpty(),
            selectedSource = prepared.source, sources = TorrentSearchSnapshot(listOf(prepared.source)),
            returnFocus = "source:${prepared.source.infoHash}", transfer = prepared.engine.status.value,
            nextTarget = null, nextReady = false, nextStatus = null, advanceRequested = false) }
        observeTransfer(prepared.engine)
        val media = episodeMedia(prepared.target).copy(title = "${prepared.target.card.title} · S${episode.seasonNumber}:E${episode.episodeNumber} · ${episode.name.orEmpty()}")
        videoStarted = false
        mutable.update { it.copy(playing = makePlaying(media, prepared.url, prepared.file, 0, prepared.target.card, episode)) }
        planNextEpisode()
    }
    private fun stopNextPreparation() {
        nextPrepareJob?.cancel(); nextPrepareJob = null
        preparedNext?.close(); preparedNext = null
        mutable.update { it.copy(nextReady = false, nextFailed = false, nextStatus = null) }
    }
    fun focusRestored() { mutable.update { it.copy(returnFocus = null) } }
    override fun onCleared() { saveProgress(force = true); libraryMetadataJob?.cancel(); nextPlanJob?.cancel(); stopNextPreparation(); stopTransfer(); super.onCleared() }

    companion object {
        private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
