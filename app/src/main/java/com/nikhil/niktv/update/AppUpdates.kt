package com.nikhil.niktv.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.*
import com.nikhil.niktv.BuildConfig
import com.nikhil.niktv.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(val version: String, val downloadUrl: String)

object AppUpdates {
    private const val PERIODIC = "niktv-periodic-update"
    private const val STARTUP = "niktv-startup-update"
    private const val DOWNLOAD = "niktv-update-download"

    fun initialize(context: Context) {
        createChannel(context)
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS).setConstraints(constraints).build()
        )
        WorkManager.getInstance(context).enqueueUniqueWork(
            STARTUP, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<UpdateCheckWorker>().setConstraints(constraints).build()
        )
    }

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val url = if (BuildConfig.DEBUG) RELEASES_URL else LATEST_URL
        val response = client.newCall(Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()).execute()
        response.use {
            if (!it.isSuccessful) error("GitHub returned HTTP ${it.code}")
            val root = Json.parseToJsonElement(it.body?.string().orEmpty())
            val releases = if (root is JsonArray) root else JsonArray(listOf(root))
            releases.asSequence().mapNotNull(::parseRelease)
                .filter { update -> isNewer(update.version, BuildConfig.VERSION_NAME) }
                .maxWithOrNull { a, b -> compareVersions(a.version, b.version) }
        }
    }

    fun download(context: Context, update: UpdateInfo) {
        val request = OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
            .setInputData(workDataOf("version" to update.version, "url" to update.downloadUrl))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniqueWork(DOWNLOAD, ExistingWorkPolicy.REPLACE, request)
    }

    fun notifyAvailable(context: Context, update: UpdateInfo) {
        if (!notificationsAllowed(context)) return
        val intent = Intent(context, UpdateActionReceiver::class.java).setAction(UpdateActionReceiver.DOWNLOAD)
            .putExtra("version", update.version).putExtra("url", update.downloadUrl)
        val action = PendingIntent.getBroadcast(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notify(context, 1001, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("NikTV ${update.version} is available")
            .setContentText("Download the update when you're ready.").setAutoCancel(true)
            .addAction(android.R.drawable.stat_sys_download, "Download", action).build())
    }

    fun notifyDownloaded(context: Context, version: String, file: File) {
        if (!notificationsAllowed(context)) return
        val intent = Intent(context, UpdateActionReceiver::class.java).setAction(UpdateActionReceiver.INSTALL)
            .putExtra("path", file.absolutePath)
        val action = PendingIntent.getBroadcast(context, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notify(context, 1002, NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done).setContentTitle("NikTV $version is ready")
            .setContentText("Tap Install to finish updating.").setAutoCancel(true)
            .addAction(android.R.drawable.stat_sys_download_done, "Install", action).build())
    }

    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
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

    private fun isNewer(candidate: String, installed: String) = compareVersions(candidate, installed) > 0
    private fun compareVersions(a: String, b: String): Int {
        val left = a.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val right = b.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        repeat(maxOf(left.size, right.size)) { i ->
            left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 }).takeIf { it != 0 }?.let { return it }
        }
        return 0
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun notificationsAllowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    private fun notify(context: Context, id: Int, notification: android.app.Notification) =
        context.getSystemService(NotificationManager::class.java).notify(id, notification)

    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    private const val CHANNEL = "niktv-updates"
    private const val RELEASES_URL = "https://api.github.com/repos/nikhilmenghani/nikTv/releases?per_page=30"
    private const val LATEST_URL = "https://api.github.com/repos/nikhilmenghani/nikTv/releases/latest"
}

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        AppUpdates.check()?.let { AppUpdates.notifyAvailable(applicationContext, it) }
        Result.success()
    } catch (_: Exception) { Result.retry() }
}

class UpdateDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val version = inputData.getString("version") ?: return@withContext Result.failure()
            val url = inputData.getString("url") ?: return@withContext Result.failure()
            val file = File(applicationContext.getExternalFilesDir(null), "NikTV-$version.apk")
            val response = OkHttpClient().newCall(Request.Builder().url(url).build()).execute()
            response.use {
                if (!it.isSuccessful) return@withContext Result.retry()
                val body = it.body ?: return@withContext Result.retry()
                file.outputStream().use { output -> body.byteStream().use { input -> input.copyTo(output) } }
            }
            AppUpdates.notifyDownloaded(applicationContext, version, file)
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }
}

class UpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            DOWNLOAD -> AppUpdates.download(context, UpdateInfo(intent.getStringExtra("version").orEmpty(), intent.getStringExtra("url").orEmpty()))
            INSTALL -> intent.getStringExtra("path")?.let { AppUpdates.install(context, File(it)) }
        }
    }
    companion object {
        const val DOWNLOAD = "com.nikhil.niktv.DOWNLOAD_UPDATE"
        const val INSTALL = "com.nikhil.niktv.INSTALL_UPDATE"
    }
}
