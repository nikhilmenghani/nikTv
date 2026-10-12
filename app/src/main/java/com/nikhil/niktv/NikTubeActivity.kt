package com.nikhil.niktv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import com.nikhil.niktv.ui.NikColors
import com.nikhil.niktv.ui.NikTubeScreen
import androidx.activity.viewModels
import com.nikhil.niktv.ui.NikTubeViewModel
import androidx.compose.runtime.DisposableEffect
import android.content.pm.ActivityInfo

class NikTubeActivity : ComponentActivity() {
    internal val tubeViewModel by viewModels<NikTubeViewModel>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val videoSessionActive = tubeViewModel.playing != null || tubeViewModel.resolving != null
            DisposableEffect(videoSessionActive) {
                val previousOrientation = requestedOrientation
                if (videoSessionActive) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                onDispose { if (videoSessionActive) requestedOrientation = previousOrientation }
            }
            MaterialTheme(colorScheme = NikColors) {
                Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) { NikTubeScreen(onBack = { finish() }, vm = tubeViewModel) }
            }
        }
    }
}
