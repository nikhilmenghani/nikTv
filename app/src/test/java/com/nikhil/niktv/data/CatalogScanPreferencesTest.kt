package com.nikhil.niktv.data

import android.app.Application
import com.nikhil.niktv.model.CatalogType
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

    @Test fun activeTypeAndRefreshStartAreScopedByProfileAndMediaType() {
        val context = RuntimeEnvironment.getApplication()
        val a = CatalogScanPreferences.id(PortalProfile("Scope A", "https://scope-a.test", "00:11:22:33:44:66"))
        val b = CatalogScanPreferences.id(PortalProfile("Scope B", "https://scope-b.test", "00:11:22:33:44:77"))

        CatalogScanPreferences.activeType(context, a, CatalogType.MOVIES.name)
        CatalogScanPreferences.activeType(context, b, CatalogType.SERIES.name)
        CatalogScanPreferences.refreshStartedAt(context, a, CatalogType.MOVIES.name, 100L)
        CatalogScanPreferences.refreshStartedAt(context, a, CatalogType.SERIES.name, 200L)
        CatalogScanPreferences.refreshStartedAt(context, b, CatalogType.MOVIES.name, 300L)

        assertEquals(CatalogType.MOVIES.name, CatalogScanPreferences.activeType(context, a))
        assertEquals(CatalogType.SERIES.name, CatalogScanPreferences.activeType(context, b))
        assertEquals(100L, CatalogScanPreferences.refreshStartedAt(context, a, CatalogType.MOVIES.name))
        assertEquals(200L, CatalogScanPreferences.refreshStartedAt(context, a, CatalogType.SERIES.name))
        assertEquals(300L, CatalogScanPreferences.refreshStartedAt(context, b, CatalogType.MOVIES.name))

        CatalogScanPreferences.activeType(context, a, null)
        CatalogScanPreferences.refreshStartedAt(context, a, CatalogType.MOVIES.name, 0L)

        assertNull(CatalogScanPreferences.activeType(context, a))
        assertEquals(CatalogType.SERIES.name, CatalogScanPreferences.activeType(context, b))
        assertEquals(0L, CatalogScanPreferences.refreshStartedAt(context, a, CatalogType.MOVIES.name))
        assertEquals(200L, CatalogScanPreferences.refreshStartedAt(context, a, CatalogType.SERIES.name))
        assertEquals(300L, CatalogScanPreferences.refreshStartedAt(context, b, CatalogType.MOVIES.name))
    }
}
