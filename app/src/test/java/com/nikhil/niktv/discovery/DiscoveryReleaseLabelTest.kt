package com.nikhil.niktv.discovery
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
class DiscoveryReleaseLabelTest {
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
