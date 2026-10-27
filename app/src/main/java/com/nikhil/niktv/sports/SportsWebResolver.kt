package com.nikhil.niktv.sports

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.resume

internal class SportsWebResolver(private val context: Context) {
    private val extractionScript by lazy { context.assets.open("sports/extract-media.js").bufferedReader().use { it.readText() } }

    suspend fun resolve(stream: SportsStream): SportsStream {
        if (stream.link.toHttpUrlOrNull()?.let { it.username.isEmpty() && it.password.isEmpty() } != true) {
            throw SportsException("This source returned an invalid web-player address.")
        }
        val script = withContext(Dispatchers.IO) { extractionScript }
        return withContext(Dispatchers.Main.immediate) { withTimeout(15_000) {
            var view: WebView? = null
            val handler = Handler(Looper.getMainLooper())
            try {
                suspendCancellableCoroutine { continuation ->
                    val web = WebView(context)
                    view = web
                    val visited = linkedSetOf(stream.link)
                    val nested = ArrayDeque<String>()
                    var page = stream.link
                    var polls = 0
                    var pageFinished = false
                    var runningScript = false
                    fun complete(url: String, referringPage: String = page, requestHeaders: Map<String, String> = emptyMap()) {
                        if (continuation.isActive && url.toHttpUrlOrNull()?.let { it.username.isEmpty() && it.password.isEmpty() } == true) {
                            // CookieManager scopes this value to the media URL; never
                            // copy a page's cookies onto an unrelated CDN origin.
                            val captured = requestHeaders.toMutableMap()
                            CookieManager.getInstance().getCookie(url)?.takeIf(String::isNotBlank)?.let { cookie ->
                                if (captured.keys.none { it.equals("Cookie", true) }) captured["Cookie"] = cookie
                            }
                            continuation.resume(stream.copy(link = url,
                                headers = sportsWebHeaders(stream, referringPage, url, captured)))
                        }
                    }
                    web.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = false
                        allowFileAccess = false
                        allowContentAccess = false
                        userAgentString = sportsWebHeaders(stream, page).getValue("User-Agent")
                    }
                    web.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { page = url; pageFinished = false; polls = 0 }
                        override fun onPageFinished(view: WebView, url: String) { page = url; pageFinished = true }
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.scheme !in setOf("https", "http")
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url.toString()
                            if (sportsMediaRequest(url)) {
                                val captured = request.requestHeaders.toMap()
                                val referringPage = captured.entries.firstOrNull { it.key.equals("Referer", true) }?.value ?: page
                                handler.post { complete(url, referringPage, captured) }
                            }
                            return null
                        }
                    }
                    val poll = object : Runnable {
                        override fun run() {
                            if (!continuation.isActive) return
                            if (!runningScript) {
                                runningScript = true
                                web.evaluateJavascript(script) { value ->
                                    runningScript = false
                                    if (!continuation.isActive) return@evaluateJavascript
                                    val found = sportsWebCandidates(value)
                                    if (found.media.isNotEmpty()) { complete(found.media.first()); return@evaluateJavascript }
                                    found.frames.filter { it !in visited && it !in nested }.forEach { nested.add(it) }
                                    if (pageFinished && ++polls >= 3 && nested.isNotEmpty() && visited.size < 5) {
                                        val next = nested.removeFirst()
                                        val referringPage = page
                                        visited.add(next)
                                        page = next
                                        polls = 0
                                        pageFinished = false
                                        web.loadUrl(next, sportsWebHeaders(stream, referringPage, next))
                                    }
                                }
                            }
                            handler.postDelayed(this, 400)
                        }
                    }
                    web.loadUrl(stream.link, sportsWebHeaders(stream, page))
                    handler.postDelayed(poll, 300)
                }
            } finally {
                handler.removeCallbacksAndMessages(null)
                view?.apply { stopLoading(); webViewClient = WebViewClient(); destroy() }
            }
        } }
    }
}
