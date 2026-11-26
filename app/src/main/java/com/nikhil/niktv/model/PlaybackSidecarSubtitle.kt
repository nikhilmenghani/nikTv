package com.nikhil.niktv.model

/** A playback-owned caption source. Its URL must never enter saved history or sync. */
data class PlaybackSidecarSubtitle(
    val id: String,
    val label: String,
    val language: String? = null,
    val url: String,
    val fileName: String
) {
    override fun toString(): String = "PlaybackSidecarSubtitle(id=$id, label=$label, language=$language)"
}
