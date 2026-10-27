package com.nikhil.niktv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Explicit foreground colours also work when hosted outside the main app Surface. */
@Composable
internal fun PlayerLevelFeedback(brightness: Boolean, level: Float, compact: Boolean, modifier: Modifier = Modifier) {
    val value = level.coerceIn(0f, 1f)
    Surface(modifier, shape = RoundedCornerShape(10.dp), color = Color(0xF21B1F27), contentColor = Color.White) {
        Column(Modifier.padding(horizontal = if (compact) 10.dp else 18.dp, vertical = if (compact) 12.dp else 16.dp)
            .width(if (compact) 88.dp else 136.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (brightness) Icons.Default.Brightness6 else Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color.White)
            Text(if (brightness) "Brightness" else "Volume", color = Color.White, style = MaterialTheme.typography.labelLarge)
            Box(Modifier.height(120.dp).width(14.dp).clip(RoundedCornerShape(999.dp))
                .background(Color.White.copy(alpha = .18f)), contentAlignment = Alignment.BottomCenter) {
                Box(Modifier.fillMaxWidth().fillMaxHeight(value).background(MaterialTheme.colorScheme.primary))
            }
            Text("${(value * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}
