package com.nikhil.niktv.ui

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
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
import androidx.compose.ui.focus.FocusDirection
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
import kotlinx.coroutines.channels.Channel
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
    val virtualInputCommands = remember {
        Channel<Pair<Int, FocusDirection?>>(Channel.UNLIMITED)
    }
    var x by rememberSaveable { mutableFloatStateOf(0f) }
    var y by rememberSaveable { mutableFloatStateOf(0f) }
    /*
     * VIRTUAL_DPAD_ACTIVITY_DISPATCH_V40
     *
     * A hardware remote enters through Activity.dispatchKeyEvent(), which
     * allows NikTV's explicit onPreviewKeyEvent routes, focusProperties and
     * Compose's normal focus search to participate in the same event path.
     *
     * Dispatching directly to the Activity content child can report a key as
     * handled inside one focus island without completing app-wide traversal.
     */
    fun send(keyCode: Int): Boolean {
        val hostActivity = activity ?: return false
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
        val handled =
            hostActivity.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN))
        hostActivity.dispatchKeyEvent(event(KeyEvent.ACTION_UP))
        return handled
    }

    /*
     * VIRTUAL_DPAD_ORDERED_TOUCH_BRIDGE_V41
     *
     * Pointer taps put Android/Compose into touch mode while the gesture is
     * still being delivered. Process virtual remote input on the next frame,
     * after the tap has completed, so Keyboard mode and focus traversal are
     * stable. A Channel preserves one command per tap and their exact order.
     */
    LaunchedEffect(virtualInputCommands) {
        for ((keyCode, direction) in virtualInputCommands) {
            // Finish the pointer/tap frame before switching input modes.
            withFrameNanos { }
            inputModeManager.requestInputMode(InputMode.Keyboard)

            if (send(keyCode)) {
                continue
            }

            if (direction != null && focusManager.moveFocus(direction)) {
                continue
            }

            /*
             * VIRTUAL_DPAD_NO_HIDDEN_FOCUS_TARGET_V41
             *
             * Never bootstrap through an invisible focusable node inside the
             * D-pad overlay. Such a node participates in spatial focus search
             * and can hijack Right/Down navigation across the entire app.
             *
             * If a screen has no current focus owner yet, enter its natural
             * traversal order directly from the Compose root. At a genuine
             * directional dead-end this also provides a linear escape path
             * instead of trapping the virtual remote in a small focus island.
             */
            if (direction != null) {
                val escapeDirection =
                    when (direction) {
                        FocusDirection.Left,
                        FocusDirection.Up -> FocusDirection.Previous

                        else -> FocusDirection.Next
                    }
                focusManager.moveFocus(escapeDirection)
            }
        }
    }

    fun navigate(
        keyCode: Int,
        direction: FocusDirection
    ) {
        virtualInputCommands.trySend(keyCode to direction)
    }

    fun select() {
        virtualInputCommands.trySend(
            KeyEvent.KEYCODE_DPAD_CENTER to null
        )
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
            DpadKey(Icons.Default.KeyboardArrowUp, "Up") {
                navigate(
                    KeyEvent.KEYCODE_DPAD_UP,
                    FocusDirection.Up
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DpadKey(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Left") {
                    navigate(
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        FocusDirection.Left
                    )
                }
                DpadKey(null, "Select") {
                    select()
                }
                DpadKey(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Right") {
                    navigate(
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        FocusDirection.Right
                    )
                }
            }
            DpadKey(Icons.Default.KeyboardArrowDown, "Down") {
                navigate(
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    FocusDirection.Down
                )
            }
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
