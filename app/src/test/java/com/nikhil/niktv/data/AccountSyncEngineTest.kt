package com.nikhil.niktv.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AccountSyncEngineTest {
    private fun record(id: String, mutation: String = id, revision: Long = 1) =
        SyncRecord("10", "person", "history", id, "{}", revision, "device-a", mutation)

    private class Local(vararg initial: SyncRecord) : SyncLocalReplica {
        val records = initial.associateBy { it.recordId }.toMutableMap()
        val queue = initial.toMutableList()
        override suspend fun merge(records: List<SyncRecord>) { records.forEach { remote ->
            val old = this.records[remote.recordId]
            if (old == null || remote.newerThan(old)) this.records[remote.recordId] = remote
        } }
        override suspend fun prepare(emptyRemoteAccount: Boolean) = Unit
        override suspend fun snapshot() = records.values.toList()
        override suspend fun pending() = queue.toList()
        override suspend fun acknowledge(ids: Collection<String>) { queue.removeAll { it.mutationId in ids } }
    }
    private class Remote(vararg initial: SyncRecord) : SyncRemoteReplica {
        var document = AccountDocument(accountId = "10", records = initial.toList())
        var generation = 1
        var writes = 0
        var conflicts = 0
        var onUpload: (() -> Unit)? = null
        override suspend fun fetch() = RemoteAccount(document, generation.toString())
        override suspend fun upload(document: AccountDocument, sha: String?) {
            onUpload?.invoke()
            if (conflicts > 0) {
                conflicts--; generation++
                throw SyncFailure("conflict", conflict = true)
            }
            check(sha == generation.toString())
            this.document = document; generation++; writes++
        }
    }

    @Test fun twoDevicesKeepBothHistoriesAndOnlyChangedDocumentsUpload() = runBlocking {
        val server = Remote(record("server-movie"))
        val first = Local(record("first-device-movie"))
        synchronizeAccount("10", first, server)
        val second = Local(record("second-device-movie"))
        synchronizeAccount("10", second, server)
        synchronizeAccount("10", first, server)
        assertEquals(setOf("server-movie", "first-device-movie", "second-device-movie"), server.document.records.map { it.recordId }.toSet())
        assertEquals(2, server.writes)
        assertTrue(first.queue.isEmpty())
        assertEquals(3, first.records.size)
    }

    @Test fun staleShaRefetchesAndMergesConcurrentRemoteChanges() = runBlocking {
        val local = Local(record("local"))
        val server = Remote()
        server.conflicts = 1
        server.onUpload = {
            if (server.conflicts > 0) server.document = server.document.copy(records = listOf(record("concurrent")))
        }
        synchronizeAccount("10", local, server)
        assertEquals(setOf("local", "concurrent"), server.document.records.map { it.recordId }.toSet())
        assertTrue(local.queue.isEmpty())
        assertEquals(1, server.writes)
    }

    @Test fun onlyCapturedMutationsAreAcknowledgedAndOlderIncludedVersionsAreRetired() = runBlocking {
        val old = record("movie", "old")
        val current = record("movie", "current", 2)
        val local = Local(current).apply { queue.add(old) }
        val server = Remote()
        server.onUpload = {
            val late = record("movie", "late", 3)
            local.records[late.recordId] = late; local.queue.add(late)
        }
        synchronizeAccount("10", local, server)
        assertEquals(listOf("late"), local.queue.map { it.mutationId })
        assertEquals("current", server.document.records.single().mutationId)
    }

    @Test fun retryAfterAnUploadedButUnacknowledgedCommitIsIdempotent() = runBlocking {
        val value = record("movie")
        val local = Local(value)
        val server = Remote(value)
        synchronizeAccount("10", local, server)
        assertEquals(0, server.writes)
        assertTrue(local.queue.isEmpty())
    }

    @Test fun boundedConflictsLeavePendingDataIntact() = runBlocking {
        val local = Local(record("movie"))
        val server = Remote().apply { conflicts = 10 }
        try { synchronizeAccount("10", local, server); fail("Expected a bounded conflict failure") }
        catch (expected: SyncFailure) { assertTrue(expected.conflict) }
        assertEquals(6, server.conflicts)
        assertEquals(1, local.queue.size)
    }

    @Test fun foreignAccountDataCannotBeMergedOrUploaded() = runBlocking {
        val local = Local(record("local"))
        val server = Remote().apply { document = AccountDocument(accountId = "20", records = listOf(record("foreign").copy(accountId = "20"))) }
        try { synchronizeAccount("10", local, server); fail("Expected account isolation") }
        catch (_: IllegalArgumentException) { }
        assertEquals(setOf("local"), local.records.keys)
        assertEquals(0, server.writes)
    }

    @Test fun deletionTombstonesSurviveAnOldOfflineDevice() = runBlocking {
        val old = record("movie")
        val deletion = old.copy(mutationId = "deleted", revision = 2, deleted = true)
        val local = Local(old)
        val server = Remote(deletion)
        synchronizeAccount("10", local, server)
        assertTrue(local.records.getValue("movie").deleted)
        assertEquals(deletion, server.document.records.single())
        assertTrue(local.queue.isEmpty())
    }
}
