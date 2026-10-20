package com.nikhil.niktv.discovery

import android.app.ActivityManager
import android.content.Context
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import com.nikhil.niktv.data.thumbnailArtworkUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val speculativeImages = Semaphore(2)

/** Bake static gradients once off-thread instead of shading the entire TV display every frame. */
internal class DiscoveryBackdropTransformation : Transformation() {
    override val cacheKey = "niktv-discovery-shaded-backdrop-v1"
    override suspend fun transform(input: android.graphics.Bitmap, size: Size): android.graphics.Bitmap = withContext(Dispatchers.Default) {
        val output = android.graphics.Bitmap.createBitmap(input.width, input.height, android.graphics.Bitmap.Config.ARGB_8888)
        output.setHasAlpha(false)
        val canvas = android.graphics.Canvas(output)
        canvas.drawColor(0xFF090B10.toInt())
        canvas.drawBitmap(input, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        val paint = android.graphics.Paint()
        paint.shader = android.graphics.LinearGradient(0f, 0f, output.width.toFloat(), 0f,
            intArrayOf(0xED090B10.toInt(), 0x73090B10, 0x33090B10), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, output.width.toFloat(), output.height.toFloat(), paint)
        paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, output.height.toFloat(),
            intArrayOf(0x26090B10, 0x66090B10, 0xF5090B10.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, output.width.toFloat(), output.height.toFloat(), paint)
        output
    }
}
internal fun discoveryArtworkPressure(context: Context): Boolean {
    val info = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
    return info.lowMemory || info.availMem < info.threshold * 2
}
internal suspend fun prefetchDiscoveryArtwork(context: Context, posters: List<String?>, adjacentHero: String? = null) = coroutineScope {
    if (discoveryArtworkPressure(context)) return@coroutineScope
    val requests = posters.filterNotNull().distinct().take(8).map { it to false } + listOfNotNull(adjacentHero?.let { it to true })
    requests.map { (url, hero) -> async(Dispatchers.IO) {
        speculativeImages.withPermit {
            ensureActive()
            if (discoveryArtworkPressure(context)) return@withPermit
            try {
                context.imageLoader.execute(ImageRequest.Builder(context).data(thumbnailArtworkUrl(url, if (hero) 1280 else 342))
                    .size(if (hero) 1280 else 342, if (hero) 720 else 513)
                    .apply { if (hero) transformations(DiscoveryBackdropTransformation()) }.build())
            } catch (c: CancellationException) { throw c }
              catch (_: Exception) { /* Speculative artwork is optional. */ }
        }
    } }.awaitAll()
}
