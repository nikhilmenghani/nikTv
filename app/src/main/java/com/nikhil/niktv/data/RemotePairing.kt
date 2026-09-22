package com.nikhil.niktv.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class RemotePairingSession(val relay: String, val id: String, val secret: String) {
    val inviteUri: String get() = Uri.Builder().scheme("niktv").authority("pair")
        .appendQueryParameter("relay", relay).appendQueryParameter("id", id)
        .appendQueryParameter("key", secret).build().toString()
}

object RemotePairing {
    private const val PREFS = "niktv_pairing_relay"
    private const val SESSION_MILLIS = 15 * 60 * 1000L
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun relay(context: Context): String = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("url", "").orEmpty()

    fun saveRelay(context: Context, input: String) {
        val url = normalized(input)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("url", url).apply()
    }

    fun normalized(input: String): String {
        val url = input.trim().trimEnd('/')
        val parsed = Uri.parse(url)
        require(parsed.scheme == "https" && !parsed.host.isNullOrBlank() && !parsed.encodedAuthority.orEmpty().contains('@')) {
            "Enter the relay's HTTPS URL."
        }
        require(parsed.query == null && parsed.fragment == null) { "Enter the relay base URL only." }
        return url
    }

    fun parseInvite(raw: String): RemotePairingSession {
        require(raw.length <= 2048) { "Pairing link is too long." }
        val uri = Uri.parse(raw.trim())
        require(uri.scheme == "niktv" && uri.host == "pair") { "Enter a NikTV pairing link." }
        val relay = normalized(uri.getQueryParameter("relay").orEmpty())
        val id = uri.getQueryParameter("id").orEmpty()
        val secret = uri.getQueryParameter("key").orEmpty()
        require(id.matches(Regex("[A-Za-z0-9_-]{22}")) && secret.matches(Regex("[A-Za-z0-9_-]{22}"))) {
            "Pairing link is incomplete."
        }
        return RemotePairingSession(relay, id, secret)
    }

    suspend fun create(context: Context, relayUrl: String): RemotePairingSession = withContext(Dispatchers.IO) {
        val relay = normalized(relayUrl)
        val id = PairingCrypto.newSecret()
        val secret = PairingCrypto.newSecret()
        request("POST", "$relay/v1/sessions", JSONObject().put("id", id).toString())
        saveRelay(context, relay)
        RemotePairingSession(relay, id, secret)
    }

    suspend fun approve(context: Context, session: RemotePairingSession) = withContext(Dispatchers.IO) {
        require(RemoteCredentials.canPairDevices(context)) { "Only the device where the GitHub token was entered manually can approve pairing." }
        val relay = normalized(session.relay)
        val envelope = PairingCrypto.seal(RemoteCredentials.pairingConfiguration(context),
            session.secret, System.currentTimeMillis() + SESSION_MILLIS)
        request("PUT", "$relay/v1/sessions/${session.id}",
            JSONObject().put("package", envelope).toString())
        saveRelay(context, relay)
    }

    suspend fun await(context: Context, session: RemotePairingSession) = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + SESSION_MILLIS
        while (System.currentTimeMillis() < deadline) {
                val response = try {
                    JSONObject(request("GET", "${session.relay}/v1/sessions/${session.id}"))
                } catch (error: IOException) {
                    if (error.message.orEmpty().contains("HTTP 404")) throw error
                    delay(3000)
                    continue
                }
                when (response.getString("status")) {
                    "ready" -> {
                        val configuration = PairingCrypto.open(response.getString("package"), session.secret)
                        RemoteCredentials.savePairedConnection(context, configuration)
                        runCatching { request("DELETE", "${session.relay}/v1/sessions/${session.id}") }
                        return@withContext
                    }
                    "pending" -> delay(3000)
                    else -> throw IOException("Remote pairing expired or was cancelled.")
                }
        }
        throw IOException("Remote pairing timed out.")
    }

    suspend fun cancel(session: RemotePairingSession) = withContext(Dispatchers.IO) {
        runCatching { request("DELETE", "${session.relay}/v1/sessions/${session.id}") }
    }

    private fun request(method: String, url: String, body: String? = null): String {
        val builder = Request.Builder().url(url)
        when (method) {
            "POST", "PUT" -> builder.method(method, body.orEmpty().toRequestBody(jsonType))
            "DELETE" -> builder.delete()
            else -> builder.get()
        }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Pairing relay returned HTTP ${response.code}.")
            return response.body?.string().orEmpty()
        }
    }
}
