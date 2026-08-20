package com.nikhil.niktv

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.nikhil.niktv.update.AppUpdates
import com.nikhil.niktv.ui.NikTvApp

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var playerActive = false
    var pipModeListener: ((Boolean) -> Unit)? = null

    private val pipSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    private fun playerPipParams() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(playerActive)
                    setSeamlessResizeEnabled(true)
                }
            }
            .build()
    } else null

    fun setPlayerActiveForPip(active: Boolean) {
        playerActive = active
        if (pipSupported) playerPipParams()?.let(::setPictureInPictureParams)
    }

    fun enterPlayerPictureInPicture(): Boolean {
        if (!pipSupported || !playerActive) return false
        return playerPipParams()?.let(::enterPictureInPictureMode) ?: false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppUpdates.initialize(applicationContext)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        setContent { NikTvApp() }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ uses auto-enter for smoother transitions. Older devices
        // enter here when Home is pressed while playback is active.
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S && playerActive) {
            enterPlayerPictureInPicture()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipModeListener?.invoke(isInPictureInPictureMode)
    }
}
