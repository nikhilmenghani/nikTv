package com.nikhil.niktv.sports

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.nikhil.niktv.data.UserSyncStore
import androidx.compose.material3.MaterialTheme
import com.nikhil.niktv.ui.NikColors
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.filled.*

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
        val users = UserSyncStore.get(applicationContext)
        val owner = users.selectedScope.value
        if (owner != null) lifecycleScope.launch {
            users.selectedScope.collect { if (it != owner) finish() }
        }
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
            val store = androidx.compose.runtime.remember { com.nikhil.niktv.data.ProfileStore(applicationContext) }
            val portals = store.profiles.collectAsStateWithLifecycle(initialValue = emptyList()).value
            val users = androidx.compose.runtime.remember { UserSyncStore.get(applicationContext) }
            val viewerScope = users.selectedScope.collectAsStateWithLifecycle().value
            val viewers = users.profiles.collectAsStateWithLifecycle(initialValue = emptyList()).value
            val viewer = viewers.firstOrNull { it.id == viewerScope?.profileId }
            val actions = portals.map { portal -> com.nikhil.niktv.discovery.DirectPortalAction(portal.name,
                if (portal.portalType == com.nikhil.niktv.model.PortalType.STALKER) androidx.compose.material.icons.Icons.Default.Tv else androidx.compose.material.icons.Icons.Default.Key,
                open = {
                    startActivity(Intent(this, com.nikhil.niktv.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(com.nikhil.niktv.discovery.PortalNavigationRequests.EXTRA_PORTAL, portal.cacheKey()))
                    finish()
                }) } + com.nikhil.niktv.discovery.DirectPortalAction("Sports", androidx.compose.material.icons.Icons.Default.SportsCricket, selected = true, open = {})
            androidx.compose.runtime.CompositionLocalProvider(com.nikhil.niktv.discovery.LocalDirectPortalActions provides actions,
                com.nikhil.niktv.ui.LocalSelectedUserProfile provides viewer,
                com.nikhil.niktv.ui.LocalOpenUserProfiles provides {
                    startActivity(Intent(this, com.nikhil.niktv.MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(com.nikhil.niktv.discovery.PortalNavigationRequests.EXTRA_PORTAL,
                            com.nikhil.niktv.discovery.PortalNavigationRequests.PROFILE_CHOOSER))
                    finish()
                }) {
            androidx.compose.material3.Surface(color = NikColors.background, contentColor = NikColors.onBackground) {
                com.nikhil.niktv.ui.LanPlaybackHost(com.nikhil.niktv.data.LanPlaybackSessions.get(applicationContext)) {
                    SportsScreen(onClose = ::finish)
                }
            }
            }
        } }
    }
}

internal fun Context.openSports() { startActivity(Intent(this, SportsActivity::class.java)) }
