package com.nikhil.niktv.model

import kotlinx.serialization.Serializable

@Serializable
data class PortalProfile(
    val name: String,
    val portalUrl: String,
    val macAddress: String,
    val serialNumber: String = "",
    val portalType: PortalType = PortalType.STALKER,
    val username: String = "",
    val password: String = ""
)

@Serializable
enum class PortalType { STALKER, XTREAM }

enum class CatalogType(val title: String, val apiType: String) {
    LIVE_TV("Live TV", "itv"), MOVIES("Movies", "vod"), SERIES("Series", "series"), RADIO("Radio", "radio")
}

data class Category(val id: String, val title: String, val type: CatalogType)
data class MediaItem(val id: String, val title: String, val logo: String?, val command: String?, val description: String? = null)

@Serializable
data class PortalSession(
    val profile: PortalProfile,
    val token: String,
    val endpointUrl: String,
    val serialNumber: String,
    val metrics: String,
    val hardwareVersion2: String,
    val random: String? = null
)
