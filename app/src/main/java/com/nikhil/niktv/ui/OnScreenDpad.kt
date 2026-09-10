package com.nikhil.niktv.ui

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import android.view.ViewConfiguration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import kotlin.math.roundToInt

internal object OnScreenDpadPreferences {
    private const val FILE = "on_screen_dpad"
    private const val ENABLED = "enabled"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, false)
    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(ENABLED, enabled).apply()
    fun sharedPreferences(context: Context) = prefs(context)
}

/*
 * REMOTE_NAVIGATION_MODE_V1
 *
 * Layout remains device-specific. A phone using the diagnostic on-screen
 * D-pad should, however, exercise the same focus presentation and remote
 * navigation policies as a physical D-pad device.
 */
internal fun Context.usesRemoteNavigation(configuration: Configuration): Boolean =
    packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION ||
        !packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) ||
        OnScreenDpadPreferences.enabled(this)

@Composable
internal fun rememberOnScreenDpadEnabled(): State<Boolean> {
    val context = LocalContext.current
    val enabled = remember { mutableStateOf(OnScreenDpadPreferences.enabled(context)) }
    DisposableEffect(context) {
        val prefs = OnScreenDpadPreferences.sharedPreferences(context)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "enabled") enabled.value = OnScreenDpadPreferences.enabled(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return enabled
}

@Composable
internal fun MovableOnScreenDpad(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var x by rememberSaveable { mutableFloatStateOf(0f) }
    var y by rememberSaveable { mutableFloatStateOf(0f) }
    // Dispatch the event exactly as a hardware remote would. Requesting focus
    // on the Android Compose host before every key clears Compose's focused
    // child, which made each virtual direction restart from a root/default item.
    val send: (KeyEvent) -> Unit = send@ { event ->
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                (activity as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
            }
            return@send
        }
        activity?.dispatchKeyEvent(event)
    }
    Surface(
        modifier = modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) },
        shape = RoundedCornerShape(24.dp),
        color = Color(0xE61A1C21),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .28f)),
        shadowElevation = 12.dp
    ) {
        Column(
            Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.DragHandle,
                    "Move on-screen D-pad",
                    Modifier
                        .width(72.dp)
                        .height(24.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = .08f))
                        .pointerInput(Unit) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                x += drag.x
                                y += drag.y
                            }
                        }
                        .padding(3.dp),
                    tint = Color.LightGray
                )
                IconButton(
                    onClick = { OnScreenDpadPreferences.setEnabled(context, false) },
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(Icons.Default.Close, "Close and disable on-screen D-pad", Modifier.size(18.dp), tint = Color.White)
                }
            }
            DpadKey(Icons.Default.KeyboardArrowUp, "Up", KeyEvent.KEYCODE_DPAD_UP, send)
            Row(verticalAlignment = Alignment.CenterVertically) {
                DpadKey(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left", KeyEvent.KEYCODE_DPAD_LEFT, send)
                DpadKey(null, "Select", KeyEvent.KEYCODE_DPAD_CENTER, send)
                DpadKey(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right", KeyEvent.KEYCODE_DPAD_RIGHT, send)
            }
            DpadKey(Icons.Default.KeyboardArrowDown, "Down", KeyEvent.KEYCODE_DPAD_DOWN, send)
            DpadKey(Icons.AutoMirrored.Filled.ArrowBack, "Back", KeyEvent.KEYCODE_BACK, send)
        }
    }
}

private fun virtualRemoteEvent(
    keyCode: Int,
    downTime: Long,
    action: Int,
    repeat: Int = 0,
    cancelled: Boolean = false
): KeyEvent = KeyEvent(
    downTime, SystemClock.uptimeMillis(), action, keyCode, repeat, 0,
    KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
    KeyEvent.FLAG_VIRTUAL_HARD_KEY or
        (if (repeat == 1) KeyEvent.FLAG_LONG_PRESS else 0) or
        (if (cancelled) KeyEvent.FLAG_CANCELED else 0),
    InputDevice.SOURCE_DPAD
)

@Composable
private fun DpadKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    label: String,
    keyCode: Int,
    send: (KeyEvent) -> Unit
) {
    val dispatch by rememberUpdatedState(send)
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(46.dp)
            .background(if (pressed) Color.White.copy(alpha = .18f) else Color.Transparent, CircleShape)
            // Stable key: focus changes/recomposition must not cancel a hold.
            .pointerInput(keyCode) {
                coroutineScope {
                    val repeatScope = this
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val downTime = SystemClock.uptimeMillis()
                        pressed = true
                        dispatch(virtualRemoteEvent(keyCode, downTime, KeyEvent.ACTION_DOWN))
                        val repeats = repeatScope.launch {
                            delay(ViewConfiguration.getLongPressTimeout().toLong())
                            var repeat = 1
                            while (true) {
                                dispatch(virtualRemoteEvent(keyCode, downTime, KeyEvent.ACTION_DOWN, repeat++))
                                delay(ViewConfiguration.getKeyRepeatDelay().toLong())
                            }
                        }
                        var cancelled = true
                        try {
                            val up = waitForUpOrCancellation(pass = PointerEventPass.Main)
                            up?.consume()
                            cancelled = up == null
                        } finally {
                            repeats.cancel()
                            pressed = false
                            dispatch(virtualRemoteEvent(keyCode, downTime, KeyEvent.ACTION_UP, cancelled = cancelled))
                        }
                    }
                }
            }
            .semantics {
                contentDescription = label
                onClick {
                    val downTime = SystemClock.uptimeMillis()
                    dispatch(virtualRemoteEvent(keyCode, downTime, KeyEvent.ACTION_DOWN))
                    dispatch(virtualRemoteEvent(keyCode, downTime, KeyEvent.ACTION_UP))
                    true
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(30.dp), tint = Color.White)
        else Surface(Modifier.size(22.dp), CircleShape, color = Color(0xFFE50914)) { }
    }
}
