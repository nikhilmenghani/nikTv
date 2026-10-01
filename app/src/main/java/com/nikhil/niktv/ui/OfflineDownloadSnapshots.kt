package com.nikhil.niktv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikhil.niktv.data.OfflineDownloadInfo
import com.nikhil.niktv.data.OfflineDownloadStatus
import com.nikhil.niktv.model.OfflineMediaDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** One observable snapshot shared by tiles, player controls and the downloads screen. */
internal object OfflineDownloadSnapshots {
    private val mutableState = MutableStateFlow<Map<String, OfflineDownloadInfo>>(emptyMap())
    val state = mutableState.asStateFlow()
    fun publish(value: Map<String, OfflineDownloadInfo>) { mutableState.value = value }
}

/** WorkManager futures, Media3 indices and DownloadManager cursors must never block Main. */
internal suspend fun queryOfflineDownloadSnapshot(
    entries: List<OfflineMediaDownload>,
    query: (OfflineMediaDownload) -> OfflineDownloadInfo
): Map<String, OfflineDownloadInfo> = withContext(Dispatchers.IO) {
    entries.distinctBy { it.key }.associate { it.key to query(it) }
}

@Composable
internal fun observedOfflineDownloadInfo(entry: OfflineMediaDownload?): OfflineDownloadInfo? {
    val infos by OfflineDownloadSnapshots.state.collectAsStateWithLifecycle()
    return entry?.let { infos[it.key] ?: OfflineDownloadInfo(OfflineDownloadStatus.QUEUED) }
}
