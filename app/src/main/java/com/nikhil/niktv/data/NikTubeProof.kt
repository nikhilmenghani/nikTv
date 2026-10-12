package com.nikhil.niktv.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class NikTubeProofToken(val token: String, val sessionToken: String, val visitor: String,
    val timestamp: Int, val playerUrl: String, val clientVersion: String, val expiresAt: Long)

internal class NikTubeProof(private val context: Context) {
    private val mutex = Mutex()
    private val cached = mutableMapOf<String, NikTubeProofToken>()
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    suspend fun decipherN(playerUrl: String, input: String): String = mutex.withLock {
        withTimeout(25_000) { withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val main = Handler(Looper.getMainLooper())
                val web = WebView(context.applicationContext)
                var destroyed = false
                fun destroy() {
                    if (destroyed) return
                    destroyed = true
                    web.removeJavascriptInterface("NikTubeSignature")
                    web.stopLoading()
                    web.destroy()
                }
                web.settings.javaScriptEnabled = true
                web.settings.blockNetworkLoads = true
                web.addJavascriptInterface(object {
                    @JavascriptInterface fun player(): String {
                        val uri = android.net.Uri.parse(playerUrl)
                        require(uri.scheme == "https" && uri.host == "www.youtube.com" && uri.path.orEmpty().startsWith("/s/player/"))
                        return http.newCall(Request.Builder().url(playerUrl).build()).execute().use {
                            check(it.isSuccessful)
                            it.body?.string() ?: error("Missing player script")
                        }
                    }
                    @JavascriptInterface fun value(): String = input
                    @JavascriptInterface fun success(value: String) {
                        main.post {
                            if (continuation.isActive) {
                                destroy()
                                continuation.resume(value)
                            }
                        }
                    }
                    @JavascriptInterface fun failure() {
                        main.post {
                            if (continuation.isActive) {
                                destroy()
                                continuation.resumeWithException(IllegalStateException("YouTube's current player could not prepare this stream."))
                            }
                        }
                    }
                }, "NikTubeSignature")
                continuation.invokeOnCancellation { main.post { destroy() } }
                val code = listOf("meriyah-6.1.4.min.js", "astring-1.9.0.min.js", "yt.solver.core.js")
                    .joinToString("\n") { asset -> context.assets.open("niktube/$asset").bufferedReader().use { it.readText() } }
                val runner = """
                    try {
                      const n = NikTubeSignature.value();
                      const result = jsc({type:'player', player:NikTubeSignature.player(), requests:[{type:'n', challenges:[n]}]});
                      const response = result.responses[0];
                      if (response.type !== 'result' || typeof response.data[n] !== 'string'
                          || !response.data[n] || response.data[n].startsWith('enhanced_except_')) throw new Error();
                      NikTubeSignature.success(response.data[n]);
                    } catch (_) { NikTubeSignature.failure(); }
                """.trimIndent()
                web.loadDataWithBaseURL("https://www.youtube.com/", "<!doctype html><script>$code\n$runner</script>", "text/html", "UTF-8", null)
            }
        } }
    }

    suspend fun token(videoId: String): NikTubeProofToken = mutex.withLock {
        check(com.nikhil.niktv.BuildConfig.NIKTUBE_BOTGUARD_API_KEY.isNotBlank()) {
            "NikTube playback verification is not configured in this build."
        }
        cached[videoId]?.takeIf { it.expiresAt > SystemClock.elapsedRealtime() }?.let { return@withLock it }
        val result = withTimeout(35_000) { withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { continuation ->
                val main = Handler(Looper.getMainLooper())
                val web = WebView(context.applicationContext)
                var destroyed = false
                fun destroy() {
                    if (destroyed) return
                    destroyed = true
                    web.removeJavascriptInterface("NikTubeProof")
                    web.stopLoading()
                    web.destroy()
                }
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.settings.blockNetworkLoads = true
                web.settings.userAgentString = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36(KHTML, like Gecko)"
                val userAgent = web.settings.userAgentString
                web.addJavascriptInterface(object {
                    @JavascriptInterface fun video(): String = videoId
                    @JavascriptInterface fun request(url: String, body: String, headerJson: String): String {
                        val parsed = android.net.Uri.parse(url)
                        require(parsed.scheme == "https" && parsed.host in setOf("www.youtube.com", "www.google.com", "www.gstatic.com", "jnn-pa.googleapis.com"))
                        val request = Request.Builder().url(url).header("User-Agent", userAgent)
                        if (parsed.host == "www.youtube.com" && parsed.path == "/api/jnn/v1/GenerateIT") {
                            request.header("x-goog-api-key", com.nikhil.niktv.BuildConfig.NIKTUBE_BOTGUARD_API_KEY)
                        }
                        val headers = JSONObject(headerJson)
                        headers.keys().forEach { key -> if (!key.equals("User-Agent", true)) request.header(key, headers.getString(key)) }
                        if (body.isNotEmpty()) {
                            val type = headers.keys().asSequence().firstOrNull { it.equals("Content-Type", true) }
                                ?.let(headers::getString) ?: "application/json"
                            request.header("Origin", "https://www.youtube.com").header("Referer", "https://www.youtube.com/")
                                .post(body.toRequestBody(type.toMediaType()))
                        }
                        return http.newCall(request.build()).execute().use { response ->
                            check(response.isSuccessful) { "Verification service returned ${response.code}" }
                            response.body?.string() ?: error("Empty verification response")
                        }
                    }
                    @JavascriptInterface fun success(json: String) {
                        main.post {
                            if (!continuation.isActive) return@post
                            try {
                                val response = JSONObject(json)
                                val token = response.getString("token")
                                require(token.length in 20..4096 && token.matches(Regex("[A-Za-z0-9_=-]+")))
                                val sessionToken = response.getString("sessionToken")
                                require(sessionToken.length in 20..4096 && sessionToken.matches(Regex("[A-Za-z0-9_=-]+")))
                                val ttl = response.optLong("ttl", 600).coerceIn(60, 1800)
                                val result = NikTubeProofToken(token, sessionToken, response.getString("visitor"),
                                    response.getInt("timestamp"), response.getString("playerUrl"), response.getString("clientVersion"),
                                    SystemClock.elapsedRealtime() + (ttl - 30) * 1000)
                                destroy()
                                continuation.resume(result)
                            } catch (_: Exception) {
                                destroy()
                                continuation.resumeWithException(IllegalStateException("YouTube verification returned invalid data."))
                            }
                        }
                    }
                    @JavascriptInterface fun failure(stage: String) {
                        main.post {
                            if (continuation.isActive) {
                                destroy()
                                continuation.resumeWithException(IllegalStateException("YouTube verification failed at ${stage.take(40)}. Retry playback."))
                            }
                        }
                    }
                }, "NikTubeProof")
                continuation.invokeOnCancellation { main.post { destroy() } }
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) { view.evaluateJavascript("mintProof()", null) }
                }
                val library = context.assets.open("niktube/bgutils.js").bufferedReader().use { it.readText() }
                val runner = context.assets.open("niktube/proof.js").bufferedReader().use { it.readText() }
                web.loadDataWithBaseURL("https://www.youtube.com/", "<!doctype html><html><body><script>$library\n$runner</script></body></html>", "text/html", "UTF-8", null)
            }
        } }
        cached.entries.removeAll { it.value.expiresAt <= SystemClock.elapsedRealtime() }
        cached[videoId] = result
        result
    }
}
