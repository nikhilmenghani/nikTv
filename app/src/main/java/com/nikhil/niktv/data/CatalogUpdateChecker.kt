package com.nikhil.niktv.data

import android.content.Context
import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.Category
import com.nikhil.niktv.model.PortalCatalogPage
import com.nikhil.niktv.model.PortalSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.MessageDigest

internal fun catalogPageFingerprint(ids: List<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    ids.forEach { id ->
        digest.update(id.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun effectiveCatalogCategories(categories: List<Category>): List<Category> {
    val available = categories.filter { it.id.isNotBlank() }.distinctBy { it.id }
    return available.firstOrNull { it.id == "*" }?.let(::listOf) ?: available
}

/** Null means the saved bucket was not scanned or the provider did not report a total. */
internal fun providerPageDelta(baselineLastPage: Int, reportedTotalPages: Int?): Int? =
    if (baselineLastPage <= 0 || reportedTotalPages == null) null
    else reportedTotalPages - baselineLastPage

internal fun providerCategoryTopologyChanged(
    scannedBaselineIds: Set<String>,
    currentEffectiveIds: Set<String>
): Boolean {
    if (scannedBaselineIds.isEmpty()) return false
    val baselineUsedWildcard = "*" in scannedBaselineIds
    val currentUsesWildcard = currentEffectiveIds == setOf("*")
    return baselineUsedWildcard != currentUsesWildcard
}

internal data class CatalogUpdateEvidence(
    val newCategories: Int = 0,
    val removedCategories: Int = 0,
    val renamedCategories: Int = 0,
    val newPages: Int = 0,
    val newPagesExact: Boolean = false,
    val topologyChanged: Boolean = false,
    val firstPageChanged: Boolean = false,
    val lastPageChanged: Boolean = false
)

internal data class CatalogUpdateVerdict(
    val state: CatalogUpdateState,
    val detail: String
)

internal fun catalogUpdateVerdict(evidence: CatalogUpdateEvidence): CatalogUpdateVerdict {
    if (evidence.topologyChanged) {
        return CatalogUpdateVerdict(
            CatalogUpdateState.CHANGED,
            "Provider category layout changed; refresh recommended."
        )
    }
    val additions = evidence.newCategories > 0 || evidence.newPages > 0
    if (additions) {
        val details = buildList {
            if (evidence.newPages > 0) add(if (evidence.newPagesExact) {
                "${evidence.newPages} new provider ${if (evidence.newPages == 1) "page" else "pages"} available"
            } else {
                "Provider page count increased by ${evidence.newPages}"
            })
            if (evidence.newCategories > 0) add(
                "${evidence.newCategories} new ${if (evidence.newCategories == 1) "category" else "categories"}"
            )
            if (evidence.firstPageChanged || evidence.lastPageChanged) add("Existing pages also changed")
        }
        return CatalogUpdateVerdict(CatalogUpdateState.UPDATE_AVAILABLE, details.joinToString(" · ") + ".")
    }
    if (evidence.removedCategories > 0 || evidence.renamedCategories > 0 ||
        evidence.firstPageChanged || evidence.lastPageChanged) {
        return CatalogUpdateVerdict(
            CatalogUpdateState.CHANGED,
            "Catalog changed; refresh recommended."
        )
    }
    return CatalogUpdateVerdict(
        CatalogUpdateState.NO_CHANGES,
        "No changes detected in provider categories, page counts, or sampled pages."
    )
}

/** Performs bounded, read-only provider probes and compares them with the last completed sync. */
class CatalogUpdateChecker internal constructor(
    context: Context,
    private val fetchCategories: suspend (PortalSession, CatalogType) -> List<Category>,
    private val fetchPage: suspend (PortalSession, Category, Int) -> PortalCatalogPage,
    private val pace: suspend (Long) -> Unit
) {
    constructor(context: Context) : this(context, StalkerPortalClient(context))

    private constructor(context: Context, portal: StalkerPortalClient) : this(
        context,
        { session, type -> portal.categories(session, type) },
        { session, category, page -> portal.catalogPage(session, category, page, includeEpg = false) },
        { delay(it) }
    )

    private val repository = CatalogRepository(context.applicationContext)

    suspend fun check(
        session: PortalSession,
        type: CatalogType,
        requestDelayMillis: Long = 2_000L,
        checkControl: () -> Unit = {},
        onProgress: (String) -> Unit = {}
    ): CatalogTypeUpdateRow = withContext(Dispatchers.IO) {
        val profile = session.profile
        val key = profile.cacheKey()
        val previous = repository.updateStatus(profile, type)
        repository.saveUpdateStatus(CatalogTypeUpdateRow(
            profile = key,
            type = type.name,
            state = CatalogUpdateState.CHECKING.name,
            detail = "Checking provider categories and page metadata…",
            checkedAt = previous?.checkedAt ?: 0L
        ))
        val checkpoint = repository.scanCheckpoint(profile, type)
        if (!checkpoint.hasData) return@withContext finish(profile, type, CatalogUpdateState.NOT_SCANNED,
            "No completed local scan is available. Scan ${type.title} first.")
        if (!checkpoint.complete) return@withContext finish(profile, type, CatalogUpdateState.INCOMPLETE,
            "The local ${type.title} scan is incomplete. Resume it before checking for newer pages.")

        try {
            checkControl()
            onProgress("Fetching ${type.title} categories")
            pace(requestDelayMillis)
            checkControl()
            val available = fetchCategories(session, type)
                .filter { it.id.isNotBlank() }
                .distinctBy { it.id }
            val baselines = repository.ensureProviderBaseline(profile, type)
            if (baselines.isEmpty()) error("No completed sync baseline is available")
            val baselineWasEmpty = baselines.size == 1 &&
                baselines.single().category == CatalogRepository.EMPTY_BUCKET
            if (available.isEmpty()) {
                return@withContext if (baselineWasEmpty) {
                    finish(profile, type, CatalogUpdateState.NO_CHANGES,
                        "No changes detected; this provider media type is still empty.")
                } else {
                    finish(profile, type, CatalogUpdateState.FAILED,
                        "Provider returned no categories. Existing local records were retained; retry the check or investigate provider category mapping.")
                }
            }
            if (baselineWasEmpty) {
                return@withContext finish(
                    profile, type, CatalogUpdateState.UPDATE_AVAILABLE,
                    "Provider now reports ${available.size} ${if (available.size == 1) "category" else "categories"}.",
                    CatalogUpdateEvidence(newCategories = available.size)
                )
            }
            val effective = effectiveCatalogCategories(available)
            val baselineById = baselines.associateBy { it.category }
            val currentById = available.associateBy { it.id }
            val baselineManifest = baselines.associate { it.category to it.title }
            val scannedBaselineIds = baselines.asSequence()
                .filter { it.lastPage > 0 }
                .map { it.category }
                .toSet()
            val topologyChanged = providerCategoryTopologyChanged(
                scannedBaselineIds,
                effective.mapTo(mutableSetOf()) { it.id }
            )
            val newCategoryIds = currentById.keys - baselineManifest.keys
            val removedCategoryIds = baselineManifest.keys - currentById.keys
            val renamed = currentById.keys.intersect(baselineManifest.keys)
                .count { currentById.getValue(it).title != baselineManifest.getValue(it) }

            var newPages = 0
            var firstChanged = false
            var lastChanged = false

            effective.forEachIndexed { index, category ->
                val baseline = baselineById[category.id]
                    ?.takeIf { it.lastPage > 0 }
                    ?: return@forEachIndexed
                onProgress("${type.title} · ${category.title} · ${index + 1}/${effective.size}")
                checkControl()
                pace(requestDelayMillis)
                checkControl()
                val first = fetchPage(session, category, 1)
                val firstHash = catalogPageFingerprint(first.items.map { it.id })
                firstChanged = firstChanged || firstHash != baseline.firstPageHash

                val reportedPageDelta = providerPageDelta(baseline.lastPage, first.totalPages)
                reportedPageDelta?.let { delta ->
                    if (delta > 0) newPages += delta
                    if (delta < 0) lastChanged = true
                }

                var currentLastIds = first.items.map { it.id }
                if (baseline.lastPage > 1) {
                    onProgress("${type.title} · ${category.title} · sampling last synced page")
                    checkControl()
                    pace(requestDelayMillis)
                    checkControl()
                    val last = fetchPage(session, category, baseline.lastPage)
                    currentLastIds = last.items.map { it.id }
                    lastChanged = lastChanged ||
                        catalogPageFingerprint(currentLastIds) != baseline.lastPageHash
                }

                // Some portals omit totalPages. Probe exactly one page past the saved
                // boundary so a clean append is still detectable without a full scan.
                if (reportedPageDelta == null) {
                    onProgress("${type.title} · ${category.title} · checking for an added page")
                    checkControl()
                    pace(requestDelayMillis)
                    checkControl()
                    val next = fetchPage(session, category, baseline.lastPage + 1)
                    val knownBoundaryIds = (first.items.map { it.id } + currentLastIds).toHashSet()
                    if (next.items.any { it.id !in knownBoundaryIds }) newPages += 1
                }
            }

            val evidence = CatalogUpdateEvidence(
                newCategories = newCategoryIds.size,
                removedCategories = removedCategoryIds.size,
                renamedCategories = renamed,
                newPages = newPages,
                // Room can reconstruct a completed page count, but not the exact
                // provider page size used by that scan. Keep this comparison
                // deliberately qualified until provider metadata is persisted.
                newPagesExact = false,
                topologyChanged = topologyChanged,
                firstPageChanged = firstChanged,
                lastPageChanged = lastChanged
            )
            val verdict = catalogUpdateVerdict(evidence)
            finish(profile, type, verdict.state, verdict.detail, evidence)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            finish(profile, type, CatalogUpdateState.FAILED,
                "Could not complete the provider check (${error.javaClass.simpleName}). Try again.")
        }
    }

    private suspend fun finish(
        profile: com.nikhil.niktv.model.PortalProfile,
        type: CatalogType,
        state: CatalogUpdateState,
        detail: String,
        evidence: CatalogUpdateEvidence = CatalogUpdateEvidence()
    ): CatalogTypeUpdateRow {
        val row = CatalogTypeUpdateRow(
            profile = profile.cacheKey(),
            type = type.name,
            state = state.name,
            detail = detail,
            newPages = evidence.newPages,
            newPagesExact = evidence.newPagesExact,
            newCategories = evidence.newCategories,
            removedCategories = evidence.removedCategories,
            checkedAt = System.currentTimeMillis()
        )
        repository.saveUpdateStatus(row)
        return row
    }
}
