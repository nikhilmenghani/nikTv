package com.nikhil.niktv.model

private val cooldownPrefixes = listOf(
    "Portal requests are paused for ",
    "Portal protection is active. Wait ",
    "The portal is temporarily rate-limiting requests. Wait "
)
private val waitDuration = Regex("^(?:(?:\\d+h\\s+)?(?:\\d+m\\s+)?\\d+s\\b|\\d+\\s+seconds?\\b)")

internal fun isPortalCooldownDiagnostic(message: String): Boolean = cooldownPrefixes.any(message::startsWith)

/** Render from the original provider deadline, not from when the popup was opened. */
internal fun portalDiagnosticsCountdown(message: String, deadlineMillis: Long?, nowMillis: Long): String {
    val prefix = cooldownPrefixes.firstOrNull(message::startsWith) ?: return message
    if (deadlineMillis == null) return message
    val tail = message.removePrefix(prefix)
    val duration = waitDuration.find(tail) ?: return message
    if (deadlineMillis <= nowMillis) return "The provider’s requested wait has ended. You can try again."
    val seconds = (deadlineMillis - nowMillis + 999L) / 1000L
    return prefix + providerSearchWaitLabel(seconds) + tail.substring(duration.range.last + 1)
}
