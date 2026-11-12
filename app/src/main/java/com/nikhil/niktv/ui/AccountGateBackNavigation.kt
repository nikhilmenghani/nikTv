package com.nikhil.niktv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

internal enum class AccountGateBackAction { ROOT, OPEN_PEOPLE, RESUME_CONTENT }

/** Unavailable content returns to people; connection editors retain their own Back handling. */
internal fun accountGateBackAction(
    peopleVisible: Boolean,
    choosingProfile: Boolean,
    selectedViewerAvailable: Boolean,
    state: NikTvState
): AccountGateBackAction = when {
    peopleVisible -> if (choosingProfile && selectedViewerAvailable &&
        (state.session != null || state.offlineDownloadsOpen)) AccountGateBackAction.RESUME_CONTENT else AccountGateBackAction.ROOT
    selectedViewerAvailable && state.session == null && !state.profileEditorOpen && !state.offlineDownloadsOpen -> AccountGateBackAction.OPEN_PEOPLE
    else -> AccountGateBackAction.ROOT
}

/** Shared by the real gate and isolated remote regressions; child editors still own Back. */
@Composable
internal fun AccountGateBackHandler(
    peopleVisible: Boolean,
    choosingProfile: Boolean,
    selectedViewerAvailable: Boolean,
    state: NikTvState,
    openPeople: () -> Unit,
    resumeContent: () -> Unit
) {
    val action = accountGateBackAction(peopleVisible, choosingProfile, selectedViewerAvailable, state)
    BackHandler(enabled = action != AccountGateBackAction.ROOT) {
        when (action) {
            AccountGateBackAction.OPEN_PEOPLE -> openPeople()
            AccountGateBackAction.RESUME_CONTENT -> resumeContent()
            AccountGateBackAction.ROOT -> Unit
        }
    }
}
