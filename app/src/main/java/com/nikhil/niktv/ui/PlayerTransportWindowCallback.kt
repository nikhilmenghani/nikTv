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
