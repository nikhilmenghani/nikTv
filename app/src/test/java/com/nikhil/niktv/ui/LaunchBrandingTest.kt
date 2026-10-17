package com.nikhil.niktv.ui

import android.app.Application
import android.util.TypedValue
import com.nikhil.niktv.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class LaunchBrandingTest {
    @Test fun hostPlatformSplashUsesNikTvLogo() {
        val theme = RuntimeEnvironment.getApplication().resources.newTheme()
        theme.applyStyle(R.style.Theme_NikTv, true)
        val icon = TypedValue()
        assertTrue(theme.resolveAttribute(android.R.attr.windowSplashScreenAnimatedIcon, icon, true))
        assertEquals(R.drawable.niktv_logo_foreground, icon.resourceId)
    }

    @Test fun everyRememberedFlowSplashVariantKeepsHostBrandingAndFlowContentTheme() {
        val resources = RuntimeEnvironment.getApplication().resources
        val styles = R.style::class.java.fields.filter { it.name.startsWith("Theme_Flow_Starting") }
        assertTrue(styles.size > 3)
        for (style in styles) {
            val theme = resources.newTheme().apply { applyStyle(style.getInt(null), true) }
            val value = TypedValue()
            assertTrue(style.name, theme.resolveAttribute(R.attr.windowSplashScreenAnimatedIcon, value, true))
            assertEquals(style.name, R.drawable.niktv_logo_foreground, value.resourceId)
            assertTrue(style.name, theme.resolveAttribute(R.attr.postSplashScreenTheme, value, true))
            assertEquals(style.name, R.style.Theme_Flow, value.resourceId)
        }
    }
}
