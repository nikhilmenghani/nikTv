package com.nikhil.niktv.data
import com.nikhil.niktv.model.MediaItem
import org.junit.Assert.*
import org.junit.Test
class TmdbSeriesMatchingTest {
 private val series = TmdbSeries(123, "The Kapil Sharma Show", "The Kapil Sharma Show", null, null, null, "2016-01-01", null)
 private fun item(title: String) = MediaItem(title, title, null, null)
 @Test fun articleAndYear() {
  val candidate = item("Kapil Sharma Show (Hindi) 2019")
  assertEquals(listOf(candidate), rankTmdbSeriesMatches(series, listOf(candidate)))
 }
 @Test fun networkPrefix() {
  val candidate = item("Sony : Kapil Sharma Show (Hindi)")
  assertEquals(listOf(candidate), rankTmdbSeriesMatches(series, listOf(candidate)))
 }
 @Test fun differentShow() {
  assertTrue(rankTmdbSeriesMatches(series, listOf(item("The Great Indian Kapil Show (Hindi)"))).isEmpty())
 }
 @Test fun translatedTitle() {
  val translated = series.copy(name = "Sister-in-Law Is at Home!", originalName = "Original script title", alternativeNames = listOf("Bhabiji Ghar Par Hain"))
  val candidate = item("Bhabiji Ghar Par Hain (Hindi)")
  assertEquals(listOf(candidate), rankTmdbSeriesMatches(translated, listOf(candidate)))
  assertTrue(rankTmdbSeriesMatches(translated, listOf(item("Sister Sister"))).isEmpty())
 }
 @Test fun cacheMigration() {
  assertFalse(com.nikhil.niktv.model.TmdbAvailabilityEntry(10L, emptyList(), 2).isFresh(20L, 100L))
  assertTrue(com.nikhil.niktv.model.TmdbAvailabilityEntry(10L, listOf(item("Show"))).isFresh(20L, 100L))
 }
}
