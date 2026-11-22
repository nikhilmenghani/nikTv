package com.nikhil.niktv.sports

/** A separate portal and Activity instance; shares only the Sports presentation/player shell. */
class PlayfyActivity : SportsActivity() {
    override val isPlayfyPortal: Boolean = true
}
