package com.nikhil.niktv.data

import android.app.Application
import com.nikhil.niktv.model.PortalProfile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class CatalogScanPreferencesTest {
    @Test fun scheduleAndResumeCursorAreProfileScopedAndOffByDefault() {
        val context = RuntimeEnvironment.getApplication()
        val a = CatalogScanPreferences.id(PortalProfile("A", "https://a.test", "00:11:22:33:44:55"))
        val b = CatalogScanPreferences.id(PortalProfile("B", "https://b.test", "00:11:22:33:44:55"))
        assertEquals(0, CatalogScanPreferences.hours(context, a))
        CatalogScanPreferences.hours(context, a, 24)
        CatalogScanPreferences.cursor(context, a, 2)
        assertEquals(24, CatalogScanPreferences.hours(context, a))
        assertEquals(2, CatalogScanPreferences.cursor(context, a))
        assertEquals(0, CatalogScanPreferences.hours(context, b))
        assertEquals(-1, CatalogScanPreferences.cursor(context, b))
    }
    @Test fun providerFallbackCoversEmptyOrIncompleteCatalogs() {
        assertTrue(needsProviderSearch(true, 100, -1))
        assertTrue(needsProviderSearch(false, 0, -1))
        assertTrue(needsProviderSearch(false, 100, 1))
        assertFalse(needsProviderSearch(false, 100, -1))
    }
}
