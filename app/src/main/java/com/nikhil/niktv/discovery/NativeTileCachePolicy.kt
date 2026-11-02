package com.nikhil.niktv.discovery

/** A rendering cache is expendable; focus/navigation never depend on it. */
internal class NativeTileCachePolicy(private val requested: Boolean) {
    private var foreground = false
    private var pressured = false
    val enabled get() = requested && foreground && !pressured
    fun windowFocus(focused: Boolean) { foreground = focused }
    fun memoryPressure() { pressured = true }
}
