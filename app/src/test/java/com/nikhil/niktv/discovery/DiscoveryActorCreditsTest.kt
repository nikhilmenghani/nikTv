package com.nikhil.niktv.discovery

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class DiscoveryActorCreditsTest {
    @Test fun creditsKeepReleasedMoviesAndSeriesWithDistinctIdentities() {
        val root = Json.parseToJsonElement("""{"cast":[
          {"id":1,"media_type":"movie","title":"Movie","release_date":"2020-01-01","popularity":5},
          {"id":1,"media_type":"tv","name":"Series","first_air_date":"2021-01-01","popularity":10},
          {"id":1,"media_type":"movie","title":"Duplicate","release_date":"2020-01-01"},
          {"id":2,"media_type":"movie","title":"Future","release_date":"2099-01-01"},
          {"id":3,"media_type":"movie","title":"Unknown date"},
          {"id":4,"media_type":"movie","title":"Sensitive","adult":true,"release_date":"2020-01-01"},
          {"id":5,"media_type":"person","name":"Wrong type"}
        ],"crew":[{"id":6,"media_type":"movie","title":"Crew only","release_date":"2020-01-01"}]}""").jsonObject
        assertEquals(listOf("tv:1", "movie:1"), discoveryActorCredits(root, "2026-10-19").map { it.key })
    }
    @Test fun navigationSkipsNonFocusableSupplementRowsAndReturnsFromCast() {
        val rows = listOf(0, 1, 2, 5)
        assertEquals(5, adjacentDiscoveryRow(2, 1, rows))
        assertEquals(2, adjacentDiscoveryRow(5, -1, rows))
        assertNull(adjacentDiscoveryRow(5, 1, rows))
    }
    @Test fun oldCachedCastRemainsDeserializable() {
        val person = Json.decodeFromString<DiscoveryPerson>("""{"name":"Actor","role":"Role"}""")
        assertNull(person.id)
    }
    @Test fun actorResultsAreBounded() {
        val entries = (1..100).joinToString(",") { """{"id":$it,"media_type":"movie","title":"Movie $it","release_date":"2020-01-01"}""" }
        val root = Json.parseToJsonElement("""{"cast":[$entries]}""").jsonObject
        assertEquals(60, discoveryActorCredits(root, "2026-10-19").size)
    }
}
