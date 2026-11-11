package com.nikhil.niktv.ui

import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import com.nikhil.niktv.data.LanCommand
import com.nikhil.niktv.data.LanReply
import java.lang.ref.WeakReference
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Called on Main only, after the LAN server authenticates the paired sender. */
internal object LanUiRemote {
    private var root = WeakReference<View>(null)
    fun attach(view: View): View { root = WeakReference(view); return view }
    fun detach(view: View) { if (root.get() === view) root.clear() }
    fun execute(command: LanCommand): LanReply {
        val registered = root.get()?.takeIf { it.isAttachedToWindow }
            ?: return LanReply(false, "Open NikTV on the receiver.")
        val window = if (Build.VERSION.SDK_INT >= 29)
            android.view.inspector.WindowInspector.getGlobalWindowViews().lastOrNull { it.hasWindowFocus() }
                else registered.rootView.takeIf { it.hasWindowFocus() }
        window ?: return LanReply(false, "Bring NikTV to the foreground.")
        if (command.action == "navigate") {
            if (command.key == "back") {
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(window)
                if (insets?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true) {
                    val keyboard = window.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                    keyboard.hideSoftInputFromWindow(window.windowToken, 0)
                    return LanReply(true, "Keyboard dismissed")
                }
                window.findViewTreeOnBackPressedDispatcherOwner()?.let {
                    it.onBackPressedDispatcher.onBackPressed()
                    return LanReply(true, "Back")
                }
            }
            val code = when (command.key) {
                "up" -> KeyEvent.KEYCODE_DPAD_UP; "down" -> KeyEvent.KEYCODE_DPAD_DOWN
                "left" -> KeyEvent.KEYCODE_DPAD_LEFT; "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
                "ok" -> KeyEvent.KEYCODE_DPAD_CENTER; "back" -> KeyEvent.KEYCODE_BACK
                else -> return LanReply(false, "Invalid navigation key")
            }
            window.requestFocusFromTouch()
            val at = android.os.SystemClock.uptimeMillis()
            fun event(action: Int) = KeyEvent(at, android.os.SystemClock.uptimeMillis(), action, code, 0, 0,
                android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, android.view.InputDevice.SOURCE_DPAD)
            window.dispatchKeyEvent(event(KeyEvent.ACTION_DOWN))
            window.dispatchKeyEvent(event(KeyEvent.ACTION_UP))
            return LanReply(true, "Remote key sent")
        }
        val input = window.findFocus()?.onCreateInputConnection(EditorInfo())
            ?: return LanReply(false, "Select a text field on the receiver first.")
        val accepted = when (command.action) {
            "text" -> command.text?.takeIf { it.length <= 1024 }?.let { input.commitText(it, 1) } ?: false
            "clear_text" -> { input.beginBatchEdit(); try {
                input.commitText("", 1) && input.deleteSurroundingText(16384, 16384)
            } finally { input.endBatchEdit() } }
            else -> false
        }
        return LanReply(accepted, if (accepted) "Text updated" else "Select an editable text field first.")
    }
}

@Composable
internal fun LanUiRemoteControls(send: suspend (String, String?, String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun command(action: String, key: String? = null, value: String? = null) {
        if (sending && action != "navigate") return
        sending = true
        scope.launch { try {
            send(action, key, value); error = null
            if (action == "text") text = ""
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Command failed. Check the receiver and connection." }
        finally { sending = false } }
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Remote control", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(text, { text = it.take(1024) }, Modifier.fillMaxWidth(), label = { Text("Text for selected field") },
            singleLine = true, visualTransformation = PasswordVisualTransformation())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NikTvSecondaryActionButton({ command("clear_text") }, Modifier.weight(1f), enabled = !sending,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                Text("Clear receiver field", style = MaterialTheme.typography.labelMedium)
            }
            NikTvPrimaryActionButton({ command("text", value = text) }, Modifier.weight(1f), enabled = !sending && text.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                Text("Send text", style = MaterialTheme.typography.labelMedium)
            }
        }
        Text("Select a field on the receiver, then send text. Sent text is cleared from this phone.", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        NikTvSecondaryActionButton({ command("navigate", "up") }, Modifier.size(48.dp), contentPadding = PaddingValues(8.dp)) {
            Icon(Icons.Default.KeyboardArrowUp, "Up")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NikTvSecondaryActionButton({ command("navigate", "left") }, Modifier.size(48.dp), contentPadding = PaddingValues(8.dp)) {
                Icon(Icons.Default.KeyboardArrowLeft, "Left")
            }
            NikTvPrimaryActionButton({ command("navigate", "ok") }, Modifier.width(64.dp).height(48.dp)) { Text("OK") }
            NikTvSecondaryActionButton({ command("navigate", "right") }, Modifier.size(48.dp), contentPadding = PaddingValues(8.dp)) {
                Icon(Icons.Default.KeyboardArrowRight, "Right")
            }
        }
        NikTvSecondaryActionButton({ command("navigate", "down") }, Modifier.size(48.dp), contentPadding = PaddingValues(8.dp)) {
            Icon(Icons.Default.KeyboardArrowDown, "Down")
        }
        NikTvSecondaryActionButton({ command("navigate", "back") }) { Text("Back") }
    }
}
