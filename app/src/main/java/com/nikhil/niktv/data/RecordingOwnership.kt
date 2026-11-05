package com.nikhil.niktv.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.util.UUID

/** Device-local MediaStore associations. No source URL, title, token or hardware identity is saved. */
class RecordingOwnership(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("niktv_recording_ownership", Context.MODE_PRIVATE)

    fun visible(recordings: List<RecordedLiveTvMedia>, owner: UserScope?): List<RecordedLiveTvMedia> =
        if (owner == null) emptyList() else synchronized(lock) { recordings.filter { read(it.uri) == owner } }

    fun unassigned(recordings: List<RecordedLiveTvMedia>): List<RecordedLiveTvMedia> =
        synchronized(lock) { recordings.filter { validUri(it.uri) && read(it.uri) == null } }

    /** Explicit legacy import cannot take a recording away from an existing account/person. */
    fun assignUnassigned(owner: UserScope, recordings: List<RecordedLiveTvMedia>): Int = synchronized(lock) {
        validate(owner)
        val missing = recordings.map { it.uri }.distinct().filter { validUri(it) && read(it) == null }
        if (missing.isNotEmpty()) {
            val value = encode(owner)
            val edit = preferences.edit()
            missing.forEach { edit.putString(it.toString(), value) }
            check(edit.commit()) { "Could not assign recordings. Your media files were kept." }
        }
        missing.size
    }

    /** Only called immediately after inserting a new physical MediaStore item. */
    internal fun created(uri: Uri, owner: UserScope?) = synchronized(lock) {
        require(validUri(uri)) { "Invalid recording URI." }
        val edit = preferences.edit()
        if (owner == null) edit.remove(uri.toString()) else { validate(owner); edit.putString(uri.toString(), encode(owner)) }
        check(edit.commit()) { "Could not save recording ownership." }
    }

    internal fun forget(uri: Uri) = synchronized(lock) {
        check(preferences.edit().remove(uri.toString()).commit()) { "Could not remove recording ownership." }
    }

    private fun read(uri: Uri): UserScope? {
        if (!validUri(uri)) return null
        return runCatching {
            val value = JSONObject(preferences.getString(uri.toString(), null) ?: return null)
            UserScope(value.getString("accountId"), value.getString("profileId")).also(::validate)
        }.getOrNull()
    }
    private fun encode(owner: UserScope): String = JSONObject().put("accountId", owner.accountId).put("profileId", owner.profileId).toString()
    private fun validate(owner: UserScope) {
        require(owner.accountId.toLongOrNull()?.let { it > 0 } == true)
        UUID.fromString(owner.profileId)
    }
    private fun validUri(uri: Uri): Boolean = uri.scheme == "content" && uri.authority == "media" &&
        uri.query == null && uri.fragment == null && uri.lastPathSegment?.toLongOrNull()?.let { it >= 0 } == true

    companion object { private val lock = Any() }
}
