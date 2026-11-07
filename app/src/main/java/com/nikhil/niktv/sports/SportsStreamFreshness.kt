package com.nikhil.niktv.sports

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val SPORTS_EXPIRED_LINK_MESSAGE =
    "The provider is still supplying an expired stream link. Choose another source or try again later."

internal class SportsExpiredLinkException : java.io.IOException(SPORTS_EXPIRED_LINK_MESSAGE)

/** Read the published expiry; a client cannot extend or recreate a provider's signature. */
internal fun sportsSignedLinkExpired(link: String, now: Long = System.currentTimeMillis()): Boolean {
    val url = link.toHttpUrlOrNull() ?: return false
    return listOf("hdnea", "hdnts").any { name ->
        url.queryParameterValues(name).filterNotNull().any { token ->
            Regex("(?:^|~)exp=(\\d+)(?:~|$)").find(token)?.groupValues?.get(1)?.toLongOrNull()
                ?.let { it <= now / 1000 } == true
        }
    }
}
