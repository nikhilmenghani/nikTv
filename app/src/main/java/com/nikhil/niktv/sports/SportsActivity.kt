package com.nikhil.niktv.sports

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import com.nikhil.niktv.ui.NikColors

open class SportsActivity : ComponentActivity(), com.nikhil.niktv.ui.PlayerPictureInPictureHost {
    private var playerActive = false
    override var pipModeListener: ((Boolean) -> Unit)? = null
    private val pipSupported: Boolean get() = android.os.Build.VERSION.SDK_INT >= 26 &&
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
        !packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)

    private fun pipParams() = if (android.os.Build.VERSION.SDK_INT >= 26) android.app.PictureInPictureParams.Builder()
        .setAspectRatio(android.util.Rational(16, 9)).apply {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                setAutoEnterEnabled(playerActive)
                setSeamlessResizeEnabled(true)
            }
        }.build() else null

    override fun setPlayerActiveForPip(active: Boolean) {
        playerActive = active
        if (pipSupported) pipParams()?.let(::setPictureInPictureParams)
    }

    override fun enterPlayerPictureInPicture(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 26 || !pipSupported || !playerActive) return false
        return pipParams()?.let { runCatching { enterPictureInPictureMode(it) }.getOrDefault(false) } ?: false
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (android.os.Build.VERSION.SDK_INT in 26 until 31 && playerActive) enterPlayerPictureInPicture()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, config: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPip, config)
        pipModeListener?.invoke(inPip)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        showSportsContent()
    }

    override fun onStart() {
        super.onStart()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    protected open fun showSportsContent() {
        setContent { MaterialTheme(colorScheme = NikColors) {
            androidx.compose.material3.Surface(color = NikColors.background, contentColor = NikColors.onBackground) {
                SportsScreen(onClose = ::finish)
            }
        } }
    }
}

internal fun Context.openSports() { startActivity(Intent(this, SportsActivity::class.java)) }
