package com.nikhil.niktv.ui

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.LiveProgramme

/** Provider-free TV queue fixture; available only in the isolated regression APK. */
class PlayerQueueRegressionActivity : ComponentActivity() {
    companion object {
        @Volatile var selected = false
        @Volatile var selectedId: String? = null
        @Volatile var selectedIndex: Int? = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected = false
        selectedId = null
        selectedIndex = null
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        PlayerQueuePreferences.setLayout(this, PlayerQueueLayout.valueOf(intent.getStringExtra("layout") ?: "GRID"))
        setContent {
            val tv = Configuration(LocalConfiguration.current).apply {
                if (!intent.getBooleanExtra("touchRail", false)) {
                    uiMode = Configuration.UI_MODE_TYPE_TELEVISION
                    screenHeightDp = 540
                    screenWidthDp = 960
                }
            }
            CompositionLocalProvider(LocalConfiguration provides tv) {
                MaterialTheme {
                    PlayerQueueOverlay(
                        items = (0..99).map { MediaItem("$it", "Fixture channel $it", null, null,
                            liveProgramme = if (it % 2 == 0) LiveProgramme("Current programme $it", System.currentTimeMillis() - 60000, System.currentTimeMillis() + 60000) else null) },
                        playingId = intent.getStringExtra("playingId") ?: "0", onTogglePin = {},
                        pinnedIds = intent.getStringExtra("pinnedId")?.let { listOf(it) }.orEmpty(),
                        onDismiss = { finish() }, onSelect = { item, index -> selected = true; selectedId = item.id; selectedIndex = index }
                    )
                }
            }
        }
    }
}
