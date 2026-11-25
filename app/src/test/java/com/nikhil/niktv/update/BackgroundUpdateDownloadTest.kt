package com.nikhil.niktv.update

import android.app.Application
import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import androidx.work.NetworkType
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class BackgroundUpdateDownloadTest {
    private val update = UpdateInfo("99.0.0", "https://example.invalid/update-arm64.apk", UpdatePackage.ARM64)

    @Test fun waitsForUnmeteredNetworkAndEnoughStorageWithoutExpeditedWork() {
        val work = backgroundUpdateWork(update)
        assertEquals(NetworkType.UNMETERED, work.workSpec.constraints.requiredNetworkType)
        assertTrue(work.workSpec.constraints.requiresStorageNotLow())
        assertFalse(work.workSpec.expedited)
        assertEquals(update.version, work.workSpec.input.getString("version"))
        assertEquals(update.downloadUrl, work.workSpec.input.getString("url"))
        assertEquals("ARM64", work.workSpec.input.getString("package"))
    }

    @Test fun downloadUsesAppStorageWithoutRequestingPublicStoragePermission() {
        val context = RuntimeEnvironment.getApplication()
        val request = Shadows.shadowOf(backgroundUpdateRequest(context, update, "NikTV-update.apk"))
        val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates").canonicalPath
        assertEquals(File(folder, "NikTV-update.apk").canonicalPath, File(request.destination.path!!).canonicalPath)
        assertFalse(request.allowedOverMetered)
        assertFalse(request.allowedOverRoaming)
        assertEquals(DownloadManager.Request.VISIBILITY_VISIBLE, request.notificationVisibility)
    }

    @Test fun reusesQueuedRunningPausedAndReadyDownloadButNotWrongPackageOrFailure() {
        val states = listOf(UpdateDownloadState.Queued(7, update.version, update.downloadUrl),
            UpdateDownloadState.Downloading(7, update.version, update.downloadUrl, 10, 100, 10),
            UpdateDownloadState.Paused(7, update.version, update.downloadUrl, 10, 100, "Offline"),
            UpdateDownloadState.Ready(7, update.version, update.downloadUrl, Uri.parse("content://downloads/7")))
        for (state in states) {
            assertEquals(7L, reusableUpdateDownload(state, update))
            assertNull(reusableUpdateDownload(state, update.copy(downloadUrl = "https://example.invalid/update-arm32.apk")))
            assertNull(reusableUpdateDownload(state, update.copy(version = "99.0.1")))
        }
        assertNull(reusableUpdateDownload(UpdateDownloadState.Idle, update))
        assertNull(reusableUpdateDownload(UpdateDownloadState.Failed(7, update.version, update.downloadUrl, null, "Failed"), update))
    }

    @Test fun olderQueuedWorkerCannotReplaceNewerDownload() {
        assertEquals(7L, reusableUpdateDownload(UpdateDownloadState.Queued(7, "99.0.1", "https://example.invalid/newer.apk"), update))
    }
}
