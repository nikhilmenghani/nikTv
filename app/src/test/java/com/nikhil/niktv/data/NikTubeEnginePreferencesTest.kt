package com.nikhil.niktv.data

import android.app.Application
import android.content.pm.PackageManager
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NikTubeEnginePreferencesTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("niktube_engine", 0).edit().clear().commit()
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, false)
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, true)
    }

    @Test fun touchDevicesDefaultToFlow() {
        assertEquals(NikTubeEngine.FLOW, NikTubeEnginePreferences.resolved(context))
    }

    @Test fun televisionDefaultsToSmartTube() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        assertEquals(NikTubeEngine.SMARTTUBE, NikTubeEnginePreferences.resolved(context))
    }

    @Test fun tabletCanExplicitlyChooseSmartTubeAndKeepThatChoice() {
        NikTubeEnginePreferences.set(context, NikTubeEngine.SMARTTUBE)
        assertEquals(NikTubeEngine.SMARTTUBE, NikTubeEnginePreferences.selected(context))
        assertEquals(NikTubeEngine.SMARTTUBE, NikTubeEnginePreferences.resolved(context))
    }

    @Test fun unknownStoredChoiceFallsBackToAutomatic() {
        context.getSharedPreferences("niktube_engine", 0).edit().putString("engine", "REMOVED_ENGINE").commit()
        assertEquals(NikTubeEngine.AUTO, NikTubeEnginePreferences.selected(context))
        assertEquals(NikTubeEngine.FLOW, NikTubeEnginePreferences.resolved(context))
    }
}
