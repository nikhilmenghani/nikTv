package com.nikhil.niktv.data
import org.junit.Assert.*
import org.junit.Test
class PortalEpisodeClassificationTest {
 @Test fun topLevelSentinelsAreNotEpisodes() {
  listOf(null, "", "0", "-1").forEach { assertFalse(isPortalEpisodeRow(false, it)) }
 }
 @Test fun actualEpisodesAreExcluded() {
  assertTrue(isPortalEpisodeRow(false, "123"))
  assertTrue(isPortalEpisodeRow(true, "0"))
 }
}
