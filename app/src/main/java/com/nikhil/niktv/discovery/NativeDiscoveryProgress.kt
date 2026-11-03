package com.nikhil.niktv.discovery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/** Passive tile decoration: no themed/animated ProgressBar drawables to inflate per card. */
internal class NativeDiscoveryProgress(context: Context) : View(context) {
    private val paint = Paint()
    var fraction: Float? = null
        set(value) {
            val next = value?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
            if (field == next) return
            field = next
            visibility = if (next == null) INVISIBLE else VISIBLE
            invalidate()
        }

    init {
        visibility = INVISIBLE
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.ProgressBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(
            AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, 1f, fraction ?: 0f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val progress = fraction ?: return
        paint.color = 0x4DFFFFFF
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = Color.rgb(229, 9, 37)
        val filled = width * progress
        if (layoutDirection == LAYOUT_DIRECTION_RTL) {
            canvas.drawRect(width - filled, 0f, width.toFloat(), height.toFloat(), paint)
        } else {
            canvas.drawRect(0f, 0f, filled, height.toFloat(), paint)
        }
    }
}
