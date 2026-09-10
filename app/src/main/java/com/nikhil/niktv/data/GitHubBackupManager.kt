package com.nikhil.niktv.data

// GITHUB_BACKUP_V1
//
// Uploads an existing NikTV JSON backup to a user-configurable private GitHub
// repository. The GitHub token is encrypted with Android Keystore and is never
// added to the NikTV backup payload.

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nikhil.niktv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class GitHubBackupConfig(
    val username: String = "nikhilmenghani",
    val repository: String = "tracker",
    val token: String = ""
)

data class GitHubBackupUpload(
    val path: String,
    val htmlUrl: String
)

class GitHubBackupManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun loadConfig(): GitHubBackupConfig {
        val username =
            prefs.getString(KEY_USERNAME, DEFAULT_USERNAME)
                ?.trim()
                .orEmpty()
                .ifBlank { DEFAULT_USERNAME }
        val repository =
            prefs.getString(KEY_REPOSITORY, DEFAULT_REPOSITORY)
                ?.trim()
                .orEmpty()
                .ifBlank { DEFAULT_REPOSITORY }

        val token =
            runCatching {
                val encrypted = prefs.getString(KEY_TOKEN_CIPHERTEXT, null)
                val iv = prefs.getString(KEY_TOKEN_IV, null)
                if (encrypted.isNullOrBlank() || iv.isNullOrBlank()) {
                    ""
                } else {
                    decryptToken(encrypted, iv)
                }
            }.getOrDefault("")

        return GitHubBackupConfig(
            username = username,
            repository = repository,
            token = token
        )
    }

    fun saveConfig(config: GitHubBackupConfig) {
        val username = config.username.trim().ifBlank { DEFAULT_USERNAME }
        val repository =
            config.repository.trim().removeSuffix(".git")
                .ifBlank { DEFAULT_REPOSITORY }
        val token = config.token.trim()

        prefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_REPOSITORY, repository)
            .apply()

        if (token.isBlank()) {
            prefs.edit()
                .remove(KEY_TOKEN_CIPHERTEXT)
                .remove(KEY_TOKEN_IV)
                .apply()
        } else {
            val encrypted = encryptToken(token)
            prefs.edit()
                .putString(KEY_TOKEN_CIPHERTEXT, encrypted.ciphertext)
                .putString(KEY_TOKEN_IV, encrypted.iv)
                .apply()
        }
    }

    suspend fun uploadBackup(
        rawBackup: String,
        config: GitHubBackupConfig
    ): GitHubBackupUpload = withContext(Dispatchers.IO) {
        val normalized = normalizeAndValidate(config)
        val repository = fetchRepository(normalized)

        require(repository.privateRepository) {
            "GitHub repository ${normalized.username}/${normalized.repository} is public. " +
                "NikTV backups contain portal credentials, so plaintext backups are only " +
                "uploaded to private repositories."
        }

        val decorated = decorateBackup(rawBackup)
        val root = JSONObject(decorated)
        val metadata = root.getJSONObject(BACKUP_METADATA_KEY)
        val settingsVersion = metadata.optInt("settingsVersion", 1)

        val timestamp =
            SimpleDateFormat(
                "yyyy-MM-dd_HH-mm-ss-SSS",
                Locale.US
            ).format(Date())

        val deviceFolder =
            slug("${Build.MANUFACTURER}-${Build.MODEL}")
        val fileName =
            "NikTV_${slug(Build.MODEL)}_" +
                "app-${slug(BuildConfig.VERSION_NAME)}_" +
                "settings-v${settingsVersion}_$timestamp.json"
        val path = "backups/$deviceFolder/$fileName"

        val payload = JSONObject()
            .put(
                "message",
                "NikTV backup · ${deviceDisplayName()} · " +
                    "app ${BuildConfig.VERSION_NAME} · settings v$settingsVersion · $timestamp"
            )
            .put(
                "content",
                Base64.encodeToString(
                    decorated.toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
            )
            .put("branch", repository.defaultBranch)

        val request = Request.Builder()
            .url(
                "https://api.github.com/repos/" +
                    "${normalized.username}/${normalized.repository}/contents/$path"
            )
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer ${normalized.token}")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .put(
                payload.toString()
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw githubFailure(
                    "upload backup",
                    response.code,
                    body
                )
            }

            val json = JSONObject(body)
            val htmlUrl =
                json.optJSONObject("content")
                    ?.optString("html_url")
                    .orEmpty()

            GitHubBackupUpload(
                path = path,
                htmlUrl = htmlUrl
            )
        }
    }

    private fun decorateBackup(rawBackup: String): String {
        val root = JSONObject(rawBackup)
        val settingsVersion = root.optInt("formatVersion", 1)
        val now = Date()

        val device = JSONObject()
            .put("displayName", deviceDisplayName())
            .put("manufacturer", Build.MANUFACTURER)
            .put("model", Build.MODEL)
            .put("device", Build.DEVICE)
            .put("product", Build.PRODUCT)
            .put("androidVersion", Build.VERSION.RELEASE)
            .put("sdkInt", Build.VERSION.SDK_INT)

        val metadata = JSONObject()
            .put("settingsVersion", settingsVersion)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("appVersionCode", BuildConfig.VERSION_CODE)
            .put(
                "exportedAt",
                SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss Z",
                    Locale.US
                ).format(now)
            )
            .put("exportedAtMillis", now.time)
            .put("device", device)

        // ProfileStore imports with ignoreUnknownKeys=true, so this metadata is
        // additive and older/newer compatible without changing the backup schema.
        root.put(BACKUP_METADATA_KEY, metadata)
        return root.toString(2)
    }

    private fun fetchRepository(
        config: GitHubBackupConfig
    ): RepositoryMetadata {
        val request = Request.Builder()
            .url(
                "https://api.github.com/repos/" +
                    "${config.username}/${config.repository}"
            )
            .header("Accept", "application/vnd.github+json")
            .header("Authorization", "Bearer ${config.token}")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .get()
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw githubFailure(
                    "read repository ${config.username}/${config.repository}",
                    response.code,
                    body
                )
            }

            val json = JSONObject(body)
            return RepositoryMetadata(
                privateRepository = json.optBoolean("private", false),
                defaultBranch =
                    json.optString("default_branch", "main")
                        .ifBlank { "main" }
            )
        }
    }

    private fun normalizeAndValidate(
        config: GitHubBackupConfig
    ): GitHubBackupConfig {
        val username = config.username.trim()
        val repository =
            config.repository.trim().removeSuffix(".git")
        val token = config.token.trim()

        require(username.isNotBlank()) {
            "GitHub username is required."
        }
        require(repository.isNotBlank()) {
            "GitHub repository is required."
        }
        require(token.isNotBlank()) {
            "GitHub token is required."
        }
        require(GITHUB_NAME.matches(username)) {
            "GitHub username contains unsupported characters."
        }
        require(GITHUB_NAME.matches(repository)) {
            "GitHub repository contains unsupported characters."
        }

        return GitHubBackupConfig(
            username = username,
            repository = repository,
            token = token
        )
    }

    private fun githubFailure(
        action: String,
        statusCode: Int,
        body: String
    ): IllegalStateException {
        val apiMessage =
            runCatching {
                JSONObject(body).optString("message")
            }.getOrNull().orEmpty()

        val hint =
            when (statusCode) {
                401 ->
                    " Check that the token is valid."
                403 ->
                    " Check token access and repository permissions."
                404 ->
                    " Check the username/repository and ensure the token can access it."
                409 ->
                    " GitHub reported a repository conflict; retry the upload."
                422 ->
                    " GitHub rejected the file request."
                else -> ""
            }

        return IllegalStateException(
            buildString {
                append("Could not $action (GitHub HTTP $statusCode).")
                if (apiMessage.isNotBlank()) {
                    append(" ")
                    append(apiMessage)
                    append(".")
                }
                append(hint)
            }
        )
    }

    private fun deviceDisplayName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android device" }

    private fun slug(value: String): String =
        value.trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "-")
            .trim('-')
            .take(80)
            .ifBlank { "device" }

    private fun encryptToken(token: String): EncryptedToken {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            getOrCreateSecretKey()
        )
        val encrypted =
            cipher.doFinal(token.toByteArray(Charsets.UTF_8))

        return EncryptedToken(
            ciphertext =
                Base64.encodeToString(
                    encrypted,
                    Base64.NO_WRAP
                ),
            iv =
                Base64.encodeToString(
                    cipher.iv,
                    Base64.NO_WRAP
                )
        )
    }

    private fun decryptToken(
        ciphertext: String,
        iv: String
    ): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(
                128,
                Base64.decode(iv, Base64.NO_WRAP)
            )
        )
        return String(
            cipher.doFinal(
                Base64.decode(
                    ciphertext,
                    Base64.NO_WRAP
                )
            ),
            Charsets.UTF_8
        )
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore =
            KeyStore.getInstance("AndroidKeyStore")
                .apply { load(null) }

        val existing =
            keyStore.getKey(
                KEYSTORE_ALIAS,
                null
            ) as? SecretKey

        if (existing != null) {
            return existing
        }

        val generator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(
                    KeyProperties.BLOCK_MODE_GCM
                )
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .build()
        )
        return generator.generateKey()
    }

    private data class RepositoryMetadata(
        val privateRepository: Boolean,
        val defaultBranch: String
    )

    private data class EncryptedToken(
        val ciphertext: String,
        val iv: String
    )

    companion object {
        private const val PREFS_NAME = "github_backup_config"
        private const val KEY_USERNAME = "username"
        private const val KEY_REPOSITORY = "repository"
        private const val KEY_TOKEN_CIPHERTEXT = "token_ciphertext"
        private const val KEY_TOKEN_IV = "token_iv"
        private const val KEYSTORE_ALIAS = "niktv_github_backup_token_v1"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val DEFAULT_USERNAME = "nikhilmenghani"
        private const val DEFAULT_REPOSITORY = "tracker"
        private const val GITHUB_API_VERSION = "2026-03-10"
        private const val BACKUP_METADATA_KEY = "backupMetadata"
        private val JSON_MEDIA_TYPE =
            "application/json; charset=utf-8".toMediaType()
        private val GITHUB_NAME =
            Regex("^[A-Za-z0-9_.-]+$")
    }
}
