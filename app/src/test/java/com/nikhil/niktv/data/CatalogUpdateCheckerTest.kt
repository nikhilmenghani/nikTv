package com.nikhil.niktv.data

import com.nikhil.niktv.model.CatalogType
import com.nikhil.niktv.model.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogUpdateCheckerTest {
    @Test fun exactNewPagesAreReportedAsAnAvailableUpdate() {
        val verdict = catalogUpdateVerdict(
            CatalogUpdateEvidence(newPages = 3, newPagesExact = true)
        )

        assertEquals(CatalogUpdateState.UPDATE_AVAILABLE, verdict.state)
        assertTrue(verdict.detail.contains("3 new provider pages"))
    }

    @Test fun addedCategoryIsAnAvailableUpdate() {
        val verdict = catalogUpdateVerdict(CatalogUpdateEvidence(newCategories = 1))

        assertEquals(CatalogUpdateState.UPDATE_AVAILABLE, verdict.state)
        assertTrue(verdict.detail.contains("1 new category"))
    }

    @Test fun qualifiedPageIncreaseDoesNotClaimExactNewPages() {
        val verdict = catalogUpdateVerdict(
            CatalogUpdateEvidence(newPages = 3, newPagesExact = false)
        )

        assertEquals(CatalogUpdateState.UPDATE_AVAILABLE, verdict.state)
        assertTrue(verdict.detail.contains("page count increased by 3"))
        assertTrue(!verdict.detail.contains("new provider pages"))
    }

    @Test fun wildcardTopologyChangeOutranksApparentPageAndCategoryGrowth() {
        val verdict = catalogUpdateVerdict(
            CatalogUpdateEvidence(
                topologyChanged = true,
                newCategories = 12,
                newPages = 900
            )
        )

        assertEquals(CatalogUpdateState.CHANGED, verdict.state)
        assertTrue(verdict.detail.contains("category layout changed"))
        assertTrue(!verdict.detail.contains("900"))
    }

    @Test fun paginationRequiresAScannedBaselineAndProviderTotal() {
        assertNull(providerPageDelta(0, 900))
        assertNull(providerPageDelta(120, null))
        assertEquals(3, providerPageDelta(120, 123))
        assertEquals(-2, providerPageDelta(120, 118))
    }

    @Test fun switchingBetweenWildcardAndConcreteScanningIsATopologyChange() {
        assertTrue(providerCategoryTopologyChanged(setOf("*"), setOf("1", "2")))
        assertTrue(providerCategoryTopologyChanged(setOf("1", "2"), setOf("*")))
        assertTrue(!providerCategoryTopologyChanged(setOf("1", "2"), setOf("1", "2", "3")))
        assertTrue(!providerCategoryTopologyChanged(emptySet(), setOf("*")))
    }

    @Test fun changedFingerprintOrCategoryMetadataRecommendsRefresh() {
        listOf(
            CatalogUpdateEvidence(firstPageChanged = true),
            CatalogUpdateEvidence(lastPageChanged = true),
            CatalogUpdateEvidence(removedCategories = 1),
            CatalogUpdateEvidence(renamedCategories = 1)
        ).forEach { evidence ->
            assertEquals(CatalogUpdateState.CHANGED, catalogUpdateVerdict(evidence).state)
        }
    }

    @Test fun unchangedEvidenceReportsNoChanges() {
        val verdict = catalogUpdateVerdict(CatalogUpdateEvidence())

        assertEquals(CatalogUpdateState.NO_CHANGES, verdict.state)
        assertTrue(verdict.detail.startsWith("No changes detected"))
    }

    @Test fun pageFingerprintIsStableAndOrderSensitive() {
        val original = catalogPageFingerprint(listOf("10", "20", "30"))

        assertEquals(original, catalogPageFingerprint(listOf("10", "20", "30")))
        assertNotEquals(original, catalogPageFingerprint(listOf("30", "20", "10")))
        assertNotEquals(original, catalogPageFingerprint(listOf("10", "20", "31")))
    }

    @Test fun wildcardBecomesTheOnlyEffectiveProviderCategory() {
        val categories = listOf(
            Category("", "Invalid", CatalogType.MOVIES),
            Category("1", "Action", CatalogType.MOVIES),
            Category("*", "All movies", CatalogType.MOVIES),
            Category("*", "Duplicate all", CatalogType.MOVIES),
            Category("2", "Comedy", CatalogType.MOVIES)
        )

        assertEquals(listOf(categories[2]), effectiveCatalogCategories(categories))
    }

    @Test fun concreteEffectiveCategoriesDropBlankAndDuplicateIds() {
        val first = Category("1", "First title", CatalogType.SERIES)
        val duplicate = Category("1", "Duplicate title", CatalogType.SERIES)
        val second = Category("2", "Second", CatalogType.SERIES)

        assertEquals(
            listOf(first, second),
            effectiveCatalogCategories(listOf(Category("", "Invalid", CatalogType.SERIES), first, duplicate, second))
        )
    }
}
