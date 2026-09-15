package com.nikhil.niktv.ui

/**
 * Owns one locally-created playback resource and guarantees that its release
 * action is executed at most once.
 */
internal class PlayerLease<T : Any>(
    val player: T,
    private val releasePlayer: (T) -> Unit
) {
    private var released = false

    fun releaseOnce() {
        if (released) return
        released = true
        releasePlayer(player)
    }
}

/**
 * Derive the active player from current ownership instead of storing a second,
 * independently remembered player reference.
 */
internal fun <T : Any> selectActivePlayer(
    castSessionActive: Boolean,
    castPlayer: T,
    localPlayer: T?
): T =
    if (castSessionActive) {
        castPlayer
    } else {
        requireNotNull(localPlayer) {
            "Local playback requires an owned local player"
        }
    }
