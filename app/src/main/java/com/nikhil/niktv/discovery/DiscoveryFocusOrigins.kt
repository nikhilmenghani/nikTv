package com.nikhil.niktv.discovery

import androidx.compose.runtime.saveable.listSaver
import com.nikhil.niktv.model.DashboardSurface

/** Model commits precede composition disposal; obsolete focus callbacks must not publish. */
internal fun DiscoveryState.ownsFocusRoute(profile: String, surface: DashboardSurface, route: String): Boolean =
    this.profile == profile && this.surface == surface && (detail?.key ?: "landing") == route

/** String route identities can repeat after Back; only the current composition may publish. */
internal class DiscoveryFocusRouteOwner {
    private var current: Any? = null
    fun claim(token: Any) { current = token }
    fun owns(token: Any) = current === token
    fun release(token: Any) { if (owns(token)) current = null }
}

/** Permit explicit entry and retain eligibility until the parked target actually loses focus. */
internal class DiscoveryFocusParking {
    private var requests = 0
    private var focused = false
    val canFocus: Boolean get() = requests > 0 || focused
    fun focusChanged(focused: Boolean) { this.focused = focused }
    fun request(block: () -> Boolean): Boolean {
        requests++
        return try { block() } finally { requests-- }
    }
}

/** Restore destinations are event bookkeeping, not state that changes the screen's drawing. */
internal class DiscoveryFocusOrigins(
    var returningTo: String? = null,
    var routes: Map<String, String> = emptyMap(),
) {
    fun saved(): List<String> = listOf(returningTo.orEmpty()) + routes.flatMap { listOf(it.key, it.value) }

    companion object {
        fun restored(values: List<String>): DiscoveryFocusOrigins = DiscoveryFocusOrigins(
            values.firstOrNull()?.takeIf(String::isNotEmpty),
            values.drop(1).chunked(2).mapNotNull { pair ->
                if (pair.size == 2) pair[0] to pair[1] else null
            }.toMap(),
        )
        val Saver = listSaver<DiscoveryFocusOrigins, String>(save = { it.saved() }, restore = ::restored)
    }
}

/** Capture a return destination before a recomposed lazy list can publish automatic focus. */
internal class DiscoveryFocusRestoration(val target: String?) {
    var pending = target != null
        private set
    var canceled = false
        private set

    fun accepts(key: String) = !pending || key == target
    fun finish() { pending = false }
    fun cancel() { canceled = true; pending = false }
}
