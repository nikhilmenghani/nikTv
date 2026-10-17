package com.nikhil.niktv.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Main-thread owner; native commands are dispatched by the session, never by AudioManager. */
internal class VlcAudioFocus(
    context: Context,
    private val play: (Boolean) -> Unit,
    private val volume: (Float) -> Unit,
    private val intentChanged: (Boolean) -> Unit
) : AutoCloseable {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val intent = PlaybackAudioIntent()
    private var closed = false
    private var held = false
    val shouldPlay get() = !closed && held && intent.shouldPlay
    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        if (!closed) {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> intent.gain()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> intent.transientLoss(duck = true)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> intent.transientLoss()
                AudioManager.AUDIOFOCUS_LOSS -> { intent.permanentLoss(); held = false; intentChanged(false) }
            }
            volume(if (intent.ducked) .2f else 1f)
            play(shouldPlay)
        }
    }
    private val request = if (Build.VERSION.SDK_INT >= 26) AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setOnAudioFocusChangeListener(listener).build() else null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, value: Intent?) {
            if (value?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY && !closed) request(false)
        }
    }
    init { ContextCompat.registerReceiver(app, receiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED) }

    @Suppress("DEPRECATION")
    fun request(value: Boolean, apply: Boolean = true) {
        if (closed) return
        intent.userRequest(value)
        intentChanged(value)
        if (value && !held) {
            held = (if (Build.VERSION.SDK_INT >= 26) manager.requestAudioFocus(request!!) else
                manager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (held) intent.gain()
        } else if (!value) abandon()
        if (apply) play(shouldPlay)
    }
    @Suppress("DEPRECATION")
    private fun abandon() {
        if (held) {
            if (Build.VERSION.SDK_INT >= 26) manager.abandonAudioFocusRequest(request!!) else manager.abandonAudioFocus(listener)
        }
        held = false
    }
    override fun close() {
        closed = true
        abandon()
        app.unregisterReceiver(receiver)
    }
}
