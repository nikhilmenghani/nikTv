package com.nikhil.niktv.ui

import android.view.KeyEvent
import android.view.Window

/** Preserve the original Activity dispatch for every unhandled event. */
internal class PlayerTransportWindowCallback(
    private val original: Window.Callback,
    private val handle: (KeyEvent) -> Boolean
) : Window.Callback by original {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        handle(event) || original.dispatchKeyEvent(event)
}

/** null is unhandled; zero consumes releases/repeats without navigating twice. */
internal fun liveChannelTransportDirection(keyCode: Int, action: Int, repeatCount: Int): Int? {
    val direction = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> 1
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> -1
        else -> return null
    }
    return if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) direction else 0
}
