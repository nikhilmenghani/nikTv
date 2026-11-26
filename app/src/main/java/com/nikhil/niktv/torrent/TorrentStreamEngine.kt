package com.nikhil.niktv.torrent

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.frostwire.jlibtorrent.*
import com.frostwire.jlibtorrent.alerts.*
import com.frostwire.jlibtorrent.swig.settings_pack
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.BitSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal data class TorrentVideoFile(val index: Int, val name: String, val sizeBytes: Long)
internal data class TorrentStreamStatus(
    val stage: String = "Idle", val downloadedBytes: Long = 0, val totalBytes: Long = 0,
    val downloadBytesPerSecond: Long = 0, val peers: Int = 0, val seeds: Int = 0,
    val bufferedBytes: Long = 0, val error: String? = null, val backgroundPaused: Boolean = false,
    val verifiedRanges: List<TorrentVerifiedRange> = emptyList(), val storageWarning: String? = null
)

/** Explicit peer/local descriptors are an isolated-test hook, never enabled by the portal. */
internal data class TorrentEngineOptions(
    val discovery: Boolean = true, val downloadLimitBytesPerSecond: Int = 0,
    val peerHost: String? = null, val peerPort: Int = 0, val allowLocalDescriptor: Boolean = false,
    val cacheLimitBytes: Long = 20L * 1024 * 1024 * 1024,
    val trace: ((String) -> Unit)? = null,
    val persistentCacheId: String? = null,
    val preserveCacheOnClose: Boolean = false,
    val streamCacheOwner: String? = null
)

/** One playback-owned torrent. The player only receives hash-verified, flushed file ranges. */
internal class TorrentStreamEngine(context: Context, private val options: TorrentEngineOptions = TorrentEngineOptions()) : AutoCloseable {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val mutable = MutableStateFlow(TorrentStreamStatus())
    val status: StateFlow<TorrentStreamStatus> = mutable.asStateFlow()
    private val nativeLock = Any()
    private val flushLock = Object()
    private val flushed = mutableSetOf<Int>()
    private val pendingFlush = mutableSetOf<Int>()
    private var inFlightFlush: Set<Int>? = null
    private var manager: SessionManager? = null
    private var handle: TorrentHandle? = null
    private var info: TorrentInfo? = null
    private var cache: File? = null
    private var streamLease: TorrentStreamCache.Lease? = null
    private val pieceBitsLock = Any()
    private val verifiedPieces = BitSet()
    private val refreshPieceSnapshot = AtomicBoolean(true)
    private var lastDiskCheck = 0L
    private var hasRetainedPayload = false
    private var selected: TorrentVideoFile? = null
    private var selectedOffset = 0L
    private var pieceLength = 0
    private var server: TorrentRangeServer? = null
    @Volatile private var subtitleFiles: List<TorrentSubtitleFile> = emptyList()
    val subtitles: List<TorrentSubtitleFile> get() = subtitleFiles
    private val requestGeneration = AtomicLong()
    private val subtitleGeneration = AtomicLong()
    // nativeLock protects caption priorities; enabling a skipped file is an
    // asynchronous disk operation because its bytes may be in the partfile.
    private val enabledSubtitles = mutableSetOf<Int>()
    private var activeSubtitlePieces: IntRange? = null
    private val playbackOffset = AtomicLong()
    private val backgroundPaused = AtomicBoolean(false)
    private val downloadLimit = AtomicInteger(options.downloadLimitBytesPerSecond)
    private val pauseClockLock = Any()
    private var pausedAtMillis = 0L
    private var pausedDurationMillis = 0L
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build()
    private var prepared = false
    private val cleanupFinished = CompletableDeferred<Unit>()
    /** Captured off the UI thread; a public v1 identity, never a source URL. */
    @Volatile var sourceInfoHash: String? = null
        private set

    suspend fun prepare(source: String): List<TorrentVideoFile> = withContext(Dispatchers.IO) {
        check(!closed.get() && !prepared) { "Choose a new source before starting another torrent." }
        check(Build.VERSION.SDK_INT >= 26) { "Torrent streaming requires Android 8 or newer." }
        mutable.update { if (closed.get()) it else TorrentStreamStatus(stage = "Reading torrent metadata", backgroundPaused = backgroundPaused.get()) }
        var unattachedLease: TorrentStreamCache.Lease? = null
        try {
            val descriptor = if (source.startsWith("magnet:?", true)) null else readDescriptor(source)
            val ti = descriptor?.let(::TorrentInfo)
            val persistentId = options.persistentCacheId
            require(persistentId == null || CACHE_DIRECTORY.matches(persistentId)) { "Invalid download identity" }
            if (persistentId == null && !options.streamCacheOwner.isNullOrBlank()) {
                val parsedMagnet = if (ti == null) AddTorrentParams.parseMagnetUri(source) else null
                // Keep the native parameter owner alive while reading its borrowed hashes.
                val hash = synchronized(parsedMagnet ?: ti!!) {
                    val hashes = ti?.infoHashType() ?: requireNotNull(parsedMagnet).infoHashes.swig()
                    when {
                        hashes.has_v1() -> "v1_${hashes.v1.to_hex()}"
                        hashes.has_v2() -> "v2_${hashes.v2.to_hex()}"
                        else -> null
                    }
                }
                if (hash != null) streamLease = runCatching { TorrentStreamCache.acquire(app, requireNotNull(options.streamCacheOwner), hash) }.getOrNull()
                unattachedLease = streamLease
            }
            val root = streamLease?.directory?.parentFile ?: if (persistentId == null) File(app.cacheDir, "torrent_streams") else File(app.filesDir, "torrent_partial")
            root.mkdirs()
            val canonicalRootDir = root.canonicalFile
            val (dir, orphans) = synchronized(cacheRegistryLock) {
                ensureOpen()
                val created = streamLease?.directory ?: File(canonicalRootDir, persistentId ?: UUID.randomUUID().toString()).canonicalFile
                check(created.parentFile == canonicalRootDir && created.path !in activeCaches) { "This torrent download is already running" }
                check(created.isDirectory || created.mkdir()) { "Could not create torrent cache" }
                cache = created
                hasRetainedPayload = (streamLease != null || persistentId != null) && TorrentStreamCache.allocatedBytes(created) > 0
                unattachedLease = null
                activeCaches.add(created.path)
                created to canonicalRootDir.listFiles().orEmpty().asSequence()
                    .filter { persistentId == null && streamLease == null && it.isDirectory && CACHE_DIRECTORY.matches(it.name) && it.canonicalFile.parentFile == canonicalRootDir && it.canonicalPath !in activeCaches }
                    .take(16).toList()
            }
            // Process death cannot run close(). Only this engine's UUID children
            // are eligible, and live sessions remain registered through teardown.
            orphans.forEach { orphan ->
                currentCoroutineContext().ensureActive()
                if (!closed.get() && orphan.canonicalFile.parentFile == canonicalRootDir) orphan.deleteRecursively()
            }
            val session = SessionManager()
            session.addListener(object : AlertListener {
                override fun types(): IntArray = intArrayOf(AlertType.ADD_TORRENT.swig(), AlertType.CACHE_FLUSHED.swig(),
                    AlertType.TORRENT_ERROR.swig(), AlertType.FILE_ERROR.swig(), AlertType.METADATA_FAILED.swig(),
                    AlertType.PIECE_FINISHED.swig(), AlertType.TORRENT_CHECKED.swig())
                override fun alert(alert: Alert<*>) {
                    if (closed.get()) return
                    when (alert) {
                        is AddTorrentAlert -> {
                            if (alert.error().isError) fail("Could not start this torrent: ${alert.error().message()}")
                            else synchronized(nativeLock) {
                                if (!closed.get()) {
                                    // The alert's handle is a borrowed C++ member
                                    // invalidated when the alert queue is drained.
                                    // Lookup returns an independently owned handle.
                                    val hashes = alert.handle().swig().info_hashes()
                                    sourceInfoHash = if (hashes.has_v1()) hashes.v1.to_hex() else null
                                    val native = if (hashes.has_v1()) requireNotNull(manager).swig().find_torrent(hashes.v1)
                                        else requireNotNull(manager).swig().find_torrent(hashes.v2)
                                    handle = TorrentHandle(native)
                                    handle?.unsetFlags(TorrentFlags.AUTO_MANAGED)
                                    handle?.setDownloadLimit(downloadLimit.get())
                                    if (backgroundPaused.get()) handle?.pause() else handle?.resume()
                                    trace("added paused=${backgroundPaused.get()} limit=${downloadLimit.get()}")
                                }
                            }
                        }
                        is CacheFlushedAlert -> synchronized(flushLock) {
                            trace("flush-complete pieces=${inFlightFlush?.sorted()} alreadyFlushed=${flushed.size}")
                            inFlightFlush?.let(flushed::addAll)
                            inFlightFlush = null
                            flushLock.notifyAll()
                        }
                        is TorrentErrorAlert -> fail("Torrent transfer failed: ${alert.error().message()}")
                        is FileErrorAlert -> fail("Could not write the torrent cache. Check free storage.")
                        is MetadataFailedAlert -> fail("The torrent metadata could not be verified. Choose another source.")
                        is PieceFinishedAlert -> synchronized(pieceBitsLock) {
                            val index = alert.pieceIndex()
                            if (index in 0..1_000_000) verifiedPieces.set(index)
                        }
                        is TorrentCheckedAlert -> refreshPieceSnapshot.set(true)
                    }
                }
            })
            synchronized(nativeLock) {
                ensureOpen()
                manager = session
                val settings = SettingsPack()
                    .setBoolean(settings_pack.bool_types.enable_dht.swigValue(), options.discovery)
                    .setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), false)
                    .setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), false)
                    .setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), false)
                    .setBoolean(settings_pack.bool_types.allow_multiple_connections_per_ip.swigValue(), !options.discovery)
                    .setInteger(settings_pack.int_types.connections_limit.swigValue(), 80)
                    .setInteger(settings_pack.int_types.download_rate_limit.swigValue(), downloadLimit.get())
                    .setInteger(settings_pack.int_types.upload_rate_limit.swigValue(), 128 * 1024)
                    .setInteger(settings_pack.int_types.max_metadata_size.swigValue(), MAX_DESCRIPTOR)
                if (!options.discovery) settings
                    .setString(settings_pack.string_types.listen_interfaces.swigValue(), "127.0.0.1:0")
                    .setString(settings_pack.string_types.outgoing_interfaces.swigValue(), "127.0.0.1")
                    .setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), false)
                    .setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), false)
                session.start(SessionParams(settings))
                if (ti != null) session.download(ti, dir, null, Priority.array(Priority.IGNORE, ti.numFiles()),
                    if (options.peerHost != null) listOf(TcpEndpoint(options.peerHost, options.peerPort)) else null, TorrentFlags.UPLOAD_MODE)
                else session.download(source, dir, TorrentFlags.UPLOAD_MODE)
            }
            val metadataDeadline = activeTimeMillis() + 90_000
            run {
                while (true) {
                    ensureOpen()
                    if (activeTimeMillis() >= metadataDeadline) throw IOException("No torrent metadata arrived. Try another source.")
                    mutable.value.error?.let { throw IOException(it) }
                    val current = synchronized(nativeLock) { handle?.takeIf { it.isValid } }
                    if (current != null) {
                        if (options.peerHost != null && options.peerPort > 0) synchronized(nativeLock) {
                            ensureOpen(); current.swig().connect_peer(TcpEndpoint(options.peerHost, options.peerPort).swig())
                        }
                        val metadata = synchronized(nativeLock) { current.torrentFile() }
                        if (metadata != null && metadata.isValid) { info = metadata; break }
                    }
                    delay(250)
                }
            }
            val metadata = requireNotNull(info)
            pieceLength = metadata.pieceLength()
            require(pieceLength in 1..MAX_PIECE) { "This torrent uses unsupported oversized pieces." }
            val files = metadata.files()
            // Libtorrent sanitizes paths too; verify them before allowing any file priority.
            val canonicalRoot = dir.canonicalPath + File.separator
            for (i in 0 until files.numFiles()) {
                require(File(dir, files.filePath(i)).canonicalPath.startsWith(canonicalRoot)) { "This torrent contains an unsafe file path." }
                require(files.fileFlags(i).and_(FileStorage.FLAG_SYMLINK).to_int() == 0) { "Torrent symbolic links are not supported." }
            }
            val video = (0 until files.numFiles()).filter { !files.padFileAt(it) }.mapNotNull { index ->
                val name = files.filePath(index)
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension !in VIDEO_EXTENSIONS || Regex("(?i)(^|[/\\\\ ._-])sample([/\\\\ ._-]|$)").containsMatchIn(name)) null
                else TorrentVideoFile(index, name, files.fileSize(index)).takeIf { it.sizeBytes > 0 }
            }
            require(video.isNotEmpty()) { "This torrent has no supported video files." }
            prepared = true
            trace("metadata-ready files=${video.size} pieces=${metadata.numPieces()} pieceLength=$pieceLength")
            mutable.update { if (closed.get()) it else it.copy(stage = "Choose a video", error = null) }
            scope.launch { monitor() }
            video
        } catch (cancelled: CancellationException) {
            close(); throw cancelled
        } catch (error: Throwable) {
            val message = if (error is LinkageError) "Torrent streaming is unavailable on this device."
                else error.message ?: "Could not open this torrent."
            fail(message); close(); throw IOException(message, error)
        } finally { unattachedLease?.let(TorrentStreamCache::release) }
    }

    suspend fun play(fileIndex: Int): String = try { playSelected(fileIndex) }
    catch (error: LinkageError) {
        val message = "Torrent streaming is unavailable on this device."
        fail(message); close(); throw IOException(message, error)
    }

    private suspend fun playSelected(fileIndex: Int): String = withContext(Dispatchers.IO) {
        ensureOpen()
        check(prepared && selected == null) { "Choose a new torrent source to restart playback." }
        val metadata = requireNotNull(info)
        val files = metadata.files()
        require(fileIndex in 0 until files.numFiles())
        val size = files.fileSize(fileIndex)
        require(files.filePath(fileIndex).substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS)
        require(size > 0) { "This video is empty." }
        if (options.persistentCacheId != null) require(size <= options.cacheLimitBytes) { "This video exceeds the offline download size limit." }
        val dir = requireNotNull(cache)
        synchronized(cacheRegistryLock) {
            ensureOpen()
            // usableSpace already excludes bytes physically written by other
            // streams. Reserve only their still-undownloaded selected bytes.
            val remainingOwnedBytes = cacheReservations.asSequence().filter { it.key != dir.canonicalPath }
                .sumOf { (_, reservation) -> (reservation.sizeBytes - reservation.progress.value.downloadedBytes).coerceAtLeast(0) }
            val policy = torrentStoragePolicy(dir.usableSpace, size, 0, remainingOwnedBytes, options.persistentCacheId != null)
            require(!policy.blocked) {
                "Not enough free space to download this video. Choose a smaller source."
            }
            mutable.update { it.copy(storageWarning = policy.warning) }
            cacheReservations[dir.canonicalPath] = CacheReservation(if (options.persistentCacheId != null) size else minOf(size, 8L * 1024 * 1024), status)
        }
        selected = TorrentVideoFile(fileIndex, files.filePath(fileIndex), size)
        streamLease?.let { TorrentStreamCache.select(it, requireNotNull(selected)) }
        selectedOffset = files.fileOffset(fileIndex)
        trace("play file=$fileIndex bytes=$size offset=$selectedOffset paused=${backgroundPaused.get()}")
        synchronized(nativeLock) {
            ensureOpen()
            requireNotNull(handle).apply {
                prioritizeFiles(Array(files.numFiles()) { if (it == fileIndex) Priority.NORMAL else Priority.IGNORE })
                unsetFlags(TorrentFlags.UPLOAD_MODE.or_(TorrentFlags.AUTO_MANAGED))
                setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
                if (backgroundPaused.get()) pause() else resume()
            }
        }
        mutable.update { if (closed.get()) it else it.copy(stage = "Buffering", totalBytes = size, error = null) }
        // A real initial buffer, not an overall torrent percentage, determines readiness.
        val startupEnd = minOf(size, 2L * 1024 * 1024)
        // Do not give the whole steady-state window critical deadlines while a
        // capped background transfer is still trying to finish its first buffer.
        try {
            if (hasRetainedPayload) recheckRetainedSelection(fileIndex)
            prioritize(selectedOffset, startupEnd)
            val startupDeadline = activeTimeMillis() + 90_000
            run {
                val operation = currentCoroutineContext()[Job]
                var offset = 0L
                while (offset < startupEnd) {
                    ensureOpen()
                    awaitVerifiedAndFlushed(((selectedOffset + offset) / pieceLength).toInt(), null, operation, startupDeadline)
                    offset = minOf(startupEnd, ((selectedOffset + offset) / pieceLength + 1) * pieceLength - selectedOffset)
                }
            }
            val matched = matchingTorrentSubtitles(requireNotNull(selected).name,
                (0 until files.numFiles()).filter { !files.padFileAt(it) }.map {
                    TorrentVideoFile(it, files.filePath(it), files.fileSize(it))
                })
            val opened = TorrentRangeServer(this@TorrentStreamEngine, requireNotNull(selected), matched)
            opened.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = opened
            subtitleFiles = opened.subtitles
            mutable.update { if (closed.get()) it else it.copy(stage = "Streaming") }
            trace("stream-ready downloaded=${mutable.value.downloadedBytes}")
            opened.url
        } catch (cancelled: CancellationException) { close(); throw cancelled }
        catch (error: Throwable) {
            val message = if (error is LinkageError) "Torrent streaming is unavailable on this device."
                else error.message ?: "Could not buffer this torrent."
            fail(message); close(); throw IOException(message, error)
        }
    }

    private suspend fun recheckRetainedSelection(fileIndex: Int) {
        mutable.update { if (closed.get()) it else it.copy(stage = "Checking downloaded parts") }
        val deadline = activeTimeMillis() + 90_000
        suspend fun waitFor(label: String, complete: (TorrentHandle) -> Boolean) {
            while (true) {
                currentCoroutineContext().ensureActive()
                ensureOpen()
                mutable.value.error?.let { throw IOException(it) }
                if (activeTimeMillis() >= deadline) throw IOException("Timed out checking saved stream parts")
                if (synchronized(nativeLock) { ensureOpen(); complete(requireNotNull(handle)) }) break
                delay(100)
            }
            trace("cache-recheck $label")
        }
        // A new native torrent initially checks default NORMAL files. Cached
        // shared pieces may instead contain ignored-file bytes in its partfile.
        // Establish the complete selected/ignored disk layout before rechecking.
        waitFor("priorities-ready") { current ->
            val priorities = current.filePriorities()
            priorities.size == requireNotNull(info).numFiles() && priorities.withIndex().all { (index, priority) ->
                priority == if (index == fileIndex) Priority.NORMAL else Priority.IGNORE
            }
        }
        fun isChecking(current: TorrentHandle) = current.status(TorrentHandle.QUERY_NAME).state() in setOf(TorrentStatus.State.CHECKING_FILES, TorrentStatus.State.CHECKING_RESUME_DATA)
        // forceRecheck is a no-op while an initial check is still queued/running.
        waitFor("initial-check-finished") { !isChecking(it) }
        synchronized(nativeLock) {
            ensureOpen()
            requireNotNull(handle).forceRecheck()
            // This synchronous status call follows forceRecheck in native queue order.
            trace("cache-recheck started state=${requireNotNull(handle).status(TorrentHandle.QUERY_NAME).state()}")
        }
        synchronized(pieceBitsLock) { verifiedPieces.clear() }
        refreshPieceSnapshot.set(true)
        waitFor("verified") { !isChecking(it) }
        refreshPieceSnapshot.set(true)
        synchronized(nativeLock) {
            if (options.peerHost != null && options.peerPort > 0) {
                requireNotNull(handle).swig().connect_peer(TcpEndpoint(options.peerHost, options.peerPort).swig())
            }
        }
        hasRetainedPayload = false
        mutable.update { if (closed.get()) it else it.copy(stage = "Buffering") }
    }

    /** Yield only this engine's transfer; already verified HTTP reads remain usable. */
    suspend fun setBackgroundPaused(paused: Boolean) = withContext(Dispatchers.IO) {
        synchronized(nativeLock) {
            ensureOpen()
            if (backgroundPaused.getAndSet(paused) != paused) {
                synchronized(pauseClockLock) {
                    val now = SystemClock.elapsedRealtime()
                    if (paused) pausedAtMillis = now else {
                        pausedDurationMillis += now - pausedAtMillis
                        pausedAtMillis = 0L
                    }
                }
                handle?.let { if (paused) it.pause() else it.resume() }
                trace("pause=$paused downloaded=${mutable.value.downloadedBytes}")
                mutable.update { if (closed.get()) it else it.copy(backgroundPaused = paused) }
            }
        }
        synchronized(flushLock) { flushLock.notifyAll() }
    }

    /** Zero means unlimited. Caps are local to this session and its current torrent. */
    suspend fun setDownloadLimit(bytesPerSecond: Int) = withContext(Dispatchers.IO) {
        require(bytesPerSecond >= 0) { "The download limit must not be negative." }
        synchronized(nativeLock) {
            ensureOpen()
            downloadLimit.set(bytesPerSecond)
            manager?.downloadRateLimit(bytesPerSecond)
            handle?.setDownloadLimit(bytesPerSecond)
            trace("limit=$bytesPerSecond")
        }
    }

    /** For background jobs: return only after every selected byte is verified and persisted. */
    suspend fun completedFile(): File = withContext(Dispatchers.IO) {
        val file = requireNotNull(selected) { "Choose a video before downloading" }
        val first = (selectedOffset / pieceLength).toInt()
        val last = ((selectedOffset + file.sizeBytes - 1) / pieceLength).toInt()
        // Deadlines are only for playback. A full-file job proceeds sequentially
        // and may take hours, while each missing piece still has a finite wait.
        synchronized(nativeLock) {
            ensureOpen()
            requireNotNull(handle).clearPieceDeadlines()
            requireNotNull(handle).swig().set_sequential_range(first, last)
        }
        for (piece in first..last) {
            currentCoroutineContext().ensureActive()
            awaitVerifiedAndFlushed(piece, null, currentCoroutineContext()[Job])
        }
        val output = File(requireNotNull(cache), file.name)
        check(output.isFile && output.length() == file.sizeBytes) { "Downloaded video is incomplete" }
        output
    }

    /** Native file handles must be released before a completed file is moved. */
    suspend fun closeAndAwait() {
        close()
        withContext(NonCancellable) { cleanupFinished.await() }
    }

    private fun activeTimeMillis(): Long = synchronized(pauseClockLock) {
        val now = SystemClock.elapsedRealtime()
        now - pausedDurationMillis - if (pausedAtMillis != 0L) now - pausedAtMillis else 0L
    }

    private suspend fun readDescriptor(source: String): ByteArray {
        val uri = Uri.parse(source)
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val call = http.newCall(Request.Builder().url(source).build())
                val cancel = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
                try { call.execute().use { response ->
                    require(response.isSuccessful) { "Torrent source returned HTTP ${response.code}." }
                    require(response.body.contentLength() <= MAX_DESCRIPTOR) { "The torrent descriptor is too large." }
                    boundedRead(response.body.byteStream())
                } } finally { cancel?.dispose() }
            }
            "file" -> {
                require(options.allowLocalDescriptor) { "Use a magnet or HTTPS torrent link." }
                File(requireNotNull(uri.path)).inputStream().use(::boundedRead)
            }
            "content" -> {
                require(options.allowLocalDescriptor) { "Use a magnet or HTTPS torrent link." }
                requireNotNull(app.contentResolver.openInputStream(uri)).use(::boundedRead)
            }
            else -> throw IllegalArgumentException("Enter a magnet or HTTPS torrent link.")
        }
    }

    private fun boundedRead(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            ensureOpen()
            val count = input.read(buffer)
            if (count < 0) return out.toByteArray()
            require(out.size() + count <= MAX_DESCRIPTOR) { "The torrent descriptor is too large." }
            out.write(buffer, 0, count)
        }
    }

    private fun prioritize(absoluteOffset: Long, windowBytes: Long = 8L * 1024 * 1024,
        rebaseSequential: Boolean = false) = synchronized(nativeLock) {
        ensureOpen()
        val current = requireNotNull(handle)
        val first = (absoluteOffset / pieceLength).toInt()
        val selectedEnd = selected?.let { selectedOffset + it.sizeBytes } ?: Long.MAX_VALUE
        val endOffset = minOf(absoluteOffset + windowBytes.coerceAtLeast(1), selectedEnd)
        // A selected file can begin inside a shared piece (e.g. a preceding SRT).
        val end = minOf(requireNotNull(info).numPieces(), ((endOffset + pieceLength - 1) / pieceLength).toInt())
        val urgentOffset = minOf(endOffset, absoluteOffset + 2L * 1024 * 1024)
        val urgentEnd = minOf(end, ((urgentOffset + pieceLength - 1) / pieceLength).toInt())
        val observedRate = mutable.value.downloadBytesPerSecond.takeIf { it > 0 }
        val cap = downloadLimit.get().takeIf { it > 0 }?.toLong()
        val rate = (if (cap != null) observedRate?.coerceAtMost(cap) ?: cap else observedRate ?: 512L * 1024).coerceAtLeast(1)
        trace("prioritize first=$first urgentEnd=$urgentEnd end=$end window=$windowBytes rate=$rate rebase=$rebaseSequential")
        // A seek must not leave background sequential work fetching old gaps.
        if (rebaseSequential) {
            val last = minOf(requireNotNull(info).numPieces() - 1, ((selectedEnd - 1) / pieceLength).toInt())
            current.swig().set_sequential_range(first, last)
        }
        current.clearPieceDeadlines()
        // Only the immediate contiguous buffer is time-critical. Marking all
        // eight MiB urgent scatters queued blocks and can starve the very first
        // missing piece on a capped/slower link. Deadline spacing reflects the
        // available throughput instead of pretending every piece takes 120 ms.
        for (piece in first until urgentEnd) if (!current.havePiece(piece)) {
            val deadline = ((piece - first).toLong() * pieceLength * 1_000 / rate).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            current.setPieceDeadline(piece, deadline)
        }
        applySubtitleDeadlines(current, rate)
    }

    private fun applySubtitleDeadlines(current: TorrentHandle, rate: Long) {
        // Captions never displace the imminent video buffer. Reapply after video
        // reprioritization, which clears the native deadline table.
        val videoOffset = selectedOffset + playbackOffset.get()
        val videoEnd = minOf(videoOffset + 2L * 1024 * 1024, selectedOffset + (selected?.sizeBytes ?: 0))
        val videoFirstPiece = (videoOffset / pieceLength).toInt()
        val videoEndPiece = ((videoEnd + pieceLength - 1) / pieceLength).toInt()
        activeSubtitlePieces?.let { pieces -> for (piece in pieces) if (!current.havePiece(piece) && piece !in videoFirstPiece until videoEndPiece) {
            val delay = (10_000L + (piece - pieces.first).toLong() * pieceLength * 1_000 / rate.coerceAtLeast(1))
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            current.setPieceDeadline(piece, delay)
        } }
    }

    /** A verified piece may still be in libtorrent's write cache. Wait for its flush completion. */
    private fun awaitVerifiedAndFlushed(piece: Int, generation: Long?, operation: Job? = null,
        deadlineActiveTime: Long = Long.MAX_VALUE, cancelled: (() -> Boolean)? = null, fatalTimeout: Boolean = true) {
        val until = minOf(activeTimeMillis() + 60_000, deadlineActiveTime)
        var lastTrace = 0L
        while (true) {
            ensureOpen()
            operation?.ensureActive()
            if (cancelled?.invoke() == true) throw IOException("Subtitle request cancelled")
            if (generation != null && generation != requestGeneration.get()) throw IOException("Playback moved to another range")
            mutable.value.error?.let { throw IOException(it) }
            if (activeTimeMillis() >= until) {
                val message = "Timed out waiting for torrent pieces. Try another source."
                if (fatalTimeout) fail(message)
                throw IOException(message)
            }
            val verified = synchronized(nativeLock) { ensureOpen(); requireNotNull(handle).havePiece(piece) }
            if (options.trace != null && SystemClock.elapsedRealtime() - lastTrace >= 1_000) {
                lastTrace = SystemClock.elapsedRealtime()
                val flush = synchronized(flushLock) { "flushed=${piece in flushed} inFlight=${inFlightFlush?.sorted()} pending=${pendingFlush.sorted()}" }
                val current = mutable.value
                trace("wait piece=$piece verified=$verified $flush paused=${backgroundPaused.get()} downloaded=${current.downloadedBytes} rate=${current.downloadBytesPerSecond} peers=${current.peers}")
            }
            if (verified) synchronized(flushLock) {
                if (piece in flushed) return
                pendingFlush.add(piece)
                if (inFlightFlush == null) {
                    inFlightFlush = pendingFlush.toSet()
                    pendingFlush.clear()
                    trace("flush-request pieces=${inFlightFlush?.sorted()}")
                    synchronized(nativeLock) { ensureOpen(); requireNotNull(handle).flushCache() }
                }
                flushLock.wait(100)
            } else synchronized(flushLock) { flushLock.wait(100) }
        }
    }

    internal fun openRange(start: Long, end: Long): InputStream {
        val generation = requestGeneration.incrementAndGet()
        trace("range start=$start end=$end generation=$generation")
        synchronized(flushLock) { flushLock.notifyAll() }
        playbackOffset.set(start)
        prioritize(selectedOffset + start, rebaseSequential = true)
        val path = File(requireNotNull(cache), requireNotNull(selected).name)
        return object : InputStream() {
            private var offset = start
            private var file: RandomAccessFile? = null
            private var done = false
            override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
            override fun read(buffer: ByteArray, from: Int, count: Int): Int {
                if (count == 0) return 0
                if (done || offset > end) return -1
                val absolute = selectedOffset + offset
                val piece = (absolute / pieceLength).toInt()
                awaitVerifiedAndFlushed(piece, generation)
                val length = minOf(count.toLong(), end - offset + 1, pieceLength - absolute % pieceLength).toInt()
                val reader = file ?: RandomAccessFile(path, "r").also { file = it }
                reader.seek(offset)
                reader.readFully(buffer, from, length)
                offset += length
                playbackOffset.set(offset)
                // Priorities follow playback requests rather than the old file beginning.
                if (offset <= end && (selectedOffset + offset) / pieceLength != piece.toLong()) prioritize(selectedOffset + offset)
                return length
            }
            override fun available(): Int = if (done) 0 else (end - offset + 1).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            override fun close() { done = true; file?.close(); synchronized(flushLock) { flushLock.notifyAll() } }
        }
    }

    internal fun openSubtitle(index: Int, start: Long, end: Long): InputStream {
        ensureOpen()
        val subtitle = subtitleFiles.firstOrNull { it.index == index } ?: throw IOException("Subtitle not found")
        require(start >= 0 && end < subtitle.sizeBytes && end >= start)
        val generation = subtitleGeneration.incrementAndGet()
        val cancelled = AtomicBoolean(false)
        val deadline = activeTimeMillis() + 30_000
        val metadata = requireNotNull(info).files()
        val absoluteStart = metadata.fileOffset(index)
        val pieces = (absoluteStart / pieceLength).toInt()..((absoluteStart + subtitle.sizeBytes - 1) / pieceLength).toInt()
        val path = File(requireNotNull(cache), subtitle.name)
        return object : InputStream() {
            private var offset = start
            private var file: RandomAccessFile? = null
            private var ready = false
            private fun isCancelled() = cancelled.get() || generation != subtitleGeneration.get()
            private fun enableFile() {
                if (ready) return
                synchronized(nativeLock) {
                    ensureOpen()
                    if (isCancelled()) throw IOException("Subtitle request cancelled")
                    if (index !in enabledSubtitles) requireNotNull(handle).filePriority(index, Priority.NORMAL)
                    activeSubtitlePieces = pieces
                    trace("subtitle-enable index=$index pieces=$pieces")
                }
                while (true) {
                    ensureOpen()
                    if (isCancelled()) throw IOException("Subtitle request cancelled")
                    if (activeTimeMillis() >= deadline) throw IOException("Timed out preparing this subtitle")
                    // Native m_file_priority changes only after the disk worker
                    // has moved skipped shared-piece bytes out of the partfile.
                    val moved = synchronized(nativeLock) { ensureOpen(); requireNotNull(handle).filePriority(index) != Priority.IGNORE }
                    if (moved) break
                    synchronized(flushLock) { flushLock.wait(50) }
                }
                synchronized(flushLock) {
                    val firstEnable = synchronized(nativeLock) { enabledSubtitles.add(index) }
                    if (firstEnable) {
                        // A preceding video flush cannot prove that a later
                        // partfile migration was persisted. Flush these again.
                        flushed.removeAll(pieces.toSet())
                        inFlightFlush = inFlightFlush?.minus(pieces.toSet())
                    }
                }
                // The completed native file-priority operation recomputes every
                // piece's priority. Deadlines set before that async disk callback
                // lose their urgent priority, leaving a far-away caption behind
                // the whole sequential video. Restore both windows afterwards.
                if (isCancelled()) throw IOException("Subtitle request cancelled")
                prioritize(selectedOffset + playbackOffset.get())
                trace("subtitle-ready index=$index pieces=$pieces")
                ready = true
            }
            override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
            override fun read(buffer: ByteArray, from: Int, count: Int): Int {
                if (count == 0) return 0
                if (isCancelled()) throw IOException("Subtitle request cancelled")
                if (offset > end) return -1
                enableFile()
                val absolute = absoluteStart + offset
                awaitVerifiedAndFlushed((absolute / pieceLength).toInt(), null, deadlineActiveTime = deadline,
                    cancelled = ::isCancelled, fatalTimeout = false)
                val length = minOf(count.toLong(), end - offset + 1, pieceLength - absolute % pieceLength).toInt()
                val reader = file ?: RandomAccessFile(path, "r").also { file = it }
                reader.seek(offset)
                reader.readFully(buffer, from, length)
                offset += length
                return length
            }
            override fun available(): Int = if (isCancelled()) 0 else (end - offset + 1).coerceAtLeast(0).toInt()
            override fun close() {
                cancelled.set(true)
                file?.close()
                synchronized(nativeLock) { if (generation == subtitleGeneration.get()) activeSubtitlePieces = null }
                synchronized(flushLock) { flushLock.notifyAll() }
            }
        }
    }

    private suspend fun monitor() {
        while (currentCoroutineContext().isActive && !closed.get()) {
            var transferError: String? = null
            runCatching {
                synchronized(nativeLock) {
                    if (!closed.get()) handle?.takeIf { it.isValid }?.let { current ->
                        val snapshot = current.status()
                        val file = selected
                        val downloaded = file?.let { current.fileProgress().getOrNull(it.index) } ?: snapshot.totalWantedDone()
                        var buffered = 0L
                        if (file != null) {
                            var offset = playbackOffset.get().coerceAtMost(file.sizeBytes)
                            while (offset < file.sizeBytes && buffered < 8 * 1024 * 1024) {
                                val absolute = selectedOffset + offset
                                if (!current.havePiece((absolute / pieceLength).toInt())) break
                                val n = minOf(file.sizeBytes - offset, pieceLength - absolute % pieceLength)
                                buffered += n; offset += n
                            }
                        }
                        if (file != null && refreshPieceSnapshot.getAndSet(false)) {
                            synchronized(pieceBitsLock) {
                                val bits = current.status(TorrentHandle.QUERY_PIECES).pieces()
                                val count = minOf(bits.size(), requireNotNull(info).numPieces())
                                verifiedPieces.clear()
                                for (piece in 0 until count) if (bits.getBit(piece)) verifiedPieces.set(piece)
                            }
                        }
                        val ranges = if (file != null) synchronized(pieceBitsLock) {
                            verifiedTorrentRanges(verifiedPieces, selectedOffset, file.sizeBytes, pieceLength)
                        } else emptyList()
                        mutable.update { it.copy(downloadedBytes = downloaded, totalBytes = file?.sizeBytes ?: snapshot.totalWanted(),
                            downloadBytesPerSecond = snapshot.downloadPayloadRate().toLong(), peers = snapshot.numPeers(),
                            seeds = snapshot.numSeeds(), bufferedBytes = buffered, verifiedRanges = ranges) }
                        trace("transfer downloaded=$downloaded rate=${snapshot.downloadPayloadRate()} peers=${snapshot.numPeers()} buffered=$buffered paused=${backgroundPaused.get()}")
                        if (snapshot.errorCode().isError) transferError = "Torrent transfer failed: ${snapshot.errorCode().message()}"
                    }
                }
            }
            transferError?.let(::fail)
            val file = selected
            if (file != null && options.persistentCacheId == null && SystemClock.elapsedRealtime() - lastDiskCheck >= 5_000) {
                lastDiskCheck = SystemClock.elapsedRealtime()
                val verified = mutable.value.verifiedRanges.sumOf { it.endExclusiveBytes - it.startBytes }
                val warning = torrentStoragePolicy(cache?.usableSpace ?: 0, file.sizeBytes, verified, 0, false).warning
                mutable.update { if (closed.get()) it else it.copy(storageWarning = warning) }
            }
            delay(1_000)
        }
    }

    private fun ensureOpen() { if (closed.get()) throw IOException("Torrent stream closed") }
    private fun trace(message: String) { options.trace?.let { callback -> runCatching { callback("${SystemClock.elapsedRealtime()} $message") } } }
    private fun fail(message: String) {
        mutable.update { if (closed.get()) it else it.copy(stage = "Could not stream", error = message) }
        synchronized(flushLock) { flushLock.notifyAll() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        trace("closed")
        mutable.update { it.copy(stage = "Closed", backgroundPaused = false) }
        scope.cancel()
        requestGeneration.incrementAndGet()
        subtitleGeneration.incrementAndGet()
        subtitleFiles = emptyList()
        synchronized(flushLock) { flushLock.notifyAll() }
        val opened = server; server = null
        // SessionManager.stop joins its native/alert workers. Never do it on the UI thread.
        cleanupScope.launch {
            try {
                opened?.stop()
                val session = synchronized(nativeLock) { val old = manager; manager = null; handle = null; old }
                runCatching { session?.stop() }
                synchronized(cacheRegistryLock) { cache }?.let { dir ->
                    try {
                        val lease = streamLease
                        if (lease != null) {
                            // Remove the native ownership record before making
                            // the cache lease reusable. An immediate replay then
                            // either gets that lease or a clean ephemeral one.
                            synchronized(cacheRegistryLock) {
                                activeCaches.remove(dir.canonicalPath)
                                cacheReservations.remove(dir.canonicalPath)
                            }
                            TorrentStreamCache.release(lease)
                        }
                        else {
                            val root = (if (options.persistentCacheId == null) File(app.cacheDir, "torrent_streams") else File(app.filesDir, "torrent_partial")).canonicalFile
                            if (!options.preserveCacheOnClose && dir.canonicalFile.parentFile == root) dir.deleteRecursively()
                        }
                    } finally {
                        synchronized(cacheRegistryLock) {
                            activeCaches.remove(dir.canonicalPath)
                            cacheReservations.remove(dir.canonicalPath)
                        }
                    }
                }
            } finally {
                cleanupFinished.complete(Unit)
            }
        }
    }

    companion object {
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val cacheRegistryLock = Any()
        private val activeCaches = mutableSetOf<String>()
        private data class CacheReservation(val sizeBytes: Long, val progress: StateFlow<TorrentStreamStatus>)
        private val cacheReservations = mutableMapOf<String, CacheReservation>()
        private val CACHE_DIRECTORY = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private const val MAX_DESCRIPTOR = 8 * 1024 * 1024
        private const val MAX_PIECE = 16 * 1024 * 1024
        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "avi", "mov", "ts", "m2ts")
    }
}

internal class TorrentRangeServer(private val engine: TorrentStreamEngine, private val video: TorrentVideoFile,
    private val matchingSubtitles: List<TorrentSubtitleFile> = emptyList()) : NanoHTTPD("127.0.0.1", 0) {
    private val token = UUID.randomUUID().toString()
    // NanoHTTPD applies this policy after serve(), overriding response flags.
    // Preserve exact byte lengths for caption HEAD/Range as well as video.
    override fun useGzipWhenAccepted(response: Response): Boolean = false
    val url get() = "http://127.0.0.1:$listeningPort/$token/video.${video.name.substringAfterLast('.', "mp4")}"
    val subtitles get() = matchingSubtitles.map { it.copy(url = "http://127.0.0.1:$listeningPort/$token/subtitle/${it.index}.${it.name.substringAfterLast('.').lowercase()}") }
    override fun serve(session: IHTTPSession): Response {
        val route = session.uri.substringBefore('?')
        val subtitle = matchingSubtitles.firstOrNull { route == "/$token/subtitle/${it.index}.${it.name.substringAfterLast('.').lowercase()}" }
        val isVideo = route == "/$token/video.${video.name.substringAfterLast('.', "mp4")}"
        if (!isVideo && subtitle == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        if (session.method != Method.GET && session.method != Method.HEAD) return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Use GET or HEAD")
        val size = subtitle?.sizeBytes ?: video.sizeBytes
        val range = try { TorrentByteRange.parse(session.headers["range"], size) }
        catch (_: IllegalArgumentException) { return newFixedLengthResponse(Response.Status.RANGE_NOT_SATISFIABLE, MIME_PLAINTEXT, "").apply { addHeader("Content-Range", "bytes */$size") } }
        val mime = if (subtitle != null) when (subtitle.name.substringAfterLast('.').lowercase()) {
            "vtt" -> "text/vtt"; "ass", "ssa" -> "text/x-ssa"; else -> "application/x-subrip"
        } else when (video.name.substringAfterLast('.').lowercase()) { "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; "ts", "m2ts" -> "video/mp2t"; else -> "video/mp4" }
        return try {
            val stream = if (session.method == Method.HEAD) java.io.ByteArrayInputStream(byteArrayOf())
                else if (subtitle != null) engine.openSubtitle(subtitle.index, range.first, range.last)
                else engine.openRange(range.first, range.last)
            newFixedLengthResponse(if (range.partial) Response.Status.PARTIAL_CONTENT else Response.Status.OK, mime, stream, range.last - range.first + 1).apply {
                setGzipEncoding(false)
                addHeader("Accept-Ranges", "bytes"); addHeader("Cache-Control", "no-store")
                if (range.partial) addHeader("Content-Range", "bytes ${range.first}-${range.last}/$size")
            }
        } catch (_: IOException) { newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, MIME_PLAINTEXT, "Waiting for torrent pieces") }
    }
}

internal data class TorrentByteRange(val first: Long, val last: Long, val partial: Boolean) {
    companion object {
        fun parse(header: String?, length: Long): TorrentByteRange {
            require(length > 0)
            if (header == null) return TorrentByteRange(0, length - 1, false)
            val match = Regex("bytes=(\\d*)-(\\d*)").matchEntire(header.trim()) ?: throw IllegalArgumentException("Invalid range")
            val (left, right) = match.destructured
            require(left.isNotEmpty() || right.isNotEmpty())
            if (left.isEmpty()) {
                val count = requireNotNull(right.toLongOrNull()); require(count > 0)
                return TorrentByteRange((length - count).coerceAtLeast(0), length - 1, true)
            }
            val first = requireNotNull(left.toLongOrNull())
            val last = if (right.isBlank()) length - 1 else requireNotNull(right.toLongOrNull()).coerceAtMost(length - 1)
            require(first in 0 until length && last >= first)
            return TorrentByteRange(first, last, true)
        }
    }
}
