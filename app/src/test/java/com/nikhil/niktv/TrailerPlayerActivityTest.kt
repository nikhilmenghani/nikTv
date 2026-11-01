package com.nikhil.niktv

import android.app.Application
import android.app.SearchManager
import android.content.Intent
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import org.robolectric.RuntimeEnvironment
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class TrailerPlayerActivityTest {
    private lateinit var controller: ActivityController<TrailerPlayerActivity>
    private lateinit var activity: TrailerPlayerActivity
    @Before fun start() {
        val intent = Intent(RuntimeEnvironment.getApplication(), TrailerPlayerActivity::class.java)
            .putExtra("video_key", "abcdefghijk").putExtra("title", "Fixture trailer")
        controller = Robolectric.buildActivity(TrailerPlayerActivity::class.java, intent).setup().visible()
        activity = controller.get()
    }
    @After fun stop() { controller.pause().stop().destroy() }
    private fun field(name: String): Any? = TrailerPlayerActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun bridge(method: String, state: Int? = null) {
        val type = TrailerPlayerActivity::class.java.declaredClasses.single { it.simpleName == "PlayerBridge" }
        val bridge = type.declaredConstructors.single().apply { isAccessible = true }.newInstance(activity)
        val target = if (state == null) type.getDeclaredMethod(method) else type.getDeclaredMethod(method, Int::class.javaPrimitiveType)
        target.isAccessible = true
        if (state == null) target.invoke(bridge) else target.invoke(bridge, state)
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun startupAndFirstPlayingFrameKeepControlsHidden() {
        assertEquals(View.GONE, (field("footer") as View).visibility)
        assertEquals(View.VISIBLE, (field("loadingProgress") as View).visibility)
        bridge("ready")
        assertEquals(View.GONE, (field("footer") as View).visibility)
        assertEquals(View.VISIBLE, (field("loadingProgress") as View).visibility)
        bridge("state", 1)
        assertEquals(View.GONE, (field("loadingProgress") as View).visibility)
        assertEquals(View.GONE, (field("footer") as View).visibility)
    }
    @Test fun inheritedRemoteRepeatDoesNotRevealChromeButFreshPressDoesWithoutPausing() {
        bridge("ready"); bridge("state", 1)
        activity.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(View.GONE, (field("footer") as View).visibility)
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(View.VISIBLE, (field("footer") as View).visibility)
        assertEquals(true, field("playbackRequested"))
        activity.onBackPressedDispatcher.onBackPressed()
        assertEquals(View.GONE, (field("footer") as View).visibility)
        assertFalse(activity.isFinishing)
    }
    @Test fun captionsStartOffAndUserCanToggleThemBothWays() {
        bridge("ready")
        val button = field("captions") as Button
        assertEquals("CC Off", button.text.toString())
        button.performClick()
        assertEquals("CC On", button.text.toString())
        button.performClick()
        assertEquals("CC Off", button.text.toString())
    }

    private fun externalTarget(): Intent {
        (field("external") as Button).performClick()
        val chooser = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        return chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
    }

    @Test fun restrictedOrPrivateTrailerSearchesByTitleWithExternalAppQuery() {
        activity.intent.putExtra("title", "Chashme Baddoor")
        listOf(100, 101, 150).forEach { code ->
            bridge("error", code)
            val target = externalTarget()
            assertEquals(Intent.ACTION_VIEW, target.action)
            assertEquals("/results", target.data?.path)
            assertNull(target.data?.getQueryParameter("v"))
            assertEquals("Chashme Baddoor official trailer", target.getStringExtra(SearchManager.QUERY))
            assertEquals("Chashme Baddoor official trailer", target.data?.getQueryParameter("search_query"))
            assertNull(target.data?.getQueryParameter("v"))
        }
    }

    @Test fun workingTrailerOpenWithStillTargetsTheExactVideo() {
        bridge("ready"); bridge("state", 1)
        val target = externalTarget()
        assertEquals("/watch", target.data?.path)
        assertEquals("abcdefghijk", target.data?.getQueryParameter("v"))
        assertEquals("abcdefghijk", target.data?.getQueryParameter("v"))
    }

    @Test fun networkOrPlayerIdentityFailureKeepsDirectVideoFallback() {
        bridge("error", 153)
        assertEquals("abcdefghijk", externalTarget().data?.getQueryParameter("v"))
    }

    @Test fun fallbackSearchPreservesTitleCharactersAndRemovesProviderLabels() {
        val title = "English | Léon & Amélie (FHD)"
        val target = externalTrailerIntent(title, null)
        assertEquals("Léon & Amélie official trailer", target.data?.getQueryParameter("search_query"))
        assertEquals("Léon & Amélie official trailer", target.data?.getQueryParameter("search_query"))
        assertEquals(title, target.getStringExtra(Intent.EXTRA_TITLE))
    }

    @Test fun errorMessagesDistinguishMissingVideoFromEmbeddingRestriction() {
        assertTrue(trailerFailureMessage(100).contains("private"))
        assertTrue(trailerFailureMessage(150).contains("embedded players"))
        assertTrue(trailerFailureMessage(153).contains("verify"))
    }
}
