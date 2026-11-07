package com.nikhil.niktv.ui

import android.graphics.Bitmap
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** Opt-in audit evidence from isolated UI fixtures; never captures another app. */
internal object NavigationAuditEvidence {
    fun capture(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return
        if (root.packageName?.toString() != context.packageName) return
        val folder = File(context.externalCacheDir, "navigation-audit").apply { mkdirs() }
        val name = label.replace(Regex("[^a-zA-Z0-9_-]"), "-")
        val image = instrumentation.uiAutomation.takeScreenshot() ?: return
        try { File(folder, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { image.recycle() }
        val lines = mutableListOf<String>()
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            node ?: return
            val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
            val label = node.text ?: node.contentDescription
            if (label != null || node.isFocusable) lines += "${" ".repeat(depth)}$label bounds=$bounds focused=${node.isFocused} clickable=${node.isClickable}"
            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
        }
        visit(root, 0)
        File(folder, "$name.txt").writeText(lines.joinToString("\n"))
    }
}
