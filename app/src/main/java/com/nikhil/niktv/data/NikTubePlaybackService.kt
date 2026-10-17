package com.nikhil.niktv.data

import android.app.*
import android.content.*
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.nikhil.niktv.NikTubeActivity
import com.nikhil.niktv.R
import com.nikhil.niktv.model.PlayingMedia

/** A single playback owner across watch page, fullscreen, mini-player and PiP. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object NikTubeSession {
    var player by mutableStateOf<ExoPlayer?>(null); private set
    var video by mutableStateOf<NikTubeVideo?>(null); private set
    var failure by mutableStateOf<String?>(null); private set
    var playing by mutableStateOf(false); private set
    var backgroundAudio by mutableStateOf(false); private set
    private var app: Context? = null
    fun initialize(context: Context) {
        app = context.applicationContext
        backgroundAudio = context.getSharedPreferences("niktube_playback", 0).getBoolean("background", false)
    }
    fun start(context: Context, item: NikTubeVideo, media: PlayingMedia, captions: List<NikTubeCaption> = emptyList()) {
        val application = context.applicationContext
        initialize(application)
        val engine = player ?: ExoPlayer.Builder(application, DefaultRenderersFactory(application).setEnableDecoderFallback(true)).build().also { engine ->
            engine.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            engine.setHandleAudioBecomingNoisy(true)
            engine.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(value: Boolean) { playing = value; NikTubePlaybackService.instance?.refresh() }
                override fun onPlayerError(error: PlaybackException) { failure = "Could not play this video. Retry to request a fresh stream, or open it externally." }
            })
            player = engine
        }
        video = item; failure = null
        engine.trackSelectionParameters = engine.trackSelectionParameters.buildUpon().clearOverrides().build()
        val transport = DefaultHttpDataSource.Factory().setDefaultRequestProperties(media.streamRequestHeaders)
            .setKeepPostFor302Redirects(true).setConnectTimeoutMs(15_000).setReadTimeoutMs(15_000)
        val sources = DefaultMediaSourceFactory(androidx.media3.datasource.DefaultDataSource.Factory(application, transport))
        val metadata = MediaMetadata.Builder().setTitle(item.title).setArtist(item.channel).build()
        val source = when {
            media.nativeSabr != null -> com.newtube.sabr.SabrMediaSource(media.nativeSabr, transport,
                androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.getSingletonInstance(application))
            media.separateAudioUrl != null -> MergingMediaSource(
                sources.createMediaSource(MediaItem.Builder().setUri(media.url).setMediaMetadata(metadata).build()),
                sources.createMediaSource(MediaItem.fromUri(media.separateAudioUrl)))
            else -> sources.createMediaSource(MediaItem.Builder().setUri(media.url).setMediaMetadata(metadata).build())
        }
        val subtitleSources = captions.map { caption -> androidx.media3.exoplayer.source.SingleSampleMediaSource.Factory(transport)
            .setTreatLoadErrorsAsEndOfStream(true).createMediaSource(MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(caption.url))
                .setLanguage(caption.language).setLabel(caption.label).setMimeType(MimeTypes.TEXT_VTT).build(), C.TIME_UNSET) }
        engine.setMediaSource(if (subtitleSources.isEmpty()) source else MergingMediaSource(source, *subtitleSources.toTypedArray()))
        engine.seekTo(media.resumePositionMillis.coerceAtLeast(0)); engine.prepare(); engine.play()
        engine.repeatMode = Player.REPEAT_MODE_OFF
        android.util.Log.i("NikTubeSession", "Playback session ${System.identityHashCode(engine)} opened a video")
        ContextCompat.startForegroundService(application, Intent(application, NikTubePlaybackService::class.java))
    }
    fun toggle() { player?.let { if (it.playWhenReady) it.pause() else it.play() } }
    fun setBackground(enabled: Boolean) {
        backgroundAudio = enabled
        app?.getSharedPreferences("niktube_playback", 0)?.edit()?.putBoolean("background", enabled)?.apply()
    }
    fun close() {
        player?.release(); player = null; video = null; playing = false; failure = null
        app?.stopService(Intent(app, NikTubePlaybackService::class.java))
    }
}

class NikTubePlaybackService : Service() {
    companion object { internal var instance: NikTubePlaybackService? = null; private const val CHANNEL = "niktube-playback"; private const val ID = 280 }
    private lateinit var session: MediaSession
    override fun onCreate() {
        super.onCreate(); instance = this
        val manager = getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "NikTube playback", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "NikTube").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { NikTubeSession.player?.play() }
                override fun onPause() { NikTubeSession.player?.pause() }
                override fun onStop() { NikTubeSession.close() }
                override fun onSeekTo(position: Long) { NikTubeSession.player?.seekTo(position) }
            })
            isActive = true
        }
        refresh()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        when (intent?.action) { "toggle" -> NikTubeSession.toggle(); "close" -> NikTubeSession.close() }
        if (NikTubeSession.player == null) stopSelf() else refresh()
        return START_NOT_STICKY
    }
    internal fun refresh() {
        if (!::session.isInitialized) return
        session.setPlaybackState(PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SEEK_TO)
            .setState(if (NikTubeSession.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, NikTubeSession.player?.currentPosition ?: 0, 1f).build())
        fun action(value: String) = PendingIntent.getService(this, value.hashCode(), Intent(this, NikTubePlaybackService::class.java).setAction(value), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, NikTubeActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (android.os.Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        val notification = builder.setSmallIcon(R.drawable.niktv_logo_foreground).setContentTitle(NikTubeSession.video?.title ?: "NikTube")
            .setContentText(NikTubeSession.video?.channel).setContentIntent(open).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, if (NikTubeSession.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play), if (NikTubeSession.playing) "Pause" else "Play", action("toggle")).build())
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Close", action("close")).build())
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1)).build()
        startForeground(ID, notification)
    }
    override fun onTaskRemoved(rootIntent: Intent?) { if (!NikTubeSession.backgroundAudio) NikTubeSession.close() }
    override fun onDestroy() { instance = null; session.release(); if (NikTubeSession.player != null) NikTubeSession.close(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
