package com.nikhil.niktv.torrent

import android.app.ActivityOptions
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.nikhil.niktv.MainActivity
import com.nikhil.niktv.data.*
import com.nikhil.niktv.discovery.*
import com.nikhil.niktv.model.PortalType
import com.nikhil.niktv.sports.openPlayfy
import com.nikhil.niktv.sports.openSports
import com.nikhil.niktv.ui.*
import kotlinx.coroutines.launch

private const val EXTRA_PORTAL_FOCUS = "niktv.focus_opened_portal"
private const val EXTRA_TORRENT_DOWNLOAD = "torrent_download_key"

/** The torrent session and its local HTTP lease belong to this activity's retained model. */
class TorrentActivity : ComponentActivity(), PlayerPictureInPictureHost {
    private val model by viewModels<TorrentPortalViewModel>()
    private var playerActive = false
    override var pipModeListener: ((Boolean) -> Unit)? = null
    private val pipSupported get() = Build.VERSION.SDK_INT >= 26 &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
        !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    private fun pipParams() = if (Build.VERSION.SDK_INT >= 26) PictureInPictureParams.Builder()
        .setAspectRatio(Rational(16, 9)).apply {
            if (Build.VERSION.SDK_INT >= 31) { setAutoEnterEnabled(playerActive); setSeamlessResizeEnabled(true) }
        }.build() else null
    override fun setPlayerActiveForPip(active: Boolean) {
        playerActive = active
        if (Build.VERSION.SDK_INT >= 26 && pipSupported) pipParams()?.let(::setPictureInPictureParams)
    }
    override fun enterPlayerPictureInPicture(): Boolean {
        if (Build.VERSION.SDK_INT < 26 || !pipSupported || !playerActive) return false
        val params = pipParams() ?: return false
        return runCatching { enterPictureInPictureMode(params) }.getOrDefault(false)
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (Build.VERSION.SDK_INT in 26 until 31 && playerActive) enterPlayerPictureInPicture() }
    override fun onPictureInPictureModeChanged(inPip: Boolean, config: Configuration) {
        super.onPictureInPictureModeChanged(inPip, config); pipModeListener?.invoke(inPip)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // NikTV always uses a dark surface, even when Android's system theme is
        // light. Automatic bar styling would draw black icons on this backdrop.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        val users = UserSyncStore.get(applicationContext)
        val capturedOwner = users.selectedScope.value
        val playerStore = ProfileStore(applicationContext).let { store ->
            capturedOwner?.let(store::scoped) ?: store
        }
        lifecycleScope.launch { users.selectedScope.collect { if (it != capturedOwner) finish() } }
        val offlineDownloadKey = intent.getStringExtra(EXTRA_TORRENT_DOWNLOAD)?.takeIf { it.isNotBlank() }
        if (savedInstanceState == null) {
            if (offlineDownloadKey != null) model.openDownload(offlineDownloadKey)
            else intent.getStringExtra("torrent_source")?.let(model::manual)
        }
        val focusOpenedPortal = intent.getBooleanExtra(EXTRA_PORTAL_FOCUS, false)
        intent.removeExtra(EXTRA_PORTAL_FOCUS)
        setContent { MaterialTheme(colorScheme = NikColors) {
            var controlsTimeout by remember { mutableIntStateOf(3) }
            LaunchedEffect(playerStore) {
                playerStore.playerControlsTimeoutSeconds.collect { controlsTimeout = it }
            }
            val scope by users.selectedScope.collectAsStateWithLifecycle()
            val loadedHidden: Set<String>? by remember(scope) { ProfilePortalAccess.observe(applicationContext, scope) }
                .collectAsStateWithLifecycle(initialValue = null)
            val hidden = loadedHidden ?: setOf(ProfilePortalAccess.TORRENTS)
            LaunchedEffect(loadedHidden, scope) {
                if (offlineDownloadKey == null && loadedHidden != null && ProfilePortalAccess.TORRENTS in hidden)
                    openMain(PortalNavigationRequests.PROFILE_CHOOSER)
            }
            val portals by remember { ProfileStore(applicationContext).profiles }.collectAsStateWithLifecycle(initialValue = emptyList())
            val viewers by users.profiles.collectAsStateWithLifecycle(initialValue = emptyList())
            val viewer = viewers.firstOrNull { it.id == scope?.profileId }
            val viewerLayout by rememberViewerLayout(scope)
            val state by model.state.collectAsStateWithLifecycle()
            val orientation by rememberUiOrientationMode()
            ApplyUiOrientation(orientation, playingVideo = state.playing != null)
            val browserState = rememberSaveableStateHolder()
            val portalFocus = remember { PortalFocusReturn() }
            val view = LocalView.current
            val actions = portals.filter { ProfilePortalAccess.key(it) !in hidden }.map { portal ->
                DirectPortalAction(portal.displayName, if (portal.portalType == PortalType.STALKER) Icons.Default.Tv else Icons.Default.Key,
                    id = portal.cacheKey(), open = { openMain(portal.cacheKey(), !view.isInTouchMode) })
            } + listOfNotNull(
                DirectPortalAction("Sports", Icons.Default.SportsCricket, id = "sports", opensNewWindow = true, open = {
                    lifecycleScope.launch { scope?.let { users.rememberDestination(it, ViewerDestination(ViewerDestinationKind.SPORTS)) }; openSports(!view.isInTouchMode); finish() }
                }).takeIf { ProfilePortalAccess.SPORTS !in hidden },
                DirectPortalAction("Playfy TV", Icons.Default.LiveTv, id = "playfy", opensNewWindow = true, open = {
                    lifecycleScope.launch { scope?.let { users.rememberDestination(it, ViewerDestination(ViewerDestinationKind.PLAYFY)) }; openPlayfy(!view.isInTouchMode); finish() }
                }).takeIf { ProfilePortalAccess.PLAYFY !in hidden },
                DirectPortalAction("Torrents", Icons.Default.Download, selected = true, id = "torrents", opensNewWindow = true, open = {})
                    .takeIf { ProfilePortalAccess.TORRENTS !in hidden })
            val entryFocus = if (focusOpenedPortal && actions.size > 1) portalFocus.requester("torrents") else null
            LaunchedEffect(entryFocus, loadedHidden, portals) {
                if (entryFocus != null && loadedHidden != null && ProfilePortalAccess.TORRENTS !in hidden) {
                    withFrameNanos { }
                    runCatching { entryFocus.requestFocus() }
                }
            }
            CompositionLocalProvider(LocalViewingOwner provides scope, LocalSelectedUserProfile provides viewer,
                LocalViewerLayout provides viewerLayout,
                LocalOpenUserProfiles provides { openMain(PortalNavigationRequests.PROFILE_CHOOSER) },
                LocalDirectPortalActions provides actions, LocalPortalFocusReturn provides portalFocus) {
                Surface(color = NikColors.background, contentColor = NikColors.onBackground) {
                    if (state.playing != null) {
                        Box(Modifier.fillMaxSize()) {
                            CompositionLocalProvider(LocalPlaybackDownloadProgress provides model.downloadProgress.takeUnless { state.playing!!.offlinePlayback }) {
                            PlayerScreen(state.playing!!, if (offlineDownloadKey != null) ::finish else model::exitPlayer,
                                model::retry, model::retryDecoder, model::retry,
                                {}, model::nextEpisode, model::progress, playbackEngine = state.playbackEngine,
                                controlsTimeoutSeconds = controlsTimeout, onControlsTimeoutChanged = { seconds ->
                                    controlsTimeout = seconds
                                    lifecycleScope.launch { playerStore.setPlayerControlsTimeoutSeconds(seconds) }
                                },
                                onChooseAlternateSource = if (state.title != null && offlineDownloadKey == null) { position, _ -> model.chooseSource(position) } else null,
                                onPlayItem = model::queueEpisode, onVideoFrameRendered = model::playbackStarted,
                                onPlaybackEngineChanged = model::playbackEngine, startFullscreen = true)
                            }
                            state.transfer?.storageWarning?.takeIf { !state.advanceRequested }?.let { warning ->
                                Surface(Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(16.dp).widthIn(max = 640.dp),
                                    shape = RoundedCornerShape(12.dp), color = Color(0xDC231C13), contentColor = Color(0xFFFFCC80)) {
                                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.WarningAmber, contentDescription = null, Modifier.size(18.dp))
                                        Text(warning, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            if (state.advanceRequested && !state.nextReady) Surface(
                                Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(16.dp).widthIn(max = 640.dp),
                                shape = RoundedCornerShape(12.dp), color = Color(0xEE171B24), contentColor = Color.White) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (state.nextFailed) Icon(Icons.Default.ErrorOutline, contentDescription = "Preparation failed", Modifier.size(18.dp))
                                        else CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                        Text(state.nextStatus ?: "Preparing the next episode…", style = MaterialTheme.typography.bodySmall)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        NikTvActionButton("Choose source", model::chooseNextSource)
                                        NikTvActionButton("Retry next", model::retryNext)
                                    }
                                }
                            }
                        }
                    } else if (offlineDownloadKey != null) {
                        // An owned local download remains playable without enabling
                        // the portal. Never expose its online browser through this route.
                        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                            if (state.error == null) CircularProgressIndicator()
                            Text(state.error ?: state.loading ?: "Opening downloaded video…")
                            NikTvActionButton("Close", ::finish)
                        }
                    } else if (loadedHidden != null && ProfilePortalAccess.TORRENTS !in hidden)
                        browserState.SaveableStateProvider("torrent-browser") {
                            TorrentPortalScreen(model, viewer?.name ?: "Profile", { openMain(PortalNavigationRequests.PROFILE_CHOOSER) }, ::finish, entryFocus)
                        }
                }
            }
        } }
    }

    private fun openMain(portal: String, focus: Boolean = false) {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(PortalNavigationRequests.EXTRA_PORTAL, portal)
            .putExtra(PortalNavigationRequests.EXTRA_RESTORE_FOCUS, focus), ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
        finish()
    }
    override fun onStart() { super.onStart(); window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    override fun onStop() { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); super.onStop() }
    override fun finish() { super.finish(); if (Build.VERSION.SDK_INT < 34) @Suppress("DEPRECATION") overridePendingTransition(0, 0) }
}

internal fun Context.openTorrents(focusPortal: Boolean = false) {
    val intent = Intent(this, TorrentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        .putExtra(EXTRA_PORTAL_FOCUS, focusPortal)
    if (findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
}

internal fun Context.openTorrentDownload(key: String) {
    val intent = Intent(this, TorrentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        .putExtra(EXTRA_TORRENT_DOWNLOAD, key)
    if (findActivity() == null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
}
