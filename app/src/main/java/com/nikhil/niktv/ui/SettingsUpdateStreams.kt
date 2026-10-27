package com.nikhil.niktv.ui

import android.content.Context
import androidx.compose.runtime.*
import com.nikhil.niktv.update.*
import kotlinx.coroutines.flow.StateFlow

/** Rendering can be exercised without initializing the update service or scheduling network work. */
internal data class SettingsUpdateStreams(
    val download: StateFlow<UpdateDownloadState>,
    val pending: StateFlow<UpdateInfo?>,
    val enforcement: StateFlow<Boolean>,
    val startupCheck: StateFlow<Boolean>,
    val updatePackage: StateFlow<UpdatePackage>,
    val obsoleteApks: suspend (Context) -> DownloadedApkCleanup?
)

internal val LocalSettingsUpdateStreams = staticCompositionLocalOf<SettingsUpdateStreams?> { null }

@Composable
internal fun settingsUpdateStreams(): SettingsUpdateStreams = LocalSettingsUpdateStreams.current ?: remember {
    SettingsUpdateStreams(AppUpdates.downloadState, AppUpdates.pendingUpdate,
        AppUpdates.updateEnforcementEnabled, AppUpdates.startupUpdateCheckEnabled,
        AppUpdates.updatePackage, { AppUpdates.obsoleteDownloadedApks(it) })
}
