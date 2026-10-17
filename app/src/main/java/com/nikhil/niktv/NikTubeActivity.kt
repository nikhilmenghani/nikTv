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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.nikhil.niktv.data.NikTubeSession
import com.nikhil.niktv.ui.NikTubeMobileViewModel
import com.nikhil.niktv.ui.NikTubeMobileScreen

class NikTubeActivity : ComponentActivity() {
    internal val tubeViewModel by viewModels<NikTubeViewModel>()
    internal val tubeMobileViewModel by viewModels<NikTubeMobileViewModel>()
    var tubeInPip by mutableStateOf(false); private set
    private val tv: Boolean get() = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) || !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val engine = com.nikhil.niktv.data.NikTubeEnginePreferences.resolved(this)
            val target = if (engine == com.nikhil.niktv.data.NikTubeEngine.SMARTTUBE) {
                Class.forName("com.liskovsoft.smartyoutubetv2.tv.ui.main.MainApplication")
                    .getMethod("initialize", android.content.Context::class.java).invoke(null, applicationContext)
                "com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"
            } else {
                (application as NikTvApplication).restoreHostDensity(this)
                "io.github.aedev.flow.MainActivity"
            }
            startActivity(android.content.Intent(intent).setClassName(packageName, target))
            finish()
            return
        }
        if (!tv) window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        setContent {
            val videoSessionActive = tv && (tubeViewModel.playing != null || tubeViewModel.resolving != null)
            DisposableEffect(videoSessionActive) {
                val previousOrientation = requestedOrientation
                if (videoSessionActive) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                onDispose { if (videoSessionActive) requestedOrientation = previousOrientation }
            }
            MaterialTheme(colorScheme = NikColors) {
                Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    if (tv) NikTubeScreen(onBack = { finish() }, vm = tubeViewModel)
                    else NikTubeMobileScreen(back = { finish() }, vm = tubeMobileViewModel)
                }
            }
        }
    }
    override fun onPictureInPictureModeChanged(inPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPictureInPictureMode, newConfig)
        tubeInPip = inPictureInPictureMode
    }
    override fun onStop() {
        if (!tv && android.os.Build.VERSION.SDK_INT < 26) {
            tubeMobileViewModel.saveProgress(true)
            if (!isChangingConfigurations && !tubeInPip && !NikTubeSession.backgroundAudio) NikTubeSession.player?.pause()
        }
        super.onStop()
    }
}
