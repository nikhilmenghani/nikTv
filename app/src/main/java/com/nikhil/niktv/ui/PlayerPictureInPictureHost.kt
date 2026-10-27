package com.nikhil.niktv.ui

/** Player chrome can be hosted outside MainActivity, e.g. standalone Sports. */
interface PlayerPictureInPictureHost {
    var pipModeListener: ((Boolean) -> Unit)?
    fun setPlayerActiveForPip(active: Boolean)
    fun enterPlayerPictureInPicture(): Boolean
}
