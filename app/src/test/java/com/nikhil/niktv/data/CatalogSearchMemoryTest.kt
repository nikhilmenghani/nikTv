package com.nikhil.niktv.data

import android.app.Application
import com.nikhil.niktv.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class CatalogSearchMemoryTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun cache(profile: String, title: String) = SearchCatalogCache(profile, CatalogType.MOVIES, 1,
        listOf(MediaItem("id", title, null, "stream")), setOf("*"))
    @Before fun clear() { CatalogDiskCache.clear(context) }

    @Test fun repeatedSearchReadsReuseDecodedCatalogueButWritesInvalidateIt() = runBlocking {
        CatalogDiskCache.write(context, "search:one:MOVIES", cache("one", "Drishyam"))
        val first = CatalogDiskCache.read<SearchCatalogCache>(context, "search:one:MOVIES")!!
        assertSame(first, CatalogDiskCache.read<SearchCatalogCache>(context, "search:one:MOVIES"))
        CatalogDiskCache.write(context, "search:one:MOVIES", cache("one", "Drishyam 2"))
        assertEquals("Drishyam 2", CatalogDiskCache.read<SearchCatalogCache>(context, "search:one:MOVIES")!!.items.single().title)
    }

    @Test fun differentProfilesStayIsolatedAndClearingAlsoClearsResidentData() = runBlocking {
        CatalogDiskCache.write(context, "search:one:MOVIES", cache("one", "First"))
        CatalogDiskCache.write(context, "search:two:MOVIES", cache("two", "Second"))
        assertEquals("First", CatalogDiskCache.read<SearchCatalogCache>(context, "search:one:MOVIES")!!.items.single().title)
        assertEquals("Second", CatalogDiskCache.read<SearchCatalogCache>(context, "search:two:MOVIES")!!.items.single().title)
        CatalogDiskCache.clear(context)
        assertNull(CatalogDiskCache.read<SearchCatalogCache>(context, "search:two:MOVIES"))
    }

    @Test fun unrelatedCatalogueWritesDoNotEvictTheActiveSearchCatalogue() = runBlocking {
        CatalogDiskCache.write(context, "search:one:MOVIES", cache("one", "Drishyam"))
        val first = CatalogDiskCache.read<SearchCatalogCache>(context, "search:one:MOVIES")!!
        CatalogDiskCache.write(context, "browse:one:SERIES", cache("one", "Other"))
        assertSame(first, CatalogDiskCache.read<SearchCatalogCache>(context, "search:one:MOVIES"))
    }
}
