package com.nikhil.niktv.ui

import android.content.Context
import android.view.SurfaceView
import org.videolan.libvlc.util.VLCVideoLayout
import kotlin.math.min

internal data class VlcCaptionProjection(val left: Float, val top: Float, val scale: Float)

/** Keep the entire authored plane inside the viewport without distorting glyphs. */
internal fun fitVlcCaptionPlane(
    sourceWidth: Int, sourceHeight: Int,
    viewportLeft: Float, viewportTop: Float, viewportWidth: Float, viewportHeight: Float
): VlcCaptionProjection {
    if (sourceWidth <= 0 || sourceHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0)
        return VlcCaptionProjection(viewportLeft, viewportTop, 0f)
    val scale = min(1f, min(viewportWidth / sourceWidth, viewportHeight / sourceHeight))
    return VlcCaptionProjection(viewportLeft + (viewportWidth - sourceWidth * scale) / 2f, viewportTop, scale)
}

/**
 * LibVLC 3.6.5 assigns the same LayoutParams to video and SPU and offers no
 * runtime subtitle-position API. Transform only the separate SPU SurfaceView.
 * Uniform fitting retains authored top/bottom placement and glyph aspect;
 * translating the full plane upward would clip authored top titles.
 */
internal class VlcCaptionSafeLayout(context: Context) : VLCVideoLayout(context) {
    private var controlsRequested = false
    private var controlsMounted = false
    private var fallbackTop = 0f
    private var fallbackBottom = 0f
    private var topControlsBottom = Float.NaN
    private var bottomControlsTop = Float.NaN
    private val windowLocation = IntArray(2)
    private val parentLocation = IntArray(2)

    fun setCaptionSafeArea(controlsShown: Boolean, fallbackTopDp: Int, fallbackBottomDp: Int) {
        controlsRequested = controlsShown
        fallbackTop = fallbackTopDp * resources.displayMetrics.density
        fallbackBottom = fallbackBottomDp * resources.displayMetrics.density
        projectCaptions()
    }

    fun setControlsMounted(mounted: Boolean) {
        controlsMounted = mounted
        projectCaptions()
    }

    fun setTopControlsBottom(bottomInWindow: Float) {
        topControlsBottom = bottomInWindow
        projectCaptions()
    }

    fun setBottomControlsTop(topInWindow: Float) {
        bottomControlsTop = topInWindow
        projectCaptions()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        projectCaptions()
    }

    private fun projectCaptions() {
        val subtitles = findViewById<SurfaceView>(org.videolan.R.id.surface_subtitles) ?: return
        val parent = subtitles.parent as? android.view.View ?: return
        if (width <= 0 || height <= 0 || subtitles.width <= 0 || subtitles.height <= 0) return
        val zoom = scaleY.takeIf { it > 0f } ?: 1f
        getLocationInWindow(windowLocation)
        parent.getLocationInWindow(parentLocation)
        // getLocationInWindow includes this view's zoom. Recover its original
        // clipping viewport, then convert that safe viewport into SPU-parent units.
        val windowLeft = windowLocation[0] - (1f - scaleX) * pivotX
        val windowTop = windowLocation[1] - (1f - zoom) * pivotY
        val windowBottom = windowTop + height
        val gap = 8f * resources.displayMetrics.density
        // AnimatedVisibility keeps the controls mounted during their exit fade.
        val controlsShown = controlsRequested || controlsMounted
        val safeTop = if (controlsShown) {
            if (topControlsBottom.isFinite()) (topControlsBottom + gap).coerceIn(windowTop, windowBottom)
            else windowTop + fallbackTop
        } else windowTop
        val safeBottom = if (controlsShown) {
            if (bottomControlsTop.isFinite()) (bottomControlsTop - gap).coerceIn(windowTop, windowBottom)
            else windowBottom - fallbackBottom
        } else windowBottom
        val projection = fitVlcCaptionPlane(
            subtitles.width, subtitles.height,
            (windowLeft - parentLocation[0]) / zoom,
            (safeTop - parentLocation[1]) / zoom,
            width / zoom,
            (safeBottom - safeTop).coerceAtLeast(1f) / zoom
        )
        subtitles.pivotX = 0f
        subtitles.pivotY = 0f
        subtitles.scaleX = projection.scale
        subtitles.scaleY = projection.scale
        subtitles.translationX = projection.left - subtitles.left
        subtitles.translationY = projection.top - subtitles.top
    }
}
