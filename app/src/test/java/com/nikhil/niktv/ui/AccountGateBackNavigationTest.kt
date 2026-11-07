package com.nikhil.niktv.ui

import com.nikhil.niktv.model.PortalProfile
import com.nikhil.niktv.model.PortalSession
import org.junit.Assert.assertEquals
import org.junit.Test

class AccountGateBackNavigationTest {
    private val profile = PortalProfile("Fixture", "https://account-back.invalid", "")
    private val connected = NikTvState(session = PortalSession(profile, "fixture", profile.portalUrl, "", "", ""))

    @Test fun portalBackThenPeopleBackReachesRootWithoutReturningToPortals() {
        val portals = NikTvState(restoring = false)
        assertEquals(AccountGateBackAction.OPEN_PEOPLE, accountGateBackAction(false, false, true, portals))
        assertEquals(AccountGateBackAction.ROOT, accountGateBackAction(true, true, true, portals))
    }

    @Test fun explicitPeopleChooserCanReturnToAnExistingSessionOrOfflineDestination() {
        assertEquals(AccountGateBackAction.RESUME_CONTENT, accountGateBackAction(true, true, true, connected))
        assertEquals(AccountGateBackAction.RESUME_CONTENT,
            accountGateBackAction(true, true, true, NikTvState(offlineDownloadsOpen = true)))
    }

    @Test fun initialChooserAndUnavailableViewerDoNotResumeHiddenContent() {
        assertEquals(AccountGateBackAction.ROOT, accountGateBackAction(true, false, true, connected))
        assertEquals(AccountGateBackAction.ROOT, accountGateBackAction(true, true, false, connected))
    }

    @Test fun contentAndPortalEditorsKeepTheirOwnBackHandling() {
        assertEquals(AccountGateBackAction.ROOT, accountGateBackAction(false, false, true, connected))
        assertEquals(AccountGateBackAction.ROOT,
            accountGateBackAction(false, false, true, NikTvState(profileEditorOpen = true)))
        assertEquals(AccountGateBackAction.ROOT,
            accountGateBackAction(false, false, true, NikTvState(offlineDownloadsOpen = true)))
    }
}
