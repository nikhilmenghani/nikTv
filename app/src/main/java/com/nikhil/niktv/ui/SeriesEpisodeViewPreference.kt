package com.nikhil.niktv.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nikhil.niktv.data.SeriesEpisodeViewPreferences
import com.nikhil.niktv.data.UserSyncStore

@Composable
internal fun rememberSeriesEpisodeListView(defaultListView: Boolean): Pair<Boolean, (Boolean) -> Unit> {
    val context = LocalContext.current.applicationContext
    val users = remember(context) { UserSyncStore.get(context) }
    val owner = users.selectedScope.collectAsStateWithLifecycle().value
    var listView by remember(context, owner, defaultListView) {
        mutableStateOf(SeriesEpisodeViewPreferences.listView(context, owner, defaultListView))
    }
    return listView to { list ->
        listView = list
        SeriesEpisodeViewPreferences.setListView(context, owner, list)
    }
}
