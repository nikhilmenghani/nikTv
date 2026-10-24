package com.nikhil.niktv.discovery
import org.junit.Assert.*
import org.junit.Test
class ActorSortTest {
 @Test fun sortsYearsAndRatingsBothWays() {
  val old = DiscoveryCard(1, false, "Old", date = "2000-01-01", rating = 8.0)
  val new = DiscoveryCard(2, true, "New", date = "2020-01-01", rating = 6.0)
  assertEquals(listOf(new, old), sortActorCards(listOf(old, new), "Year: newest"))
  assertEquals(listOf(old, new), sortActorCards(listOf(new, old), "Year: oldest"))
  assertEquals(listOf(old, new), sortActorCards(listOf(new, old), "Rating: highest"))
  assertEquals(listOf(new, old), sortActorCards(listOf(old, new), "Rating: lowest"))
 }
}
