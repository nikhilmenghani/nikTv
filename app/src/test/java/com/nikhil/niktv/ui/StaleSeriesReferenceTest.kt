package com.nikhil.niktv.ui
import com.nikhil.niktv.model.MediaItem
import org.junit.Assert.*
import org.junit.Test
class StaleSeriesReferenceTest {
 private val old = MediaItem("old", "Homeland (English) - ENGLISH | SERIES", null, "old-command")
 @Test fun exactUniqueProviderReplacementRetainsItsPlaybackReference() {
  val fresh = old.copy(id="fresh", command="fresh-command")
  assertEquals(fresh, staleSeriesReplacement(old, listOf(fresh, fresh)))
  assertEquals("old-command", old.command)
 }
 @Test fun ambiguousAndUnrelatedTitlesAreNotSubstituted() {
  val fresh = old.copy(id="fresh", command="fresh-command")
  assertNull(staleSeriesReplacement(old, listOf(fresh, fresh.copy(id="other"))))
  assertNull(staleSeriesReplacement(old, listOf(fresh.copy(title="Homeland Security"))))
  assertNull(staleSeriesReplacement(old, listOf(old)))
  assertNull(staleSeriesReplacement(old.copy(title="Homeland (2011)"), listOf(fresh.copy(title="Homeland (2019)"))))
  assertNull(staleSeriesReplacement(old, listOf(fresh.copy(command=null))))
 }
}
