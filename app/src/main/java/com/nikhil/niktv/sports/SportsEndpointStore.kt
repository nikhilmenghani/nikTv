package com.nikhil.niktv.sports

import android.content.Context
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.put

/** Only public catalogue configuration, independent of accounts and IPTV profiles. */
internal interface SportsEndpointStore {
    fun load(country: String): SportsConfiguration?
    fun save(country: String, configuration: SportsConfiguration)
    fun known(country: String): List<SportsConfiguration> = listOfNotNull(load(country))
    fun preferred(country: String): String? = null
    fun prefer(country: String, base: String?) {}
}

/** Read and written by the repository on Dispatchers.IO, including the first preferences load. */
internal class SportsEndpointPreferences(context: Context, private val preferencesName: String = "sports_catalogue_endpoints_v1") : SportsEndpointStore {
    private val application = context.applicationContext
    private val preferences by lazy { application.getSharedPreferences(preferencesName, Context.MODE_PRIVATE) }

    override fun load(country: String): SportsConfiguration? = runCatching {
        preferences.getString(key(country), null)?.let { sportsConfiguration(it, country) }
    }.getOrNull()

    override fun save(country: String, configuration: SportsConfiguration) {
        val history = (listOf(configuration) + known(country)).distinctBy { it.primary }.take(8)
        // Commit on the IO dispatcher so a process restart cannot lose the working endpoint.
        check(preferences.edit().putString(key(country), body(configuration))
            .putString("history_${key(country)}", JsonArray(history.map { Json.parseToJsonElement(body(it)) }).toString())
            .commit()) { "Could not save the working sports server." }
    }

    override fun known(country: String): List<SportsConfiguration> = runCatching {
        val rows = Json.parseToJsonElement(preferences.getString("history_${key(country)}", "[]")!!) as? JsonArray
        (listOfNotNull(load(country)) + rows.orEmpty().mapNotNull {
            runCatching { sportsConfiguration(it.toString(), country) }.getOrNull()
        }).distinctBy { it.primary }.take(8)
    }.getOrElse { listOfNotNull(load(country)) }

    override fun preferred(country: String): String? = runCatching {
        preferences.getString("preferred_${key(country)}", null)?.let { sportsConfiguration(it, country).primary }
    }.getOrNull()

    override fun prefer(country: String, base: String?) {
        val edit = preferences.edit()
        if (base == null) edit.remove("preferred_${key(country)}")
        else edit.putString("preferred_${key(country)}", body(SportsConfiguration(base)))
        check(edit.commit()) { "Could not save the preferred sports server." }
    }

    private fun body(configuration: SportsConfiguration) = buildJsonObject {
            put("cric_api2", configuration.primary)
            put("userAgent", configuration.userAgent)
        }.toString()

    private fun key(country: String) = "working_${sportsCountry(country).ifBlank { "unknown" }}"
}
