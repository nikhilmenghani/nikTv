package com.nikhil.niktv.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nikhil.niktv.data.AlternateSourceTarget
import java.net.URI
import java.net.URLDecoder
import java.util.UUID

/** Official public embed API. Episode changes stay in NikTV so progress cannot change identity. */
internal fun vidyEmbeddedUrl(target: AlternateSourceTarget, positionMillis: Long = 0, autoplay: Boolean = true): String? {
    val id = target.tmdbId?.takeIf { it > 0 } ?: return null
    val path = if (target.series) {
        val season = target.season?.takeIf { it >= 0 } ?: return null
        val episode = target.episode?.takeIf { it > 0 } ?: return null
        "/tv/$id/$season/$episode"
    } else "/movie/$id"
    return "https://vidy.st$path?color=E50914&autoplay=$autoplay&progress=${positionMillis.coerceAtLeast(0) / 1000}"
}

private const val VIDY_HOST_ORIGIN = "https://niktv.invalid"
private val VIDY_EMBED_HOSTS = setOf("vidy.st", "www.vidy.st")

internal fun vidyAllowedEmbedNavigation(url: String): Boolean = runCatching {
    val uri = URI(url)
    uri.scheme == "https" && uri.host in VIDY_EMBED_HOSTS && uri.userInfo == null && uri.port in listOf(-1, 443)
}.getOrDefault(false)

internal data class VidyPlaybackEvent(val event: String, val positionMillis: Long, val durationMillis: Long)

/** Only the local main-frame relay with the current nonce can affect progress or pause intent. */
internal fun vidyPlaybackEvent(url: String, nonce: String, mainFrame: Boolean): VidyPlaybackEvent? = runCatching {
    val uri = URI(url)
    if (!mainFrame || uri.scheme != "https" || uri.host != "niktv.invalid" || uri.path != "/__vidy_event" ||
        uri.userInfo != null || uri.port != -1 || url.length > 2048) return@runCatching null
    val fields = uri.rawQuery.orEmpty().split('&').map {
        val pair = it.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
    }
    if (fields.map { it.first }.distinct().size != fields.size) return@runCatching null
    val values = fields.toMap()
    if (nonce.isBlank() || values["nonce"] != nonce || values["event"] !in setOf("play", "pause", "timeupdate", "ended")) return@runCatching null
    val position = values["position"]?.toDoubleOrNull() ?: return@runCatching null
    val duration = values["duration"]?.toDoubleOrNull() ?: return@runCatching null
    if (!position.isFinite() || !duration.isFinite() || position < 0 || duration <= 0 ||
        duration > 7 * 24 * 60 * 60 || position > duration + 2) return@runCatching null
    VidyPlaybackEvent(values.getValue("event"), (position.coerceAtMost(duration) * 1000).toLong(), (duration * 1000).toLong())
}.getOrNull()

/** No native player is created here. The caller commits this route only after native resolution. */
@Composable
internal fun VidyEmbeddedPlayer(
    target: AlternateSourceTarget,
    resumePositionMillis: Long = 0,
    playWhenReady: Boolean = true,
    onClose: () -> Unit,
    onProgress: (Long, Long) -> Unit = { _, _ -> },
    onCompleted: () -> Unit = {},
    onChooseSource: (() -> Unit)? = null,
    onPlaybackIntentChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val window = context.findHostActivity()?.window
    val close = rememberUpdatedState(onClose)
    val progress = rememberUpdatedState(onProgress)
    val complete = rememberUpdatedState(onCompleted)
    val chooseSource = rememberUpdatedState(onChooseSource)
    val playbackIntentChanged = rememberUpdatedState(onPlaybackIntentChanged)
    val host = remember(context, target) {
        VidyEmbedHost(context, target, resumePositionMillis, playWhenReady,
            { close.value() }, { position, duration -> progress.value(position, duration) },
            { complete.value() }, onChooseSource?.let { { chooseSource.value?.invoke() } },
            { playing -> playbackIntentChanged.value(playing) })
    }
    BackHandler { if (!host.closeFullscreen()) host.requestClose() }
    AndroidView(factory = { host.root }, modifier = Modifier.fillMaxSize())
    LaunchedEffect(host) {
        // Let the previous native player's DisposableEffect release its stream first.
        withFrameNanos { }
        window?.let { WindowCompat.getInsetsController(it, it.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        } }
        host.activate()
    }
    DisposableEffect(host, owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> host.foreground(true)
                Lifecycle.Event.ON_STOP -> host.foreground(false)
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        host.foreground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose {
            owner.lifecycle.removeObserver(observer); host.release()
            window?.let { WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private class VidyEmbedHost(
    context: android.content.Context,
    private val target: AlternateSourceTarget,
    position: Long,
    autoplay: Boolean,
    private val close: () -> Unit,
    private val progress: (Long, Long) -> Unit,
    private val completed: () -> Unit,
    chooseSource: (() -> Unit)?,
    private val playbackIntentChanged: (Boolean) -> Unit,
) {
    val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
    private val main = Handler(Looper.getMainLooper())
    private var disposed = false
    private var activated = false
    private var visible = false
    private var nonce = ""
    private var positionMillis = position.coerceAtLeast(0)
    private var durationMillis = 0L
    private var lastReportAt = 0L
    private var lastReportedPosition = -1L
    private var wantsPlayback = autoplay
    private var completionSent = false
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var web: WebView? = null
    private val status = TextView(context).apply {
        text = "Loading embedded player…"; setTextColor(Color.LTGRAY); gravity = Gravity.CENTER
        setPadding(dp(24), dp(12), dp(24), dp(12))
        setBackgroundColor(0xCC181B22.toInt())
    }
    private val loading = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        indeterminateTintList = android.content.res.ColorStateList.valueOf(0xFFE50914.toInt())
        contentDescription = "Loading embedded player"
    }
    private val startupTimeout = Runnable {
        if (visible && !disposed) failure("The embedded source is taking too long. Reload or choose another source.")
    }
    private val closeButton: Button

    init {
        val browser = runCatching { WebView(context) }.getOrNull()
        web = browser
        browser?.apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true; isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(true)
            settings.setGeolocationEnabled(false)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            setDownloadListener { _, _, _, _, _ -> }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                override fun onCreateWindow(view: WebView, dialog: Boolean, gesture: Boolean, result: android.os.Message): Boolean = false
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (disposed || customView != null) { callback.onCustomViewHidden(); return }
                    customView = view; customCallback = callback
                    root.addView(view, 1, FrameLayout.LayoutParams(-1, -1))
                }
                override fun onHideCustomView() { closeFullscreen() }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val url = request.url.toString()
                    val event = vidyPlaybackEvent(url, nonce, request.isForMainFrame)
                    if (event != null) receive(event)
                    // Host is loaded with loadDataWithBaseURL. No page, popup, custom scheme,
                    // or intent may replace it; frame navigation is limited to the embed hosts.
                    return request.isForMainFrame || !vidyAllowedEmbedNavigation(url)
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && visible) failure("Could not load the embedded player. Reload or choose another source.")
                }
                @androidx.annotation.RequiresApi(26)
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    if (web === view) {
                        web = null; (view.parent as? ViewGroup)?.removeView(view); view.destroy()
                        failure("The embedded player stopped. Choose another source.")
                    }
                    return true
                }
            }
        }
        root.addView(browser ?: View(context), FrameLayout.LayoutParams(-1, -1))
        root.addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(loading, FrameLayout.LayoutParams(dp(160), dp(4), Gravity.CENTER).apply { topMargin = dp(70) })
        val toolbar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(28))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), Color.TRANSPARENT))
        }
        fun action(label: String, click: () -> Unit): Button = Button(context).apply {
            text = label; contentDescription = label; textSize = 13f; isAllCaps = false
            setTextColor(Color.WHITE); isFocusableInTouchMode = true
            minWidth = dp(64); minimumWidth = dp(64)
            setPadding(dp(12), 0, dp(12), 0)
            background = StateListDrawable().apply {
                fun surface(fill: Int, border: Int) = GradientDrawable().apply {
                    setColor(fill); cornerRadius = dp(12).toFloat(); setStroke(dp(1), border)
                }
                addState(intArrayOf(android.R.attr.state_focused), surface(0xFF303A49.toInt(), Color.WHITE))
                addState(intArrayOf(), surface(0xAA181B22.toInt(), 0x33FFFFFF))
            }
            setOnClickListener { click() }
            setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (event.action == KeyEvent.ACTION_DOWN) web?.requestFocus()
                    true
                } else false
            }
            toolbar.addView(this, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(8) })
        }
        closeButton = action("Close") { requestClose() }
        action("Reload") { load() }
        chooseSource?.let { source -> action("Sources") { reportProgress(force = true); source() } }
        toolbar.addView(TextView(context).apply {
            text = "Vidy · ${target.title}"; setTextColor(Color.LTGRAY); textSize = 13f
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(toolbar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        browser?.setOnKeyListener { _, key, event ->
            when (key) {
                KeyEvent.KEYCODE_BACK -> { if (event.action == KeyEvent.ACTION_UP && !closeFullscreen()) requestClose(); true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU -> {
                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) closeButton.requestFocus()
                    true
                }
                else -> false
            }
        }
        if (browser == null) failure("An Android WebView is required for embedded playback. Choose another source.")
    }

    private fun dp(value: Int) = (value * root.resources.displayMetrics.density).toInt()

    fun activate() {
        if (disposed || activated) return
        activated = true
        if (visible) { web?.onResume(); load() }
    }

    fun foreground(active: Boolean) {
        if (disposed || active == visible) return
        visible = active
        root.keepScreenOn = active
        if (!activated) return
        if (active) { web?.onResume(); load() }
        else {
            reportProgress(force = true)
            main.removeCallbacks(startupTimeout); closeFullscreen()
            // WebView.onPause alone does not guarantee HTML video stops. Unload the frame.
            nonce = ""; web?.stopLoading(); web?.loadUrl("about:blank"); web?.onPause()
        }
    }

    private fun load() {
        if (disposed || !visible) return
        val player = web ?: return
        val url = vidyEmbeddedUrl(target, positionMillis, wantsPlayback)
            ?: run { failure("A matching movie or episode identity is required for this source."); return }
        closeFullscreen(); nonce = UUID.randomUUID().toString()
        status.text = "Loading embedded player…"; status.visibility = View.VISIBLE; loading.visibility = View.VISIBLE
        main.removeCallbacks(startupTimeout); main.postDelayed(startupTimeout, 45_000L)
        player.loadDataWithBaseURL("$VIDY_HOST_ORIGIN/", vidyHostHtml(url, nonce), "text/html", "utf-8", null)
        player.requestFocus()
    }

    private fun receive(event: VidyPlaybackEvent) {
        if (disposed || !visible || nonce.isBlank()) return
        positionMillis = event.positionMillis
        durationMillis = event.durationMillis
        when (event.event) {
            "play" -> { wantsPlayback = true; playbackIntentChanged(true) }
            "pause" -> { wantsPlayback = false; playbackIntentChanged(false) }
            "ended" -> wantsPlayback = false
        }
        main.removeCallbacks(startupTimeout); status.visibility = View.GONE; loading.visibility = View.GONE
        reportProgress(force = event.event != "timeupdate")
        if (event.event == "ended" && !completionSent) { completionSent = true; completed() }
    }

    private fun reportProgress(force: Boolean = false) {
        if (durationMillis <= 0 || completionSent) return
        val timestamp = SystemClock.elapsedRealtime()
        if (force || lastReportedPosition < 0 || timestamp - lastReportAt >= 5_000L ||
            kotlin.math.abs(positionMillis - lastReportedPosition) >= 10_000L) {
            lastReportAt = timestamp; lastReportedPosition = positionMillis
            progress(positionMillis, durationMillis)
        }
    }

    private fun failure(message: String) {
        main.removeCallbacks(startupTimeout); status.text = message; status.visibility = View.VISIBLE; loading.visibility = View.GONE
    }

    fun closeFullscreen(): Boolean {
        val view = customView ?: return false
        customView = null; root.removeView(view)
        customCallback?.onCustomViewHidden(); customCallback = null
        return true
    }

    fun requestClose() {
        if (disposed) return
        reportProgress(force = true)
        close()
    }

    fun release() {
        if (disposed) return
        reportProgress(force = true)
        disposed = true; visible = false; nonce = ""; root.keepScreenOn = false
        main.removeCallbacksAndMessages(null); closeFullscreen()
        web?.let { player ->
            player.stopLoading(); player.loadUrl("about:blank"); player.onPause()
            player.webChromeClient = null; player.webViewClient = WebViewClient()
            (player.parent as? ViewGroup)?.removeView(player); player.destroy()
        }
        web = null
    }
}

/** The third-party frame never receives a native JavaScript interface or the host's relay nonce. */
internal fun vidyHostHtml(url: String, nonce: String): String {
    require(vidyAllowedEmbedNavigation(url))
    require(nonce.matches(Regex("[A-Za-z0-9-]{16,64}")))
    val escaped = url.replace("&", "&amp;").replace("\"", "&quot;")
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; frame-src https://vidy.st https://www.vidy.st; style-src 'unsafe-inline'; script-src 'nonce-$nonce'">
        <style>html,body,iframe{margin:0;padding:0;width:100%;height:100%;border:0;background:#000;overflow:hidden}</style></head>
        <body><iframe id="player" src="$escaped" allowfullscreen allow="encrypted-media; autoplay *; fullscreen *"
        sandbox="allow-scripts allow-same-origin allow-forms allow-presentation" referrerpolicy="strict-origin-when-cross-origin"></iframe>
        <script nonce="$nonce">(function(){
        const frame=document.getElementById('player');
        window.addEventListener('message',function(message){
          if(message.source!==frame.contentWindow||!['https://vidy.st','https://www.vidy.st'].includes(message.origin))return;
          if(typeof message.data==='string'&&message.data.length>8192)return;
          try{let data=typeof message.data==='string'?JSON.parse(message.data):message.data;
            if(!data||typeof data!=='object')return;
            if(data.type==='PLAYER_EVENT'&&data.data&&typeof data.data==='object')data=data.data;
            if(!['play','pause','timeupdate','ended'].includes(data.event))return;
            const p=Number(data.currentTime),d=Number(data.duration);
            if(!Number.isFinite(p)||!Number.isFinite(d)||p<0||d<=0||d>604800||p>d+2)return;
            const event=new URLSearchParams({nonce:'$nonce',event:data.event,position:String(p),duration:String(d)});
            window.location.replace('$VIDY_HOST_ORIGIN/__vidy_event?'+event.toString());
          }catch(ignored){}
        });})();</script></body></html>""".trimIndent()
}
