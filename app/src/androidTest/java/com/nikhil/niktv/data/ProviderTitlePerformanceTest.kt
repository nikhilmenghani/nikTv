package com.nikhil.niktv.data

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import java.io.File
import com.nikhil.niktv.model.CatalogType
import org.junit.Assert.*
import org.junit.Test
import kotlin.system.measureNanoTime

/** No network, UI input, credentials or personal data required. */
class ProviderTitlePerformanceTest {
    @Test fun visibleTitlesStayWithinFrameBudget() {
        val stems = listOf("Whalefall (English) (Cam)", "Friends (English-Comedy Central)",
            "Sony SAB Hindi : Taarak Mehta Ka Ooltah Chashmah", "Dune (2021) (English)")
        repeat(300) { providerDisplayTitle(stems[it % stems.size], CatalogType.SERIES) }
        val cold = measureNanoTime { repeat(10000) {
            assertTrue(providerDisplayTitle("Title $it " + stems[it % stems.size], CatalogType.MOVIES).isNotBlank())
        } }
        val fixture = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "provider-title-fixture.json")
        if (fixture.exists()) {
            val data = JSONArray(fixture.readText())
            val elapsed = measureNanoTime { repeat(data.length()) { index ->
                val item = data.getJSONObject(index)
                providerDisplayTitle(item.getString("title"), CatalogType.valueOf(item.getString("type")))
            } }
            Log.i("NikTvTitlePerf", "cachedCatalogueTitles=${data.length()} totalMs=${elapsed / 1e6}")
        }
        val visible = List(30) { "Film $it (English) (4K)" }
        visible.forEach { providerDisplayTitle(it, CatalogType.MOVIES) }
        val frames = LongArray(1000) {
            measureNanoTime { repeat(30) { providerDisplayTitle(visible[it], CatalogType.MOVIES) } }
        }.sorted()
        Log.i("NikTvTitlePerf", "cold10000Ms=${cold / 1e6} cached30P50Us=${frames[500] / 1000} cached30P95Us=${frames[950] / 1000} maxUs=${frames.last() / 1000}")
        assertTrue("30 cached visible titles p95 must fit one 60Hz frame", frames[950] < 16000000L)
    }
}
