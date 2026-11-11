package com.nikhil.niktv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Fixed hero and toolbar geometry; only the supplied episode viewport scrolls. */
@Composable
internal fun SeriesDetailScaffold(header: @Composable () -> Unit, episodes: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        header()
        Box(Modifier.weight(1f).fillMaxWidth()) { episodes() }
    }
}
