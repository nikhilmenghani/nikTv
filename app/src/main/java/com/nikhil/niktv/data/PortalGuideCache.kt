package com.nikhil.niktv.data

import com.nikhil.niktv.model.LiveProgramme
import com.nikhil.niktv.model.isMissingLiveProgrammeTitle
import kotlinx.serialization.Serializable

/** Cache successful empty guides briefly too, so an outage doesn't trigger a full download per tile. */
@Serializable
internal data class PortalGuideCache(
    val key: String,
    val fetchedAtMillis: Long,
    val schedules: Map<String, List<LiveProgramme>>,
    val coverageEndMillis: Map<String, Long> = emptyMap()
) {
    private val ttlMillis get() = if (schedules.values.any { it.isNotEmpty() }) 30 * 60_000L else 5 * 60_000L

    fun reusableFor(key: String, now: Long, force: Boolean = false): Boolean =
        !force && this.key == key && now - fetchedAtMillis in 0 until ttlMillis

    fun channelWindowExpired(id: String, epgId: String?, now: Long): Boolean =
        (coverageEndMillis[id] ?: coverageEndMillis[epgId])?.let { it > fetchedAtMillis && it <= now } == true
}

/** Store only useful timed metadata, never URLs, descriptions or provider credentials. */
internal fun boundedPortalGuide(key: String, fetchedAt: Long, schedules: Map<String, List<LiveProgramme>>, now: Long): PortalGuideCache {
    val result = linkedMapOf<String, List<LiveProgramme>>()
    val coverage = linkedMapOf<String, Long>()
    var remainingRows = 20_000
    var remainingCharacters = 2_000_000
    for ((id, rows) in schedules) {
        if (result.size >= 5_000 || remainingRows <= 0 || remainingCharacters <= 0) break
        rows.mapNotNull { it.endTimeMillis }.maxOrNull()?.takeIf { it > fetchedAt }
            ?.let { if (coverage.size < 5_000) coverage[id] = it }
        val valid = rows.asSequence().filter {
            !isMissingLiveProgrammeTitle(it.title) && it.startTimeMillis != null && it.endTimeMillis != null &&
                it.endTimeMillis > now && it.endTimeMillis > it.startTimeMillis && it.startTimeMillis < now + 48 * 60 * 60_000L
        }.distinctBy { Triple(it.title, it.startTimeMillis, it.endTimeMillis) }.sortedBy { it.startTimeMillis }
        val kept = mutableListOf<LiveProgramme>()
        for (row in valid) {
            if (remainingRows <= 0 || row.title.length > remainingCharacters) break
            kept += row
            remainingRows--
            remainingCharacters -= row.title.length
        }
        if (kept.isNotEmpty()) result[id] = kept
    }
    return PortalGuideCache(key, fetchedAt, result, coverage)
}
