package com.nikhil.niktv.model

import org.junit.Assert.*
import org.junit.Test

class XtreamContentPolicyTest {
    private val connection = "opaque-connection"
    private val general = XtreamContentPolicy(enabled = true)
    private fun item(title: String = "A title", language: String? = null) =
        MediaItem("42", title, null, null, portalCategoryId = "1", contentLanguage = language)
    private fun allows(policy: XtreamContentPolicy, category: String, type: CatalogType = CatalogType.MOVIES,
        item: MediaItem = item()) = XtreamContentFilter(policy, connection).allows(item, type, Category("1", category, type))

    @Test fun disabledPolicyPreservesExistingCatalogue() {
        assertTrue(allows(general.copy(enabled = false), "ARABIC | MOVIES"))
    }
    @Test fun arabicSubtitlesDoNotOverrideEnglishAudio() {
        assertFalse(allows(general, "ARABIC | MOVIES"))
        assertTrue(allows(general, "ENGLISH | ARABIC SUBTITLES"))
    }
    @Test fun hindiDubbingUsesTargetLanguageAndCanBeDisabled() {
        val parents = XtreamContentPolicy.parents()
        assertTrue(allows(parents, "ENGLISH | HINDI DUBBED"))
        assertTrue(allows(parents, "SOUTH INDIAN | HINDI DUBBED"))
        assertFalse(allows(parents.copy(includeDubbed = false), "ENGLISH | HINDI DUBBED"))
        assertFalse(allows(parents, "ENGLISH | MOVIES"))
    }
    @Test fun yearAndNetworkFoldersHaveEntertainmentSemantics() {
        assertTrue(allows(general, "ENGLISH | FHD 2026"))
        assertTrue(allows(general, "INDIAN | FHD 2026"))
        assertTrue(allows(general, "COLORS HINDI", CatalogType.SERIES))
    }
    @Test fun mixedFoldersNeedReview() {
        assertFalse(allows(general, "INDIAN | GENERAL"))
        assertFalse(allows(general, "DISNEY+HOTSTAR", CatalogType.SERIES))
        assertTrue(allows(general.copy(unclassified = UnclassifiedContent.SHOW), "DISNEY+HOTSTAR", CatalogType.SERIES))
    }
    @Test fun isoProviderAudioLanguageIsRecognized() {
        assertTrue(allows(general, "ENGLISH | MOVIES", item = item(language = "hi")))
        assertFalse(allows(general, "ENGLISH | MOVIES", item = item(language = "ar")))
    }
    @Test fun ordinaryTitleWordsDoNotOverrideTheProviderLanguageOrGenre() {
        assertTrue(allows(XtreamContentPolicy.parents(), "HINDI | MOVIES", item = item("English Vinglish")))
        assertTrue(allows(general.copy(interests = setOf("entertainment")), "ENGLISH | MOVIES", item = item("News of the World")))
        assertFalse(allows(general, "ENGLISH | MOVIES", item = item("Movie [Arabic]")))
        assertTrue(allows(XtreamContentPolicy.parents(), "ENGLISH | MOVIES", item = item("Movie [Hindi Dubbed]")))
    }
    @Test fun devotionalChannelsInsideMixedIndiaFolders() {
        val parents = XtreamContentPolicy.parents()
        assertTrue(allows(parents, "INDIA | SD", CatalogType.LIVE_TV, item("IN: Aastha Bhajan")))
        assertTrue(allows(parents, "INDIA | SD", CatalogType.LIVE_TV, item("IN: Sanskar TV HD")))
        assertTrue(allows(parents, "INDIA | ACTIVE", CatalogType.LIVE_TV, item("Tata Play Darshan")))
        assertFalse(allows(parents.copy(interests = setOf("entertainment")), "INDIA | SD", CatalogType.LIVE_TV, item("IN: Aastha Bhajan")))
        assertFalse(allows(parents, "ISLAMIC | MOVIES"))
    }
    @Test fun sportAndCommentaryLanguageAreIndependent() {
        assertTrue(allows(general, "SPORTS | CRICKET", CatalogType.LIVE_TV, item("CRIC || STAR SPORTS 1 HINDI HD")))
        assertTrue(allows(general, "SPORTS | CRICKET", CatalogType.LIVE_TV, item("CRIC || WILLOW SPORTS HD")))
        assertFalse(allows(general, "SPORTS | CRICKET", CatalogType.LIVE_TV, item("CRIC || STAR SPORTS 1 TAMIL 4K")))
        assertFalse(allows(general, "SPORTS | FOOTBALL", CatalogType.LIVE_TV, item("UK: SKY FOOTBALL ENGLISH")))
    }
    @Test fun disablingReplaysKeepsLiveCricket() {
        val policy = general.copy(includeReplays = false)
        assertFalse(allows(policy, "CRICKET | MOVIES & EVENTS", item = item(language = "en")))
        assertTrue(allows(policy, "SPORTS | CRICKET", CatalogType.LIVE_TV, item("CRIC || WILLOW SPORTS HD")))
    }
    @Test fun kidsFolderIsNotEnoughToAuthorizeAnimation() {
        val kids = XtreamContentPolicy.kids()
        assertTrue(allows(kids, "INDIA | KIDS", CatalogType.LIVE_TV, item("IN: Sony_Yay_Hindi")))
        assertFalse(allows(kids, "INDIA | KIDS", CatalogType.LIVE_TV, item("TAMIL: POGO")))
        assertFalse(allows(kids, "KIDS", CatalogType.SERIES, item("Q-Force", "en")))
        assertTrue(allows(kids, "CBeebies", CatalogType.SERIES, item("Supertato")))
    }
    @Test fun explicitExceptionsNeverAuthorizeAdultFlagInKidsMode() {
        val key = XtreamContentPolicy.overrideKey(connection, CatalogType.MOVIES, "1")
        val policy = XtreamContentPolicy.kids().copy(categoryOverrides = mapOf(key to ContentOverride.ALLOW))
        assertTrue(allows(policy, "KIDS | MOVIES"))
        assertFalse(allows(policy, "KIDS | MOVIES", item = item().copy(isAdult = true)))
        assertFalse(allows(general.copy(categoryOverrides = mapOf(key to ContentOverride.HIDE)), "ENGLISH | MOVIES"))
    }
    @Test fun newCategoriesHeldPerMediaType() {
        val policy = general.copy(knownCategories = mapOf(connection to setOf("MOVIES:old")))
        assertFalse(allows(policy, "ENGLISH | MOVIES"))
        assertTrue(allows(policy.copy(reviewNewCategories = false), "ENGLISH | MOVIES"))
        assertTrue(allows(policy, "HINDI TV SERIES", CatalogType.SERIES))
    }
    @Test fun mixedFoldersRemainFetchableForAllowedChannels() {
        val filter = XtreamContentFilter(XtreamContentPolicy.parents(), connection)
        assertTrue(filter.allowsCategory(Category("1", "INDIA | SD", CatalogType.LIVE_TV)))
        assertFalse(filter.allowsCategory(Category("2", "ARABIC | MOVIES", CatalogType.MOVIES)))
    }
}
