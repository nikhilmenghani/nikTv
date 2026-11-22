package com.nikhil.niktv.data

import android.app.Application
import android.content.Intent
import com.nikhil.niktv.sports.PlayfyActivity
import com.nikhil.niktv.sports.SportsActivity
import com.nikhil.niktv.sports.openPlayfy
import com.nikhil.niktv.sports.openSports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class PlayfyPortalNavigationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val users get() = UserSyncStore.get(context)
    private fun owner(type: UserProfileType) = UserScope("101", UserSyncStore.defaultProfileId("101", type))

    @Before fun setup() = runBlocking {
        withContext(Dispatchers.IO) { users.db.clearAllTables() }
        users.disconnect()
        users.selectAccount("101")
        users.initializeDefaults("101")
        users.selectProfile(owner(UserProfileType.GENERAL).profileId)
        Unit
    }

    @Test fun portalEntryIntentsUseSeparateActivities() {
        context.openPlayfy()
        val playfy = shadowOf(context).nextStartedActivity
        assertEquals(PlayfyActivity::class.java.name, playfy.component?.className)
        assertTrue(playfy.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        context.openSports()
        assertEquals(SportsActivity::class.java.name, shadowOf(context).nextStartedActivity.component?.className)
    }

    @Test fun rememberedPlayfyDoesNotReplaceSportsOrAnotherPersonsDestination() = runBlocking {
        val general = owner(UserProfileType.GENERAL)
        val kids = owner(UserProfileType.KIDS)
        users.rememberDestination(general, ViewerDestination(ViewerDestinationKind.PORTAL, "local-connection"))
        users.rememberDestination(general, ViewerDestination(ViewerDestinationKind.PLAYFY))
        users.rememberDestination(kids, ViewerDestination(ViewerDestinationKind.SPORTS))
        assertEquals(ViewerDestination(ViewerDestinationKind.PLAYFY), users.rememberedDestination(general))
        assertEquals("local-connection", users.rememberedPortalKey(general))
        assertEquals(ViewerDestination(ViewerDestinationKind.SPORTS), users.rememberedDestination(kids))
    }

    @Test fun profileVisibilityCanHideEitherProviderIndependently() = runBlocking {
        val general = owner(UserProfileType.GENERAL)
        val kids = owner(UserProfileType.KIDS)
        ProfilePortalAccess.setAllowed(context, general, ProfilePortalAccess.PLAYFY, false)
        assertTrue(ProfilePortalAccess.PLAYFY in ProfilePortalAccess.hidden(context, general))
        assertFalse(ProfilePortalAccess.SPORTS in ProfilePortalAccess.hidden(context, general))
        assertTrue(ProfilePortalAccess.hidden(context, kids).isEmpty())
        ProfilePortalAccess.setAllowed(context, general, ProfilePortalAccess.PLAYFY, true)
        ProfilePortalAccess.setAllowed(context, general, ProfilePortalAccess.SPORTS, false)
        assertFalse(ProfilePortalAccess.PLAYFY in ProfilePortalAccess.hidden(context, general))
        assertTrue(ProfilePortalAccess.SPORTS in ProfilePortalAccess.hidden(context, general))
    }

    @Test fun setupCanStartInPlayfyWithoutAConnectionOrSportsSelection() {
        val profile = UserProfile(owner(UserProfileType.GENERAL).profileId, "Viewer")
        val playfy = ProfileSetupDraft(profile, "101", selected = setOf(ProfilePortalAccess.PLAYFY))
        assertTrue(playfy.canOpen)
        assertEquals(ProfilePortalAccess.PLAYFY, playfy.start)
        val both = playfy.copy(selected = setOf(ProfilePortalAccess.PLAYFY, ProfilePortalAccess.SPORTS), startingPortal = ProfilePortalAccess.PLAYFY)
        assertEquals(ProfilePortalAccess.PLAYFY, both.start)
    }

    @Test fun savingPlayfyOnlySetupRestoresItsOwnStartingPortalAndVisibility() = runBlocking {
        val general = owner(UserProfileType.GENERAL)
        val profile = UserProfile(general.profileId, "Viewer")
        ProfileSetupStore.save(context, ProfileSetupDraft(profile, "101", existing = true,
            selected = setOf(ProfilePortalAccess.PLAYFY), startingPortal = ProfilePortalAccess.PLAYFY))
        assertEquals(ViewerDestination(ViewerDestinationKind.PLAYFY), users.rememberedDestination(general))
        assertTrue(ProfilePortalAccess.SPORTS in ProfilePortalAccess.hidden(context, general))
        assertFalse(ProfilePortalAccess.PLAYFY in ProfilePortalAccess.hidden(context, general))
        val restored = ProfileSetupStore.load(context, profile)
        assertEquals(ProfilePortalAccess.PLAYFY, restored.start)
        assertTrue(ProfilePortalAccess.PLAYFY in restored.selected)
        assertFalse(ProfilePortalAccess.SPORTS in restored.selected)
    }
}
