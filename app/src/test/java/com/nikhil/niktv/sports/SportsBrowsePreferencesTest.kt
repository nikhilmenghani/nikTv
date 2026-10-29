package com.nikhil.niktv.sports

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class SportsBrowsePreferencesTest {
    @Test fun liveAndCricketSurviveReopeningAndCanResetIndependently() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("sports_browse", 0).edit().clear().commit()
        val first = SportsBrowsePreferences(context)
        assertEquals(SportsBrowseDefaults(), first.read())
        first.setStatus("Live"); first.setSport("Cricket")
        val reopened = SportsBrowsePreferences(context)
        assertEquals(SportsBrowseDefaults("Cricket", "Live"), reopened.read())
        reopened.setStatus("Now & next")
        assertEquals(SportsBrowseDefaults("Cricket", "Now & next"), first.read())
        reopened.setSport("All sports")
        assertEquals(SportsBrowseDefaults(), first.read())
    }

    @Test fun highlightVersionAndRegionSurviveReopeningIndependently() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("cricket_video_region", 0).edit().clear().commit()
        val first = CricketBrowsePreferences(context)
        first.setVersion("6.16.04"); first.setRegion("IN")
        assertEquals(CricketBrowseDefaults("6.16.04", "IN"), CricketBrowsePreferences(context).read())
        first.setVersion("16.11.01")
        assertEquals(CricketBrowseDefaults("16.11.01", "IN"), CricketBrowsePreferences(context).read())
        context.getSharedPreferences("cricket_video_region", 0).edit()
            .putString("app_version", "invalid").putString("region", "invalid").commit()
        assertEquals(CricketBrowseDefaults(), CricketBrowsePreferences(context).read())
    }
}
