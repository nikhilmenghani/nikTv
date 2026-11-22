package com.nikhil.niktv.sports

internal enum class SportsServerHealth(val title: String) {
    UNCHECKED("Not checked"), CHECKING("Checking"), AVAILABLE("Available"), UNAVAILABLE("Unavailable")
}

internal data class SportsServer(val base: String, val health: SportsServerHealth = SportsServerHealth.UNCHECKED) {
    val label: String get() = base.removePrefix("https://").trimEnd('/')
}

internal data class SportsServersState(
    val servers: List<SportsServer> = emptyList(),
    val preferred: String? = null,
    val active: String? = null,
    val checking: Boolean = false,
)
