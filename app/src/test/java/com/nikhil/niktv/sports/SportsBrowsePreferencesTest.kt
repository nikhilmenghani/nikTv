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
}
