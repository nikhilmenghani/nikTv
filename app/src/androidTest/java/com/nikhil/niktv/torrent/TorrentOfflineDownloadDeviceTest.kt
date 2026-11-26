package com.nikhil.niktv.torrent

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.*
import com.nikhil.niktv.model.*
import com.nikhil.niktv.ui.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class TorrentOfflineDownloadDeviceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private fun main(action: () -> Unit) = ins.runOnMainSync(action)
    private fun playerView(view: View): PlayerView? = when (view) {
        is PlayerView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { playerView(view.getChildAt(it)) }
        else -> null
    }
    private suspend fun await(label: String, timeout: Long = 30_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; delay(100) }
        fail(label)
    }

    @Test fun genericFileDownloadsWithoutPlayerAndRemainsPrivateToItsOwner() = runBlocking {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("torrent_peer_port")?.toIntOrNull()
        assumeTrue("Controlled legal native peer required", port != null)
        val owner = UserScope("fixture-account", "generic-file-profile")
        val descriptor = File(context.filesDir, "torrent-fixture.torrent")
        val settings = TorrentEngineOptions(discovery = false, peerHost = "127.0.0.1", peerPort = port!!,
            allowLocalDescriptor = true, allowDownloadFiles = true)
        val probe = TorrentStreamEngine(context, settings)
        val file = try {
            val selected = probe.prepare(descriptor.toURI().toString()).first { it.name.endsWith("torrent-proof-long.en.srt") }
            assertFalse(selected.media)
            try { probe.play(selected.index); fail("Nonmedia must never enter the player") }
            catch (_: IllegalArgumentException) { }
            selected
        } finally { probe.closeAndAwait() }
        var requestId: String? = null
        try {
            TorrentDownloadWorker.regressionOptions = settings
            val handle = TorrentOfflineDownloads.enqueue(context, "generic-subtitle", "Legal subtitle file",
                descriptor.toURI().toString(), file.index, file.name, file.sizeBytes, owner, genericFile = true)
            requestId = handle.requestId
            assertEquals("SRT", handle.fileType)
            await("Native worker finishes the generic file", 90_000) {
                val info = TorrentOfflineDownloads.info(context, handle.requestId)
                check(info.status != OfflineDownloadStatus.FAILED) { "Generic file download failed" }
                info.status == OfflineDownloadStatus.COMPLETE
            }
            assertNull("Generic files have no player URI", TorrentOfflineDownloads.playableUri(context, handle.requestId))
            val job = TorrentOfflineDownloads.files(context, owner).single { it.id == handle.requestId.removePrefix(TorrentOfflineDownloads.PREFIX) }
            assertTrue(job.genericFile)
            assertTrue(TorrentOfflineDownloads.files(context, UserScope("fixture-account", "other-profile")).none { it.id == job.id })
            val uri = checkNotNull(TorrentOfflineDownloads.fileUri(context, job))
            assertEquals("content", uri.scheme)
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertEquals(file.sizeBytes, bytes.size.toLong())
            assertEquals(args.getString("torrent_subtitle_srt_sha256"),
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        } finally {
            TorrentDownloadWorker.regressionOptions = null
            requestId?.let { TorrentOfflineDownloads.remove(context, it) }
        }
    }

    @Test fun workerCompletesMovesAndReopensSeekableOfflineVideo() = runBlocking {
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        (context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager)
            .setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        val args = InstrumentationRegistry.getArguments()
        val port = args.getString("torrent_peer_port")?.toIntOrNull()
        assumeTrue("Controlled legal native peer required", port != null)
        val descriptor = File(context.filesDir, "torrent-download-fixture.torrent").takeIf(File::isFile)
            ?: File(context.filesDir, "torrent-fixture.torrent")
        val settings = TorrentEngineOptions(discovery = false, peerHost = "127.0.0.1", peerPort = port!!, allowLocalDescriptor = true)
        val probe = TorrentStreamEngine(context, settings)
        val video = try { probe.prepare(descriptor.toURI().toString()).single() } finally { probe.closeAndAwait() }
        var handle: OfflineDownloadHandle? = null
        var host: PlaybackFixtureHostActivity? = null
        val evidence = File(context.getExternalFilesDir(null), "torrent-offline-evidence").apply { mkdirs() }
        try {
            host = ins.startActivitySync(Intent(context, PlaybackFixtureHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as PlaybackFixtureHostActivity
            TorrentDownloadWorker.regressionOptions = settings
            val queued = TorrentOfflineDownloads.enqueue(context, "legal-offline-fixture", "Legal offline torrent fixture",
                descriptor.toURI().toString(), Int.MAX_VALUE, video.name, video.sizeBytes, UserScope("fixture-account", "fixture-profile"))
            handle = queued
            val requestId = queued.requestId
            val started = SystemClock.elapsedRealtime()
            await("WorkManager completes the verified native download", 240_000) {
                val info = OfflineMediaDownloads.info(context, requestId)
                check(info.status != OfflineDownloadStatus.FAILED) { "Native background download failed" }
                info.status == OfflineDownloadStatus.COMPLETE
            }
            val finishedMs = SystemClock.elapsedRealtime() - started
            TorrentDownloadWorker.regressionOptions = null
            val uri = checkNotNull(OfflineMediaDownloads.playableUri(context, requestId, ""))
            assertTrue("Offline playback must use a persistent local file", uri.startsWith("file:"))
            val file = File(android.net.Uri.parse(uri).path!!)
            assertTrue(file.canonicalPath.startsWith(File(context.filesDir, "torrent_downloads").canonicalPath + File.separator))
            assertEquals(video.sizeBytes, file.length())
            await("Partial cache is removed after successful move", 5_000) {
                !File(context.filesDir, "torrent_partial/${requestId.removePrefix(TorrentOfflineDownloads.PREFIX)}").exists()
            }
            val prefix = ByteArray(32768)
            RandomAccessFile(file, "r").use { it.readFully(prefix) }
            val digest = MessageDigest.getInstance("SHA-256").digest(prefix).joinToString("") { "%02x".format(it) }
            assertEquals(args.getString("torrent_download_prefix_sha256") ?: args.getString("torrent_prefix_sha256"), digest)
            val activity = checkNotNull(host)
            main { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                PlayerScreen(PlayingMedia(MediaItem("offline-native", "Offline native fixture", null, null), uri,
                    CatalogType.MOVIES, externalPlayback = true), onBack = {}, onRetry = {}, onRetryAlternateDecoder = {},
                    onPlaybackAuthorizationFailure = {}, onPlayPrevious = {}, onPlayNext = {}, onProgress = { _, _, _ -> },
                    playbackEngine = PlaybackEngine.MEDIA3, controlsTimeoutSeconds = 0, startFullscreen = true)
            } } }
            var player: Player? = null
            await("Persistent offline video actually decodes") {
                var decoded = false
                main {
                    player = playerView(activity.window.decorView)?.player
                    val native = player as? androidx.media3.exoplayer.ExoPlayer
                    native?.videoDecoderCounters?.ensureUpdated()
                    decoded = player?.isPlaying == true && (native?.videoDecoderCounters?.renderedOutputBufferCount ?: 0) > 10
                }
                decoded
            }
            var target = 0L
            val seekStart = SystemClock.elapsedRealtime()
            main { target = ((player?.duration ?: 0) * 3 / 4).coerceAtLeast(1000); player?.seekTo(target) }
            await("Offline full-file seek resumes promptly", 10_000) {
                var ready = false
                main { ready = player?.isPlaying == true && (player?.currentPosition ?: 0) >= target - 500 }
                ready
            }
            File(evidence, "result.txt").writeText("PASS actualWorkManager verifiedMove persistentUri mismatchedIndexExactNameFallback offlineDecoded seekMs=${SystemClock.elapsedRealtime() - seekStart} downloadMs=$finishedMs bytes=${file.length()}\n")
        } catch (failure: Throwable) {
            File(evidence, "failure.txt").writeText(failure.stackTraceToString())
            throw failure
        } finally {
            TorrentDownloadWorker.regressionOptions = null
            host?.let { main { it.finish() } }
            handle?.let { OfflineMediaDownloads.remove(context, it.requestId) }
        }
    }
}
