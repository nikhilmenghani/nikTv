package com.nikhil.niktv.model

/** Provider file references live only for the current playback, never in synced history. */
data class PortalPlaybackVariant(
    val id: String,
    val label: String,
    val height: Int,
    val language: String,
    val command: String
)

data class PortalPlaybackResolution(
    val url: String,
    val variants: List<PortalPlaybackVariant> = emptyList(),
    val selectedId: String? = null,
    val preferredHeight: Int = 0
)
