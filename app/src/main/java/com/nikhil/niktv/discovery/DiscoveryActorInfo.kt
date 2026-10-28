package com.nikhil.niktv.discovery

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.Period

@Serializable
internal data class DiscoveryActorInfo(val name: String, val portrait: String? = null,
    val biography: String? = null, val birthday: String? = null, val deathday: String? = null,
    val birthplace: String? = null, val department: String? = null, val aliases: List<String> = emptyList())

internal fun parseActorInfo(root: JsonObject, person: DiscoveryPerson): DiscoveryActorInfo {
    fun value(key: String) = root[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    return DiscoveryActorInfo(value("name") ?: person.name,
        value("profile_path")?.let { "https://image.tmdb.org/t/p/w342$it" } ?: person.portrait,
        value("biography"), value("birthday"), value("deathday"), value("place_of_birth"), value("known_for_department"),
        (root["also_known_as"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }.distinct())
}
internal fun actorAge(info: DiscoveryActorInfo, today: LocalDate = LocalDate.now()): Int? = runCatching {
    val birth = LocalDate.parse(info.birthday ?: return null)
    val end = info.deathday?.let(LocalDate::parse) ?: today
    Period.between(birth, end).years.takeIf { !birth.isAfter(end) }
}.getOrNull()

internal fun toggleActorSort(current: String, year: Boolean): String = if (year) {
    if (current == "Year: newest") "Year: oldest" else "Year: newest"
} else if (current == "Rating: highest") "Rating: lowest" else "Rating: highest"
