package com.nikhil.niktv.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import com.nikhil.niktv.model.*
import java.io.File
import java.util.UUID

/** Opt-in, separate package, synthetic fixtures only. Never reads provider profiles. */
class PlaybackRegressionActivity : ComponentActivity() {
    companion object {
        @Volatile var instance: PlaybackRegressionActivity? = null
        @Volatile var creations = 0
        @Volatile var navigations = 0
        @Volatile var currentMediaId = "fixture"
        @Volatile var progress = 0L
        @Volatile var knownDuration = 0L
        @Volatile var retryNow: (() -> Unit)? = null
        @Volatile var retries = 0
        @Volatile var reauthentications = 0
        @Volatile var retryPosition = -1L
        @Volatile var automaticRetries = 0
        @Volatile var firstFrames = 0
        @Volatile var enginePreferenceChanges = 0
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        creations++
        navigations = 0
        currentMediaId = "fixture"
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        progress = 0L
        retries = 0
        reauthentications = 0
        automaticRetries = 0
        firstFrames = 0
        enginePreferenceChanges = 0
        val engine = PlaybackEngine.valueOf(intent.getStringExtra("engine") ?: "MEDIA3")
        val first = MediaItem("fixture", "Local regression fixture", null, null)
        val second = MediaItem("second", "Second local fixture", null, null)
        val initial = PlayingMedia(
            media = first,
            previousEpisode = second, nextEpisode = second, episodeQueue = listOf(first, second),
            url = intent.getStringExtra("url") ?: android.net.Uri.fromFile(File(cacheDir, "retry.wav")).toString(),
            catalogType = CatalogType.valueOf(intent.getStringExtra("catalog") ?: "MOVIES"),
            playbackFormat = intent.getStringExtra("format").orEmpty(),
            streamRequestHeaders = intent.getStringArrayExtra("headers").orEmpty().toList().chunked(2)
                .filter { it.size == 2 }.associate { it[0] to it[1] },
            progressKey = "synthetic-fixture",
            playWhenReady = intent.getBooleanExtra("playWhenReady", true),
            resumePositionMillis = savedInstanceState?.getLong("fixtureResume", 2500L) ?: 2500L,
            authorizationRetryCount = intent.getIntExtra("authorizationRetries", 1)
        )
        val current = mutableStateOf(initial)
        fun navigate(item: MediaItem) {
            navigations++
            currentMediaId = item.id
            current.value = current.value.copy(media = item, progressKey = "synthetic-${item.id}",
                playbackAttemptId = UUID.randomUUID().toString())
        }
        fun retry(position: Long) {
            retryPosition = position
            retries++
            current.value = current.value.copy(playbackAttemptId = UUID.randomUUID().toString(),
                resumePositionMillis = position, authorizationRetryCount = 0, playWhenReady = true)
        }
        retryNow = { retry(2500L) }
        window.callback = PlayerTransportWindowCallback(window.callback) { event ->
            if (event.action != android.view.KeyEvent.ACTION_DOWN || event.repeatCount != 0) false
            else when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> { navigate(second); true }
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { navigate(first); true }
                else -> false
            }
        }
        setContent {
            MaterialTheme {
                PlayerScreen(
                    media = current.value,
                    onBack = { finish() },
                    onRetry = { position -> retry(position) },
                    onRetryAlternateDecoder = {},
                    onReauthenticate = { position ->
                        reauthentications++
                        retryPosition = position
                        current.value = current.value.copy(
                            playbackAttemptId = UUID.randomUUID().toString(),
                            resumePositionMillis = position, playWhenReady = true
                        )
                    },
                    onPlaybackIntentChanged = { current.value = current.value.copy(playWhenReady = it) },
                    onVideoFrameRendered = { firstFrames++ },
                    onPlaybackAuthorizationFailure = { automaticRetries++ },
                    onPlayPrevious = { navigate(first) },
                    onPlayNext = { navigate(second) },
                    onProgress = { _, position, duration ->
                        progress = position
                        knownDuration = duration
                        android.util.Log.i("NikTvRegression", "progress=$position duration=$duration retries=$retries")
                    },
                    onPlaybackEngineChanged = { enginePreferenceChanges++ },
                    playbackEngine = engine
                )
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("fixtureResume", progress.coerceAtLeast(2500L))
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if (instance === this) { instance = null; retryNow = null }
        super.onDestroy()
    }
}
