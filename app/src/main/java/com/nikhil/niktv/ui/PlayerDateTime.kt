package com.nikhil.niktv.ui

import android.text.format.DateFormat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local device date/time, visible only alongside the playback controls. */
@Composable
internal fun PlayerDateTime(compact: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0] ?: Locale.getDefault()
    val now by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            // Refresh settings/time-zone changes as well as minute boundaries.
            delay(1_000L)
        }
    }
    val date = Date(now)
    val datePattern = DateFormat.getBestDateTimePattern(locale, "EEE d MMM y")
    val dateText = SimpleDateFormat(datePattern, locale).format(date)
    val timeText = DateFormat.getTimeFormat(context).format(date)
    Text(
        text = "$dateText  •  $timeText",
        modifier = modifier,
        color = Color.White.copy(alpha = 0.85f),
        style = if (compact) MaterialTheme.typography.labelSmall
            else MaterialTheme.typography.labelLarge
    )
}
