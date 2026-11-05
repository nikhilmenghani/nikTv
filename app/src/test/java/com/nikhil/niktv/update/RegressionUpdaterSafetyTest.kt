package com.nikhil.niktv.update

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import com.nikhil.niktv.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class RegressionUpdaterSafetyTest {
    @Test fun isolatedRegressionCannotCheckDownloadInstallOrCleanProductionApks() = runBlocking {
        assumeTrue(BuildConfig.DEBUG && BuildConfig.APPLICATION_ID == "com.nikhil.niktv.regression")
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getPackageName() = BuildConfig.APPLICATION_ID
            override fun getApplicationContext(): Context = this
        }
        AppUpdates.initialize(context)
        AppUpdates.setUpdateEnforcementEnabled(true)
        AppUpdates.setStartupUpdateCheckEnabled(true)
        assertFalse(AppUpdates.updateEnforcementEnabled.value)
        assertFalse(AppUpdates.startupUpdateCheckEnabled.value)
        assertNull(AppUpdates.check())
        val update = UpdateInfo("99.0.0", "https://github.com/nikhilmenghani/nikTv/releases/download/dev-v99.0.0/NikTV-dev-v99.0.0-universal.apk")
        assertFalse(AppUpdates.canStartDownload(update))
        assertEquals(DownloadedApkCleanup(0, 0L), AppUpdates.obsoleteDownloadedApks(context))
        assertEquals(DownloadedApkCleanup(0, 0L), AppUpdates.deleteObsoleteDownloadedApks(context))
        try { AppUpdates.download(context, update); fail("Regression downloads must be rejected") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("disabled")) }
        try { AppUpdates.install(context); fail("Regression installer handoff must be rejected") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("disabled")) }
        Unit
    }
}
