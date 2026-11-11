package com.nikhil.niktv

import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import com.nikhil.niktv.data.AccountSyncManager
import com.nikhil.niktv.data.UserSyncStore
import com.nikhil.niktv.update.AppUpdates
import com.nikhil.niktv.data.GitHubBackupScheduler
import com.nikhil.niktv.data.PairingInvites
import com.nikhil.niktv.data.SearchMetadataSyncScheduler
import com.nikhil.niktv.ui.NikTvApp

class MainActivity : ComponentActivity(), com.nikhil.niktv.ui.PlayerPictureInPictureHost {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var playerActive = false
    private var startedOnce = false
    override var pipModeListener: ((Boolean) -> Unit)? = null
    var playerTransportKeyHandler: ((KeyEvent) -> Boolean)? = null

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

    override fun setPlayerActiveForPip(active: Boolean) {
        playerActive = active
        if (pipSupported) playerPipParams()?.let(::setPictureInPictureParams)
    }

    override fun enterPlayerPictureInPicture(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !pipSupported || !playerActive) return false
        return playerPipParams()?.let { enterPictureInPictureMode(it) } ?: false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.callback = com.nikhil.niktv.ui.PlayerTransportWindowCallback(window.callback) { event ->
            playerTransportKeyHandler?.invoke(event) == true
        }
        PairingInvites.accept(intent?.data)
        intent?.getStringExtra(com.nikhil.niktv.discovery.PortalNavigationRequests.EXTRA_PORTAL)?.let {
            com.nikhil.niktv.discovery.PortalNavigationRequests.pending.value = it
            intent.removeExtra(com.nikhil.niktv.discovery.PortalNavigationRequests.EXTRA_PORTAL)
        }
        setContent { com.nikhil.niktv.ui.SetupRecoveryGate(prepare = {
            AppUpdates.initialize(applicationContext)
            GitHubBackupScheduler.initialize(applicationContext)
            SearchMetadataSyncScheduler.initialize(applicationContext)
            com.nikhil.niktv.data.NewEpisodeNotificationScheduler.initialize(applicationContext)
        }) { NikTvApp() } }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            lifecycleScope.launch {
                val sync = AccountSyncManager.get(applicationContext)
                combine(sync.state, UserSyncStore.get(applicationContext).selectedScope) { account, viewer ->
                    account.initialized && viewer != null && viewer.accountId == account.accountId
                }.first { it }
                // Token validation and first profile selection precede optional notifications.
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        PairingInvites.accept(intent.data)
        intent.getStringExtra(com.nikhil.niktv.discovery.PortalNavigationRequests.EXTRA_PORTAL)?.let {
            com.nikhil.niktv.discovery.PortalNavigationRequests.pending.value = it
            intent.removeExtra(com.nikhil.niktv.discovery.PortalNavigationRequests.EXTRA_PORTAL)
        }
    }

    override fun onStart() {
        super.onStart()
        // The account gate performs initial setup/launch sync. This handles
        // returning from the background without duplicating the first request.
        if (startedOnce) AccountSyncManager.get(applicationContext).requestLaunchSync()
        startedOnce = true
    }

    override fun onStop() {
        super.onStop()
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
