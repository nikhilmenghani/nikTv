package com.nikhil.niktv.sports

import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.ProfileStore
import com.nikhil.niktv.model.PlaybackEngine
import com.nikhil.niktv.ui.PlaybackDomain
import com.nikhil.niktv.ui.resolvePlayerEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SportsPlaybackPreferenceIsolationTest {
    @Test fun settingsAndAutoFallbackNeverCrossPlaybackDomains() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".regression"))
        val iptv = ProfileStore(context)
        val sports = SportsPlaybackPreferences(context)
        val oldIptvEngine = iptv.playbackEngine.first()
        val oldIptvTimeout = iptv.playerControlsTimeoutSeconds.first()
        val oldSports = sports.settings.first()
        val fallbacks = context.getSharedPreferences("player_engine_fallbacks", android.content.Context.MODE_PRIVATE)
        val key = "mtk:isolation-fixture"
        val hadKey = fallbacks.contains(key)
        val oldCount = fallbacks.getInt(key, 0)
        try {
            fallbacks.edit().putInt(key, 2).commit()
            assertEquals(PlaybackEngine.VLC, PlaybackEngine.AUTO.resolvePlayerEngine(context, "isolation-fixture", PlaybackDomain.IPTV))
            assertEquals(PlaybackEngine.MEDIA3, PlaybackEngine.AUTO.resolvePlayerEngine(context, "isolation-fixture", PlaybackDomain.SPORTS))
            sports.setPlaybackEngine(PlaybackEngine.FLOW)
            sports.setPlayerControlsTimeoutSeconds(11)
            assertEquals(oldIptvEngine, iptv.playbackEngine.first())
            assertEquals(oldIptvTimeout, iptv.playerControlsTimeoutSeconds.first())
            iptv.setPlaybackEngine(PlaybackEngine.VLC)
            iptv.setPlayerControlsTimeoutSeconds(7)
            assertEquals(SportsPlaybackSettings(11, PlaybackEngine.FLOW), sports.settings.first())
        } finally {
            sports.setPlaybackEngine(oldSports.engine)
            sports.setPlayerControlsTimeoutSeconds(oldSports.timeout)
            iptv.setPlaybackEngine(oldIptvEngine)
            iptv.setPlayerControlsTimeoutSeconds(oldIptvTimeout)
            if (hadKey) fallbacks.edit().putInt(key, oldCount).commit() else fallbacks.edit().remove(key).commit()
        }
    }
}
