package com.nikhil.niktv.sports

import android.content.Context
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class PlaybackSimulationEvent(
    val id: String,
    val title: String,
    val description: String,
    val isLive: Boolean,
    val sources: List<PlaybackSimulationSource>,
)

internal data class PlaybackSimulationSource(val id: String, val title: String)

/** Controlled local metadata leading to public sample media through the real Sports player. */
internal class PlaybackSimulationRepository(context: Context) {
    private val assets = context.applicationContext.assets

    suspend fun catalogue(report: (String) -> Unit): List<PlaybackSimulationEvent> = withContext(Dispatchers.IO) {
        ensureActive()
        val bootstrap = document("bootstrap.json")
        require(bootstrap.text("catalogue") == "catalogue.json" && bootstrap.text("resolver") == "resolver.json") {
            "The playback simulation bootstrap is invalid."
        }
        report("Local bootstrap fixture loaded")
        val events = readEvents()
        report("Local catalogue fixture loaded · ${events.size} sample events")
        events
    }

    suspend fun resolve(
        event: PlaybackSimulationEvent,
        source: PlaybackSimulationSource,
        report: (String) -> Unit,
    ): SportsStream = withContext(Dispatchers.IO) {
        ensureActive()
        val selected = readEvents().firstOrNull { it.id == event.id }
            ?: throw SportsException("This sample event is unavailable. Reload the simulation.")
        val choice = selected.sources.firstOrNull { it.id == source.id }
            ?: throw SportsException("This source does not belong to the selected sample event.")
        report("Local resolver fixture selected · ${choice.title}")
        val envelope = document("resolver.json")["responses"]?.jsonObject
            ?.get("${selected.id}:${choice.id}")?.jsonPrimitive?.content
            ?: throw SportsException("This sample resolver response is unavailable.")
        val decoded = try {
            Base64.getDecoder().decode(envelope).toString(Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            throw SportsException("The sample resolver fixture could not be decoded.")
        }
        report("Local Base64 fixture decoded")
        val media = Json.parseToJsonElement(decoded).jsonObject
        val link = media.text("url")
        if (link !in sampleUrls) throw SportsException("The simulation returned an unsupported sample URL.")
        report("Public DASH sample validated · ${selected.title}")
        SportsStream(link, choice.title, isLive = selected.isLive,
            contentId = "playback-simulation:${selected.id}:${choice.id}")
    }

    private fun readEvents(): List<PlaybackSimulationEvent> = document("catalogue.json")
        .getValue("events").jsonArray.map { value ->
            val event = value.jsonObject
            PlaybackSimulationEvent(event.text("id"), event.text("title"), event.text("description"),
                event.getValue("isLive").jsonPrimitive.boolean,
                event.getValue("sources").jsonArray.map { source ->
                    val choice = source.jsonObject
                    PlaybackSimulationSource(choice.text("id"), choice.text("title"))
                })
        }

    private fun document(name: String): JsonObject = assets.open("playback-simulation/$name")
        .bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private companion object {
        val sampleUrls = setOf(
            "https://storage.googleapis.com/shaka-live-assets/player-source.mpd",
            "https://storage.googleapis.com/shaka-demo-assets/angel-one/dash.mpd",
        )
    }
}
