package com.nikhil.niktv.torrent

import android.util.Log
import com.nikhil.niktv.BuildConfig

/** Enabled only in the explicit local minified smoke build; shipping APKs omit it. */
internal inline fun traceTorrentFocus(message: () -> String) {
    // Routine debug logs are removed by the release rules. This opt-in trace
    // must survive minification; its entire branch is absent in shipping APKs.
    if (BuildConfig.TORRENT_FOCUS_DIAGNOSTICS) Log.w("NikTvTorrentFocus", message())
}
