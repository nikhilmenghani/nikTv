package com.nikhil.niktv.discovery

import android.app.Application
import com.nikhil.niktv.data.clearTemporaryCaches
import com.nikhil.niktv.data.directorySize
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class DiscoveryStorageTest {
    @Test fun discoveryMetadataUsesTheExistingTemporaryCacheClearAction() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val directory = File(app.cacheDir, "discovery_preview")
        DiscoveryCache(directory).write("public-feed", "metadata")
        val before = directorySize(directory)
        assertTrue(before > 0)
        assertTrue(clearTemporaryCaches(app) >= before)
        assertEquals(0L, directorySize(directory))
    }
}
