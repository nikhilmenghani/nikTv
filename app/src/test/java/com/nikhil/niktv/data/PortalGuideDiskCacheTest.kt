package com.nikhil.niktv.data

import android.app.Application
import com.nikhil.niktv.model.LiveProgramme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class PortalGuideDiskCacheTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun guide(key: String) = PortalGuideCache(key, 1_000L,
        mapOf("1" to listOf(LiveProgramme(key, 1_000L, 10_000L))))

    @Test fun profilesRoundTripSeparatelyAndOnlyTwoFilesAreRetained() = runBlocking {
        val disk = PortalGuideDiskCache(context)
        disk.write(guide("profile-a")); disk.write(guide("profile-b"))
        assertEquals("profile-a", disk.read("profile-a")!!.schedules.getValue("1").single().title)
        assertEquals("profile-b", disk.read("profile-b")!!.schedules.getValue("1").single().title)
        assertNull(disk.read("unknown-profile"))
        disk.write(guide("profile-c"))
        assertNotNull(disk.read("profile-c"))
        assertEquals(2, context.cacheDir.resolve("live-guide-v1").listFiles()!!.count { it.name.endsWith(".json") })
    }

    @Test fun corruptDiskDataIsRebuiltInsteadOfDisablingProgrammeLookup() = runBlocking {
        val disk = PortalGuideDiskCache(context)
        disk.write(guide("wio"))
        context.cacheDir.resolve("live-guide-v1").listFiles()!!.forEach { it.writeText("{broken") }
        val cache = SharedPortalGuideCache(this, now = { 2_000L }, read = disk::read, write = disk::write)
        var fetched = false
        assertNotNull(cache.get("wio") { fetched = true; guide("wio").schedules })
        assertTrue(fetched)
        assertNotNull(disk.read("wio"))
    }

    @Test fun fileNamesCannotExposeIdentityOrEscapeTheCacheFolder() = runBlocking {
        val disk = PortalGuideDiskCache(context)
        disk.write(guide("../private-profile"))
        assertNotNull(disk.read("../private-profile"))
        assertTrue(context.cacheDir.resolve("live-guide-v1").listFiles()!!.all { it.name.matches(Regex("[a-f0-9]{64}\\.json")) })
    }
}
