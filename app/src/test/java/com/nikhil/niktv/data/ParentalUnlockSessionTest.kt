package com.nikhil.niktv.data

import android.app.Application
import android.content.Context
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class ParentalUnlockSessionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        context.getSharedPreferences("parental_controls", Context.MODE_PRIVATE).edit().clear().commit()
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
    }
    @Test fun twentyFourHourUnlockSurvivesContextRecreationAndExpiresAtItsDeadline() {
        ParentalControls.setRelockMinutes(context, 1440)
        ParentalControls.unlock(context)
        ShadowSystemClock.advanceBy(Duration.ofHours(23))
        val recreated = context.createPackageContext(context.packageName, 0)
        assertTrue(ParentalControls.unlocked(recreated))
        ShadowSystemClock.advanceBy(Duration.ofHours(1))
        assertFalse(ParentalControls.unlocked(recreated))
    }
    @Test fun changingToTwentyFourHoursExtendsAnAuthenticatedUnlockInsteadOfLockingIt() {
        ParentalControls.unlock(context)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(4))
        ParentalControls.setRelockMinutes(context, 1440)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(6))
        assertTrue(ParentalControls.unlocked(context))
        ShadowSystemClock.advanceBy(Duration.ofHours(24))
        assertFalse(ParentalControls.unlocked(context))
    }
    @Test fun changingPinOrExplicitLockRevokesTheSession() {
        ParentalControls.unlock(context)
        ParentalControls.setPin(context, "1234")
        assertFalse(ParentalControls.unlocked(context))
        ParentalControls.unlock(context)
        ParentalControls.lock(context)
        assertFalse(ParentalControls.unlocked(context))
    }
    @Test fun rebootDoesNotReviveAnElapsedTimeDeadlineFromAnotherBoot() {
        ParentalControls.setRelockMinutes(context, 1440)
        ParentalControls.unlock(context)
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 8)
        assertFalse(ParentalControls.unlocked(context))
    }
    @Test fun changingAnIntervalWhileLockedDoesNotAuthorizeContent() {
        ParentalControls.setRelockMinutes(context, 1440)
        assertFalse(ParentalControls.unlocked(context))
    }
}
