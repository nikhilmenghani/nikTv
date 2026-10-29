package com.nikhil.niktv.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.RemoteCredentials
import com.nikhil.niktv.sports.SportsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Explicit live integration check; requires a locally provisioned debug read token. */
class CricfyRemoteConfigurationDeviceTest {
    @Test fun syncedMyenvKeysDecryptTheLiveCatalogue() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.nikhil.niktv.debug")
        assertTrue("GitHub connection must be configured", RemoteCredentials.configured(context))
        RemoteCredentials.refresh(context, force = true)
        for (index in 1..2) {
            assertTrue("Configured AES key must have a valid byte length",
                RemoteCredentials.get("NIKTV_CRICFY_AES_KEY_$index").toByteArray().size in setOf(16, 24, 32))
            assertEquals("Configured IV must be 16 bytes", 16,
                RemoteCredentials.get("NIKTV_CRICFY_AES_IV_$index").toByteArray().size)
        }
        assertTrue("Remote configuration must decrypt a nonempty live catalogue",
            SportsRepository().categories(refresh = true).isNotEmpty())
    }
}
