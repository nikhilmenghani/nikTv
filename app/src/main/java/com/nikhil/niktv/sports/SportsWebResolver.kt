package com.nikhil.niktv.sports

import android.content.Context
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
    suspend fun resolve(stream: SportsStream): SportsStream = withContext(Dispatchers.Main.immediate) {
        withTimeout(12_000) {
            var view: WebView? = null
            try {
                suspendCancellableCoroutine { continuation ->
                    val web = WebView(context)
                    view = web
                    web.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = false
                        allowFileAccess = false
                        allowContentAccess = false
                        userAgentString = stream.headers["User-Agent"] ?: SPORTS_USER_AGENT
                    }
                    web.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.scheme !in setOf("https", "http")
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url.toString()
                            val path = url.toHttpUrlOrNull()?.encodedPath.orEmpty().lowercase(java.util.Locale.ROOT)
                            if (listOf(".m3u8", ".mpd", ".mp4").any(path::endsWith)) {
                                view.post {
                                    if (continuation.isActive) {
                                        val headers = request.requestHeaders.toMutableMap()
                                        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
                                        continuation.resume(stream.copy(link = url, headers = stream.headers + headers))
                                    }
                                }
                            }
                            return null
                        }
                    }
                    web.loadUrl(stream.link, stream.headers)
                }
            } finally {
                view?.apply { stopLoading(); webViewClient = WebViewClient(); destroy() }
            }
        }
    }
}
