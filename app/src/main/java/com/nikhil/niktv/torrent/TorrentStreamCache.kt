package com.nikhil.niktv.torrent

import android.content.Context
import android.system.Os
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** App-private stream pieces, separate from permanent offline videos and worker partials. */
internal object TorrentStreamCache {
    private const val QUOTA = 2L * 1024 * 1024 * 1024
    private const val MAX_AGE = 7L * 24 * 60 * 60 * 1000
    private val lock = Any()
    private val active = mutableSetOf<String>()
    private val entryName = Regex("[0-9a-f]{64}_(v1_[0-9a-f]{40}|v2_[0-9a-f]{64})")

    internal class Lease internal constructor(val directory: File, internal val manifest: File, internal val key: String) {
        internal var discard = false
    }

    fun acquire(context: Context, owner: String, nativeHash: String): Lease? = synchronized(lock) {
        require(owner.isNotBlank() && owner.length <= 4096)
        require(Regex("v1_[0-9a-f]{40}|v2_[0-9a-f]{64}").matches(nativeHash))
        val ownerHash = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val root = File(context.filesDir, "torrent_stream_cache").apply { mkdirs() }.canonicalFile
        prune(root)
        val key = "${ownerHash}_$nativeHash"
        val directory = File(root, key).canonicalFile
        check(directory.parentFile == root)
        if (directory.path in active) return null // A second engine must never mutate a live directory.
        val manifest = File(root, "$key.json")
        val saved = read(manifest)
        if (directory.exists() && saved?.optString("key") != key) {
            directory.deleteRecursively()
            manifest.delete()
        }
        check(directory.isDirectory || directory.mkdir()) { "Could not create stream cache" }
        active.add(directory.path)
        write(manifest, saved?.takeIf { it.optString("key") == key } ?: JSONObject().put("key", key))
        directory.setLastModified(System.currentTimeMillis())
        Lease(directory, manifest, key)
    }

    /** Native infohash verification is authoritative; this also rejects stale file metadata. */
    fun select(lease: Lease, video: TorrentVideoFile) = synchronized(lock) {
        val metadata = read(lease.manifest) ?: JSONObject().put("key", lease.key)
        val files = metadata.optJSONObject("files") ?: JSONObject()
        if (files.has(video.name) && files.optLong(video.name) != video.sizeBytes) {
            lease.discard = true
            throw java.io.IOException("Saved stream cache does not match this video. Please play it again.")
        }
        while (files.length() >= 32 && !files.has(video.name)) files.remove(files.keys().next())
        metadata.put("files", files.put(video.name, video.sizeBytes))
        write(lease.manifest, metadata)
    }

    /** Called only after the owning native session and disk workers have stopped. */
    fun release(lease: Lease) = synchronized(lock) {
        active.remove(lease.directory.path)
        if (lease.discard) {
            lease.directory.deleteRecursively(); lease.manifest.delete()
        } else {
            lease.directory.setLastModified(System.currentTimeMillis())
        }
        prune(requireNotNull(lease.directory.parentFile))
    }

    fun clearInactive(context: Context): Long = synchronized(lock) {
        val root = File(context.filesDir, "torrent_stream_cache").canonicalFile
        var removed = 0L
        root.listFiles().orEmpty().filter { it.isDirectory && entryName.matches(it.name) && it.canonicalFile.parentFile == root }
            .filter { it.canonicalPath !in active }.forEach { entry ->
                val bytes = allocatedBytes(entry)
                if (entry.deleteRecursively()) { removed += bytes; File(root, "${entry.name}.json").delete() }
            }
        removed
    }

    private fun prune(root: File) {
        val now = System.currentTimeMillis()
        val entries = root.listFiles().orEmpty().filter {
            it.isDirectory && entryName.matches(it.name) && it.canonicalFile.parentFile == root
        }.sortedBy { it.lastModified() }
        var total = entries.sumOf(::allocatedBytes)
        for (entry in entries) {
            if (entry.canonicalPath in active) continue
            if (total <= QUOTA && now - entry.lastModified() <= MAX_AGE) continue
            val bytes = allocatedBytes(entry)
            if (entry.deleteRecursively()) {
                File(root, "${entry.name}.json").delete()
                total = (total - bytes).coerceAtLeast(0)
            }
        }
    }

    private fun read(file: File): JSONObject? = runCatching {
        if (!file.isFile || file.length() > 512 * 1024) return null
        JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })
    }.getOrNull()

    private fun write(file: File, value: JSONObject) {
        // A cache index is optional when storage is low; native verified bytes
        // remain authoritative and playback may still be attempted.
        runCatching {
            val atomic = AtomicFile(file)
            val output = atomic.startWrite()
            try { output.write(value.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
            catch (failure: Throwable) { atomic.failWrite(output); throw failure }
        }
    }

    /** Sparse video length is not disk consumption. Never follow cached symlinks. */
    fun allocatedBytes(file: File): Long {
        if (!file.exists() || android.os.Build.VERSION.SDK_INT >= 26 && Files.isSymbolicLink(file.toPath())) return 0
        return if (file.isDirectory) file.listFiles().orEmpty().sumOf(::allocatedBytes)
        else runCatching { Os.stat(file.path).st_blocks * 512L }.getOrDefault(file.length()).coerceAtLeast(0)
    }
}

internal data class TorrentVerifiedRange(val startBytes: Long, val endExclusiveBytes: Long)
internal data class TorrentStoragePolicy(val blocked: Boolean, val warning: String?)

internal fun torrentStoragePolicy(freeBytes: Long, selectedBytes: Long, verifiedBytes: Long,
    otherReservedBytes: Long, explicitDownload: Boolean): TorrentStoragePolicy {
    val remaining = (selectedBytes - verifiedBytes).coerceAtLeast(0)
    val reserve = otherReservedBytes.coerceIn(0, Long.MAX_VALUE - 128L * 1024 * 1024) + 128L * 1024 * 1024
    val required = if (remaining > Long.MAX_VALUE - reserve) Long.MAX_VALUE else remaining + reserve
    return if (explicitDownload) TorrentStoragePolicy(freeBytes < required, null)
    else TorrentStoragePolicy(false, if (freeBytes < remaining) "Storage is low: ${(freeBytes.coerceAtLeast(0) / 1048576)} MB free. Playback may stop if storage fills." else null)
}

internal fun verifiedTorrentRanges(pieces: java.util.BitSet, selectedOffset: Long, size: Long, pieceLength: Int): List<TorrentVerifiedRange> {
    if (pieceLength <= 0 || size <= 0) return emptyList()
    val first = (selectedOffset / pieceLength).toInt()
    val lastExclusive = ((selectedOffset + size + pieceLength - 1) / pieceLength).toInt()
    val ranges = mutableListOf<TorrentVerifiedRange>()
    var next = pieces.nextSetBit(first)
    while (next >= first && next < lastExclusive) {
        val end = pieces.nextClearBit(next).coerceAtMost(lastExclusive)
        ranges += TorrentVerifiedRange((next.toLong() * pieceLength - selectedOffset).coerceAtLeast(0),
            (end.toLong() * pieceLength - selectedOffset).coerceAtMost(size))
        next = pieces.nextSetBit(end)
    }
    return ranges
}
