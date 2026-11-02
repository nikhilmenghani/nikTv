package com.nikhil.niktv.discovery

/** Remote actions commit on release, so the release cannot activate a new popup. */
internal class NativeTileActivation {
    enum class Action { NONE, CLICK, LONG_CLICK }
    private var key: Int? = null
    private var started = 0L
    private var held = false

    fun down(code: Int, time: Long, longPress: Boolean) {
        if (key == null) { key = code; started = time; held = false }
        if (key == code) held = held || longPress
    }

    fun up(code: Int, time: Long, canceled: Boolean, timeout: Long): Action {
        val valid = key == code && !canceled
        val long = held || time - started >= timeout
        cancel()
        return when {
            !valid -> Action.NONE
            long -> Action.LONG_CLICK
            else -> Action.CLICK
        }
    }

    fun cancel() { key = null; started = 0; held = false }
}
