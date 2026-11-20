package com.nikhil.niktv.ui

import java.util.IdentityHashMap

/** Main-thread view ownership. A retiring view may never detach a player rebound elsewhere. */
internal class PlaybackSurfaceBindings<Player : Any, View : Any> {
    private val byView = IdentityHashMap<View, Player>()
    private val byPlayer = IdentityHashMap<Player, View>()
    fun player(view: View): Player? = byView[view]
    fun bind(view: View, player: Player) {
        byView.put(view, player)?.let { old -> if (byPlayer[old] === view) byPlayer.remove(old) }
        byPlayer.put(player, view)?.let { old -> if (byView[old] === player) byView.remove(old) }
    }
    fun release(view: View): Player? {
        val player = byView.remove(view) ?: return null
        if (byPlayer[player] !== view) return null
        byPlayer.remove(player)
        return player
    }
}
