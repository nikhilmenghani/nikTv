package com.nikhil.niktv.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.nikhil.niktv.data.UserScope

/** The authenticated viewer boundary shared by browse, search, recommendation, playback
 * and offline destinations. A future content policy can be supplied at this boundary;
 * profile type is stored independently of labels. This phase applies no restrictions. */
internal val LocalViewingOwner = staticCompositionLocalOf<UserScope?> { null }
