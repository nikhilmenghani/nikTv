package com.nikhil.niktv.sports

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.RemoteCredentials
import com.nikhil.niktv.ui.NikColors
import com.nikhil.niktv.ui.SportsFixtureHostActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in live check; provision configuration into the isolated regression package first. */
class CricbuzzDirectDeviceTest {
    @Test fun liveCanadianCatalogueUsesDirectSigningAndEncryptedConfiguration() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("cricbuzzLive") == "true")
        val context = ins.targetContext
        check(context.packageName.endsWith(".regression"))
        RemoteCredentials.initialize(context)
        assertFalse("One-time plaintext import must be consumed", context.filesDir.resolve("local_cricket_configuration_import").exists())
        val key = RemoteCredentials.get("NIKTV_CRICBUZZ_AUTH_KEY")
        assertTrue("Runtime signing key is configured", key.isNotBlank())
        val stored = context.getSharedPreferences("github_remote_credentials", 0).getString("local_cricket_values", "").orEmpty()
        assertTrue("Configuration must be encrypted at rest", stored.isNotBlank() && !stored.contains(key))
        val direct = CricbuzzDirectClient(CricketHighlightsHttpTransport())
        assertTrue(direct.configured)
        val videos = direct.featured()
        assertTrue("Live Canadian featured highlights", videos.isNotEmpty())
        assertTrue("Full and preview addresses remain distinct", videos.any { it.fullUrl.isNotBlank() && it.fullUrl != it.previewUrl })
        val activity = ins.startActivitySync(Intent(context, SportsFixtureHostActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as SportsFixtureHostActivity
        try {
            ins.runOnMainSync { activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            ins.runOnMainSync { activity.setContent { MaterialTheme(colorScheme = NikColors) {
                CricketHighlightsContent(CricketHighlightsState(videos = videos, configured = true), {}, {}, {}, {})
            } } }
            ins.waitForIdleSync()
            Thread.sleep(1500)
        } finally { activity.finish() }
    }
}
