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

/** Small, cached three-pass box blur; no full-screen blur pass while navigating. */
internal class DiscoveryGlassTransformation : Transformation() {
    override val cacheKey = "niktv-discovery-glass-v1"
    override suspend fun transform(input: android.graphics.Bitmap, size: Size): android.graphics.Bitmap = withContext(Dispatchers.Default) {
        val width = 96
        val height = (width.toFloat() * input.height / input.width).toInt().coerceIn(1, 96)
        val scaled = android.graphics.Bitmap.createScaledBitmap(input, width, height, true)
        var pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        repeat(3) {
            for (horizontal in listOf(true, false)) {
                val output = IntArray(pixels.size)
                for (y in 0 until height) for (x in 0 until width) {
                    var a = 0; var r = 0; var g = 0; var b = 0
                    for (offset in -3..3) {
                        val sx = if (horizontal) (x + offset).coerceIn(0, width - 1) else x
                        val sy = if (horizontal) y else (y + offset).coerceIn(0, height - 1)
                        val c = pixels[sy * width + sx]
                        a += c ushr 24; r += (c ushr 16) and 255; g += (c ushr 8) and 255; b += c and 255
                    }
                    output[y * width + x] = ((a / 7) shl 24) or ((r / 7) shl 16) or ((g / 7) shl 8) or (b / 7)
                }
                pixels = output
            }
        }
        android.graphics.Bitmap.createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888)
    }
}

/** Shade both ends of portrait artwork so top actions and bottom text remain readable. */
internal class DiscoveryPhoneBackdropTransformation : Transformation() {
    override val cacheKey = "niktv-discovery-phone-shaded-v2"
    override suspend fun transform(input: android.graphics.Bitmap, size: Size): android.graphics.Bitmap = withContext(Dispatchers.Default) {
        val output = android.graphics.Bitmap.createBitmap(input.width, input.height, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        canvas.drawBitmap(input, 0f, 0f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, output.height.toFloat(),
                intArrayOf(0xF5090B10.toInt(), 0x18090B10, 0xA6090B10.toInt(), 0xFF090B10.toInt()),
                floatArrayOf(0f, .35f, .7f, 1f), android.graphics.Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, output.width.toFloat(), output.height.toFloat(), paint)
        output
    }
}

/** Bake static gradients once off-thread instead of shading the entire TV display every frame. */
internal class DiscoveryBackdropTransformation : Transformation() {
    override val cacheKey = "niktv-discovery-shaded-backdrop-v2"
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
            intArrayOf(0xF5090B10.toInt(), 0x26090B10, 0xFF090B10.toInt()), floatArrayOf(0f, .35f, 1f), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, output.width.toFloat(), output.height.toFloat(), paint)
        output
    }
}
internal fun discoveryArtworkPressure(context: Context): Boolean {
    val info = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
    return info.lowMemory || info.availMem < info.threshold * 2
}
internal suspend fun prefetchDiscoveryArtwork(context: Context, posters: List<String?>, adjacentHero: String? = null,
    portraitHero: Boolean = false, portraitPoster: Boolean = false) = coroutineScope {
    if (withContext(Dispatchers.IO) { discoveryArtworkPressure(context) }) return@coroutineScope
    val requests = posters.filterNotNull().distinct().take(8).map { it to false } + listOfNotNull(adjacentHero?.let { it to true })
    requests.map { (url, hero) -> async(Dispatchers.IO) {
        speculativeImages.withPermit {
            ensureActive()
            if (discoveryArtworkPressure(context)) return@withPermit
            try {
                context.imageLoader.execute(ImageRequest.Builder(context).data(thumbnailArtworkUrl(url, if (hero) { if (portraitPoster) 780 else 1280 } else 342))
                    .size(if (hero) { if (portraitPoster) 780 else 1280 } else 342, if (hero) { if (portraitPoster) 1170 else 720 } else 513)
                    .apply { if (hero) transformations(if (portraitHero) DiscoveryPhoneBackdropTransformation() else DiscoveryBackdropTransformation()) }.build())
            } catch (c: CancellationException) { throw c }
              catch (_: Exception) { /* Speculative artwork is optional. */ }
        }
    } }.awaitAll()
}
