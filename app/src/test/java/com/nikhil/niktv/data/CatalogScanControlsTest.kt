package com.nikhil.niktv.data

import android.app.Application
import com.nikhil.niktv.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class CatalogScanControlsTest {
    @After fun closeDatabase() {
        // Production uses one database per process; Robolectric resets SQLite between tests.
        val field = CatalogDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (field.get(null) as? CatalogDatabase)?.close()
        field.set(null, null)
    }
    private val context get() = RuntimeEnvironment.getApplication()
    private fun session() = PortalSession(PortalProfile("Test", "https://example.test", "00:11:22:33:44:55"), "", "", "", "", "")
    private val category = Category("1", "Movies", CatalogType.MOVIES)
    private fun item(id: String) = MediaItem(id, "Movie $id", null, "/media/$id")

    @Test fun pauseAfterResponseCommitsPageAndResumeStartsWithNextPage() = runBlocking {
        val session = session()
        val key = CatalogOperations.scan(CatalogScanPreferences.id(session.profile))
        val pages = mutableListOf<Int>()
        val pacing = mutableListOf<Long>()
        val scanner = SearchCatalogScanner(context, { _, _ -> listOf(category) }, { _, _, page ->
            pages += page
            if (page == 1) CatalogOperations.control(context, key, "Paused")
            PortalCatalogPage(listOf(item("$page")), page, page < 2)
        }, { pacing += it })
        try { scanner.scan(session, CatalogType.MOVIES, 0, refreshCompleted = false); fail("Must yield after page commit") }
        catch (held: CatalogOperationHeld) { assertEquals("Paused", held.state) }
        assertEquals(listOf(1), pages)
        assertEquals(1, CatalogRepository(context).browse(session.profile.cacheKey(), CatalogType.MOVIES)!!.pagesByCategory["1"])
        assertEquals("Stored", CatalogOperations.events(context, key).single().outcome)
        CatalogOperations.control(context, key, "Ready")
        scanner.scan(session, CatalogType.MOVIES, 0, refreshCompleted = false)
        assertEquals(listOf(1, 2), pages)
        assertTrue(pacing.all { it >= 2000 })
        assertEquals(setOf("1", "2"), CatalogRepository(context).search(session.profile.cacheKey(), CatalogType.MOVIES)!!.items.map { it.id }.toSet())
    }

    @Test fun providerFailureKeepsCursorAndSuccessfulRetryClearsFailure() = runBlocking {
        val session = session()
        val key = CatalogOperations.scan(CatalogScanPreferences.id(session.profile))
        var failPage = true
        val pages = mutableListOf<Int>()
        val scanner = SearchCatalogScanner(context, { _, _ -> listOf(category) }, { _, _, page ->
            pages += page
            if (page == 2 && failPage) throw java.io.IOException("URL containing secret must not appear in report")
            PortalCatalogPage(listOf(item("$page")), page, page < 2)
        }, {})
        assertEquals(1, scanner.scan(session, CatalogType.MOVIES, 0, refreshCompleted = false).failures)
        assertEquals(1, CatalogRepository(context).browse(session.profile.cacheKey(), CatalogType.MOVIES)!!.pagesByCategory["1"])
        assertEquals(1, CatalogOperations.events(context, key, true).size)
        assertFalse(CatalogOperations.events(context, key, true).single().detail.contains("secret"))
        failPage = false
        scanner.scan(session, CatalogType.MOVIES, 0, refreshCompleted = false)
        assertEquals(listOf(1, 2, 2), pages)
        assertTrue(CatalogOperations.events(context, key, true).isEmpty())
    }

    @Test fun repeatedPageDoesNotAdvanceCheckpoint() = runBlocking {
        val session = session()
        val scanner = SearchCatalogScanner(context, { _, _ -> listOf(category) }, { _, _, page ->
            PortalCatalogPage(listOf(item("same")), page, true)
        }, {})
        assertEquals(1, scanner.scan(session, CatalogType.MOVIES, 0, refreshCompleted = false).failures)
        assertEquals(1, CatalogRepository(context).browse(session.profile.cacheKey(), CatalogType.MOVIES)!!.pagesByCategory["1"])
    }

    @Test fun providerPageTotalIsRetainedAcrossWorkerBatches() = runBlocking {
        val session = session()
        val operation = CatalogOperations.scan(CatalogScanPreferences.id(session.profile))
        val scanner = SearchCatalogScanner(context, { _, _ -> listOf(category) }, { _, _, page ->
            PortalCatalogPage(listOf(item("$page")), page, hasMore = false, totalPages = 673, totalItems = 9_422)
        }, {})
        scanner.scan(session, CatalogType.MOVIES, 0, refreshCompleted = false)
        assertEquals(673, CatalogOperations.pageTotal(context, operation, category.id))
    }

    @Test fun catalogProgressExposesAccurateDecimalPercentage() {
        assertEquals("37.8%", CatalogOperationProgress("Saving", page = 3_736, totalPages = 9_890).percentText)
        assertEquals("99.9%", CatalogOperationProgress("Saving", page = 9_877, totalPages = 9_891).percentText)
        assertEquals("100.0%", CatalogOperationProgress("Done", page = 10, totalPages = 10).percentText)
        assertNull(CatalogOperationProgress("Connecting").percentText)
    }

    @Test fun remainingTimeFormattingIsCompact() {
        assertEquals("8m", formatRemainingTime(7 * 60_000L + 1))
        assertEquals("3h 12m", formatRemainingTime((3 * 60 + 12) * 60_000L))
        assertEquals("2d 5h", formatRemainingTime((2 * 24 + 5) * 60 * 60_000L))
        assertNull(formatRemainingTime(0))
    }

    @Test fun pausedBackupDoesNotConnectAndLogsPauseInsteadOfFailure() = runBlocking {
        CatalogPreferences.setBackupEnabled(context, true)
        CatalogOperations.control(context, CatalogOperations.BACKUP, "Paused")
        try { CatalogBackupManager(context).uploadAll(); fail("Paused upload must not connect") }
        catch (held: CatalogOperationHeld) { assertEquals("Paused", held.state) }
        assertEquals("Paused", BackupActivityLog.read(context).first().status)
        assertTrue(CatalogOperations.held(context, CatalogOperations.BACKUP))
        CatalogOperations.control(context, CatalogOperations.BACKUP, "Ready")
        assertFalse(CatalogOperations.held(context, CatalogOperations.BACKUP))
    }

    @Test fun stoppedScanMakesNoProviderRequestsAndDoesNotDiscardCursor() = runBlocking {
        val session = session()
        val id = CatalogScanPreferences.id(session.profile)
        CatalogScanPreferences.cursor(context, id, 1)
        CatalogOperations.control(context, CatalogOperations.scan(id), "Stopped")
        var calls = 0
        val scanner = SearchCatalogScanner(context, { _, _ -> calls++; listOf(category) }, { _, _, _ -> error("Unexpected page request") }, {})
        try { scanner.scan(session, CatalogType.MOVIES, 0); fail("Stopped scan should not run") }
        catch (_: CatalogOperationHeld) { }
        assertEquals(0, calls)
        assertEquals(1, CatalogScanPreferences.cursor(context, id))
        assertFalse(CatalogOperations.held(context, CatalogOperations.BACKUP))
    }
}
