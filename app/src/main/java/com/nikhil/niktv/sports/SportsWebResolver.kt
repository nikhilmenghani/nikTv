package com.nikhil.niktv.sports

import android.content.Context
import android.os.Handler
import android.os.Looper
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
                    fun complete(url: String, referringPage: String = page) {
                        if (continuation.isActive && url.toHttpUrlOrNull()?.let { it.username.isEmpty() && it.password.isEmpty() } == true) {
                            continuation.resume(stream.copy(link = url, headers = sportsWebHeaders(stream, referringPage)))
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
                                val referringPage = request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value
                                handler.post { complete(url, referringPage ?: page) }
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
                                        visited.add(next)
                                        page = next
                                        polls = 0
                                        pageFinished = false
                                        web.loadUrl(next)
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
