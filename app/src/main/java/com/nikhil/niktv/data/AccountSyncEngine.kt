package com.nikhil.niktv.data

internal interface SyncLocalReplica {
    suspend fun merge(records: List<SyncRecord>)
    suspend fun prepare(emptyRemoteAccount: Boolean)
    suspend fun snapshot(): List<SyncRecord>
    suspend fun pending(): List<SyncRecord>
    suspend fun acknowledge(ids: Collection<String>)
}

internal interface SyncRemoteReplica {
    suspend fun fetch(): RemoteAccount
    suspend fun upload(document: AccountDocument, sha: String?)
}

/** Preserve cloud records for viewers this device has not opted into. */
internal class EnabledProfileReplica(private val local: SyncLocalReplica, private val enabled: Set<String>) : SyncLocalReplica {
    private var untouched = emptyList<SyncRecord>()
    private fun accepts(record: SyncRecord) = record.profileId.isBlank() || record.collection == "profile" || record.profileId in enabled
    override suspend fun merge(records: List<SyncRecord>) {
        untouched = records.filterNot(::accepts)
        local.merge(records.filter(::accepts))
    }
    override suspend fun prepare(emptyRemoteAccount: Boolean) = local.prepare(emptyRemoteAccount)
    override suspend fun snapshot() = local.snapshot().filter(::accepts) + untouched
    override suspend fun pending() = local.pending().filter(::accepts)
    override suspend fun acknowledge(ids: Collection<String>) = local.acknowledge(ids)
}

/** The transport atomically publishes the account index and changed profile files. */
internal suspend fun synchronizeAccount(
    accountId: String,
    local: SyncLocalReplica,
    remote: SyncRemoteReplica,
    status: (String) -> Unit = {},
) {
    repeat(4) { attempt ->
        status(if (attempt == 0) "Downloading and merging profiles…" else "Merging another device’s changes…")
        val downloaded = remote.fetch()
        require(downloaded.document.accountId == accountId && downloaded.document.records.all { it.accountId == accountId }) {
            "Remote records belong to another account. Local data is preserved."
        }
        local.merge(downloaded.document.records)
        // A process may stop after committing defaults but before migration/upload. Remote
        // emptiness remains true on retry; the local initializer and import marker are idempotent.
        local.prepare(downloaded.document.records.isEmpty())
        val pending = local.pending()
        val merged = local.snapshot()
        if (downloaded.needsUpgrade || downloaded.sha == null || downloaded.document.records.toSet() != merged.toSet()) {
            status("Uploading queued changes…")
            try { remote.upload(AccountDocument(accountId = accountId, records = merged), downloaded.sha) }
            catch (failure: SyncFailure) {
                if (failure.conflict && attempt < 3) return@repeat
                throw failure
            }
        }
        // Superseded local mutations are represented by their successful uploaded winner too.
        // A new mutation arriving during HTTP is absent from this captured pending list.
        val winners = merged.associateBy { Triple(it.profileId, it.collection, it.recordId) }
        local.acknowledge(pending.filter { queued ->
            val winner = winners[Triple(queued.profileId, queued.collection, queued.recordId)]
            winner != null && (winner.mutationId == queued.mutationId || winner.newerThan(queued))
        }.map { it.mutationId })
        return
    }
}
