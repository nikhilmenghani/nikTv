package com.nikhil.niktv.sports

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** Private Sports-only storage. Never reads or changes an IPTV profile or its favourites. */
internal class SportsLibraryStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("sports_library_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    fun favourites(): List<SportsChannel> = runCatching { json.decodeFromString<List<SportsChannel>>(prefs.getString("favourites", "[]")!!) }.getOrDefault(emptyList())
    fun playlists(): List<SportsCategory> = runCatching { json.decodeFromString<List<SportsCategory>>(prefs.getString("playlists", "[]")!!) }.getOrDefault(emptyList())
    fun toggle(channel: SportsChannel): List<SportsChannel> {
        val old = favourites()
        val next = if (old.any { it.key == channel.key }) old.filterNot { it.key == channel.key } else listOf(channel) + old
        prefs.edit().putString("favourites", json.encodeToString(next)).apply()
        return next
    }
    fun addUrl(title: String, link: String): SportsCategory {
        val url = link.trim()
        if (!sportsNetworkUrl(url)) throw SportsException("Enter a valid HTTP or HTTPS playlist address.")
        val item = SportsCategory("custom-${sportsDigest(url)}", title.trim().ifBlank { "My playlist" }.take(150), link = url, custom = true)
        save(item)
        return item
    }
    fun importFile(uri: Uri, title: String): SportsCategory {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (output.size() + n > 4 * 1024 * 1024) throw SportsException("Choose a playlist smaller than 4 MB.")
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        } ?: throw SportsException("The selected file could not be opened.")
        val id = "local-${sportsDigest(bytes.toString(Charsets.UTF_8))}"
        val category = SportsCategory(id, title.trim().ifBlank { "Imported playlist" }.take(150), link = "local:$id", custom = true)
        SportsPlaylist.parse(bytes.toString(Charsets.UTF_8), category)
        localFile(category).apply { parentFile?.mkdirs(); writeBytes(bytes) }
        save(category)
        return category
    }
    fun localBody(category: SportsCategory): String = localFile(category).let {
        if (!it.exists()) throw SportsException("This imported playlist is no longer available. Import it again.")
        if (it.length() > 4 * 1024 * 1024) throw SportsException("This playlist is too large to display.")
        it.readText()
    }
    fun remove(category: SportsCategory): List<SportsCategory> {
        val next = playlists().filterNot { it.id == category.id }
        prefs.edit().putString("playlists", json.encodeToString(next)).apply()
        if (category.link.startsWith("local:")) localFile(category).delete()
        return next
    }
    private fun save(item: SportsCategory) { prefs.edit().putString("playlists", json.encodeToString(playlists().filterNot { it.id == item.id } + item)).apply() }
    private fun localFile(category: SportsCategory): File {
        if (!category.id.matches(Regex("local-[a-f0-9]{24}"))) throw SportsException("This imported playlist is invalid.")
        return File(context.filesDir, "sports-playlists/${category.id}.m3u")
    }
}
