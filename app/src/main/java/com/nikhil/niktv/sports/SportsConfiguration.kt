package com.nikhil.niktv.sports

import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class SportsConfiguration(
    val primary: String = "https://cftkhffmgf147.top/",
    val secondary: String? = null,
    val userAgent: String = SPORTS_USER_AGENT,
)

internal fun sportsConfiguration(body: String, country: String): SportsConfiguration {
    val root = SportsCatalogue.document(body) as? JsonObject ?: throw SportsException("Sports configuration is unavailable.")
    fun parameter(key: String): String? {
        val entry = (root["parameters"] as? JsonObject)?.get(key) as? JsonObject
        val conditional = entry?.get("conditionalValues") as? JsonObject
        val region = sportsCountry(country)
        if (region.isNotEmpty()) {
            for (row in (root["conditions"] as? JsonArray).orEmpty()) {
                val condition = row as? JsonObject ?: continue
                val match = Regex("^\\s*device\\.country\\s+(in|not\\s+in)\\s+\\[\\s*((?:'[A-Za-z]{2}'\\s*,?\\s*)+)\\]\\s*$").matchEntire(condition.text("expression")) ?: continue
                val countries = Regex("'([A-Za-z]{2})'").findAll(match.groupValues[2]).map { sportsCountry(it.groupValues[1]) }.toSet()
                val matches = if (match.groupValues[1] == "in") region in countries else region !in countries
                if (matches) ((conditional?.get(condition.text("name")) as? JsonObject)?.text("value"))?.takeIf(String::isNotBlank)?.let { return it }
            }
        }
        return (entry?.get("defaultValue") as? JsonObject)?.text("value")?.takeIf(String::isNotBlank)
            ?: (root["configs_key"] as? JsonObject)?.text(key)?.takeIf(String::isNotBlank)
            ?: root.text(key).takeIf(String::isNotBlank)
    }
    fun base(value: String?): String? {
        val url = value?.trim()?.toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
        return url.toString().trimEnd('/') + "/"
    }
    val primary = base(parameter("cric_api2")) ?: throw SportsException("Sports configuration has an invalid server address.")
    val agent = parameter("userAgent")?.trim()?.takeIf { it.length in 1..1024 && it.all { c -> c.code in 32..126 } } ?: SPORTS_USER_AGENT
    return SportsConfiguration(primary, base(parameter("cric_api1"))?.takeUnless { it == primary }, agent)
}
