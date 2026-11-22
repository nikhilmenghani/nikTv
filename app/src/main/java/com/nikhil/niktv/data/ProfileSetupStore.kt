package com.nikhil.niktv.data

import android.content.Context
import com.nikhil.niktv.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.UUID

internal data class ProfilePortalChoice(val portal: PortalProfile, val saved: Boolean) {
    val key get() = ProfilePortalAccess.key(portal)
    val ready get() = portal.portalUrl.isNotBlank() && when (portal.portalType) {
        PortalType.XTREAM -> portal.username.isNotBlank() && portal.password.isNotBlank()
        else -> portal.macAddress.isNotBlank()
    }
}

internal data class ProfileSetupDraft(
    val profile: UserProfile,
    val accountId: String?,
    val existing: Boolean = false,
    val enabled: Boolean = true,
    val layout: ViewerLayout = ViewerLayout.STANDARD,
    val portals: List<ProfilePortalChoice> = emptyList(),
    val selected: Set<String> = emptySet(),
    val startingPortal: String? = null,
    val content: XtreamContentPolicy = XtreamContentPolicy(),
    val parental: ProfileParentalPolicy = ProfileParentalPolicy(),
    val pinchToZoom: Boolean = false,
    val quality: Int = 0
) {
    val canOpen get() = enabled && selected.isNotEmpty()
    val start get() = startingPortal?.takeIf { it in selected } ?: portals.firstOrNull { it.key in selected }?.key
        ?: ProfilePortalAccess.SPORTS.takeIf { it in selected }
        ?: ProfilePortalAccess.PLAYFY.takeIf { it in selected }
}

/** Setup operates on the edited owner, never on the profile currently watching. */
internal object ProfileSetupStore {
    fun builtInConnections(context: Context): List<PortalProfile> {
        val identity = cast4kLegacyDeviceIdentity(context)
        return listOf(
            PortalProfile("Stalker", RemoteCredentials.get("NIKTV_DEFAULT_PORTAL_URL"),
                RemoteCredentials.get("NIKTV_DEFAULT_MAC_ADDRESS").ifBlank { identity.macAddress },
                RemoteCredentials.get("NIKTV_DEFAULT_SERIAL_NUMBER").ifBlank { identity.serialNumber }, PortalType.STALKER),
            PortalProfile("Xtream", RemoteCredentials.get("NIKTV_XTREAM_PORTAL_URL"), "", portalType = PortalType.XTREAM,
                username = RemoteCredentials.get("NIKTV_XTREAM_USERNAME"), password = RemoteCredentials.get("NIKTV_XTREAM_PASSWORD")))
    }

    suspend fun choices(context: Context): List<ProfilePortalChoice> = withContext(Dispatchers.IO) {
        val saved = ProfileStore(context).connections.first()
        (saved.map { ProfilePortalChoice(it, true) } + builtInConnections(context)
            .filter { builtIn -> saved.none { it.portalType == builtIn.portalType } }.map { ProfilePortalChoice(it, false) })
            .distinctBy { it.portal.cacheKey() }
    }

    suspend fun load(context: Context, profile: UserProfile?): ProfileSetupDraft = withContext(Dispatchers.IO) {
        val users = UserSyncStore.get(context)
        val account = users.selectedAccount.value
        val owner = if (profile != null && account != null) UserScope(account, profile.id) else null
        if (owner != null) UserProfilePreferences.warm(context, owner)
        val portals = choices(context)
        val hidden = ProfilePortalAccess.hidden(context, owner)
        val selected = if (owner == null) emptySet() else portals.filter {
            users.hasPortalAssociation(owner, it.portal) && it.key !in hidden
        }.mapTo(mutableSetOf()) { it.key }.also {
            if (ProfilePortalAccess.SPORTS !in hidden) it.add(ProfilePortalAccess.SPORTS)
            if (ProfilePortalAccess.PLAYFY !in hidden) it.add(ProfilePortalAccess.PLAYFY)
        }
        val destination = owner?.let { users.rememberedDestination(it) }
        val start = when (destination?.kind) {
            ViewerDestinationKind.SPORTS -> ProfilePortalAccess.SPORTS
            ViewerDestinationKind.PLAYFY -> ProfilePortalAccess.PLAYFY
            else -> portals.firstOrNull { it.portal.cacheKey() == destination?.portalKey }?.key
        }
        ProfileSetupDraft(profile ?: UserProfile(UUID.randomUUID().toString(), "", 0xFFE50914, "", UserProfileType.GENERAL),
            account, profile != null, owner?.let { it.profileId in users.enabledProfileIds(it.accountId) } ?: true,
            ProfileBrowsePreferences.layout(context, owner), portals, selected, start,
            XtreamContentPreferences.read(context, owner), owner?.let { ParentalControls.policy(context, it) } ?: ProfileParentalPolicy(),
            PlayerGesturePreferences.pinchToZoom(context, owner), PortalQualityPreferences.height(context, owner))
    }

    suspend fun save(context: Context, draft: ProfileSetupDraft): UserProfile = withContext(Dispatchers.IO) {
        val users = UserSyncStore.get(context)
        val account = requireNotNull(draft.accountId) { "Connect your account before saving a profile." }
        check(users.selectedAccount.value == account) { "The account changed. Reopen profile setup." }
        require(draft.profile.name.isNotBlank()) { "Enter a profile name." }
        val chosen = draft.portals.filter { it.key in draft.selected }
        require(chosen.all { it.ready }) { "Set up the selected connection first." }
        require(draft.selected.all { key -> key in setOf(ProfilePortalAccess.SPORTS, ProfilePortalAccess.PLAYFY) || chosen.any { it.key == key } })
        val owner = UserScope(account, draft.profile.id)
        val connections = ProfileStore(context).scoped(owner)
        // Credentials are device-owned. No association is made with the watching profile.
        chosen.forEach { connections.saveConnection(it.portal) }
        val hidden = ProfilePortalAccess.hidden(context, owner).toMutableSet().apply {
            draft.portals.forEach { if (it.key in draft.selected) remove(it.key) else add(it.key) }
            if (ProfilePortalAccess.SPORTS in draft.selected) remove(ProfilePortalAccess.SPORTS) else add(ProfilePortalAccess.SPORTS)
            if (ProfilePortalAccess.PLAYFY in draft.selected) remove(ProfilePortalAccess.PLAYFY) else add(ProfilePortalAccess.PLAYFY)
        }
        UserProfilePreferences.saveBatch(context, owner, listOf(
            UserProfilePreferences.Change("profile_portal_access_v1", "hidden", hidden),
            UserProfilePreferences.Change(ProfileBrowsePreferences.DOMAIN, "layout", draft.layout.name),
            UserProfilePreferences.Change("xtream_content_policy_v1", "policy", Json.encodeToString(XtreamContentPolicy.serializer(), draft.content)),
            UserProfilePreferences.Change("niktv_playback_ui", "pinch_to_zoom", draft.pinchToZoom),
            UserProfilePreferences.Change("iptv_playback_quality", "height", draft.quality),
            UserProfilePreferences.Change("profile_parental_v1", "enabled", draft.parental.protectionEnabled, false),
            UserProfilePreferences.Change("profile_parental_v1", "show_sensitive_iptv", draft.parental.showSensitiveIptv, false),
            UserProfilePreferences.Change("profile_parental_v1", "relock_minutes", draft.parental.relockMinutes, false)
        )) {
            check(users.selectedAccount.value == account) { "The account changed. Reopen profile setup." }
            if (draft.existing) check(users.dao.records(account).any { it.collection == "profile" && it.recordId == owner.profileId && !it.deleted }) { "This profile is no longer available." }
            users.updateProfile(draft.profile.copy(name = draft.profile.name.trim(), avatar = draft.profile.avatar.trim()))
            draft.portals.forEach { choice ->
                if (choice.key in draft.selected) users.associatePortal(owner, choice.portal)
                else if (users.hasPortalAssociation(owner, choice.portal)) users.removePortal(owner, choice.portal)
            }
        }
        users.setProfileEnabled(owner.profileId, draft.enabled)
        when (val start = draft.start) {
            ProfilePortalAccess.SPORTS -> users.rememberDestination(owner, ViewerDestination(ViewerDestinationKind.SPORTS))
            ProfilePortalAccess.PLAYFY -> users.rememberDestination(owner, ViewerDestination(ViewerDestinationKind.PLAYFY))
            null -> Unit
            else -> chosen.firstOrNull { it.key == start }?.let {
                users.rememberDestination(owner, ViewerDestination(ViewerDestinationKind.PORTAL, it.portal.cacheKey()))
            }
        }
        draft.profile
    }

    suspend fun setPortalAllowed(context: Context, owner: UserScope, portal: PortalProfile, allowed: Boolean) = withContext(Dispatchers.IO) {
        val users = UserSyncStore.get(context)
        check(users.selectedAccount.value == owner.accountId) { "The account changed. Reopen profile settings." }
        UserProfilePreferences.warm(context, owner)
        if (allowed) {
            require(ProfilePortalChoice(portal, false).ready) { "Set up this connection first." }
            ProfileStore(context).scoped(owner).saveConnection(portal)
        }
        users.withEditLock {
        val hidden = ProfilePortalAccess.hidden(context, owner).toMutableSet().apply {
            if (allowed) remove(ProfilePortalAccess.key(portal)) else add(ProfilePortalAccess.key(portal))
        }
        UserProfilePreferences.saveBatch(context, owner,
            listOf(UserProfilePreferences.Change("profile_portal_access_v1", "hidden", hidden))) {
            check(users.selectedAccount.value == owner.accountId)
            check(users.dao.records(owner.accountId).any { it.collection == "profile" && it.recordId == owner.profileId && !it.deleted }) { "This profile is no longer available." }
            if (allowed) users.associatePortal(owner, portal) else users.removePortal(owner, portal)
        }
        }
    }
}
