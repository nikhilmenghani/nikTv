package com.nikhil.niktv.discovery
import com.nikhil.niktv.model.*
import com.nikhil.niktv.data.AlternateSourceTarget
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
class AlternateRecentTest {
 @Test fun resumePrefersConcreteVariantAndSavedQuality() {
  val master = com.nikhil.niktv.data.AlternateStream("auto", "Auto", "https://fixture.invalid/master")
  val hd = master.copy(id = "hd", height = 720)
  val full = master.copy(id = "full", height = 1080)
  assertEquals(hd, com.nikhil.niktv.ui.alternateResumeStream(listOf(master, hd, full), null))
  assertEquals(full, com.nikhil.niktv.ui.alternateResumeStream(listOf(master, hd, full), 1080))
 }

 @Test fun remembersConcreteSourceWithoutSavingItsUrl() {
  val auto = com.nikhil.niktv.data.AlternateSourceProvider.AUTO
  assertEquals(com.nikhil.niktv.data.AlternateSourceProvider.MOVY, com.nikhil.niktv.ui.alternateHistoryProvider(auto, "1080p · Movy Alpha"))
  assertEquals(com.nikhil.niktv.data.AlternateSourceProvider.ONLYFLIX, com.nikhil.niktv.ui.alternateHistoryProvider(auto, "Auto · OnlyFlix"))
  assertEquals(auto, com.nikhil.niktv.ui.alternateHistoryProvider(auto, "Unknown"))
 }

 @Test fun alternateHistoryRoundTripsAndFindsOwnProgress() {
  val target = AlternateSourceTarget("Example", false, tmdbId = 123)
  val recent = RecentItem(FavoriteKind.MOVIE, MediaItem("alternate", "Example", null, null), profileKey = "profile-a", alternateTarget = target)
  val saved = Json.decodeFromString<RecentItem>(Json.encodeToString(recent))
  assertEquals(target, saved.alternateTarget)
  val key = com.nikhil.niktv.ui.alternateProgressKey("profile-a", target)
  assertEquals(key, discoveryRecentProgressPrefix(saved))
  assertEquals(10000L, discoveryRecentProgress(saved, listOf(PlaybackProgress(key, 10000, 90000)))?.positionMillis)
  assertNull(discoveryRecentProgress(saved.copy(profileKey = "profile-b"), listOf(PlaybackProgress(key, 10000, 90000))))
 }
}
