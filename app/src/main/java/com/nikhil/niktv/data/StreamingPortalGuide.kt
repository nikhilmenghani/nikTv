package com.nikhil.niktv.data

import android.util.JsonReader
import android.util.JsonToken
import com.nikhil.niktv.model.LiveProgramme
import com.nikhil.niktv.model.isMissingLiveProgrammeTitle
import java.io.Reader

/** Skip the large provider descriptions/flags instead of constructing a full JSON object tree. */
internal fun readStreamingPortalGuide(input: Reader, now: Long): Map<String, List<LiveProgramme>> {
    data class Row(val channel: String?, val programme: LiveProgramme)
    var remaining = 20_000
    var characters = 2_000_000
    val prefixed = java.io.PushbackReader(input, 1)
    var found = false
    for (ignored in 0 until 4096) {
        val char = prefixed.read()
        if (char < 0) break
        if (char == '{'.code || char == '['.code) { prefixed.unread(char); found = true; break }
    }
    if (!found) throw java.io.IOException("Provider guide did not contain JSON")
    val reader = JsonReader(prefixed).apply { isLenient = true }
    fun primitive(): String? = when (reader.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        else -> { reader.skipValue(); null }
    }
    fun time(value: String?): Long? = value?.toLongOrNull()?.takeIf { it > 0 }?.let {
        if (it < 10_000_000_000L) it * 1000 else it
    }
    val wrappers = setOf("js", "data", "programs", "epg", "now_playing", "current_program", "current_programme")
    fun node(hint: String?, depth: Int): MutableList<Row> {
        if (depth > 24 || remaining <= 0 || characters <= 0) { reader.skipValue(); return mutableListOf() }
        val rows = mutableListOf<Row>()
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                while (reader.hasNext()) rows += node(hint, depth + 1)
                reader.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                var channel: String? = null
                val titles = mutableMapOf<String, String>()
                val starts = mutableMapOf<String, Long>()
                val ends = mutableMapOf<String, Long>()
                while (reader.hasNext()) {
                    when (val key = reader.nextName()) {
                        "ch_id", "channel_id" -> channel = primitive()?.takeIf(String::isNotBlank)
                        "program_name", "programme_name", "title", "name", "cur_playing", "playing" ->
                            primitive()?.takeIf { it.isNotBlank() }?.let { titles[key] = it }
                        "start_timestamp", "start_ts", "start_time" -> time(primitive())?.let { starts[key] = it }
                        "stop_timestamp", "end_timestamp", "stop_ts", "end_ts", "end_time" -> time(primitive())?.let { ends[key] = it }
                        else -> if (reader.peek() == JsonToken.BEGIN_ARRAY || reader.peek() == JsonToken.BEGIN_OBJECT) {
                            rows += node(if (key in wrappers) hint else key, depth + 1)
                        } else reader.skipValue()
                    }
                }
                reader.endObject()
                if (channel != null) {
                    rows.indices.forEach { index ->
                        if (rows[index].channel == null || rows[index].channel == hint) rows[index] = rows[index].copy(channel = channel)
                    }
                }
                val title = listOf("program_name", "programme_name", "title", "name", "cur_playing", "playing")
                    .firstNotNullOfOrNull { titles[it] }
                val start = listOf("start_timestamp", "start_ts", "start_time").firstNotNullOfOrNull { starts[it] }
                val end = listOf("stop_timestamp", "end_timestamp", "stop_ts", "end_ts", "end_time").firstNotNullOfOrNull { ends[it] }
                if (title != null && !isMissingLiveProgrammeTitle(title) && start != null && end != null &&
                    end > now && end > start && start < now + 48 * 60 * 60_000L && remaining > 0 && title.length <= characters) {
                    rows += Row(channel ?: hint, LiveProgramme(title, start, end, now))
                    remaining--
                    characters -= title.length
                }
            }
            else -> reader.skipValue()
        }
        return rows
    }
    return node(null, 0).asSequence().filter { !it.channel.isNullOrBlank() }
        .groupBy({ it.channel!! }, { it.programme }).entries.take(5_000).associate { (id, programmes) ->
            id to programmes.distinctBy { Triple(it.title, it.startTimeMillis, it.endTimeMillis) }.sortedBy { it.startTimeMillis }
        }
}
