package com.nikhil.niktv.ui

/** An occurrence, not just a channel ID: pinned and provider tiles can intentionally repeat. */
internal data class QueueOccurrence(val id: String, val ordinal: Int, val pinned: Boolean = false)

internal fun queueOccurrences(ids: List<String>): List<QueueOccurrence> {
    val total = ids.groupingBy { it }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return ids.map { id ->
        val occurrence = seen.getOrDefault(id, 0)
        seen[id] = occurrence + 1
        // Provider rows are unique; the intentional duplicates form the pinned prefix.
        // Keep the provider identity stable when a pin is added or removed.
        if (occurrence < total.getValue(id) - 1) QueueOccurrence(id, occurrence, pinned = true)
        else QueueOccurrence(id, 0)
    }
}

/** Committed cursor and pending user intent are independent of the active player. */
internal class PlaybackNavigationCursor {
    private var committed: QueueOccurrence? = null
    private var selected: QueueOccurrence? = null
    var pendingRequest: Long? = null
        private set
    val pendingId: String? get() = selected?.id.takeIf { pendingRequest != null }

    fun select(request: Long, ids: List<String>, id: String, index: Int? = null) {
        val keys = queueOccurrences(ids)
        selected = index?.let(keys::getOrNull)?.takeIf { it.id == id }
            ?: keys.firstOrNull { it.id == id } ?: QueueOccurrence(id, 0)
        pendingRequest = request
    }

    fun index(ids: List<String>, playingId: String): Int {
        val keys = queueOccurrences(ids)
        val wanted = if (pendingRequest != null) selected else committed?.takeIf { it.id == playingId }
        return keys.indexOf(wanted).takeIf { it >= 0 }
            ?: ids.indexOf(pendingId ?: playingId)
    }

    fun commit(request: Long, ids: List<String>, id: String) {
        if (pendingRequest != request) return
        val keys = queueOccurrences(ids)
        committed = selected?.takeIf { it.id == id && it in keys }
            ?: keys.firstOrNull { it.id == id } ?: QueueOccurrence(id, 0)
        selected = committed
        pendingRequest = null
    }

    fun finish(request: Long) {
        if (pendingRequest == request) { pendingRequest = null; selected = committed }
    }

    fun cancel() { pendingRequest = null; selected = committed }
}
