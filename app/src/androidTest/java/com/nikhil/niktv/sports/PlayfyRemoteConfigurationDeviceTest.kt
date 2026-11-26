package com.nikhil.niktv.sports

import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.RemoteCredentials
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in: proves the private repo alone supplies the decoder on a real device. */
class PlayfyRemoteConfigurationDeviceTest {
    @Test fun privateRepoConfigurationLoadsPlayfyWithoutLocalOverride(): Unit = runBlocking { withTimeout(120_000) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("validatePlayfyRemote") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.nikhil.niktv.debug")
        val account = context.getSharedPreferences("github_configuration_accounts", 0).getString("active", null)
        val preferences = context.getSharedPreferences("github_remote_credentials_account_$account", 0)
        assertFalse("No fixture or local decoder override", preferences.contains("local_sports_values"))
        RemoteCredentials.initialize(context)
        RemoteCredentials.refresh(context, force = true)
        assertTrue(RemoteCredentials.get("NIKTV_PLAYFY_AES_KEY").isNotBlank())
        assertTrue(RemoteCredentials.get("NIKTV_PLAYFY_AES_IV").isNotBlank())
        val repository = PlayfyRepository()
        val categories = repository.categories(refresh = true)
        assertTrue("Remote categories decode", categories.isNotEmpty())
        val events = repository.events(refresh = true)
        assertTrue("Remote events decode", events.isNotEmpty())
    } }
}
