package com.nikhil.niktv

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nikhil.niktv.ui.PlaybackSeekTarget

/** Full-screen native host with remote controls driven by YouTube's public player API. */
class TrailerPlayerActivity : ComponentActivity() {
    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val seekTarget = PlaybackSeekTarget()
    private lateinit var root: FrameLayout
    private lateinit var header: TextView
    private lateinit var footer: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: SeekBar
    private lateinit var times: TextView
    private lateinit var play: Button
    private lateinit var rewind: Button
    private lateinit var forward: Button
    private lateinit var external: Button
    private var ready = false
    private var failed = false
    private var foreground = false
    private var destroyed = false
    private var controlsVisible = false
    private var dragging = false
    private var playbackRequested = true
    private var currentState = -1
    private var positionMs = 0L
    private var durationMs = 0L
    private var revealKey: Int? = null
    private val startupTimeout = Runnable {
        if (!ready && !failed && foreground && !destroyed) fail("YouTube is taking too long to load. Choose Open with to play externally.")
    }
    private val hideControls = Runnable { if (ready && foreground && !dragging) hideChrome() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra("video_key").orEmpty()
        if (!key.matches(Regex("[A-Za-z0-9_-]{11}"))) { finish(); return }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                android.util.Log.i("NikTvTrailer", "Back controlsVisible=$controlsVisible")
                if (controlsVisible) hideChrome() else finish()
            }
        })
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterFullscreen()
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusableInTouchMode = true
        }
        header = TextView(this).apply {
            text = intent.getStringExtra("title").orEmpty()
            setTextColor(Color.WHITE); textSize = 18f; maxLines = 2
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.END
            setPadding(dp(16), dp(12), dp(16), dp(8)); visibility = View.GONE
        }
        header.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xB3000000.toInt(), Color.TRANSPARENT))
        status = TextView(this).apply {
            text = "Loading trailer..."; setTextColor(Color.LTGRAY)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        status.gravity = Gravity.CENTER
        status.setBackgroundColor(0xCC181818.toInt())
        val player = runCatching { WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = false
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            addJavascriptInterface(PlayerBridge(), "NikTvTrailer")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                @androidx.annotation.RequiresApi(26)
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    if (webView === view) {
                        webView = null
                        (view.parent as? ViewGroup)?.removeView(view)
                        view.destroy()
                        fail("YouTube stopped unexpectedly. Choose Open with to play externally.")
                    }
                    return true
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) fail("Could not load YouTube. Open this trailer in another app.")
                }
            }
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) showChrome(focusPlay = false)
                false
            }
        } }.getOrNull()
        webView = player
        root.addView(player ?: View(this), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { setMargins(dp(24), 0, dp(24), 0) })
        footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24)); visibility = View.GONE
            isClickable = true
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xE6000000.toInt(), 0x55000000, Color.TRANSPARENT))
        }
        times = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12f }
        footer.addView(times)
        progress = SeekBar(this).apply {
            max = 1; isEnabled = false; isFocusableInTouchMode = true
            progressTintList = ColorStateList.valueOf(0xFFE50914.toInt())
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnKeyListener { _, code, event ->
                when (code) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (event.action == KeyEvent.ACTION_DOWN) skip(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -10_000 else 10_000)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (event.action == KeyEvent.ACTION_DOWN) play.requestFocus()
                        true
                    }
                    else -> false
                }
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { dragging = true; handler.removeCallbacks(hideControls) }
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) times.text = "${time(value.toLong())} / ${time(durationMs)}"
                }
                override fun onStopTrackingTouch(bar: SeekBar) { dragging = false; seek(bar.progress.toLong()); scheduleHide() }
            })
        }
        footer.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        rewind = action("−10s", "Back 10 seconds") { skip(-10_000) }
        play = action("Pause", "Pause", primary = true) { togglePlayback() }
        forward = action("+10s", "Forward 10 seconds") { skip(10_000) }
        val close = action("Close", "Close trailer") { finish() }
        external = action("Open with", "Choose an external trailer player") {
            val video = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$key"))
            runCatching { startActivity(Intent.createChooser(video, "Play trailer externally")) }
                .onFailure {
                    status.text = "No app is available to open this trailer."
                    status.visibility = View.VISIBLE
                    showChrome()
                }
        }
        listOf(rewind, play, forward, close, external).forEach { button ->
            button.setOnKeyListener { _, code, event ->
                if ((code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) && progress.isEnabled) {
                    if (event.action == KeyEvent.ACTION_DOWN) progress.requestFocus()
                    true
                } else false
            }
            val compact = resources.configuration.screenWidthDp < 640
            val width = if (button === external) { if (compact) 84 else 112 }
                else if (button === play) { if (compact) 64 else 96 }
                else if (compact) 56 else 84
            button.minWidth = dp(width)
            button.minimumWidth = dp(width)
            row.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                setMargins(dp(3), 0, dp(3), 0)
            })
        }
        footer.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(footer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bottom = WindowInsetsCompat.toWindowInsetsCompat(insets, root)
                .getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
            footer.setPadding(dp(12), dp(12), dp(12), maxOf(dp(24), bottom + dp(8)))
            insets
        }
        setContentView(root)
        root.requestFocus()
        updateControls()
        val origin = "https://$packageName"
        if (player != null) {
            player.loadDataWithBaseURL("$origin/", trailerPlayerHtml(key, origin), "text/html", "UTF-8", null)
        } else fail("Embedded YouTube is unavailable on this device. Choose Open with to play externally.")
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun action(label: String, description: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
        text = label; contentDescription = description; isAllCaps = false
        setTextColor(Color.WHITE); textSize = if (resources.configuration.smallestScreenWidthDp < 600) 13f else 16f
        isFocusableInTouchMode = false; maxLines = 1
        fun background(focused: Boolean) = GradientDrawable().apply {
            setColor(if (primary) 0xFFE50914.toInt() else if (focused) 0xFF303A49.toInt() else 0xFF181818.toInt())
            cornerRadius = dp(12).toFloat()
            setStroke(dp(if (focused) 2 else 1), if (focused) 0xFFE7E9EF.toInt() else 0xFF555555.toInt())
        }
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), background(true))
            addState(intArrayOf(android.R.attr.state_pressed), background(true))
            addState(intArrayOf(), background(false))
        }
        setPadding(dp(4), dp(4), dp(4), dp(4))
        setOnClickListener { click(); scheduleHide() }
        setOnFocusChangeListener { _, focused -> if (focused) scheduleHide() }
    }

    private fun command(action: String, value: Double = 0.0) {
        if (!ready) return
        webView?.evaluateJavascript("window.niktvCommand&&window.niktvCommand('$action',$value);", null)
    }
    private fun togglePlayback() {
        if (!ready) return
        playbackRequested = !playbackRequested
        command(if (playbackRequested) "play" else "pause")
        updateControls()
    }
    private fun skip(delta: Long) {
        if (!ready || durationMs <= 0) return
        seek(seekTarget.target(positionMs, durationMs, delta, SystemClock.elapsedRealtime()))
    }
    private fun seek(target: Long) {
        val bounded = seekTarget.absolute(target, durationMs, SystemClock.elapsedRealtime())
        command("seek", bounded / 1000.0)
        positionMs = bounded
        updateControls()
        android.util.Log.i("NikTvTrailer", "Seek requestedMs=$bounded")
    }
    private fun time(ms: Long): String = "%d:%02d".format(ms / 60_000, (ms / 1000) % 60)
    private fun updateControls() {
        if (!::play.isInitialized) return
        play.text = if (playbackRequested) "Pause" else "Play"
        play.contentDescription = play.text
        play.isEnabled = ready; rewind.isEnabled = ready; forward.isEnabled = ready
        progress.isEnabled = ready && durationMs > 0
        progress.max = durationMs.coerceAtLeast(1).toInt()
        if (!dragging) { progress.progress = positionMs.toInt(); times.text = "${time(positionMs)} / ${time(durationMs)}" }
    }
    private fun scheduleHide() {
        handler.removeCallbacks(hideControls)
        if (controlsVisible && ready && foreground && !dragging) handler.postDelayed(hideControls, 3_000)
    }
    private fun showChrome(focusPlay: Boolean = true) {
        controlsVisible = true
        header.visibility = if (resources.configuration.screenHeightDp >= 360) View.VISIBLE else View.GONE
        footer.visibility = View.VISIBLE
        if (focusPlay) (if (ready) play else external).requestFocus()
        scheduleHide()
    }
    private fun hideChrome() {
        handler.removeCallbacks(hideControls)
        controlsVisible = false
        header.visibility = View.GONE; footer.visibility = View.GONE
        progress.clearFocus(); root.requestFocus()
    }
    private fun fail(message: String) {
        status.text = message; status.visibility = View.VISIBLE
        failed = true; handler.removeCallbacks(startupTimeout)
        ready = false; updateControls(); showChrome(); external.requestFocus()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (revealKey == code) {
            if (event.action == KeyEvent.ACTION_UP) revealKey = null
            return true
        }
        val navigation = code in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER)
        if (navigation && event.action == KeyEvent.ACTION_DOWN) {
            if (!controlsVisible) { revealKey = code; showChrome(); return true }
            scheduleHide()
        }
        if (code in setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                showChrome()
                when (code) {
                    KeyEvent.KEYCODE_MEDIA_REWIND -> skip(-10_000)
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> skip(10_000)
                    KeyEvent.KEYCODE_MEDIA_PLAY -> { playbackRequested = true; command("play"); updateControls() }
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> { playbackRequested = false; command("pause"); updateControls() }
                    else -> togglePlayback()
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    private inner class PlayerBridge {
        private fun update(block: () -> Unit) = runOnUiThread { if (!destroyed) block() }
        @JavascriptInterface fun ready() = update {
            ready = true; failed = false; handler.removeCallbacks(startupTimeout)
            status.visibility = View.GONE; updateControls()
            command(if (foreground) "play" else "pause")
            android.util.Log.i("NikTvTrailer", "YouTube API ready")
        }
        @JavascriptInterface fun state(value: Int) = update {
            if (value !in -1..5) return@update
            if (value == 1) playbackRequested = true else if (value == 2 || value == 0) playbackRequested = false
            if (currentState != value) {
                currentState = value; updateControls(); scheduleHide()
                android.util.Log.i("NikTvTrailer", "state=$value positionMs=$positionMs")
            }
        }
        @JavascriptInterface fun progress(seconds: Double, duration: Double) = update {
            if (!seconds.isFinite() || !duration.isFinite()) return@update
            positionMs = (seconds.coerceIn(0.0, 86_400.0) * 1000).toLong()
            durationMs = (duration.coerceIn(0.0, 86_400.0) * 1000).toLong()
            seekTarget.acknowledge(positionMs); updateControls()
        }
        @JavascriptInterface fun error(code: Int) = update {
            fail("YouTube could not play this trailer (error $code). Open it in another app.")
        }
    }
    override fun onPause() {
        foreground = false; command("pause"); webView?.onPause()
        handler.removeCallbacks(hideControls); handler.removeCallbacks(startupTimeout)
        super.onPause()
    }
    private fun enterFullscreen() {
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }
    override fun onResume() {
        super.onResume(); foreground = true; webView?.onResume()
        enterFullscreen(); scheduleHide()
        if (!ready && !failed) handler.postDelayed(startupTimeout, 15_000L)
    }
    override fun finish() {
        setResult(RESULT_OK, Intent().putExtra("remote_navigation", ::root.isInitialized && !root.isInTouchMode))
        super.finish()
    }
    override fun onDestroy() {
        destroyed = true; handler.removeCallbacksAndMessages(null)
        webView?.apply { stopLoading(); removeJavascriptInterface("NikTvTrailer"); (parent as? ViewGroup)?.removeView(this); destroy() }
        webView = null
        super.onDestroy()
    }
}

internal fun trailerPlayerHtml(key: String, origin: String): String = """
<!doctype html><html style="height:100%"><head>
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="strict-origin-when-cross-origin">
<style>html,body{margin:0;height:100%;background:black;overflow:hidden}#player{width:100%;height:100%;display:block}</style>
</head><body><div id="player"></div><script>
var player,ready=false;
function onYouTubeIframeAPIReady(){player=new YT.Player('player',{
width:'100%',height:'100%',videoId:'$key',
playerVars:{autoplay:0,controls:0,disablekb:1,fs:0,playsinline:1,rel:0,origin:'$origin'},
events:{onReady:function(){ready=true;NikTvTrailer.ready();},
onStateChange:function(e){NikTvTrailer.state(e.data);},onError:function(e){NikTvTrailer.error(e.data);}}});}
function niktvCommand(action,value){if(!ready)return;
if(action==='play')player.playVideo();else if(action==='pause')player.pauseVideo();
else if(action==='seek')player.seekTo(value,true);}
setInterval(function(){if(ready)NikTvTrailer.progress(player.getCurrentTime(),player.getDuration());},500);
</script><script src="https://www.youtube.com/iframe_api"></script></body></html>
""".trimIndent()
