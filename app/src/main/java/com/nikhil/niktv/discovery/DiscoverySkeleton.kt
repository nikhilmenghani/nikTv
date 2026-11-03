package com.nikhil.niktv.discovery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.nikhil.niktv.model.Category
import com.nikhil.niktv.model.TmdbHomeSection

/** Deferred requests count as pending; cached/empty responses and failures do not. */
internal fun discoverySectionSkeleton(section: TmdbHomeSection, state: DiscoveryState): Boolean =
    section !in state.feeds && "feed:${section.name}" !in state.errors

internal fun discoveryProviderSkeleton(profile: String, category: Category, state: ProviderDiscoveryState): Boolean {
    if (state.profile != profile) return true
    if (category.type in state.errors) return false
    val pages = providerDiscoverySources(listOf(category)).map { state.pages[category.type]?.get(it.id) }
    if (pages.any { it?.items?.isNotEmpty() == true }) return false
    return !state.ready || category.type in state.loading || pages.any { it == null || it.page == 0 }
}

internal fun discoverySkeletonSlots(viewport: Int, cardWidth: Int, gap: Int): Int =
    if (viewport <= 0 || cardWidth <= 0) 0 else (viewport / (cardWidth + gap.coerceAtLeast(0)) + 1).coerceIn(1, 12)

/** One bounded, non-interactive drawing per visible loading row; no timer/layers/images. */
internal class DiscoveryRowSkeleton(context: Context, private val cardWidth: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private fun dp(value: Float) = value * resources.displayMetrics.density
    init {
        isFocusable = false; isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    override fun hasOverlappingRendering() = false
    override fun onDraw(canvas: Canvas) {
        val inset = dp(8f); val top = dp(9f); val bottom = height - dp(14f)
        if (bottom <= top) return
        fun box(left: Float, y: Float, right: Float, end: Float, color: Int, radius: Float) {
            paint.color = color; rect.set(left, y, right, end); canvas.drawRoundRect(rect, radius, radius, paint)
        }
        repeat(discoverySkeletonSlots(width, cardWidth, dp(12f).toInt())) { index ->
            val left = inset + index * (cardWidth + dp(12f)); val right = left + cardWidth - dp(8f)
            box(left, top, right, bottom, 0xFF171D27.toInt(), dp(8f))
            val inner = left + dp(9f); val available = right - inner - dp(9f)
            box(inner, bottom - dp(33f), inner + available * .78f, bottom - dp(25f), 0xFF303947.toInt(), dp(3f))
            box(inner, bottom - dp(18f), inner + available * .52f, bottom - dp(12f), 0xFF272F3D.toInt(), dp(3f))
        }
    }
}
