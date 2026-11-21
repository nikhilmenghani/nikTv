package com.nikhil.niktv.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.R
import kotlinx.coroutines.delay

internal const val LaunchBrandingDurationMillis = 800
internal val LocalLaunchBrandingProgress = staticCompositionLocalOf<State<Float>?> { null }

@Composable
internal fun NikTvLaunchBranding() {
    val progress = LocalLaunchBrandingProgress.current
    var showProgress by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(2_500)
        showProgress = true
    }
    Box(Modifier.fillMaxSize().background(colorResource(R.color.launcher_background)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(288.dp).drawBehind {
            val glow = (progress?.value ?: 1f) * 0.22f
            drawRect(Brush.radialGradient(listOf(Color(0xFF3949CE).copy(alpha = glow), Color.Transparent)))
        }, contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.niktv_logo_foreground), "NikTV", Modifier.fillMaxSize().graphicsLayer {
                val scale = 1f + (progress?.value ?: 1f) * 0.14f
                scaleX = scale
                scaleY = scale
            })
        }
        if (showProgress) {
            CircularProgressIndicator(Modifier.offset(y = 84.dp).size(18.dp), strokeWidth = 2.dp)
        }
    }
}
