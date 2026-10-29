package com.nikhil.niktv.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nikhil.niktv.discovery.DiscoveryAction
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.PlayingMedia
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal enum class IptvFlagReason { MANUAL, PLAYBACK_FAILED }
@Serializable
internal data class IptvChannelFlag(val reason: IptvFlagReason, val updatedAt: Long) {
    val label: String get() = if (reason == IptvFlagReason.MANUAL) "Flagged unavailable" else "Previously failed"
}
internal enum class IptvAvailabilityFilter(val title: String) {
    ALL("All channels"), HIDE_FLAGGED("Hide flagged"), FLAGGED_ONLY("Flagged only");
    fun includes(flag: IptvChannelFlag?): Boolean = when (this) {
        ALL -> true
        HIDE_FLAGGED -> flag == null
        FLAGGED_ONLY -> flag != null
    }
}
internal val IptvAvailabilityAmber = Color(0xFFF3BE62)
internal fun iptvFlagDescription(flag: IptvChannelFlag): String {
    val at = java.time.format.DateTimeFormatter.ofPattern("d MMM · HH:mm").withZone(java.time.ZoneId.systemDefault())
        .format(java.time.Instant.ofEpochMilli(flag.updatedAt))
    return if (flag.reason == IptvFlagReason.MANUAL) "Flagged by you · $at. You can still try this channel."
        else "Playback failed · $at. Sources may recover; you can retry anytime."
}

/** Profile-scoped local observations. Never stores provider URLs, credentials, media or player settings. */
internal class IptvAvailabilityStore(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    internal val prefs = context.applicationContext.getSharedPreferences("iptv_channel_availability_v1", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private fun key(profile: String): String = MessageDigest.getInstance("SHA-256").digest(profile.toByteArray())
        .joinToString("") { "%02x".format(it) }
    fun flags(profile: String): Map<String, IptvChannelFlag> = if (profile.isBlank()) emptyMap() else runCatching {
        json.decodeFromString<Map<String, IptvChannelFlag>>(prefs.getString("${key(profile)}:flags", "{}")!!)
    }.getOrDefault(emptyMap())
    fun filter(profile: String): IptvAvailabilityFilter = runCatching {
        IptvAvailabilityFilter.valueOf(prefs.getString("${key(profile)}:filter", "").orEmpty())
    }.getOrDefault(IptvAvailabilityFilter.ALL)
    fun setFilter(profile: String, value: IptvAvailabilityFilter) {
        if (profile.isNotBlank()) prefs.edit().putString("${key(profile)}:filter", value.name).apply()
    }
    fun flag(profile: String, id: String, reason: IptvFlagReason) {
        if (profile.isBlank() || id.isBlank()) return
        val old = flags(profile)
        if (reason == IptvFlagReason.PLAYBACK_FAILED && old[id]?.reason == IptvFlagReason.MANUAL) return
        save(profile, old + (id to IptvChannelFlag(reason, now())))
    }
    fun clear(profile: String, id: String, automaticOnly: Boolean = false) {
        val old = flags(profile)
        if (profile.isBlank() || id !in old || (automaticOnly && old[id]?.reason == IptvFlagReason.MANUAL)) return
        save(profile, old - id)
    }
    fun observe(profile: String, active: PlayingMedia?, observed: PlayingMedia, failed: Boolean) {
        if (profile.isBlank() || active == null || active.profileKey != profile || observed.profileKey != profile ||
            active.playbackAttemptId != observed.playbackAttemptId || active.media.id != observed.media.id ||
            active.catalogType != CatalogType.LIVE_TV || observed.catalogType != CatalogType.LIVE_TV ||
            active.offlinePlayback || observed.offlinePlayback || active.alternateSource != null || observed.alternateSource != null) return
        if (failed) flag(profile, observed.media.id, IptvFlagReason.PLAYBACK_FAILED)
        else clear(profile, observed.media.id, automaticOnly = true)
    }
    private fun save(profile: String, flags: Map<String, IptvChannelFlag>) {
        prefs.edit().putString("${key(profile)}:flags", json.encodeToString(flags)).apply()
    }
}

@Stable
internal class IptvAvailabilityState(private val store: IptvAvailabilityStore, val profile: String) {
    var flags by mutableStateOf(store.flags(profile)); private set
    var filter by mutableStateOf(store.filter(profile)); private set
    fun reload() { flags = store.flags(profile); filter = store.filter(profile) }
    fun toggle(id: String) {
        if (id in flags) store.clear(profile, id) else store.flag(profile, id, IptvFlagReason.MANUAL)
        reload()
    }
    fun select(filter: IptvAvailabilityFilter) { store.setFilter(profile, filter); reload() }
}

@Composable
internal fun rememberIptvAvailability(profile: String): IptvAvailabilityState {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { IptvAvailabilityStore(context) }
    val state = remember(store, profile) { IptvAvailabilityState(store, profile) }
    DisposableEffect(store, state) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> state.reload() }
        store.prefs.registerOnSharedPreferenceChangeListener(listener)
        state.reload()
        onDispose { store.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

@Composable
internal fun IptvAvailabilityControl(state: IptvAvailabilityState, modifier: Modifier = Modifier, changed: () -> Unit = {}) {
    val entry = remember { FocusRequester() }
    val action = remember { FocusRequester() }
    var open by remember { mutableStateOf(false) }
    var restore by remember { mutableStateOf(false) }
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(restore, windowFocused) {
        if (restore && windowFocused) {
            withFrameNanos { }
            if (runCatching { action.requestFocus() }.getOrDefault(false)) restore = false
        }
    }
    DiscoveryAction("Availability: ${state.filter.title}", Icons.Default.Flag,
        selected = state.filter != IptvAvailabilityFilter.ALL, modifier = modifier.focusRequester(action)) { open = true }
    if (open) AlertDialog(onDismissRequest = { open = false; restore = true },
        containerColor = Color(0xFF181D26), shape = RoundedCornerShape(10.dp), modifier = Modifier.initialFocusEntry(entry),
        title = { Text("Channel availability") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Flags remember a previous playback failure or your own report in this profile. Unflagged channels have not necessarily been tested.", color = Color(0xFFB5BBC7))
                IptvAvailabilityFilter.entries.forEach { value ->
                    NikTvSecondaryActionButton({ state.select(value); open = false; restore = true; changed() },
                        Modifier.fillMaxWidth().then(if (value == state.filter) Modifier.focusRequester(entry) else Modifier)) {
                        if (value == state.filter) { Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                        Text(value.title)
                    }
                }
            }
        }, confirmButton = { NikTvSecondaryActionButton({ open = false; restore = true }, Modifier.fillMaxWidth()) { Text("Close") } })
}
