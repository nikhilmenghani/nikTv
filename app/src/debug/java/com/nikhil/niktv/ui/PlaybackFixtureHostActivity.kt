package com.nikhil.niktv.ui

/** Empty foreground host for direct engine tests: no second decoder or audio-focus owner. */
class PlaybackFixtureHostActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(state: android.os.Bundle?) {
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
    }
}
