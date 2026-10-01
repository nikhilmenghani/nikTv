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
        @Volatile var progress = 0L
        @Volatile var knownDuration = 0L
        @Volatile var retries = 0
        @Volatile var retryPosition = -1L
        @Volatile var automaticRetries = 0
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        progress = 0L
        retries = 0
        automaticRetries = 0
        val engine = PlaybackEngine.valueOf(intent.getStringExtra("engine") ?: "MEDIA3")
        val initial = PlayingMedia(
            media = MediaItem("fixture", "Local regression fixture", null, null),
            url = android.net.Uri.fromFile(File(cacheDir, "retry.wav")).toString(),
            catalogType = CatalogType.MOVIES,
            progressKey = "synthetic-fixture",
            resumePositionMillis = 2500L,
            authorizationRetryCount = 1
        )
        val current = mutableStateOf(initial)
        setContent {
            MaterialTheme {
                PlayerScreen(
                    media = current.value,
                    onBack = { finish() },
                    onRetry = { position ->
                        retryPosition = position
                        retries++
                        current.value = current.value.copy(
                            playbackAttemptId = UUID.randomUUID().toString(),
                            resumePositionMillis = position,
                            authorizationRetryCount = 0
                        )
                    },
                    onRetryAlternateDecoder = {},
                    onPlaybackAuthorizationFailure = { automaticRetries++ },
                    onPlayPrevious = {},
                    onPlayNext = {},
                    onProgress = { _, position, duration ->
                        progress = position
                        knownDuration = duration
                        android.util.Log.i("NikTvRegression", "progress=$position duration=$duration retries=$retries")
                    },
                    playbackEngine = engine
                )
            }
        }
    }
}
