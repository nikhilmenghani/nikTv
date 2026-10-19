package com.nikhil.niktv.data

import android.content.Context
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.request.crossfade
import com.nikhil.niktv.model.MediaItem
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

fun artworkRequest(context: Context, item: MediaItem, widthPixels: Int = 0, heightPixels: Int = 0): ImageRequest =
    ImageRequest.Builder(context)
        .data(thumbnailArtworkUrl(item.logo, widthPixels))
        .memoryCacheKey(item.artworkCacheKey(widthPixels) + if (widthPixels > 0 && heightPixels > 0) ":${widthPixels}x$heightPixels" else "")
        .diskCacheKey(item.artworkCacheKey(widthPixels))
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .networkCachePolicy(CachePolicy.ENABLED)
        .crossfade(false)
        .apply {
            if (widthPixels > 0 && heightPixels > 0) size(widthPixels, heightPixels)
        }
        .build()

suspend fun prefetchArtwork(context: Context, items: List<MediaItem>, limit: Int = 8) = coroutineScope {
    items.asSequence()
        .filter { !it.logo.isNullOrBlank() }
        .distinctBy { it.artworkCacheKey() }
        .take(limit)
        .map { item -> async {
            try { context.imageLoader.execute(artworkRequest(context, item, 342, 513)) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* An unavailable poster must not block the row. */ }
        } }
        .toList()
        .awaitAll()
}

/** Request CDN thumbnails rather than downloading full-size TMDB posters for small cards. */
internal fun thumbnailArtworkUrl(url: String?, widthPixels: Int): String? {
    if (url == null || !url.startsWith("https://image.tmdb.org/t/p/")) return url
    val size = url.removePrefix("https://image.tmdb.org/t/p/").substringBefore('/')
    // Default requests only resize poster-sized images; preserve hero/backdrop requests.
    if (widthPixels <= 0 && size !in setOf("w185", "w342", "w500")) return url
    val target = when {
        widthPixels in 1..185 || (widthPixels <= 0 && size == "w185") -> "w185"
        widthPixels <= 342 -> "w342"
        widthPixels <= 500 -> "w500"
        widthPixels <= 780 -> "w780"
        else -> "w1280"
    }
    return "https://image.tmdb.org/t/p/$target/" + url.substringAfter("/t/p/").substringAfter('/')
}

internal fun MediaItem.artworkCacheKey(widthPixels: Int = 0): String {
    // Portal artwork URLs often contain short-lived tokens. Keep the cache identity
    // stable by using the media identity and URL path rather than its query string.
    val image = thumbnailArtworkUrl(logo, widthPixels).orEmpty()
    val identity = if (image.startsWith("https://image.tmdb.org/t/p/")) image
        else "$id|$title|${image.substringBefore('?')}"
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
    return "niktv-art-v1-" + digest.take(16).joinToString("") { "%02x".format(it) }
}
