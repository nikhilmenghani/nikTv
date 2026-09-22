package com.nikhil.niktv.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-owned GitHub credentials. Nothing in this store is generated into the APK. */
object RemoteCredentials {
    private const val PREFS = "github_remote_credentials"
    private const val KEY_ALIAS = "niktv_github_remote_credentials_v1"
    private const val REFRESH_MILLIS = 24 * 60 * 60 * 1000L
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

    var values by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var lastUpdatedAt by mutableStateOf(0L)
        private set

    fun initialize(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("manually_configured") && !prefs.getString("token", null).isNullOrBlank()) {
            prefs.edit().putBoolean("manually_configured", true).apply()
        }
        values = parseKnownValues(decrypt(prefs.getString("cached_values", null)))
        lastUpdatedAt = prefs.getLong("updated_at", 0L)
    }

    fun get(name: String): String = values[name].orEmpty().trim()

    fun configured(context: Context): Boolean =
        token(context).isNotBlank() && owner(context).isNotBlank() &&
            repository(context).isNotBlank() && filePath(context).isNotBlank()

    fun canPairDevices(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("manually_configured", false) && configured(context)

    fun pairingConfiguration(context: Context): String {
        require(canPairDevices(context)) { "Only a device with a manually entered token can pair another device." }
        return JSONObject().put("token", token(context)).put("owner", owner(context))
            .put("repository", repository(context)).put("path", filePath(context)).toString()
    }

    fun savePairedConnection(context: Context, configuration: String) {
        val json = JSONObject(configuration)
        saveConnection(context, json.getString("token"), json.getString("owner"),
            json.getString("repository"), json.getString("path"), manuallyEntered = false)
    }

    fun owner(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("owner", "nikhilmenghani").orEmpty()

    fun repository(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("repository", "myenv").orEmpty().substringAfterLast('/')

    fun filePath(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("file_path", ".env").orEmpty()

    fun saveConnection(context: Context, token: String, owner: String, repository: String, path: String) {
        saveConnection(context, token, owner, repository, path, manuallyEntered = true)
    }

    private fun saveConnection(context: Context, token: String, owner: String, repository: String,
                               path: String, manuallyEntered: Boolean) {
        require(REPOSITORY_PATTERN.matches(owner.trim())) { "Enter a valid GitHub owner." }
        require(REPOSITORY_PATTERN.matches(repository.trim())) { "Enter the repository name only." }
        require(path.isNotBlank()) { "Enter the configuration file path." }
        require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Enter a valid file path." }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val effectiveToken = token.trim().ifBlank { token(context) }
        require(effectiveToken.isNotBlank()) { "Enter a GitHub token or configure one in GitHub backup settings." }
        prefs.edit()
            .putString("token", encrypt(effectiveToken))
            .putBoolean("manually_configured", manuallyEntered &&
                (token.isNotBlank() || prefs.getBoolean("manually_configured", false)))
            .putString("owner", owner.trim())
            .putString("repository", repository.trim())
            .putString("file_path", path.trim().trimStart('/'))
            .putLong("updated_at", 0L)
            .putLong("attempted_at", 0L)
            .apply()
        lastUpdatedAt = 0L
    }

    suspend fun refresh(context: Context, force: Boolean = false): Boolean = mutex.withLock {
        val app = context.applicationContext
        if (!configured(app)) return@withLock false
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force && System.currentTimeMillis() - prefs.getLong("attempted_at", 0L) < REFRESH_MILLIS) {
            return@withLock false
        }
        prefs.edit().putLong("attempted_at", System.currentTimeMillis()).apply()
        val token = token(app)
        val owner = owner(app)
        val repo = repository(app)
        require(REPOSITORY_PATTERN.matches(owner) && REPOSITORY_PATTERN.matches(repo)) { "Invalid GitHub repository." }
        val encodedPath = filePath(app).split('/').joinToString("/") { encode(it) }
        val url = "https://api.github.com/repos/$owner/$repo/contents/$encodedPath"
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github.raw+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        val fetched = withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GitHub returned HTTP ${response.code}. Check repository access and file path.")
                response.body?.string() ?: throw IOException("GitHub returned an empty file.")
            }
        }
        val parsed = parseKnownValues(fetched)
        require(parsed.isNotEmpty()) { "The file does not contain NikTV credential keys." }
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString("cached_values", encrypt(JSONObject(parsed).toString()))
            .putLong("updated_at", now)
            .apply()
        values = parsed
        lastUpdatedAt = now
        true
    }

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "github_remote_credentials_daily",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RemoteCredentialsWorker>(24, TimeUnit.HOURS).build(),
        )
    }

    private fun token(context: Context): String = decrypt(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("token", null)
    ).ifBlank { GitHubBackupManager(context).deviceToken() }

    internal fun parseKnownValues(raw: String): Map<String, String> =
        decodeValues(raw).filterKeys { it in KNOWN_KEYS }

    private fun decodeValues(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        if (raw.trimStart().startsWith('{')) {
            val json = JSONObject(raw)
            return json.keys().asSequence().associateWith { json.optString(it) }
        }
        return raw.lineSequence().mapNotNull { source ->
            val line = source.trim().removePrefix("export ").trim()
            if (line.isBlank() || line.startsWith('#') || '=' !in line) return@mapNotNull null
            val key = line.substringBefore('=').trim()
            if (!KEY_PATTERN.matches(key)) return@mapNotNull null
            val value = line.substringAfter('=').trim()
            key to when {
                value.length >= 2 && value.first() == '"' && value.last() == '"' -> value.drop(1).dropLast(1)
                value.length >= 2 && value.first() == '\'' && value.last() == '\'' -> value.drop(1).dropLast(1)
                else -> value
            }
        }.toMap()
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String?): String = runCatching {
        if (encoded.isNullOrBlank()) return@runCatching ""
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }.getOrDefault("")

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private val KNOWN_KEYS = setOf(
        "NIKTV_DEFAULT_PROFILE_NAME", "NIKTV_DEFAULT_PORTAL_URL", "NIKTV_DEFAULT_MAC_ADDRESS",
        "NIKTV_DEFAULT_SERIAL_NUMBER", "NIKTV_XTREAM_PROFILE_NAME", "NIKTV_XTREAM_PORTAL_URL",
        "NIKTV_XTREAM_USERNAME", "NIKTV_XTREAM_PASSWORD", "NIKTV_TMDB_API_KEY",
        "NIKTV_TMDB_READ_ACCESS_TOKEN", "OPEN_SUBTITLES_KEY", "G_TOKEN",
    )
    private val KEY_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*")
    private val REPOSITORY_PATTERN = Regex("[A-Za-z0-9_.-]+")
}

class RemoteCredentialsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        RemoteCredentials.refresh(applicationContext)
        Result.success()
    } catch (_: Exception) {
        // A bad token or missing file should wait for the next daily attempt,
        // rather than repeatedly querying the private repository.
        Result.success()
    }
}
