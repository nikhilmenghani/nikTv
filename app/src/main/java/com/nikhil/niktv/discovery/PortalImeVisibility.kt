package com.nikhil.niktv.discovery

import android.view.ViewTreeObserver
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Read the editor's real window; retained/movable content can outlive an inset owner. */
@Composable
internal fun rememberPortalImeVisibility(): Boolean {
    val view = LocalView.current
    fun visible() = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    var shown by remember(view) { mutableStateOf(visible()) }
    DisposableEffect(view) {
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener { shown = visible(); true }
        observer.addOnPreDrawListener(listener)
        onDispose { if (observer.isAlive) observer.removeOnPreDrawListener(listener) }
    }
    return shown
}
