package com.nikhil.niktv.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Accept explicit provider add/upload fields only; dates and durations are not upload times. */
internal fun JsonObject.providerEpisodeAddedTime(): Long? =
    listOf("added", "date_added", "added_at", "created_at", "date_add").firstNotNullOfOrNull { key ->
        val raw = (get(key) as? JsonPrimitive)?.contentOrNull?.trim() ?: return@firstNotNullOfOrNull null
        val numeric = raw.toLongOrNull()
        val millis = when {
            numeric != null && numeric in 1_000_000_000L..4_102_444_800L -> numeric * 1_000L
            numeric != null && numeric in 1_000_000_000_000L..4_102_444_800_000L -> numeric
            else -> runCatching { java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
        }
        millis?.takeIf { it in 1_000_000_000_000L..4_102_444_800_000L }
    }
