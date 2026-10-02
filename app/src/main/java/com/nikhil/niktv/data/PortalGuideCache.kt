package com.nikhil.niktv.data

import com.nikhil.niktv.model.LiveProgramme

/** Cache successful empty guides briefly too, so an outage doesn't trigger a full download per tile. */
internal data class PortalGuideCache(
    val key: String,
    val fetchedAtMillis: Long,
    val schedules: Map<String, List<LiveProgramme>>
) {
    private val ttlMillis = if (schedules.values.any { it.isNotEmpty() }) 5 * 60_000L else 60_000L

    fun reusableFor(key: String, now: Long, force: Boolean = false): Boolean =
        !force && this.key == key && now - fetchedAtMillis in 0 until ttlMillis
}
