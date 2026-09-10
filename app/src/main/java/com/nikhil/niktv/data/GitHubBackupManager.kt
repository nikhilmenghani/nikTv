package com.nikhil.niktv.data

// GITHUB_BACKUP_V2
//
// Public-repository-safe GitHub backups:
// - full NikTV JSON is encrypted locally with AES-256-GCM
// - password key is PBKDF2-HMAC-SHA256 (600,000 iterations)
// - device/app/profile metadata stays inside ciphertext
// - PAT and optionally remembered backup password use Android Keystore

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
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class GitHubBackupConfig(
    val username: String = "nikhilmenghani",
    val repository: String = "tracker",
    val token: String = "",
    val passphrase: String = "",
    val rememberPassphrase: Boolean = false
)

data class GitHubBackupUpload(
    val path: String,
    val htmlUrl: String
)

data class GitHubBackupFile(
    val name: String,
    val path: String,
    val size: Long
)

data class GitHubBackupPreview(
    val deviceName: String,
    val appVersion: String,
    val settingsVersion: Int,
    val exportedAt: String,
    val profileCount: Int
)

data class GitHubBackupDecoded(
    val rawBackup: String,
    val preview: GitHubBackupPreview
)

class GitHubBackupManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    fun loadConfig(): GitHubBackupConfig {
        val remember =
            prefs.getBoolean(KEY_REMEMBER_PASSPHRASE, false)

        return GitHubBackupConfig(
            username =
                prefs.getString(KEY_USERNAME, DEFAULT_USERNAME)
                    ?.trim().orEmpty()
                    .ifBlank { DEFAULT_USERNAME },
            repository =
                prefs.getString(KEY_REPOSITORY, DEFAULT_REPOSITORY)
                    ?.trim().orEmpty()
                    .ifBlank { DEFAULT_REPOSITORY },
            token = loadSecret(KEY_TOKEN_CIPHERTEXT, KEY_TOKEN_IV),
            passphrase =
                if (remember) {
                    loadSecret(
                        KEY_PASSPHRASE_CIPHERTEXT,
                        KEY_PASSPHRASE_IV
                    )
                } else {
                    ""
                },
            rememberPassphrase = remember
        )
    }

    fun saveConfig(config: GitHubBackupConfig) {
        val names = normalizedNames(config)
        prefs.edit()
            .putString(KEY_USERNAME, names.username)
            .putString(KEY_REPOSITORY, names.repository)
            .putBoolean(
                KEY_REMEMBER_PASSPHRASE,
                config.rememberPassphrase
            )
            .apply()

        saveSecret(
            config.token.trim(),
            KEY_TOKEN_CIPHERTEXT,
            KEY_TOKEN_IV
        )

        if (config.rememberPassphrase) {
            saveSecret(
                config.passphrase,
                KEY_PASSPHRASE_CIPHERTEXT,
                KEY_PASSPHRASE_IV
            )
        } else {
            clearSecret(
                KEY_PASSPHRASE_CIPHERTEXT,
                KEY_PASSPHRASE_IV
            )
        }
    }

    suspend fun uploadBackup(
        rawBackup: String,
        config: GitHubBackupConfig
    ): GitHubBackupUpload = withContext(Dispatchers.IO) {
        val cfg = validated(config, requirePassphrase = true)
        val branch = defaultBranch(cfg)
        val decorated = decorateBackup(rawBackup)
        val settingsVersion =
            JSONObject(decorated)
                .getJSONObject(BACKUP_METADATA_KEY)
                .optInt("settingsVersion", 1)
        val encrypted = encryptBackup(decorated, cfg.passphrase)
        val timestamp = publicTimestamp()
        val name =
            "NikTV-settings-v$settingsVersion-$timestamp.niktv"
        val path = "$BACKUP_DIRECTORY/$name"

        val payload = JSONObject()
            .put(
                "message",
                "NikTV encrypted backup · settings v$settingsVersion · $timestamp"
            )
            .put(
                "content",
                Base64.encodeToString(
                    encrypted.toByteArray(Charsets.UTF_8),
                    Base64.NO_WRAP
                )
            )
            .put("branch", branch)

        val request = githubRequest(
            cfg,
            "https://api.github.com/repos/${cfg.username}/${cfg.repository}/contents/$path"
        )
            .put(
                payload.toString()
                    .toRequestBody(JSON_MEDIA_TYPE)
            )
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw githubFailure("upload backup", response.code, body)
            }
            val json = JSONObject(body)
            GitHubBackupUpload(
                path = path,
                htmlUrl =
                    json.optJSONObject("content")
                        ?.optString("html_url").orEmpty()
            )
        }
    }

    suspend fun listBackups(
        config: GitHubBackupConfig
    ): List<GitHubBackupFile> = withContext(Dispatchers.IO) {
        val cfg = validated(config, requirePassphrase = false)
        val branch = defaultBranch(cfg)
        val url =
            "https://api.github.com/repos/${cfg.username}/${cfg.repository}/contents/" +
                "$BACKUP_DIRECTORY?ref=$branch"
        val request = githubRequest(cfg, url).get().build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 404) {
                return@withContext emptyList()
            }
            if (!response.isSuccessful) {
                throw githubFailure("list backups", response.code, body)
            }

            val items = JSONArray(body)
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val name = item.optString("name")
                    val path = item.optString("path")
                    if (
                        item.optString("type") == "file" &&
                        name.endsWith(".niktv", ignoreCase = true) &&
                        path.startsWith("$BACKUP_DIRECTORY/")
                    ) {
                        add(
                            GitHubBackupFile(
                                name = name,
                                path = path,
                                size = item.optLong("size", 0L)
                            )
                        )
                    }
                }
            }.sortedByDescending { it.name }
        }
    }

    suspend fun downloadAndDecryptBackup(
        file: GitHubBackupFile,
        config: GitHubBackupConfig
    ): GitHubBackupDecoded = withContext(Dispatchers.IO) {
        require(file.path.startsWith("$BACKUP_DIRECTORY/")) {
            "Invalid backup path."
        }
        require(file.name.endsWith(".niktv", ignoreCase = true)) {
            "Only encrypted NikTV backups can be restored here."
        }
        require(file.size <= MAX_BACKUP_BYTES) {
            "Backup is too large to restore safely."
        }

        val cfg = validated(config, requirePassphrase = true)
        val branch = defaultBranch(cfg)
        val url =
            "https://api.github.com/repos/${cfg.username}/${cfg.repository}/contents/" +
                "${file.path}?ref=$branch"

        val request = githubRequest(
            cfg,
            url,
            accept = "application/vnd.github.raw+json"
        ).get().build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw githubFailure("download backup", response.code, body)
            }
            val encrypted =
                response.body?.string()
                    ?: throw IllegalStateException(
                        "GitHub returned an empty backup."
                    )
            decryptBackup(encrypted, cfg.passphrase)
        }
    }

    fun decryptBackup(
        encryptedBackup: String,
        passphrase: String
    ): GitHubBackupDecoded {
        requireStrongPassphrase(passphrase)

        val envelope =
            runCatching { JSONObject(encryptedBackup) }
                .getOrElse {
                    throw IllegalArgumentException(
                        "This is not a valid encrypted NikTV backup."
                    )
                }

        require(
            envelope.optInt("containerVersion", -1) == CONTAINER_VERSION
        ) { "Unsupported encrypted NikTV backup version." }
        require(envelope.optString("cipher") == CIPHER_NAME) {
            "Unsupported backup cipher."
        }
        require(envelope.optString("kdf") == KDF_NAME) {
            "Unsupported backup key derivation."
        }
        require(
            envelope.optInt("iterations", -1) == PBKDF2_ITERATIONS
        ) { "Unsupported backup key-derivation work factor." }

        val salt = decodeBase64(envelope, "salt")
        val iv = decodeBase64(envelope, "iv")
        val ciphertext = decodeBase64(envelope, "ciphertext")

        require(salt.size == SALT_BYTES) { "Invalid backup salt." }
        require(iv.size == GCM_IV_BYTES) {
            "Invalid backup initialization vector."
        }
        require(
            ciphertext.isNotEmpty() &&
                ciphertext.size <= MAX_BACKUP_BYTES
        ) { "Invalid encrypted backup payload size." }

        val key = deriveKey(passphrase, salt)
        val plaintext =
            try {
                val cipher =
                    Cipher.getInstance(BACKUP_CIPHER_TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(GCM_TAG_BITS, iv)
                )
                cipher.updateAAD(BACKUP_AAD)
                cipher.doFinal(ciphertext)
            } catch (_: AEADBadTagException) {
                throw IllegalArgumentException(
                    "Incorrect backup password or the backup is corrupted."
                )
            } finally {
                key.fill(0)
            }

        val rawBackup = plaintext.toString(Charsets.UTF_8)
        plaintext.fill(0)

        val root =
            runCatching { JSONObject(rawBackup) }
                .getOrElse {
                    throw IllegalArgumentException(
                        "Decrypted data is not valid NikTV JSON."
                    )
                }
        require(root.has("formatVersion")) {
            "Decrypted data is not a NikTV settings backup."
        }

        return GitHubBackupDecoded(
            rawBackup = rawBackup,
            preview = preview(root)
        )
    }

    private fun encryptBackup(
        plaintext: String,
        passphrase: String
    ): String {
        requireStrongPassphrase(passphrase)

        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(GCM_IV_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt)

        val ciphertext =
            try {
                val cipher =
                    Cipher.getInstance(BACKUP_CIPHER_TRANSFORMATION)
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(GCM_TAG_BITS, iv)
                )
                cipher.updateAAD(BACKUP_AAD)
                cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            } finally {
                key.fill(0)
            }

        return JSONObject()
            .put("containerVersion", CONTAINER_VERSION)
            .put("cipher", CIPHER_NAME)
            .put("kdf", KDF_NAME)
            .put("passwordEncoding", "UTF-8-NFC")
            .put("iterations", PBKDF2_ITERATIONS)
            .put(
                "salt",
                Base64.encodeToString(salt, Base64.NO_WRAP)
            )
            .put(
                "iv",
                Base64.encodeToString(iv, Base64.NO_WRAP)
            )
            .put(
                "ciphertext",
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            )
            .toString(2)
    }

    /**
     * PBKDF2-HMAC-SHA256 is implemented directly using HmacSHA256 so the
     * encrypted format also works on NikTV's API 24/25 minimum devices.
     */
    private fun deriveKey(
        passphrase: String,
        salt: ByteArray
    ): ByteArray {
        val normalized =
            Normalizer.normalize(passphrase, Normalizer.Form.NFC)
        val password = normalized.toByteArray(Charsets.UTF_8)

        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(password, "HmacSHA256"))

            val blockInput = ByteArray(salt.size + 4)
            System.arraycopy(salt, 0, blockInput, 0, salt.size)
            blockInput[blockInput.lastIndex] = 1

            var u = mac.doFinal(blockInput)
            val result = u.copyOf()

            for (round in 1 until PBKDF2_ITERATIONS) {
                u = mac.doFinal(u)
                for (index in result.indices) {
                    result[index] =
                        (
                            result[index].toInt() xor
                                u[index].toInt()
                            ).toByte()
                }
            }
            u.fill(0)
            return result
        } finally {
            password.fill(0)
        }
    }

    private fun decorateBackup(rawBackup: String): String {
        val root = JSONObject(rawBackup)
        val now = Date()

        val metadata = JSONObject()
            .put(
                "settingsVersion",
                root.optInt("formatVersion", 1)
            )
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("exportedAt", readableTimestamp(now))
            .put(
                "device",
                JSONObject()
                    .put("displayName", deviceDisplayName())
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("device", Build.DEVICE)
                    .put("product", Build.PRODUCT)
                    .put("androidVersion", Build.VERSION.RELEASE)
                    .put("sdkInt", Build.VERSION.SDK_INT)
            )

        // ProfileStore ignores unknown fields, so this remains import-compatible.
        // This metadata exists only inside the encrypted plaintext.
        root.put(BACKUP_METADATA_KEY, metadata)
        return root.toString(2)
    }

    private fun preview(root: JSONObject): GitHubBackupPreview {
        val metadata = root.optJSONObject(BACKUP_METADATA_KEY)
        val device = metadata?.optJSONObject("device")
        val profileCount =
            runCatching {
                val encoded =
                    root.optJSONObject("strings")
                        ?.optString("saved_profiles").orEmpty()
                if (encoded.isBlank()) 0 else JSONArray(encoded).length()
            }.getOrDefault(0)

        return GitHubBackupPreview(
            deviceName =
                device?.optString("displayName")
                    ?.takeIf { it.isNotBlank() }
                    ?: "Unknown device",
            appVersion =
                metadata?.optString("appVersion")
                    ?.takeIf { it.isNotBlank() }
                    ?: "Unknown",
            settingsVersion =
                metadata?.optInt(
                    "settingsVersion",
                    root.optInt("formatVersion", 1)
                ) ?: root.optInt("formatVersion", 1),
            exportedAt =
                metadata?.optString("exportedAt")
                    ?.takeIf { it.isNotBlank() }
                    ?: "Unknown",
            profileCount = profileCount
        )
    }

    private fun defaultBranch(config: GitHubBackupConfig): String {
        val request = githubRequest(
            config,
            "https://api.github.com/repos/${config.username}/${config.repository}"
        ).get().build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw githubFailure(
                    "read repository ${config.username}/${config.repository}",
                    response.code,
                    body
                )
            }
            return JSONObject(body)
                .optString("default_branch", "main")
                .ifBlank { "main" }
        }
    }

    private fun githubRequest(
        config: GitHubBackupConfig,
        url: String,
        accept: String = "application/vnd.github+json"
    ): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("Authorization", "Bearer ${config.token}")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)

    private fun validated(
        config: GitHubBackupConfig,
        requirePassphrase: Boolean
    ): GitHubBackupConfig {
        val names = normalizedNames(config)
        require(GITHUB_NAME.matches(names.username)) {
            "Invalid GitHub username."
        }
        require(GITHUB_NAME.matches(names.repository)) {
            "Invalid GitHub repository."
        }
        require(config.token.trim().isNotBlank()) {
            "GitHub token is required."
        }
        if (requirePassphrase) {
            requireStrongPassphrase(config.passphrase)
        }
        return names.copy(
            token = config.token.trim(),
            passphrase = config.passphrase
        )
    }

    private fun normalizedNames(
        config: GitHubBackupConfig
    ): GitHubBackupConfig =
        config.copy(
            username =
                config.username.trim().ifBlank { DEFAULT_USERNAME },
            repository =
                config.repository.trim().removeSuffix(".git")
                    .ifBlank { DEFAULT_REPOSITORY }
        )

    private fun requireStrongPassphrase(passphrase: String) {
        require(passphrase.length >= MIN_PASSPHRASE_LENGTH) {
            "Backup password must be at least $MIN_PASSPHRASE_LENGTH characters."
        }
    }

    private fun decodeBase64(
        json: JSONObject,
        key: String
    ): ByteArray {
        val value = json.optString(key)
        require(value.isNotBlank()) {
            "Encrypted backup is missing $key."
        }
        return runCatching {
            Base64.decode(value, Base64.NO_WRAP)
        }.getOrElse {
            throw IllegalArgumentException(
                "Encrypted backup contains invalid $key."
            )
        }
    }

    private fun githubFailure(
        action: String,
        status: Int,
        body: String
    ): IllegalStateException {
        val message =
            runCatching {
                JSONObject(body).optString("message")
            }.getOrNull().orEmpty()
        val hint =
            when (status) {
                401 -> " Check that the token is valid."
                403 -> " Check repository permissions."
                404 -> " Check the username, repository and token access."
                409 -> " GitHub reported a repository conflict."
                422 -> " GitHub rejected the file request."
                else -> ""
            }
        return IllegalStateException(
            "Could not $action (GitHub HTTP $status)." +
                if (message.isBlank()) hint else " $message.$hint"
        )
    }

    private fun loadSecret(
        ciphertextKey: String,
        ivKey: String
    ): String =
        runCatching {
            val encrypted = prefs.getString(ciphertextKey, null)
            val iv = prefs.getString(ivKey, null)
            if (encrypted.isNullOrBlank() || iv.isNullOrBlank()) {
                ""
            } else {
                decryptSecret(encrypted, iv)
            }
        }.getOrDefault("")

    private fun saveSecret(
        value: String,
        ciphertextKey: String,
        ivKey: String
    ) {
        if (value.isBlank()) {
            clearSecret(ciphertextKey, ivKey)
            return
        }
        val encrypted = encryptSecret(value)
        prefs.edit()
            .putString(ciphertextKey, encrypted.ciphertext)
            .putString(ivKey, encrypted.iv)
            .apply()
    }

    private fun clearSecret(
        ciphertextKey: String,
        ivKey: String
    ) {
        prefs.edit()
            .remove(ciphertextKey)
            .remove(ivKey)
            .apply()
    }

    private fun encryptSecret(value: String): EncryptedSecret {
        val cipher =
            Cipher.getInstance(KEYSTORE_CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext =
            cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return EncryptedSecret(
            ciphertext =
                Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            iv =
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        )
    }

    private fun decryptSecret(
        ciphertext: String,
        iv: String
    ): String {
        val cipher =
            Cipher.getInstance(KEYSTORE_CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(
                GCM_TAG_BITS,
                Base64.decode(iv, Base64.NO_WRAP)
            )
        )
        return String(
            cipher.doFinal(
                Base64.decode(ciphertext, Base64.NO_WRAP)
            ),
            Charsets.UTF_8
        )
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore =
            KeyStore.getInstance("AndroidKeyStore")
                .apply { load(null) }
        val existing =
            keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        if (existing != null) return existing

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
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .build()
        )
        return generator.generateKey()
    }

    private fun deviceDisplayName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { "Android device" }

    private fun readableTimestamp(date: Date): String =
        SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss Z",
            Locale.US
        ).format(date)

    private fun publicTimestamp(): String {
        val formatter =
            SimpleDateFormat(
                "yyyyMMdd-HHmmss-SSS'Z'",
                Locale.US
            )
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date())
    }

    private data class EncryptedSecret(
        val ciphertext: String,
        val iv: String
    )

    companion object {
        private const val PREFS_NAME = "github_backup_config"
        private const val KEY_USERNAME = "username"
        private const val KEY_REPOSITORY = "repository"

        // Reuse V1 token storage so an already configured PAT survives upgrade.
        private const val KEY_TOKEN_CIPHERTEXT = "token_ciphertext"
        private const val KEY_TOKEN_IV = "token_iv"
        private const val KEYSTORE_ALIAS =
            "niktv_github_backup_token_v1"

        private const val KEY_REMEMBER_PASSPHRASE =
            "remember_backup_passphrase"
        private const val KEY_PASSPHRASE_CIPHERTEXT =
            "backup_passphrase_ciphertext"
        private const val KEY_PASSPHRASE_IV =
            "backup_passphrase_iv"

        private const val DEFAULT_USERNAME = "nikhilmenghani"
        private const val DEFAULT_REPOSITORY = "tracker"
        private const val BACKUP_DIRECTORY = "backups"
        private const val BACKUP_METADATA_KEY = "backupMetadata"

        private const val GITHUB_API_VERSION = "2026-03-10"
        private const val CONTAINER_VERSION = 1
        private const val CIPHER_NAME = "AES-256-GCM"
        private const val KDF_NAME = "PBKDF2-HMAC-SHA256"
        private const val PBKDF2_ITERATIONS = 600_000
        private const val MIN_PASSPHRASE_LENGTH = 12
        private const val SALT_BYTES = 16
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val MAX_BACKUP_BYTES =
            20L * 1024L * 1024L

        private const val KEYSTORE_CIPHER_TRANSFORMATION =
            "AES/GCM/NoPadding"
        private const val BACKUP_CIPHER_TRANSFORMATION =
            "AES/GCM/NoPadding"

        private val BACKUP_AAD =
            "NikTVBackupContainer:v1"
                .toByteArray(Charsets.UTF_8)
        private val JSON_MEDIA_TYPE =
            "application/json; charset=utf-8".toMediaType()
        private val GITHUB_NAME =
            Regex("^[A-Za-z0-9_.-]+$")
    }
}
