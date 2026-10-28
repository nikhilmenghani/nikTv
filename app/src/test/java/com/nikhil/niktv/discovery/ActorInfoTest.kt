package com.nikhil.niktv.discovery
import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
class ActorInfoTest {
 @Test fun quickSortTogglesShareExistingMenuValues() {
  assertEquals("Year: newest", toggleActorSort("Default", true))
  assertEquals("Year: oldest", toggleActorSort("Year: newest", true))
  assertEquals("Year: newest", toggleActorSort("Rating: lowest", true))
  assertEquals("Rating: highest", toggleActorSort("Year: oldest", false))
  assertEquals("Rating: lowest", toggleActorSort("Rating: highest", false))
 }
 @Test fun missingFieldsAreNotInvented() {
  val info=parseActorInfo(Json.parseToJsonElement("""{"biography":" ","birthday":null,"also_known_as":[" Alias ","Alias",""]}""").jsonObject, DiscoveryPerson("Fixture", portrait="portrait"))
  assertEquals("Fixture",info.name); assertEquals("portrait",info.portrait)
  assertNull(info.biography); assertNull(info.birthday); assertEquals(listOf("Alias"),info.aliases)
 }
 @Test fun ageUsesBirthdayAndDeathdayAndRejectsInvalidDates() {
  val today=LocalDate.of(2026,10,28)
  assertEquals(43,actorAge(DiscoveryActorInfo("Actor",birthday="1982-11-12"),today))
  assertEquals(60,actorAge(DiscoveryActorInfo("Actor",birthday="1940-01-01",deathday="2000-12-31"),today))
  assertNull(actorAge(DiscoveryActorInfo("Actor",birthday="2999-01-01"),today))
  assertNull(actorAge(DiscoveryActorInfo("Actor",birthday="invalid"),today))
 }
 @Test fun missingYearsAndRatingsRemainLastInEitherDirection() {
  val known=DiscoveryCard(1,false,"Known",date="2000-01-01",rating=7.0)
  val unknown=DiscoveryCard(2,false,"Unknown")
  for(sort in listOf("Year: newest","Year: oldest","Rating: highest","Rating: lowest")) assertEquals(listOf(known,unknown),sortActorCards(listOf(unknown,known),sort))
 }
}
