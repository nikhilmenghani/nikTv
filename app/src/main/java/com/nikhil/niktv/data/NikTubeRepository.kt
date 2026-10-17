package com.nikhil.niktv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Separate from IPTV databases and Room. Only public metadata and local library state. */
class NikTubeRepository(context: Context) : SQLiteOpenHelper(context.applicationContext, "niktube-library.db", null, 1) {
    private val legacy = context.getSharedPreferences("niktube_draft", 0)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE documents (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    suspend fun migrateHistory() = withContext(Dispatchers.IO) {
        if (read("history") == null) {
            val history = legacy.getString("history", "[]") ?: "[]"
            runCatching { JSONArray(history) }.onSuccess { write("history", history) }
        }
    }
    suspend fun history(): List<Pair<NikTubeVideo, Long>> = withContext(Dispatchers.IO) {
        val items = runCatching { JSONArray(read("history") ?: "[]") }.getOrDefault(JSONArray())
        (0 until items.length()).mapNotNull { index -> runCatching {
            val row = items.getJSONObject(index)
            decode(row) to row.optLong("position")
        }.getOrNull() }
    }
    suspend fun saveHistory(items: List<Pair<NikTubeVideo, Long>>) = withContext(Dispatchers.IO) {
        write("history", JSONArray().apply { items.forEach { (video, position) -> put(encode(video).put("position", position)) } }.toString())
    }
    suspend fun subscriptions(): List<NikTubeChannel> = withContext(Dispatchers.IO) {
        val rows = runCatching { JSONArray(read("subscriptions") ?: "[]") }.getOrDefault(JSONArray())
        (0 until rows.length()).map { val row = rows.getJSONObject(it); NikTubeChannel(row.getString("id"), row.getString("title"), row.optString("avatar")) }
    }
    suspend fun saveSubscriptions(channels: List<NikTubeChannel>) = withContext(Dispatchers.IO) {
        write("subscriptions", JSONArray().apply { channels.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("avatar", it.avatar)) } }.toString())
    }
    suspend fun playlists(): Map<String, List<NikTubeVideo>> = withContext(Dispatchers.IO) {
        val root = runCatching { JSONObject(read("playlists") ?: "{}") }.getOrDefault(JSONObject())
        root.keys().asSequence().associateWith { name -> root.getJSONArray(name).let { rows -> (0 until rows.length()).map { decode(rows.getJSONObject(it)) } } }
    }
    suspend fun savePlaylists(playlists: Map<String, List<NikTubeVideo>>) = withContext(Dispatchers.IO) {
        write("playlists", JSONObject().apply { playlists.forEach { (name, videos) -> put(name, JSONArray().apply { videos.forEach { put(encode(it)) } }) } }.toString())
    }
    suspend fun cached(key: String): NikTubePage? = withContext(Dispatchers.IO) {
        readableDatabase.rawQuery("SELECT value FROM documents WHERE key=? AND updated>?", arrayOf("page:$key", (System.currentTimeMillis() - 30 * 60_000).toString())).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            runCatching { decodePage(JSONObject(cursor.getString(0))) }.getOrNull()
        }
    }
    suspend fun cache(key: String, page: NikTubePage) = withContext(Dispatchers.IO) {
        write("page:$key", encodePage(page).toString())
        readableDatabase.rawQuery("SELECT SUM(length(value)) FROM documents WHERE key LIKE 'page:%'", null).use { cursor ->
            if (cursor.moveToFirst() && cursor.getLong(0) > 50L * 1024 * 1024) {
                writableDatabase.execSQL("DELETE FROM documents WHERE key IN (SELECT key FROM documents WHERE key LIKE 'page:%' ORDER BY updated LIMIT 20)")
            }
        }
    }
    private fun read(key: String): String? = readableDatabase.rawQuery("SELECT value FROM documents WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    private fun write(key: String, value: String) {
        writableDatabase.insertWithOnConflict("documents", null, ContentValues().apply {
            put("key", key); put("value", value); put("updated", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }
    companion object {
        internal fun encode(v: NikTubeVideo) = JSONObject().put("id", v.id).put("title", v.title).put("channel", v.channel)
            .put("duration", v.duration).put("channelId", v.channelId).put("avatar", v.avatar).put("views", v.views)
            .put("published", v.published).put("thumbnail", v.thumbnailUrl)
        internal fun decode(v: JSONObject) = NikTubeVideo(v.getString("id"), v.getString("title"), v.optString("channel"), v.optString("duration"),
            v.optString("channelId"), v.optString("avatar"), v.optString("views"), v.optString("published"), v.optString("thumbnail"))
        internal fun encodePage(page: NikTubePage) = JSONObject().put("videos", JSONArray().apply { page.videos.forEach { put(encode(it)) } }).put("continuation", page.continuation ?: JSONObject.NULL)
        internal fun decodePage(root: JSONObject): NikTubePage {
            val items = root.getJSONArray("videos")
            return NikTubePage((0 until items.length()).map { decode(items.getJSONObject(it)) }, root.optString("continuation").takeUnless { it.isBlank() || it == "null" })
        }
    }
}
