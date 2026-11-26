package com.nikhil.niktv.torrent

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TorrentStreamCacheDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun main(block: () -> Unit) = ins.runOnMainSync(block)
    @Test fun storageListsOnlyItsOwnerAndClearsInactivePiecesWithoutFollowingLinks() = runBlocking {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        val person = com.nikhil.niktv.data.UserScope("14012", UUID.randomUUID().toString())
        val owner = "${person.accountId}|${person.profileId}"
        val a = checkNotNull(TorrentStreamCache.acquire(context, owner, "v1_" + "1".repeat(40)))
        val b = checkNotNull(TorrentStreamCache.acquire(context, "$owner-other", "v1_" + "1".repeat(40)))
        try {
            TorrentStreamCache.select(a, TorrentVideoFile(0, "folder/episode.mkv", 1024L * 1024 * 1024))
            val sparse = File(a.directory, "episode.mkv")
            java.io.RandomAccessFile(sparse, "rw").use { file -> file.setLength(1024L * 1024 * 1024); file.write(ByteArray(4096) { 5 }) }
            val otherFile = File(b.directory, "keep.mkv").apply { writeBytes(ByteArray(8192) { 7 }) }
            val entries = TorrentStreamCache.entries(context, owner)
            assertEquals(1, entries.size)
            assertEquals(listOf("episode.mkv"), entries.single().names)
            assertTrue(entries.single().inUse)
            assertTrue(entries.single().bytes in 1 until sparse.length())
            val managed = com.nikhil.niktv.data.ProfileStorage.snapshot(context, person).files
                .filter { it.kind == com.nikhil.niktv.data.StorageFileKind.STREAM }
            assertEquals(1, managed.size)
            assertEquals("torrents", managed.single().portal)
            assertEquals("episode.mkv", managed.single().title)
            assertTrue(managed.single().inUse)
            assertFalse("Active playback pieces cannot be deleted", TorrentStreamCache.clearEntry(context, owner, a.key))
            try { TorrentStreamCache.clearEntry(context, owner, b.key); fail("Cannot clear another profile") }
            catch (_: IllegalArgumentException) { }
            try { TorrentStreamCache.clearEntry(context, owner, "../outside"); fail("Cannot clear arbitrary paths") }
            catch (_: IllegalArgumentException) { }
            TorrentStreamCache.release(a)
            android.system.Os.symlink(b.directory.path, File(a.directory, "other-profile-link").path)
            assertTrue(TorrentStreamCache.clearEntry(context, owner, a.key))
            assertFalse(a.directory.exists())
            assertTrue("Deleting cached stream links must preserve their targets", otherFile.exists())
            assertTrue(TorrentStreamCache.entries(context, owner).isEmpty())
            assertEquals(1, TorrentStreamCache.entries(context, "$owner-other").size)
        } finally {
            a.discard = true; TorrentStreamCache.release(a)
            b.discard = true; TorrentStreamCache.release(b)
        }
    }
    private fun playerView(view: View): PlayerView? = when (view) {
        is PlayerView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { playerView(view.getChildAt(it)) }
        else -> null
    }
    @Test fun replayReusesVerifiedOwnerCacheWithoutAnyPeer() = runBlocking {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        (context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager).setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("torrent_peer_port")?.toIntOrNull()
        assumeTrue("Controlled legal native peer required", port != null)
        val descriptor = File(context.filesDir, "torrent-fixture.torrent")
        val owner = "fixture-cache|${UUID.randomUUID()}"
        val evidence = File(context.getExternalFilesDir(null), "torrent-stream-cache-evidence").apply { mkdirs() }
        val firstTrace = File(evidence, "first-engine.txt").apply { writeText("") }
        val replayTrace = File(evidence, "replay-engine.txt").apply { writeText("") }
        val first = TorrentStreamEngine(context, TorrentEngineOptions(discovery = false, peerHost = "127.0.0.1", peerPort = port!!,
            allowLocalDescriptor = true, streamCacheOwner = owner, downloadLimitBytesPerSecond = 1048576,
            trace = { firstTrace.appendText(it + "\n") }))
        var second: TorrentStreamEngine? = null
        var host: PlaybackFixtureHostActivity? = null
        var cacheKey: String? = null
        val copiedDescriptor = File(context.filesDir, "cached-replay-${UUID.randomUUID()}.torrent")
        fun prefix(url: String): String {
            val request = URL(url).openConnection() as HttpURLConnection
            request.setRequestProperty("Range", "bytes=0-32767")
            request.connectTimeout = 5000; request.readTimeout = 15000
            return try {
                assertEquals(206, request.responseCode)
                val bytes = request.inputStream.use { it.readBytes() }
                assertEquals(32768, bytes.size)
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            } finally { request.disconnect() }
        }
        try {
            val video = first.prepare(descriptor.toURI().toString()).single()
            val initial = first.play(video.index)
            assertEquals(args.getString("torrent_prefix_sha256"), prefix(initial))
            val nativeKey = "v1_${checkNotNull(first.sourceInfoHash)}"
            cacheKey = nativeKey
            val busy = TorrentStreamCache.acquire(context, owner, nativeKey)
            busy?.let(TorrentStreamCache::release)
            assertNull("Busy cache must not be shared by a second native writer", busy)
            val otherOwner = checkNotNull(TorrentStreamCache.acquire(context, "$owner-other", nativeKey))
            assertTrue("Other profile cannot inherit this profile's cached bytes", TorrentStreamCache.allocatedBytes(otherOwner.directory) == 0L)
            otherOwner.discard = true; TorrentStreamCache.release(otherOwner)
            first.setBackgroundPaused(true)
            first.closeAndAwait()
            descriptor.copyTo(copiedDescriptor)
            val replay = TorrentStreamEngine(context, TorrentEngineOptions(discovery = false, allowLocalDescriptor = true, streamCacheOwner = owner,
                trace = { replayTrace.appendText(it + "\n") })).also { second = it }
            val reopened = replay.prepare(copiedDescriptor.toURI().toString()).single()
            val started = SystemClock.elapsedRealtime()
            val url = withTimeout(20_000) { replay.play(reopened.index) }
            assertEquals("Different source URI, same validated infohash and exact video", args.getString("torrent_prefix_sha256"), prefix(url))
            assertEquals("No DHT, trackers or injected peer on replay", 0, replay.status.value.peers)
            host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
            val activity = checkNotNull(host)
            main { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                PlayerScreen(PlayingMedia(MediaItem("cached-replay", "Verified cache replay", null, null), url, CatalogType.MOVIES, externalPlayback = true),
                    onBack = {}, onRetry = {}, onRetryAlternateDecoder = {}, onPlaybackAuthorizationFailure = {}, onPlayPrevious = {}, onPlayNext = {},
                    onProgress = { _, _, _ -> }, playbackEngine = PlaybackEngine.MEDIA3, startFullscreen = true, controlsTimeoutSeconds = 0)
            } } }
            withTimeout(15_000) {
                while (true) {
                    var decoded = false
                    main {
                        val player = playerView(activity.window.decorView)?.player as? androidx.media3.exoplayer.ExoPlayer
                        player?.videoDecoderCounters?.ensureUpdated()
                        decoded = player?.isPlaying == true && (player.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 10
                    }
                    if (decoded) break
                    delay(100)
                }
            }
            withTimeout(3000) { while (replay.status.value.verifiedRanges.isEmpty()) delay(100) }
            assertEquals(0L, replay.status.value.verifiedRanges.first().startBytes)
            assertTrue(replay.status.value.verifiedRanges.all { it.startBytes >= 0 && it.endExclusiveBytes <= video.sizeBytes })
            File(evidence, "result.txt").writeText("PASS retainedPartial peerlessReplay changedDescriptorUri isolatedOwner exclusiveLease verifiedRanges actualDecoded replayMs=${SystemClock.elapsedRealtime() - started}\n")
        } catch (failure: Throwable) {
            File(evidence, "failure.txt").writeText(failure.stackTraceToString()); throw failure
        } finally {
            host?.let { main { it.finish() } }
            first.closeAndAwait(); second?.closeAndAwait()
            cacheKey?.let { TorrentStreamCache.acquire(context, owner, it)?.let { lease -> lease.discard = true; TorrentStreamCache.release(lease) } }
            copiedDescriptor.delete()
        }
    }
}
