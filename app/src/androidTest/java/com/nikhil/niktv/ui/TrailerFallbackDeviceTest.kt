package com.nikhil.niktv.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.webkit.WebView
import android.widget.Button
import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.TrailerPlayerActivity
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/** Block the chooser and live web content: test the real remote/error UI without playing media. */
class TrailerFallbackDeviceTest {
    @Test fun restrictedTrailerFocusesOpenWithAndHandsOffTheMovieSearch() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".regression"))
        instrumentation.setInTouchMode(false)
        val target = AtomicReference<Intent?>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                @Suppress("DEPRECATION")
                target.set(intent.getParcelableExtra(Intent.EXTRA_INTENT))
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        val activity = instrumentation.startActivitySync(Intent(context, TrailerPlayerActivity::class.java)
            .putExtra("video_key", "abcdefghijk").putExtra("title", "Chashme Baddoor")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TrailerPlayerActivity
        fun field(name: String): Any? = TrailerPlayerActivity::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(activity)
        try {
            instrumentation.runOnMainSync {
                (field("webView") as? WebView)?.apply { stopLoading(); loadUrl("about:blank") }
            }
            val deadline = SystemClock.uptimeMillis() + 6000
            while (!activity.hasWindowFocus() && SystemClock.uptimeMillis() < deadline) Thread.sleep(30)
            instrumentation.runOnMainSync {
                val type = TrailerPlayerActivity::class.java.declaredClasses.single { it.simpleName == "PlayerBridge" }
                val bridge = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(activity)
                type.getDeclaredMethod("error", Int::class.javaPrimitiveType).apply { isAccessible = true }.invoke(bridge, 150)
                val external = field("external") as Button
                assertEquals(View.VISIBLE, (field("footer") as View).visibility)
                assertTrue("Remote focus must land on Open with after the restriction", external.hasFocus())
                external.performClick()
            }
            val chosen = target.get()
            assertNotNull("External chooser intent must be dispatched", chosen)
            assertEquals(Intent.ACTION_VIEW, chosen!!.action)
            assertEquals("/results", chosen.data?.path)
            assertEquals("Chashme Baddoor official trailer", chosen.data?.getQueryParameter("search_query"))
            assertNull("Do not forward the failed/private video ID", chosen.data?.getQueryParameter("v"))
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
