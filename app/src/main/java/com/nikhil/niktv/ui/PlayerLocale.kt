package com.nikhil.niktv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources

/** Follow Compose configuration changes instead of reading a process-wide locale snapshot. */
@Composable
internal fun rememberPlayerLocale(): java.util.Locale {
    val locales = LocalResources.current.configuration.locales
    return remember(locales) { locales[0] ?: java.util.Locale.getDefault() }
}
