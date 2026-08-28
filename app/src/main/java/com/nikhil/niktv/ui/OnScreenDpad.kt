package com.nikhil.niktv.ui

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

internal object OnScreenDpadPreferences {
    private const val FILE = "on_screen_dpad"
    private const val ENABLED = "enabled"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun enabled(context: Context) = prefs(context).getBoolean(ENABLED, false)
    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(ENABLED, enabled).apply()
    fun sharedPreferences(context: Context) = prefs(context)
}

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
    val focusManager = LocalFocusManager.current
    val inputModeManager = LocalInputModeManager.current
    val bootstrapFocus = remember { FocusRequester() }
    var x by rememberSaveable { mutableFloatStateOf(0f) }
    var y by rememberSaveable { mutableFloatStateOf(0f) }
    fun send(keyCode: Int): Boolean {
        val hostActivity = activity ?: return false
        val content = hostActivity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val target = content?.getChildAt(0) ?: hostActivity.window.decorView
        val pressedAt = SystemClock.uptimeMillis()
        fun event(action: Int) = KeyEvent(
            pressedAt,
            SystemClock.uptimeMillis(),
            action,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_VIRTUAL_HARD_KEY,
            InputDevice.SOURCE_DPAD
        )
        val handled = target.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN))
        target.dispatchKeyEvent(event(KeyEvent.ACTION_UP))
        return handled
    }
    fun navigate(keyCode: Int) {
        inputModeManager.requestInputMode(InputMode.Keyboard)
        if (!send(keyCode)) {
            // Touch-only screens may not have established a Compose focus
            // owner yet. Bootstrap once, then enter the screen's normal
            // traversal order. Later presses use spatial DPAD navigation.
            bootstrapFocus.requestFocus()
            focusManager.moveFocus(FocusDirection.Next)
        }
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
            Box(
                Modifier
                    .size(1.dp)
                    .alpha(0f)
                    .focusRequester(bootstrapFocus)
                    .focusable()
            )
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
            DpadKey(Icons.Default.KeyboardArrowUp, "Up") { navigate(KeyEvent.KEYCODE_DPAD_UP) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DpadKey(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left") { navigate(KeyEvent.KEYCODE_DPAD_LEFT) }
                DpadKey(null, "Select") {
                    inputModeManager.requestInputMode(InputMode.Keyboard)
                    send(KeyEvent.KEYCODE_DPAD_CENTER)
                }
                DpadKey(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right") { navigate(KeyEvent.KEYCODE_DPAD_RIGHT) }
            }
            DpadKey(Icons.Default.KeyboardArrowDown, "Down") { navigate(KeyEvent.KEYCODE_DPAD_DOWN) }
            Row(
                modifier = Modifier
                    .height(46.dp)
                    .padding(horizontal = 12.dp)
                    .pointerInput(activity) {
                        detectTapGestures {
                            (activity as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
                        }
                    }
                    .semantics {
                        contentDescription = "Back"
                        onClick {
                            (activity as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
                            true
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Back")
            }
        }
    }
}

@Composable
private fun DpadKey(icon: androidx.compose.ui.graphics.vector.ImageVector?, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .pointerInput(onClick) { detectTapGestures { onClick() } }
            .semantics {
                contentDescription = label
                onClick {
                    onClick()
                    true
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) Icon(icon, label, Modifier.size(30.dp), tint = Color.White)
        else Surface(Modifier.size(22.dp), CircleShape, color = Color(0xFFE50914)) { }
    }
}
