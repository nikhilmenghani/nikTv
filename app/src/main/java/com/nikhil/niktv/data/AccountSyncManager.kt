package com.nikhil.niktv.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import org.json.JSONObject

data class AccountSyncState(
    val accountId: String? = null,
    val login: String? = null,
    val initialized: Boolean = false,
    val syncing: Boolean = false,
    val message: String = "Connect your GitHub account to get started.",
    val error: String? = null,
    val reconnectRequired: Boolean = false,
)

/** One connection/sync mutex also makes rotation, switching and disconnect atomic with uploads. */
class AccountSyncManager private constructor(context: Context) {
    private val context = context.applicationContext
    private val vault = AccountTokenVault(context)
    private val prefs = vault.preferences
    private val store = UserSyncStore.get(context)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AccountSyncState())
    val state: StateFlow<AccountSyncState> = mutableState.asStateFlow()
    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            store.pendingChanges.collect { count ->
                if (count > 0) runCatching {
                    val accountId = authenticatedAccount() ?: return@runCatching
                    val pending = store.pending(accountId)
                    val progressOnly = pending.isNotEmpty() && pending.all { it.collection in setOf("progress", "history", "series") }
                    requestSync(if (progressOnly) "playback-progress" else "viewing-change")
                }
            }
        }
    }

    suspend fun connect(token: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (token.isBlank()) {
                mutableState.value = mutableState.value.copy(error = "Enter a GitHub personal access token.")
                return@withContext
            }
            mutableState.value = mutableState.value.copy(syncing = true, error = null, message = "Validating GitHub token…")
            try {
                val transport = GitHubSyncTransport(token.trim())
                val identity = transport.identity()
                val previous = prefs.getString("accountId", null)
                val previousToken = if (previous == identity.accountId) vault.token() else ""
                RemoteCredentials.activateAccount(context, identity.accountId, identity.login)
                RemoteCredentials.replaceAccountToken(context, previousToken, token.trim())
                if (previous != identity.accountId) {
                    previous?.let(::cancelWork)
                    store.selectAccount(identity.accountId)
                }
                GitHubBackupManager(context).adoptLegacyAccountSettings(identity.login)
                // Validated identity is saved even when the network interrupts bootstrap, but it is
                // never marked initialized until merge AND upload have succeeded.
                vault.save(token.trim(), identity.accountId, identity.login)
                mutableState.value = AccountSyncState(identity.accountId, identity.login,
                    initialized = isInitialized(identity.accountId), syncing = true, message = "Preparing private niktv-data repository…")
                val repository = transport.bootstrap(identity)
                transport.checkWrite(repository)
                syncCaptured(transport, repository, identity.accountId)
                successful(identity)
            } catch (cancelled: CancellationException) { interrupted(); throw cancelled }
            catch (error: Exception) { failed(error) }
        }
    }

    suspend fun launchSync() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val accountId = prefs.getString("accountId", null)
            val token = vault.token()
            if (accountId == null || token.isBlank()) {
                mutableState.value = AccountSyncState(reconnectRequired = accountId != null,
                    error = if (accountId != null) "Your saved token could not be opened. Reconnect to keep using this account." else null)
                return@withContext
            }
            if (store.selectedAccount.value != accountId) store.selectAccount(accountId)
            mutableState.value = AccountSyncState(accountId, prefs.getString("login", null),
                initialized = isInitialized(accountId), syncing = true, message = "Syncing account…",
                reconnectRequired = prefs.getBoolean("reconnect:$accountId", false))
            runSavedSync(accountId, token)
        }
    }

    suspend fun syncNow() = syncForAccount(null)

    internal suspend fun syncForAccount(expectedAccountId: String?) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val accountId = prefs.getString("accountId", null) ?: return@withContext
            if (expectedAccountId != null && expectedAccountId != accountId) return@withContext
            val token = vault.token()
            if (token.isBlank()) return@withContext
            mutableState.value = mutableState.value.copy(syncing = true, error = null, message = "Syncing account…")
            runSavedSync(accountId, token)
        }
    }

    private suspend fun runSavedSync(accountId: String, token: String) {
        try {
            val until = prefs.getLong("retryAfter:$accountId", 0)
            if (until > System.currentTimeMillis()) throw SyncFailure("GitHub requested a pause. Changes are saved locally and will retry automatically.", retryable = true, retryAfterMillis = until - System.currentTimeMillis())
            val transport = GitHubSyncTransport(token)
            val identity = transport.identity()
            if (identity.accountId != accountId) throw SyncFailure("The saved token belongs to a different GitHub account. Reconnect to switch accounts safely.", reconnect = true)
            RemoteCredentials.activateAccount(context, identity.accountId, identity.login)
            val repository = transport.bootstrap(identity)
            if (!isInitialized(accountId)) transport.checkWrite(repository)
            syncCaptured(transport, repository, accountId)
            successful(identity)
        } catch (cancelled: CancellationException) { interrupted(); throw cancelled }
        catch (error: Exception) { failed(error) }
    }

    private suspend fun syncCaptured(transport: GitHubSyncTransport, repository: SyncRepository, accountId: String) {
        if (store.selectedAccount.value != accountId) store.selectAccount(accountId)
        synchronizeAccount(accountId, object : SyncLocalReplica {
            override suspend fun merge(records: List<SyncRecord>) = store.merge(accountId, records)
            override suspend fun prepare(emptyRemoteAccount: Boolean) {
                if (emptyRemoteAccount) store.initializeDefaults(accountId)
                ProfileStore(context).migrateLegacy(accountId, assignToFamily = emptyRemoteAccount)
                syncConfigurationReference(accountId, repository.owner)
            }
            override suspend fun snapshot() = store.snapshot(accountId)
            override suspend fun pending() = store.pending(accountId)
            override suspend fun acknowledge(ids: Collection<String>) = store.acknowledge(accountId, ids)
        }, object : SyncRemoteReplica {
            override suspend fun fetch() = transport.fetch(repository, accountId)
            override suspend fun upload(document: AccountDocument, sha: String?) = transport.upload(repository, document, sha)
        }) { message -> mutableState.value = mutableState.value.copy(message = message) }
    }
    private suspend fun syncConfigurationReference(accountId: String, login: String) {
        val existing = store.snapshot(accountId).firstOrNull { it.collection == "account" && it.recordId == "configuration-reference" && !it.deleted }
        if (existing == null && RemoteCredentials.configured(context) && RemoteCredentials.owner(context).equals(login, ignoreCase = true)) {
            val reference = JSONObject().put("owner", RemoteCredentials.owner(context))
                .put("repository", RemoteCredentials.repository(context)).put("path", RemoteCredentials.filePath(context)).toString()
            store.db.withTransaction { store.mutate(UserScope(accountId, ""), "account", "configuration-reference", reference) }
        } else if (existing != null && !RemoteCredentials.configured(context)) {
            // This only supplies a reference to the existing private configuration mechanism.
            // Credentials themselves remain encrypted on-device and are never part of sync.
            val reference = runCatching { JSONObject(existing.payload) }.getOrNull() ?: return
            val owner = reference.optString("owner")
            val repository = reference.optString("repository")
            val path = reference.optString("path")
            if (owner.isBlank() || repository.isBlank() || path.isBlank()) return
            runCatching {
                RemoteCredentials.saveConnection(context, vault.token(), owner, repository, path)
                RemoteCredentials.refresh(context, force = true)
            }.onFailure { if (it is CancellationException) throw it }
        }
    }
    private fun successful(identity: SyncIdentity) {
        check(prefs.edit().putBoolean("initialized:${identity.accountId}", true)
            .putBoolean("reconnect:${identity.accountId}", false).remove("retryAfter:${identity.accountId}")
            .putLong("lastSync:${identity.accountId}", System.currentTimeMillis()).commit())
        mutableState.value = AccountSyncState(identity.accountId, identity.login, initialized = true, message = "Up to date")
        configureWork(identity.accountId)
    }

    private fun interrupted() {
        mutableState.value = mutableState.value.copy(syncing = false, message = "Sync interrupted. Local changes remain queued.")
    }

    private fun failed(error: Exception) {
        val failure = error as? SyncFailure
        val accountId = prefs.getString("accountId", null)
        val reconnect = failure?.reconnect == true
        if (accountId != null && reconnect) prefs.edit().putBoolean("reconnect:$accountId", true).commit()
        if (accountId != null && failure?.retryAfterMillis != null && failure.retryAfterMillis > 0) {
            prefs.edit().putLong("retryAfter:$accountId", System.currentTimeMillis() + failure.retryAfterMillis).commit()
        }
        // Never display IOException messages containing URLs or provider credentials.
        val message = when {
            failure != null -> failure.message
            error is IOException -> "Could not reach GitHub. Check your connection and retry. Your changes are saved locally."
            error is IllegalArgumentException || error is IllegalStateException -> error.message?.takeIf { !it.contains("://") } ?: "Could not complete account setup. Retry after checking your connection."
            else -> "Could not complete sync. Your local data is preserved. Retry or reconnect."
        }
        mutableState.value = mutableState.value.copy(syncing = false, error = message,
            message = if (accountId != null && isInitialized(accountId)) "Local data available" else "Setup incomplete",
            initialized = accountId != null && isInitialized(accountId), reconnectRequired = reconnect ||
                accountId?.let { prefs.getBoolean("reconnect:$it", false) } == true)
        if (accountId != null && !reconnect && (failure?.retryable == true || error is IOException)) requestSync("retry")
    }

    suspend fun disconnect() = mutex.withLock {
        withContext(Dispatchers.IO) {
            prefs.getString("accountId", null)?.let(::cancelWork)
            vault.clear()
            store.disconnect()
            RemoteCredentials.disconnect(context)
            mutableState.value = AccountSyncState(message = "Disconnected. Connect to restore your account’s local and remote profiles.")
        }
    }

    /** Call only after the transaction that persisted the user mutation completes. */
    fun requestSync(reason: String, immediate: Boolean = false) {
        val accountId = prefs.getString("accountId", null) ?: return
        if (!prefs.contains("ciphertext") || prefs.getBoolean("reconnect:$accountId", false)) return
        val request = OneTimeWorkRequestBuilder<AccountSyncWorker>()
            .setInputData(workDataOf("accountId" to accountId)).addTag(tag(accountId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(if (immediate) 0 else if (reason == "playback-progress") 60 else 3, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        val queue = when {
            immediate -> "flush"
            reason in setOf("playback-progress", "playback") -> "progress"
            else -> "edits"
        }
        WorkManager.getInstance(context).enqueueUniqueWork("niktv-sync-$queue:$accountId",
            if (immediate) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    private fun configureWork(accountId: String) {
        val request = PeriodicWorkRequestBuilder<AccountSyncWorker>(15, TimeUnit.MINUTES)
            .setInputData(workDataOf("accountId" to accountId)).addTag(tag(accountId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("niktv-sync-periodic:$accountId", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    internal fun authenticatedAccount(): String? = prefs.getString("accountId", null)
    internal suspend fun hasPending(accountId: String): Boolean = store.pending(accountId).isNotEmpty()
    private fun isInitialized(accountId: String) = prefs.getBoolean("initialized:$accountId", false)
    private fun cancelWork(accountId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(tag(accountId))
        WorkManager.getInstance(context).cancelUniqueWork("niktv-github-settings-backup")
    }
    private fun tag(accountId: String) = "niktv-account:$accountId"

    companion object {
        @Volatile private var instance: AccountSyncManager? = null
        fun get(context: Context): AccountSyncManager = instance ?: synchronized(this) {
            instance ?: AccountSyncManager(context).also { instance = it }
        }
    }
}

class AccountSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val manager = AccountSyncManager.get(applicationContext)
        if (inputData.getString("accountId") != manager.authenticatedAccount()) return Result.success()
        manager.syncForAccount(inputData.getString("accountId"))
        val state = manager.state.value
        return if (!state.reconnectRequired && (state.error != null || manager.hasPending(inputData.getString("accountId")!!))) Result.retry() else Result.success()
    }
}
