package com.nikhil.niktv.discovery

/** Imperative view ownership, without turning native focus into feed Compose state. */
internal class NativeDiscoveryFocusRestorer {
    private var owner: Any? = null
    private var request: (() -> Boolean)? = null

    fun attach(owner: Any, request: () -> Boolean) { this.owner = owner; this.request = request }
    fun detach(owner: Any) { if (this.owner === owner) { this.owner = null; request = null } }
    fun restore(): Boolean = request?.invoke() ?: false
}
