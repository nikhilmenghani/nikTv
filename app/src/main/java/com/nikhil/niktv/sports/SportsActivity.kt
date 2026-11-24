package com.nikhil.niktv.sports

import com.nikhil.niktv.ui.findActivity
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
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.nikhil.niktv.discovery.LocalPortalFocusReturn
import com.nikhil.niktv.discovery.PortalFocusReturn

private const val EXTRA_FOCUS_PORTAL = "niktv.focus_opened_portal"

open class SportsActivity : ComponentActivity(), com.nikhil.niktv.ui.PlayerPictureInPictureHost {
    protected open val isPlayfyPortal: Boolean = false
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
        // IPTV portals replace content in place; Sports must use the same direct handoff.
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        enableEdgeToEdge()
        val users = UserSyncStore.get(applicationContext)
        val owner = users.selectedScope.value
        if (owner != null) lifecycleScope.launch {
            users.selectedScope.collect { if (it != owner) finish() }
        }
        showSportsContent()
    }

    override fun finish() {
        super.finish()
        if (android.os.Build.VERSION.SDK_INT < 34) {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun openMainPortal(key: String, restoreFocus: Boolean = false) {
        startActivity(Intent(this, com.nikhil.niktv.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(com.nikhil.niktv.discovery.PortalNavigationRequests.EXTRA_PORTAL, key)
            .putExtra(com.nikhil.niktv.discovery.PortalNavigationRequests.EXTRA_RESTORE_FOCUS, restoreFocus),
            android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
        finish()
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
        val focusOpenedPortal = intent.getBooleanExtra(EXTRA_FOCUS_PORTAL, false)
        intent.removeExtra(EXTRA_FOCUS_PORTAL)
        setContent { MaterialTheme(colorScheme = NikColors) {
            val portalFocus = remember { PortalFocusReturn() }
            val portalId = if (isPlayfyPortal) "playfy" else "sports"
            val view = LocalView.current
            val store = androidx.compose.runtime.remember { com.nikhil.niktv.data.ProfileStore(applicationContext) }
            val loadedPortals: List<com.nikhil.niktv.model.PortalProfile>? = store.profiles.collectAsStateWithLifecycle(initialValue = null).value
            val users = androidx.compose.runtime.remember { UserSyncStore.get(applicationContext) }
            val viewerScope = users.selectedScope.collectAsStateWithLifecycle().value
            val loadedHiddenPortals: Set<String>? = androidx.compose.runtime.remember(viewerScope) {
                com.nikhil.niktv.data.ProfilePortalAccess.observe(applicationContext, viewerScope)
            }.collectAsStateWithLifecycle(initialValue = null).value
            val hiddenPortals = loadedHiddenPortals.orEmpty()
            val viewers = users.profiles.collectAsStateWithLifecycle(initialValue = emptyList()).value
            val viewer = viewers.firstOrNull { it.id == viewerScope?.profileId }
            val actions = loadedPortals.orEmpty().filter { com.nikhil.niktv.data.ProfilePortalAccess.key(it) !in hiddenPortals }.map { portal -> com.nikhil.niktv.discovery.DirectPortalAction(portal.displayName,
                if (portal.portalType == com.nikhil.niktv.model.PortalType.STALKER) androidx.compose.material.icons.Icons.Default.Tv else androidx.compose.material.icons.Icons.Default.Key,
                id = portal.cacheKey(), open = {
                    openMainPortal(portal.cacheKey(), restoreFocus = !view.isInTouchMode)
                }) } + listOfNotNull(
                com.nikhil.niktv.discovery.DirectPortalAction("Sports", androidx.compose.material.icons.Icons.Default.SportsCricket,
                    selected = !isPlayfyPortal, id = "sports", opensNewWindow = true, open = {
                        val restoreFocus = !view.isInTouchMode
                        if (isPlayfyPortal) lifecycleScope.launch {
                            viewerScope?.let { users.rememberDestination(it, com.nikhil.niktv.data.ViewerDestination(com.nikhil.niktv.data.ViewerDestinationKind.SPORTS)) }
                            openSports(focusPortal = restoreFocus); finish()
                        }
                    }).takeIf { com.nikhil.niktv.data.ProfilePortalAccess.SPORTS !in hiddenPortals },
                com.nikhil.niktv.discovery.DirectPortalAction("Playfy TV", androidx.compose.material.icons.Icons.Default.LiveTv,
                    selected = isPlayfyPortal, id = "playfy", opensNewWindow = true, open = {
                        val restoreFocus = !view.isInTouchMode
                        if (!isPlayfyPortal) lifecycleScope.launch {
                            viewerScope?.let { users.rememberDestination(it, com.nikhil.niktv.data.ViewerDestination(com.nikhil.niktv.data.ViewerDestinationKind.PLAYFY)) }
                            openPlayfy(focusPortal = restoreFocus); finish()
                        }
                    }).takeIf { com.nikhil.niktv.data.ProfilePortalAccess.PLAYFY !in hiddenPortals })
            val portalAccess = if (isPlayfyPortal) com.nikhil.niktv.data.ProfilePortalAccess.PLAYFY else com.nikhil.niktv.data.ProfilePortalAccess.SPORTS
            if (portalAccess in hiddenPortals) {
                androidx.compose.runtime.LaunchedEffect(viewerScope) {
                    openMainPortal(com.nikhil.niktv.discovery.PortalNavigationRequests.PROFILE_CHOOSER)
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(com.nikhil.niktv.discovery.LocalDirectPortalActions provides actions,
                LocalPortalFocusReturn provides portalFocus,
                com.nikhil.niktv.ui.LocalSelectedUserProfile provides viewer,
                com.nikhil.niktv.ui.LocalOpenUserProfiles provides {
                    openMainPortal(com.nikhil.niktv.discovery.PortalNavigationRequests.PROFILE_CHOOSER)
                }) {
            androidx.compose.material3.Surface(color = NikColors.background, contentColor = NikColors.onBackground) {
                com.nikhil.niktv.ui.LanPlaybackHost(com.nikhil.niktv.data.LanPlaybackSessions.get(applicationContext)) {
                    if (!focusOpenedPortal || loadedPortals != null && loadedHiddenPortals != null) {
                        val portalEntry = if (focusOpenedPortal && actions.size > 1 && portalAccess !in hiddenPortals)
                            portalFocus.requester(portalId) else null
                        SportsScreen(onClose = ::finish, source = if (isPlayfyPortal) SportsCatalogueSource.PLAYFY else SportsCatalogueSource.CRICFY,
                            portalEntryFocus = portalEntry)
                    }
                }
            }
            }
        } }
    }
}

internal fun Context.openSports(focusPortal: Boolean = false) {
    val intent = Intent(this, SportsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        .putExtra(EXTRA_FOCUS_PORTAL, focusPortal)
    if (findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent, android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
}

internal fun Context.openPlayfy(focusPortal: Boolean = false) {
    val intent = Intent(this, PlayfyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        .putExtra(EXTRA_FOCUS_PORTAL, focusPortal)
    if (findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent, android.app.ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
}
