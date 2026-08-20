package com.nikhil.niktv.update

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nikhil.niktv.BuildConfig
import com.nikhil.niktv.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

data class UpdateInfo(val version: String, val downloadUrl: String)

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState

    data class Queued(
        val downloadId: Long,
        val version: String,
        val downloadUrl: String
    ) : UpdateDownloadState

    data class Downloading(
        val downloadId: Long,
        val version: String,
        val downloadUrl: String,
        val bytesDownloaded: Long,
        val totalBytes: Long?,
        val percent: Int?
    ) : UpdateDownloadState

    data class Paused(
        val downloadId: Long,
        val version: String,
        val downloadUrl: String,
        val bytesDownloaded: Long,
        val totalBytes: Long?,
        val reason: String
    ) : UpdateDownloadState

    data class Ready(
        val downloadId: Long,
        val version: String,
        val downloadUrl: String,
        val contentUri: Uri,
        val awaitingUnknownSourcesPermission: Boolean = false
    ) : UpdateDownloadState

    data class Installing(
        val downloadId: Long,
        val version: String,
        val downloadUrl: String,
        val contentUri: Uri
    ) : UpdateDownloadState

    data class InstallerLaunched(
        val downloadId: Long,
        val version: String,
        val downloadUrl: String,
        val contentUri: Uri
    ) : UpdateDownloadState

    data class Failed(
        val downloadId: Long?,
        val version: String,
        val downloadUrl: String,
        val reasonCode: Int?,
        val message: String
    ) : UpdateDownloadState
}

enum class InstallHandoff {
    UNKNOWN_SOURCES_SETTINGS_LAUNCHED,
    INSTALLER_LAUNCHED
}

object AppUpdates {
    private const val PERIODIC = "niktv-periodic-update"
    private const val STARTUP = "niktv-startup-update"
    private const val PREFS = "app_update_download"
    private const val PREF_ID = "download_id"
    private const val PREF_VERSION = "version"
    private const val PREF_URL = "url"
    private const val PREF_PENDING_VERSION = "pending_version"
    private const val PREF_PENDING_URL = "pending_url"
    private const val PREF_INSTALL_AFTER_DOWNLOAD = "install_after_download"
    private const val PREF_ENFORCE_UPDATES = "enforce_updates"
    private const val CHANNEL = "niktv-updates"
    private const val AVAILABLE_NOTIFICATION_ID = 1001
    private const val READY_NOTIFICATION_ID = 1002
    private const val RELEASES_URL = "https://api.github.com/repos/nikhilmenghani/nikTv/releases?per_page=30"
    private const val LATEST_URL = "https://api.github.com/repos/nikhilmenghani/nikTv/releases/latest"
    private const val APK_MIME = "application/vnd.android.package-archive"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableDownloadState = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val downloadState: StateFlow<UpdateDownloadState> = mutableDownloadState.asStateFlow()
    private val mutablePendingUpdate = MutableStateFlow<UpdateInfo?>(null)
    val pendingUpdate: StateFlow<UpdateInfo?> = mutablePendingUpdate.asStateFlow()
    private val mutableUpdateEnforcementEnabled = MutableStateFlow(!BuildConfig.DEBUG)
    val updateEnforcementEnabled: StateFlow<Boolean> = mutableUpdateEnforcementEnabled.asStateFlow()

    const val ACTION_REQUEST_UPDATE_DOWNLOAD =
        "com.nikhil.niktv.action.REQUEST_UPDATE_DOWNLOAD"
    const val PUBLIC_DOWNLOADS_PERMISSION_MESSAGE =
        "Storage permission is needed to save the update in Downloads/NikTV. Allow it and try again."

    @Volatile
    private var initialized = false
    private lateinit var appContext: Context
    private var pollJob: Job? = null

    @Synchronized
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        initialized = true
        mutableUpdateEnforcementEnabled.value = preferences().getBoolean(PREF_ENFORCE_UPDATES, !BuildConfig.DEBUG)
        createChannel(appContext)
        restorePendingUpdate()

        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
        )
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            STARTUP,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UpdateCheckWorker>().setConstraints(constraints).build()
        )
        restoreDownload()
    }

    fun setUpdateEnforcementEnabled(enabled: Boolean) {
        check(initialized) { "AppUpdates has not been initialized" }
        preferences().edit().putBoolean(PREF_ENFORCE_UPDATES, enabled).apply()
        mutableUpdateEnforcementEnabled.value = enabled
    }

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val url = if (BuildConfig.DEBUG) RELEASES_URL else LATEST_URL
        val response = client.newCall(
            Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
        ).execute()
        response.use {
            if (!it.isSuccessful) error("GitHub returned HTTP ${it.code}")
            val root = Json.parseToJsonElement(it.body?.string().orEmpty())
            val releases = if (root is JsonArray) root else JsonArray(listOf(root))
            releases.asSequence().mapNotNull(::parseRelease)
                .filter { update -> isNewer(update.version, BuildConfig.VERSION_NAME) }
                .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
        }
    }

    @Synchronized
    fun download(context: Context, update: UpdateInfo): Long {
        ensureInitialized(context)
        require(update.version.isNotBlank()) { "Update version is missing" }
        require(update.downloadUrl.startsWith("https://")) { "Update download URL is invalid" }
        if (!canWritePublicDownloads(context)) {
            persistPendingUpdate(update)
            throw SecurityException(PUBLIC_DOWNLOADS_PERMISSION_MESSAGE)
        }

        currentDownloadIfUsable()?.let { current ->
            if (!isNewer(update.version, current.version)) {
                clearPendingUpdate(update)
                return current.downloadId
            }
            pollJob?.cancel()
            runCatching { downloadManager().remove(current.downloadId) }
                .onFailure {
                    Log.w(TAG, "Could not remove superseded download ${current.downloadId}", it)
                }
            clearDownloadMetadata()
            mutableDownloadState.value = UpdateDownloadState.Idle
        }
        val previousId = (mutableDownloadState.value as? UpdateDownloadState.Failed)?.downloadId
        if (previousId != null) {
            runCatching { downloadManager().remove(previousId) }
                .onFailure { Log.w(TAG, "Could not remove failed download $previousId", it) }
        }

        val fileName = apkFileName(update.version)
        val request = DownloadManager.Request(Uri.parse(update.downloadUrl))
            .setTitle("NikTV ${update.version}")
            .setDescription("Downloading NikTV update")
            .setMimeType(APK_MIME)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                "NikTV/$fileName"
            )

        return try {
            val id = downloadManager().enqueue(request)
            preferences().edit()
                .putLong(PREF_ID, id)
                .putString(PREF_VERSION, update.version)
                .putString(PREF_URL, update.downloadUrl)
                .apply()
            mutableDownloadState.value = UpdateDownloadState.Queued(id, update.version, update.downloadUrl)
            clearPendingUpdate(update)
            startPolling(id, update.version, update.downloadUrl)
            id
        } catch (exception: Exception) {
            preferences().edit()
                .remove(PREF_ID)
                .putString(PREF_VERSION, update.version)
                .putString(PREF_URL, update.downloadUrl)
                .apply()
            mutableDownloadState.value = UpdateDownloadState.Failed(
                downloadId = null,
                version = update.version,
                downloadUrl = update.downloadUrl,
                reasonCode = null,
                message = "Could not start download: ${exception.message ?: exception.javaClass.simpleName}"
            )
            throw exception
        }
    }

    /** Starts one user-visible update flow and hands the APK to Android's installer when ready. */
    fun downloadAndInstall(context: Context, update: UpdateInfo): Long {
        ensureInitialized(context)
        preferences().edit().putBoolean(PREF_INSTALL_AFTER_DOWNLOAD, true).apply()
        val id = download(context, update)
        if (mutableDownloadState.value is UpdateDownloadState.Ready) {
            handOffCompletedDownload()
        }
        return id
    }

    fun retry(context: Context): Long {
        val failed = mutableDownloadState.value as? UpdateDownloadState.Failed
            ?: error("There is no failed update download to retry")
        return download(context, UpdateInfo(failed.version, failed.downloadUrl))
    }

    fun requiresLegacyStoragePermission(): Boolean =
        Build.VERSION.SDK_INT in Build.VERSION_CODES.N..Build.VERSION_CODES.P

    fun canWritePublicDownloads(context: Context): Boolean =
        !requiresLegacyStoragePermission() ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

    fun canStartDownload(update: UpdateInfo): Boolean =
        currentDownloadIfUsable()?.let { isNewer(update.version, it.version) } != false

    fun deferDownload(context: Context, update: UpdateInfo) {
        ensureInitialized(context)
        persistPendingUpdate(update)
    }

    fun deferDownloadAndInstall(context: Context, update: UpdateInfo) {
        ensureInitialized(context)
        preferences().edit().putBoolean(PREF_INSTALL_AFTER_DOWNLOAD, true).apply()
        persistPendingUpdate(update)
    }

    fun dismissPendingUpdate(update: UpdateInfo) {
        clearPendingUpdate(update)
    }

    fun install(context: Context): InstallHandoff {
        ensureInitialized(context)
        val state = mutableDownloadState.value
        val candidate = when (state) {
            is UpdateDownloadState.Ready -> InstallCandidate(
                state.downloadId, state.version, state.downloadUrl, state.contentUri
            )
            is UpdateDownloadState.InstallerLaunched -> InstallCandidate(
                state.downloadId, state.version, state.downloadUrl, state.contentUri
            )
            else -> error("The update is not ready to install")
        }

        val uri = downloadManager().getUriForDownloadedFile(candidate.downloadId)
            ?: run {
                mutableDownloadState.value = UpdateDownloadState.Failed(
                    candidate.downloadId,
                    candidate.version,
                    candidate.downloadUrl,
                    null,
                    "The downloaded APK is no longer available. Download it again."
                )
                error("The downloaded APK is no longer available")
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !appContext.packageManager.canRequestPackageInstalls()
        ) {
            openUnknownSourcesSettings(appContext)
            mutableDownloadState.value = UpdateDownloadState.Ready(
                candidate.downloadId,
                candidate.version,
                candidate.downloadUrl,
                uri,
                awaitingUnknownSourcesPermission = true
            )
            return InstallHandoff.UNKNOWN_SOURCES_SETTINGS_LAUNCHED
        }

        mutableDownloadState.value = UpdateDownloadState.Installing(
            candidate.downloadId, candidate.version, candidate.downloadUrl, uri
        )
        try {
            launchPackageInstaller(appContext, uri)
        } catch (exception: Exception) {
            mutableDownloadState.value = UpdateDownloadState.Ready(
                candidate.downloadId, candidate.version, candidate.downloadUrl, uri
            )
            throw IllegalStateException(
                "Android could not open the package installer: ${exception.message ?: exception.javaClass.simpleName}",
                exception
            )
        }
        mutableDownloadState.value = UpdateDownloadState.InstallerLaunched(
            candidate.downloadId, candidate.version, candidate.downloadUrl, uri
        )
        preferences().edit().putBoolean(PREF_INSTALL_AFTER_DOWNLOAD, false).apply()
        notificationManager().cancel(READY_NOTIFICATION_ID)
        return InstallHandoff.INSTALLER_LAUNCHED
    }

    fun openDownloads(context: Context) {
        ensureInitialized(context)
        val primary = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(primary)
        } catch (first: Exception) {
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("content://downloads/public_downloads"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                appContext.startActivity(fallback)
            } catch (second: Exception) {
                second.addSuppressed(first)
                throw IllegalStateException("No Downloads app is available on this device", second)
            }
        }
    }

    fun savedLocation(version: String): String = "Downloads/NikTV/${apkFileName(version)}"

    fun notifyAvailable(context: Context, update: UpdateInfo) {
        if (!notificationsAllowed(context)) return
        val intent = Intent(context, UpdateActionReceiver::class.java)
            .setAction(UpdateActionReceiver.DOWNLOAD_AND_INSTALL)
            .putExtra("version", update.version)
            .putExtra("url", update.downloadUrl)
        val action = PendingIntent.getBroadcast(
            context,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notify(
            context,
            AVAILABLE_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("NikTV ${update.version} is available")
                .setContentText("Download the update and open Android's installer.")
                .setAutoCancel(true)
                .addAction(android.R.drawable.stat_sys_download, "Download & Install", action)
                .build()
        )
    }

    private fun notifyReadyToInstall(version: String) {
        if (!notificationsAllowed(appContext)) return
        val intent = Intent(appContext, UpdateActionReceiver::class.java)
            .setAction(UpdateActionReceiver.INSTALL)
        val action = PendingIntent.getBroadcast(
            appContext,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notify(
            appContext,
            READY_NOTIFICATION_ID,
            NotificationCompat.Builder(appContext, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("NikTV $version is ready")
                .setContentText("Tap to finish installing the update.")
                .setContentIntent(action)
                .setAutoCancel(true)
                .addAction(android.R.drawable.stat_sys_download_done, "Install", action)
                .build()
        )
    }

    private fun restoreDownload() {
        val prefs = preferences()
        val id = prefs.getLong(PREF_ID, -1L)
        val version = prefs.getString(PREF_VERSION, null).orEmpty()
        val url = prefs.getString(PREF_URL, null).orEmpty()
        if (id < 0 || version.isBlank() || url.isBlank()) {
            mutableDownloadState.value = UpdateDownloadState.Idle
            return
        }
        if (!isNewer(version, BuildConfig.VERSION_NAME)) {
            clearDownloadMetadata()
            mutableDownloadState.value = UpdateDownloadState.Idle
            return
        }
        mutableDownloadState.value = UpdateDownloadState.Queued(id, version, url)
        startPolling(id, version, url)
    }

    private fun startPolling(id: Long, version: String, url: String) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var keepPolling: Boolean
            do {
                keepPolling = try {
                    queryDownload(id, version, url)
                } catch (exception: Exception) {
                    mutableDownloadState.value = UpdateDownloadState.Failed(
                        id,
                        version,
                        url,
                        null,
                        "Could not read download status: ${exception.message ?: exception.javaClass.simpleName}"
                    )
                    false
                }
                if (keepPolling) {
                    val delayMillis =
                        if (mutableDownloadState.value is UpdateDownloadState.Paused) 3_000L else 1_000L
                    delay(delayMillis)
                }
            } while (keepPolling)
        }
    }

    private fun queryDownload(id: Long, version: String, url: String): Boolean {
        val query = DownloadManager.Query().setFilterById(id)
        val result = downloadManager().query(query)
            ?: error("Android Download Manager returned no status cursor")
        result.use { cursor ->
            if (!cursor.moveToFirst()) {
                mutableDownloadState.value = UpdateDownloadState.Failed(
                    id, version, url, null, "The download is no longer listed by Android. Try again."
                )
                return false
            }
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val bytes = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            ).coerceAtLeast(0L)
            val rawTotal = cursor.getLong(
                cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            )
            val total = rawTotal.takeIf { it > 0L }
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            when (status) {
                DownloadManager.STATUS_PENDING -> {
                    mutableDownloadState.value = UpdateDownloadState.Queued(id, version, url)
                    return true
                }
                DownloadManager.STATUS_RUNNING -> {
                    mutableDownloadState.value = UpdateDownloadState.Downloading(
                        id, version, url, bytes, total, downloadPercent(bytes, total)
                    )
                    return true
                }
                DownloadManager.STATUS_PAUSED -> {
                    mutableDownloadState.value = UpdateDownloadState.Paused(
                        id, version, url, bytes, total, pausedReasonMessage(reason)
                    )
                    return true
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val uri = downloadManager().getUriForDownloadedFile(id)
                    mutableDownloadState.value = if (uri != null) {
                        UpdateDownloadState.Ready(id, version, url, uri)
                    } else {
                        UpdateDownloadState.Failed(
                            id,
                            version,
                            url,
                            null,
                            "Android reports a completed download, but the APK cannot be opened."
                        )
                    }
                    if (uri != null && preferences().getBoolean(PREF_INSTALL_AFTER_DOWNLOAD, false)) {
                        handOffCompletedDownload()
                    }
                    return false
                }
                DownloadManager.STATUS_FAILED -> {
                    mutableDownloadState.value = UpdateDownloadState.Failed(
                        id, version, url, reason, downloadFailureMessage(reason)
                    )
                    return false
                }
                else -> {
                    mutableDownloadState.value = UpdateDownloadState.Failed(
                        id, version, url, reason, "Android reported an unknown download status ($status)."
                    )
                    return false
                }
            }
        }
    }

    private fun currentDownloadIfUsable(): ActiveDownload? = when (val state = mutableDownloadState.value) {
        is UpdateDownloadState.Queued -> ActiveDownload(state.downloadId, state.version)
        is UpdateDownloadState.Downloading -> ActiveDownload(state.downloadId, state.version)
        is UpdateDownloadState.Paused -> ActiveDownload(state.downloadId, state.version)
        is UpdateDownloadState.Ready -> ActiveDownload(state.downloadId, state.version)
        is UpdateDownloadState.Installing -> ActiveDownload(state.downloadId, state.version)
        is UpdateDownloadState.InstallerLaunched -> ActiveDownload(state.downloadId, state.version)
        else -> null
    }

    private fun downloadManager(): DownloadManager =
        appContext.getSystemService(DownloadManager::class.java)

    private fun preferences() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun notificationManager(): NotificationManager =
        appContext.getSystemService(NotificationManager::class.java)

    private fun handOffCompletedDownload() {
        val ready = mutableDownloadState.value as? UpdateDownloadState.Ready ?: return
        runCatching { install(appContext) }
            .onSuccess { handoff ->
                if (handoff == InstallHandoff.UNKNOWN_SOURCES_SETTINGS_LAUNCHED) {
                    notifyReadyToInstall(ready.version)
                }
            }
            .onFailure { exception ->
                Log.w(TAG, "Could not open the installer automatically", exception)
                notifyReadyToInstall(ready.version)
            }
    }

    private fun clearDownloadMetadata() {
        preferences().edit().remove(PREF_ID).remove(PREF_VERSION).remove(PREF_URL).apply()
    }

    private fun restorePendingUpdate() {
        val prefs = preferences()
        val version = prefs.getString(PREF_PENDING_VERSION, null).orEmpty()
        val url = prefs.getString(PREF_PENDING_URL, null).orEmpty()
        mutablePendingUpdate.value = if (
            version.isNotBlank() &&
            url.startsWith("https://") &&
            isNewer(version, BuildConfig.VERSION_NAME)
        ) {
            UpdateInfo(version, url)
        } else {
            preferences().edit().remove(PREF_PENDING_VERSION).remove(PREF_PENDING_URL).apply()
            null
        }
    }

    private fun persistPendingUpdate(update: UpdateInfo) {
        preferences().edit()
            .putString(PREF_PENDING_VERSION, update.version)
            .putString(PREF_PENDING_URL, update.downloadUrl)
            .apply()
        mutablePendingUpdate.value = update
    }

    private fun clearPendingUpdate(update: UpdateInfo) {
        if (mutablePendingUpdate.value != update) return
        preferences().edit().remove(PREF_PENDING_VERSION).remove(PREF_PENDING_URL).apply()
        mutablePendingUpdate.value = null
    }

    private fun ensureInitialized(context: Context) {
        if (!initialized) initialize(context)
    }

    private fun parseRelease(element: JsonElement): UpdateInfo? {
        val obj = element as? JsonObject ?: return null
        val prerelease = obj["prerelease"]?.jsonPrimitive?.booleanOrNull == true
        val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (BuildConfig.DEBUG && (!prerelease || !tag.startsWith("dev-v"))) return null
        if (!BuildConfig.DEBUG && prerelease) return null
        val version = tag.removePrefix("dev-v").removePrefix("v")
        val asset = obj["assets"]?.jsonArray?.firstOrNull {
            it.jsonObject["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk", true) == true
        }?.jsonObject?.get("browser_download_url")?.jsonPrimitive?.contentOrNull ?: return null
        return UpdateInfo(version, asset)
    }

    private fun isNewer(candidate: String, installed: String) =
        compareAppVersions(candidate, installed) > 0

    private fun compareVersions(a: String, b: String): Int = compareAppVersions(a, b)

    internal fun compareAppVersions(a: String, b: String): Int {
        // Build/channel labels (for example `dev-v` and `-dev`) do not change the
        // underlying app version. Only compare the numeric version components.
        val left = Regex("\\d+").findAll(a).map { it.value.toInt() }.toList()
        val right = Regex("\\d+").findAll(b).map { it.value.toInt() }.toList()
        repeat(maxOf(left.size, right.size)) { i ->
            left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
                .takeIf { it != 0 }
                ?.let { return it }
        }
        return 0
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun notificationsAllowed(context: Context) =
        Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun notify(context: Context, id: Int, notification: android.app.Notification) =
        context.getSystemService(NotificationManager::class.java).notify(id, notification)

    private fun openUnknownSourcesSettings(context: Context) {
        val packageUri = Uri.parse("package:${context.packageName}")
        val primary = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(primary)
        } catch (first: Exception) {
            val fallback = Intent(Settings.ACTION_SECURITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(fallback)
            } catch (second: Exception) {
                second.addSuppressed(first)
                throw IllegalStateException("Android could not open unknown-app-source settings", second)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun launchPackageInstaller(context: Context, uri: Uri) {
        fun Intent.withApkGrant() = apply {
            clipData = ClipData.newRawUri("NikTV update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val viewIntent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .withApkGrant()
        try {
            context.startActivity(viewIntent)
        } catch (first: Exception) {
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setData(uri)
                .withApkGrant()
            try {
                context.startActivity(installIntent)
            } catch (second: Exception) {
                second.addSuppressed(first)
                throw second
            }
        }
    }

    private fun apkFileName(version: String): String {
        val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "update" }
        return "NikTV-$safeVersion.apk"
    }

    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private const val TAG = "AppUpdates"

    private data class InstallCandidate(
        val downloadId: Long,
        val version: String,
        val downloadUrl: String,
        val uri: Uri
    )

    private data class ActiveDownload(val downloadId: Long, val version: String)
}

internal fun downloadPercent(bytesDownloaded: Long, totalBytes: Long?): Int? =
    totalBytes?.takeIf { it > 0L }?.let {
        ((bytesDownloaded.coerceIn(0L, it).toDouble() / it.toDouble()) * 100.0).toInt()
    }

internal fun formatDownloadBytes(bytes: Long): String {
    val safeBytes = bytes.coerceAtLeast(0L)
    if (safeBytes < 1024L) return "$safeBytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = safeBytes.toDouble()
    var unit = -1
    do {
        value /= 1024.0
        unit++
    } while (value >= 1024.0 && unit < units.lastIndex)
    return String.format(Locale.US, if (value >= 10) "%.0f %s" else "%.1f %s", value, units[unit])
}

internal fun pausedReasonMessage(reason: Int): String = when (reason) {
    DownloadManager.PAUSED_WAITING_TO_RETRY -> "Paused while Android waits to retry"
    DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Paused until a network is available"
    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Paused until Wi-Fi is available"
    DownloadManager.PAUSED_UNKNOWN -> "Download paused by Android"
    else -> "Download paused (reason $reason)"
}

internal fun downloadFailureMessage(reason: Int): String = when (reason) {
    DownloadManager.ERROR_CANNOT_RESUME -> "Download failed because Android could not resume it."
    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Download failed because storage is unavailable."
    DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "Download failed because the destination file already exists."
    DownloadManager.ERROR_FILE_ERROR -> "Download failed because Android could not write the APK."
    DownloadManager.ERROR_HTTP_DATA_ERROR -> "Download failed because of an HTTP data error."
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Download failed because the device is out of storage space."
    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Download failed because the server redirected too many times."
    DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Download failed because the server returned an unsupported HTTP response."
    DownloadManager.ERROR_UNKNOWN -> "Download failed for an unknown Android Download Manager reason."
    else -> "Download failed (Android reason $reason)."
}

class UpdateCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        AppUpdates.check()?.let { AppUpdates.notifyAvailable(applicationContext, it) }
        Result.success()
    } catch (exception: Exception) {
        Log.w("UpdateCheckWorker", "Update check failed; WorkManager will retry", exception)
        Result.retry()
    }
}

class UpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == INSTALL) {
            runCatching { AppUpdates.install(context) }
                .onFailure { Log.e("UpdateActionReceiver", "Could not open update installer", it) }
            return
        }
        if (intent.action != DOWNLOAD_AND_INSTALL) return
        val version = intent.getStringExtra("version").orEmpty()
        val url = intent.getStringExtra("url").orEmpty()
        val update = UpdateInfo(version, url)
        try {
            if (!AppUpdates.canWritePublicDownloads(context)) {
                AppUpdates.deferDownloadAndInstall(context, update)
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .setAction(AppUpdates.ACTION_REQUEST_UPDATE_DOWNLOAD)
                        .putExtra("version", version)
                        .putExtra("url", url)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                )
                return
            }
            AppUpdates.downloadAndInstall(context, update)
        } catch (exception: Exception) {
            Log.e("UpdateActionReceiver", "Could not start update download", exception)
        }
    }

    companion object {
        const val DOWNLOAD_AND_INSTALL = "com.nikhil.niktv.DOWNLOAD_AND_INSTALL_UPDATE"
        const val INSTALL = "com.nikhil.niktv.INSTALL_DOWNLOADED_UPDATE"
    }
}
