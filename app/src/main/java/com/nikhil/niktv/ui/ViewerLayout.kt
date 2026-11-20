package com.nikhil.niktv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikhil.niktv.data.ProfileBrowsePreferences
import com.nikhil.niktv.data.UserScope
import com.nikhil.niktv.data.ViewerLayout

internal val LocalViewerLayout = staticCompositionLocalOf { ViewerLayout.STANDARD }

@Composable
internal fun rememberViewerLayout(owner: UserScope?) = key(owner) {
    val context = LocalContext.current.applicationContext
    remember(context, owner) { ProfileBrowsePreferences.observeLayout(context, owner) }
        .collectAsStateWithLifecycle(ProfileBrowsePreferences.layout(context, owner))
}
