package com.nikhil.niktv.ui

import android.annotation.SuppressLint
import android.view.Gravity
import android.webkit.*
import android.widget.*
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nikhil.niktv.data.*
import org.json.JSONObject
import java.net.URI

internal fun cinejoyNavigationAllowed(url: String, expected: String): Boolean =
    runCatching { val u = URI(url); val e = URI(expected)
        u.scheme == "https" && e.host in setOf("cinejoy.pk", "www.cinejoy.pk", "cno.to") && u.host == e.host &&
            u.userInfo == null && u.port in listOf(-1, 443) &&
            (u.path == e.path || (e.host == "cno.to" && cnoWatchIdentity(u.path) != null && cnoWatchIdentity(u.path) == cnoWatchIdentity(e.path)))
    }.getOrDefault(false)

private fun cnoWatchIdentity(path: String): List<String>? =
    Regex("^/watch/(show/)?([0-9]+)(?:-[a-zA-Z0-9-]+)?(?:/([0-9]+)/([0-9]+))?/?$")
        .matchEntire(path)?.groupValues?.drop(1)

/** Run the public site's own resolver as a top-level page; never emulate its WASM protection. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun CinejoyPlayer(target: AlternateSourceTarget, resume: Long, close: () -> Unit,
    onProgress: (Long, Long) -> Unit, onIntent: (Boolean) -> Unit,
    onNative: (AlternateStream, Long, (String) -> Unit) -> Unit,
    nativeOnly: Boolean = false) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val progress by rememberUpdatedState(onProgress)
    val intent by rememberUpdatedState(onIntent)
    val native by rememberUpdatedState(onNative)
    val closeNow by rememberUpdatedState(close)
    val cno = target.provider == AlternateSourceProvider.CNO
    val sourceName = if (cno) "Cno" else "Cinejoy"
    val url = remember(target) { if (cno) cnoWebStream(target).url else cinejoyWebStream(target).url }
    var position by remember(target) { mutableLongStateOf(resume) }
    var duration by remember(target) { mutableLongStateOf(0) }
    var candidate by remember(target) { mutableStateOf<AlternateStream?>(null) }
    var disposed by remember(target) { mutableStateOf(false) }
    var handedOff by remember(target) { mutableStateOf(false) }
    val spinner = remember(context, target) { ProgressBar(context) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    val status = remember(context, target) { TextView(context).apply { setTextColor(-1); text = "Resolving $sourceName…" } }
    val browser = remember(context, target) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            // Resolve the page's ordinary public request, without starting a second player.
            settings.mediaPlaybackRequiresUserGesture = nativeOnly
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(true)
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(v: WebView, dialog: Boolean, gesture: Boolean, result: android.os.Message) = false
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && !cinejoyNavigationAllowed(request.url.toString(), url)
                override fun doUpdateVisitedHistory(v: WebView, visited: String, reload: Boolean) {
                    if (!cinejoyNavigationAllowed(visited, url) && visited != "about:blank") v.loadUrl(url)
                }
                override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val value = request.url.toString()
                    if (request.url.path.orEmpty().endsWith(".m3u8", true) && request.url.scheme == "https") {
                        val headers = request.requestHeaders.filterKeys { it.lowercase() in setOf("user-agent", "referer", "origin") }
                        handler.post { if (!disposed) candidate = AlternateStream(if (cno) "cno-detected" else "cinejoy-detected", "$sourceName native", value,
                            headers + ("User-Agent" to settings.userAgentString)) }
                    }
                    return null
                }
                override fun onPageFinished(v: WebView, loaded: String) { if (!disposed && !nativeOnly) status.text = "$sourceName · Stream choices are controlled by the website" }
            }
        }
    }
    fun flush() { if (duration > 0) progress(position, duration) }
    BackHandler { flush(); closeNow() }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = {
        FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            browser.alpha = if (nativeOnly) 0f else 1f
            browser.importantForAccessibility = if (nativeOnly) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            browser.isFocusable = !nativeOnly
            addView(browser, FrameLayout.LayoutParams(-1, -1).apply { topMargin = (56 * resources.displayMetrics.density).toInt() })
            val toolbar = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(0xFF181B22.toInt())
                fun button(label: String, action: () -> Unit) { addView(Button(context).apply { text = label; contentDescription = label; isAllCaps = false; setOnClickListener { action() } }) }
                button("Close") { flush(); closeNow() }
                button("Reload") { handedOff = false; candidate = null; spinner.visibility = android.view.View.VISIBLE; browser.reload() }
                if (!nativeOnly) button("Play in NikTV") {
                    val found = candidate
                    if (found == null || duration < 60_000) status.text = "Start the video first; a compatible HLS stream is required."
                    else {
                        status.text = "Checking native playback…"
                        browser.evaluateJavascript("(function(){let v=document.querySelector('video');return v?JSON.stringify({p:v.currentTime,d:v.duration,paused:v.paused,drm:!!v.mediaKeys}):null})()") { result ->
                            if (!disposed) runCatching {
                                val sample = JSONObject(org.json.JSONTokener(result).nextValue() as String)
                                val p = sample.getDouble("p"); val d = sample.getDouble("d")
                                require(!sample.optBoolean("drm") && p.isFinite() && d.isFinite() && p >= 0 && d in 60.0..604800.0 && p <= d + 2)
                                position = (p * 1000).toLong(); duration = (d * 1000).toLong()
                                intent(!sample.optBoolean("paused")); flush()
                                native(found, position) { if (!disposed) status.text = it }
                            }.onFailure { status.text = "The video is not ready for native playback." }
                        }
                    }
                }
                addView(status, LinearLayout.LayoutParams(0, -2, 1f))
            }
            addView(toolbar, FrameLayout.LayoutParams(-1, (56 * resources.displayMetrics.density).toInt()))
            if (nativeOnly) addView(spinner, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
    })
    LaunchedEffect(browser) { withFrameNanos { }; browser.loadUrl(url) }
    LaunchedEffect(candidate, nativeOnly) {
        val found = candidate
        if (nativeOnly && found != null && !handedOff) {
            handedOff = true
            native(found, resume) { if (!disposed) { status.text = it; spinner.visibility = android.view.View.GONE } }
        }
    }
    LaunchedEffect(browser, nativeOnly, handedOff) {
        if (nativeOnly) {
            kotlinx.coroutines.delay(30_000)
            if (!handedOff && !disposed) {
                handedOff = true
                browser.stopLoading()
                status.text = "$sourceName did not provide a native stream. Reload or choose another source."
                spinner.visibility = android.view.View.GONE
            }
        }
    }
    DisposableEffect(browser, owner) {
        var paused: Boolean? = null
        var sought = false
        var resumeAt = resume
        var preservePause = false
        val poll = object : Runnable {
            override fun run() {
                if (disposed || nativeOnly) return
                browser.evaluateJavascript("(function(){let v=document.querySelector('video');return v?JSON.stringify({p:v.currentTime,d:v.duration,paused:v.paused,drm:!!v.mediaKeys}):null})()") { result ->
                    if (!disposed) runCatching {
                        val decoded = org.json.JSONTokener(result).nextValue() as? String ?: return@runCatching
                        val obj = JSONObject(decoded); val p = obj.optDouble("p"); val d = obj.optDouble("d")
                        if (obj.optBoolean("drm")) candidate = null
                        if (p.isFinite() && d.isFinite() && p >= 0 && d in 60.0..604800.0 && p <= d + 2) {
                            position = (p * 1000).toLong(); duration = (d * 1000).toLong()
                            val nowPaused = obj.optBoolean("paused")
                            if (paused != nowPaused) { paused = nowPaused; intent(!nowPaused) }
                            if (!sought) { sought = true; if (resumeAt > 0 || preservePause) browser.evaluateJavascript("(function(){let v=document.querySelector('video');if(v){v.currentTime=${resumeAt.coerceAtMost(duration) / 1000.0};${if (preservePause) "v.pause();" else ""}}})()", null) }
                            flush()
                        }
                    }
                }
                handler.postDelayed(this, 5_000)
            }
        }
        handler.postDelayed(poll, 1_000)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { handler.removeCallbacks(poll); flush(); resumeAt = position; preservePause = paused == true; sought = false; browser.stopLoading(); browser.loadUrl("about:blank"); browser.onPause() }
            if (event == Lifecycle.Event.ON_START) { handler.removeCallbacks(poll); handler.postDelayed(poll, 1_000); browser.onResume(); if (browser.url == "about:blank") browser.loadUrl(url) }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            flush(); disposed = true; handler.removeCallbacks(poll); owner.lifecycle.removeObserver(observer)
            browser.stopLoading(); browser.loadUrl("about:blank"); (browser.parent as? android.view.ViewGroup)?.removeView(browser); browser.destroy()
        }
    }
}
