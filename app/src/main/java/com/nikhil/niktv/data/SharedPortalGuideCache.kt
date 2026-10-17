package com.nikhil.niktv.data

import com.nikhil.niktv.model.LiveProgramme
import kotlinx.coroutines.*

/** One independently owned metadata fetch: cancelling a tile never cancels another consumer. */
internal class SharedPortalGuideCache(
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val read: suspend (String) -> PortalGuideCache? = { null },
    private val write: suspend (PortalGuideCache) -> Unit = {},
    private val propagate: (Exception) -> Boolean = { false }
) {
    private data class Pending(val key: String, val network: Boolean, val work: Deferred<PortalGuideCache?>)
    private data class Failure(val key: String, val at: Long)
    private val lock = Any()
    private var resident: PortalGuideCache? = null
    private var diskKey: String? = null
    private var pending: Pending? = null
    private var failure: Failure? = null
    private var generation = 0L

    suspend fun cached(key: String): PortalGuideCache? = resolve(key, false, false) { emptyMap() }

    suspend fun get(key: String, force: Boolean = false, load: suspend () -> Map<String, List<LiveProgramme>>): PortalGuideCache? =
        resolve(key, true, force, load)

    /** Foreground stream resolution can cancel a bulk download without joining its cleanup. */
    fun cancelPending() = synchronized(lock) {
        generation++
        pending?.work?.cancel()
        pending = null
    }

    private suspend fun resolve(key: String, network: Boolean, force: Boolean,
                                load: suspend () -> Map<String, List<LiveProgramme>>): PortalGuideCache? {
        while (true) {
            val work = synchronized(lock) {
                val timestamp = now()
                val cached = resident?.takeIf { it.key == key }
                if (cached?.reusableFor(key, timestamp) == true &&
                    (!force || timestamp - cached.fetchedAtMillis < 60_000L)) return cached
                if (!network && diskKey == key) return null
                if (network && failure?.let { it.key == key && timestamp - it.at in 0 until
                        (if (force) 60_000L else 5 * 60_000L) } == true) return cached
                val existing = pending?.takeIf { it.key == key && !it.work.isCompleted && !it.work.isCancelled }
                if (existing != null) {
                    if (!network && existing.network) return null
                    existing
                } else {
                    pending?.work?.cancel()
                    val id = ++generation
                    val deferred = scope.async(start = CoroutineStart.LAZY) {
                        var fallback = cached
                        try {
                            val needsDisk = synchronized(lock) { diskKey != key }
                            if (needsDisk) {
                                val disk = try { read(key) } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { null }
                                fallback = disk?.takeIf { it.key == key }?.let {
                                    boundedPortalGuide(key, it.fetchedAtMillis, it.schedules, now()).copy(
                                        coverageEndMillis = it.coverageEndMillis.ifEmpty {
                                            it.schedules.mapNotNull { (channel, rows) -> rows.mapNotNull { row -> row.endTimeMillis }
                                                .maxOrNull()?.let { end -> channel to end } }.take(5_000).toMap()
                                        })
                                } ?: fallback
                                synchronized(lock) {
                                    if (generation == id) { diskKey = key; resident = fallback }
                                }
                            }
                            if (!network) return@async fallback?.takeIf { it.reusableFor(key, now()) }
                            if (fallback?.reusableFor(key, now()) == true &&
                                (!force || now() - fallback!!.fetchedAtMillis < 60_000L)) return@async fallback
                            val schedules = load()
                            val fresh = boundedPortalGuide(key, now(), schedules, now())
                            currentCoroutineContext().ensureActive()
                            synchronized(lock) {
                                if (generation == id) { resident = fresh; failure = null }
                            }
                            try { write(fresh) } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { /* Memory is still usable if the cache disk is full. */ }
                            fresh
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            if (propagate(error)) throw error
                            synchronized(lock) { if (generation == id) failure = Failure(key, now()) }
                            fallback?.takeIf { now() - it.fetchedAtMillis in 0 until 24 * 60 * 60_000L }
                        } finally {
                            synchronized(lock) { if (generation == id) pending = null }
                        }
                    }
                    Pending(key, network, deferred).also { pending = it }
                }
            }
            work.work.start()
            val result = work.work.await()
            // A foreground lookup which shared a disk-only read may still need a network fetch.
            if (network && !work.network) continue
            return result
        }
    }
}
