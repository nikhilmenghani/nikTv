package com.nikhil.niktv.sports

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import com.nikhil.niktv.ui.NikColors

class SportsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme(colorScheme = NikColors) { SportsScreen(onClose = ::finish) } }
    }
}

internal fun Context.openSports() { startActivity(Intent(this, SportsActivity::class.java)) }
