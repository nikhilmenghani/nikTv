package com.nikhil.niktv.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import com.nikhil.niktv.data.ParentalControls

/** Exercises the existing dialog and local preferences in the isolated test package. */
class ParentalRegressionActivity : ComponentActivity() {
    companion object { @Volatile var unlocked = false; @Volatile var cancelled = false }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        unlocked = false
        cancelled = false
        ParentalControls.setPin(this, "7001")
        val pin = mutableStateOf("")
        val error = mutableStateOf<String?>(null)
        val allowed = mutableStateOf(false)
        setContent {
            MaterialTheme {
                if (allowed.value) Text("Fixture unlocked")
                else ParentalPinDialog(
                    title = "Fixture parental controls", pin = pin.value,
                    onPin = { pin.value = it; error.value = null }, error = error.value,
                    dismiss = { cancelled = true; finish() },
                    confirm = {
                        if (ParentalControls.accepts(this, pin.value)) { unlocked = true; allowed.value = true }
                        else error.value = "Incorrect PIN. Try again."
                    }
                )
            }
        }
    }
}
