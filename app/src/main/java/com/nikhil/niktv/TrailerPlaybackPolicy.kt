package com.nikhil.niktv

import android.app.SearchManager
import android.content.Intent
import android.net.Uri
import com.nikhil.niktv.data.providerTmdbTitle

internal fun trailerRequiresAlternativeSearch(error: Int?): Boolean = error in listOf(100, 101, 150)

internal fun trailerFailureMessage(error: Int): String = when (error) {
    100 -> "This YouTube trailer is unavailable or private. Choose Open with to search for another trailer."
    101, 150 -> "YouTube does not allow this trailer in embedded players. Choose Open with to search for another trailer."
    153 -> "YouTube could not verify the embedded player. Choose Open with to play the trailer in another app."
    else -> "YouTube could not play this trailer (error $error). Choose Open with to play it in another app."
}

internal fun trailerSearchQuery(title: String): String =
    listOf(providerTmdbTitle(title).replace(Regex("\\s+"), " ").trim(), "official trailer")
        .filter(String::isNotBlank).joinToString(" ")

/** SmartTube parses search_query from ACTION_VIEW, as do YouTube and browsers. */
internal fun externalTrailerIntent(title: String, videoKey: String?, search: Boolean = false): Intent {
    val query = trailerSearchQuery(title)
    val useSearch = videoKey.isNullOrBlank() || (search && title.isNotBlank())
    val uri = if (useSearch) Uri.Builder().scheme("https").authority("www.youtube.com")
        .path("/results").appendQueryParameter("search_query", query).build()
    else Uri.Builder().scheme("https").authority("www.youtube.com")
        .path("/watch").appendQueryParameter("v", videoKey).build()
    return Intent(Intent.ACTION_VIEW, uri)
        .putExtra(Intent.EXTRA_TITLE, title)
        .putExtra(SearchManager.QUERY, query)
}
