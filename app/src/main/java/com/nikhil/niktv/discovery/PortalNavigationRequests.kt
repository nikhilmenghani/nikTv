package com.nikhil.niktv.discovery

/** Portal identity only; credentials and switching remain owned by the main app. */
internal object PortalNavigationRequests {
    const val EXTRA_PORTAL = "niktv.open_portal"
    const val PROFILE_CHOOSER = "profile-chooser"
    val pending = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
}
