package com.nikhil.niktv.ui

import com.nikhil.niktv.model.FavoriteKind
import com.nikhil.niktv.model.MediaItem
import com.nikhil.niktv.model.RecentItem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecentChannelHistoryTest {
    @Test fun olderHistoryWithoutCategoryStillLoads() {
        val recent = Json.decodeFromString<RecentItem>(
            """{"kind":"CHANNEL","media":{"id":"42","title":"Sports","logo":null,"command":null},"profileKey":"profile"}"""
        )
        assertEquals("42", recent.media.id)
        assertNull(recent.categoryTitle)
    }

    @Test fun categorySurvivesSavingWithoutChangingHistoryIdentity() {
        val original = RecentItem(
            kind = FavoriteKind.CHANNEL,
            media = MediaItem("42", "Sports", null, null, portalCategoryId = "sports"),
            playedAtMillis = 1_700_000_000_000L,
            profileKey = "profile",
            categoryTitle = "Live Sports"
        )
        val restored = Json.decodeFromString<RecentItem>(Json.encodeToString(original))
        assertEquals(original, restored)
        assertEquals(original.copy(categoryTitle = null).key, restored.key)
    }
}
