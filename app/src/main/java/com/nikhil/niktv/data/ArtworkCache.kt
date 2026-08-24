package com.nikhil.niktv.data

import android.content.Context
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import com.nikhil.niktv.model.MediaItem
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

fun artworkRequest(context: Context, item: MediaItem): ImageRequest =
    ImageRequest.Builder(context)
        .data(item.logo)
        .memoryCacheKey(item.artworkCacheKey())
        .diskCacheKey(item.artworkCacheKey())
        .memoryCachePolicy(CachePolicy.ENABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .networkCachePolicy(CachePolicy.ENABLED)
        .build()

suspend fun prefetchArtwork(context: Context, items: List<MediaItem>, limit: Int = 8) = coroutineScope {
    items.asSequence()
        .filter { !it.logo.isNullOrBlank() }
        .distinctBy { it.artworkCacheKey() }
        .take(limit)
        .map { item -> async { runCatching { context.imageLoader.execute(artworkRequest(context, item)) } } }
        .toList()
        .awaitAll()
}

private fun MediaItem.artworkCacheKey(): String {
    // Portal artwork URLs often contain short-lived tokens. Keep the cache identity
    // stable by using the media identity and URL path rather than its query string.
    val identity = "$id|$title|${logo.orEmpty().substringBefore('?')}"
    val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
    return "niktv-art-v1-" + digest.take(16).joinToString("") { "%02x".format(it) }
}
