package com.nikhil.niktv.ui

import android.app.Application
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PlayerQueuePreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() { PlayerQueuePreferences.preferences(context).edit().clear().commit() }
    @Test fun existingInstallKeepsGrid() { assertEquals(PlayerQueueLayout.GRID, PlayerQueuePreferences.layout(context)) }
    @Test fun listChoiceSurvivesReadingPreferencesAgain() {
        PlayerQueuePreferences.setLayout(context, PlayerQueueLayout.LIST)
        assertEquals(PlayerQueueLayout.LIST, PlayerQueuePreferences.layout(context))
    }
    @Test fun unsupportedSavedChoiceFallsBackToGrid() {
        PlayerQueuePreferences.preferences(context).edit().putString("layout", "old-layout").commit()
        assertEquals(PlayerQueueLayout.GRID, PlayerQueuePreferences.layout(context))
    }
}
