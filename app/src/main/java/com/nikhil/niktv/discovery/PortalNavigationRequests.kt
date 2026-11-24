package com.nikhil.niktv.discovery

import android.content.Intent

/** Portal identity only; credentials and switching remain owned by the main app. */
internal object PortalNavigationRequests {
    const val EXTRA_PORTAL = "niktv.open_portal"
    const val EXTRA_RESTORE_FOCUS = "niktv.restore_portal_focus"
    const val PROFILE_CHOOSER = "profile-chooser"
    class Request(val key: String, val restoreFocus: Boolean = false)
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Request?>(null)

    fun receive(intent: Intent) {
        val key = intent.getStringExtra(EXTRA_PORTAL) ?: return
        val restoreFocus = intent.getBooleanExtra(EXTRA_RESTORE_FOCUS, false)
        intent.removeExtra(EXTRA_PORTAL)
        intent.removeExtra(EXTRA_RESTORE_FOCUS)
        pending.value = Request(key, restoreFocus)
    }
}
