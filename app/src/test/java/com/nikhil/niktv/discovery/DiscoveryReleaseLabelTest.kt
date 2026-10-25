package com.nikhil.niktv.discovery
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
class DiscoveryReleaseLabelTest {
 @Test fun detailsKeepBroadcastFactsSeparateAndAllGenresReadable() {
  val card = DiscoveryCard(1, true, "Series", date = "2008-07-28", runtime = 22,
   genres = listOf("Comedy", "Drama", "Family"), rating = 8.4, certification = "TV-PG")
  assertEquals(listOf("First aired Jul 28, 2008 · 22 min", "Comedy · Drama · Family · ★ 8.4 · TV-PG"),
   discoveryDetailMetadata(card, Locale.US))
 }
 @Test fun absentMetadataDoesNotCreateEmptyRowsOrUnknownRuntime() {
  assertEquals(emptyList<String>(), discoveryDetailMetadata(DiscoveryCard(1, true, "Series", runtime = 0), Locale.US))
 }
 @Test fun movieAndSeriesHaveFullDates() {
  val movie = DiscoveryCard(1, false, "Movie", date = "2020-10-23")
  val today = LocalDate.of(2021, 1, 1)
  assertEquals("Released Oct 23, 2020", discoveryReleaseLabel(movie, Locale.US, today))
  assertEquals("First aired Oct 23, 2020", discoveryReleaseLabel(movie.copy(series = true), Locale.US, today))
  assertEquals("Releases Oct 23, 2020", discoveryReleaseLabel(movie, Locale.US, LocalDate.of(2020, 1, 1)))
  assertNull(discoveryReleaseLabel(movie.copy(date = null)))
  assertNull(discoveryReleaseLabel(movie.copy(date = "unknown")))
 }
}
