package com.nikhil.niktv.data

// GITHUB_BACKUP_SCHEDULER_V1

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object GitHubBackupScheduler {
    private const val WORK_NAME = "niktv-github-settings-backup"

    fun initialize(context: Context) {
        val appContext = context.applicationContext
        configure(
            appContext,
            GitHubBackupManager(appContext).loadConfig()
        )
    }

    fun configure(
        context: Context,
        config: GitHubBackupConfig
    ) {
        val workManager =
            WorkManager.getInstance(context.applicationContext)
        val hours = config.autoBackupIntervalHours

        if (
            config.backupMode != BackupMode.GITHUB ||
            hours <= 0 ||
            (
                config.passphrase.isNotBlank() &&
                config.passphrase.length < 12
            )
        ) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }

        val constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

        val request =
            PeriodicWorkRequestBuilder<GitHubBackupWorker>(
                hours.toLong(),
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

class GitHubBackupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val manager = GitHubBackupManager(applicationContext)
        val config = manager.loadConfig()

        if (
            config.backupMode != BackupMode.GITHUB ||
            config.autoBackupIntervalHours <= 0
        ) {
            return Result.success()
        }

        // Missing auth is a configuration state, not a transient network error.
        if (config.token.isBlank()) {
            return Result.success()
        }

        return try {
            val store = ProfileStore(applicationContext)
            val fingerprint = store.backupFingerprint()

            // Periodic work still runs, but no GitHub write occurs unless one of
            // the values included in the NikTV backup has actually changed.
            if (manager.isBackupCurrent(fingerprint, config)) {
                return Result.success()
            }

            val content = store.exportBackup()
            manager.uploadBackup(content, config)
            manager.recordSuccessfulBackupFingerprint(
                fingerprint,
                config
            )
            Result.success()
        } catch (_: IllegalArgumentException) {
            // Invalid password/configuration will not improve by immediate retry.
            Result.failure()
        } catch (_: Throwable) {
            Result.retry()
        }
    }
}
