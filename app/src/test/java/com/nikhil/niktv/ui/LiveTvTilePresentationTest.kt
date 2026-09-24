package com.nikhil.niktv.ui

import org.junit.Assert.*
import org.junit.Test

class LiveTvTilePresentationTest {
    private fun tile(title: String, category: String) = liveTvTilePresentation(title, category, xtream = true)

    @Test fun separatesActualCountryAndLanguageInMixedKidsCategory() {
        assertEquals(LiveTvTilePresentation("Sony Yay", "India · Hindi · Kids"), tile("IN: Sony_Yay_Hindi", "INDIA | KIDS"))
        assertEquals(LiveTvTilePresentation("DISNEY JR", "USA · Kids", "HD"), tile("USA: DISNEY JR HD", "INDIA | KIDS"))
        assertEquals(LiveTvTilePresentation("Discovery Kids 1", "India · Kids"), tile("IN: Discovery_Kids_1", "INDIA | KIDS"))
        assertEquals(LiveTvTilePresentation("Nickelodeon", "UK · Kids"), tile("UK:Nickelodeon", "INDIA | KIDS"))
    }

    @Test fun countryLanguageAndCollectionAreNotPartOfActorOrSeriesName() {
        assertEquals(LiveTvTilePresentation("SANJAY DUTT", "Hindi · 24x7 Movies & Actors", "HD"), tile("HINDI-SANJAY DUTT MOVIES HD", "HINDI | MOVIES & ACTORS 24/7"))
        assertEquals("RAJESH KHANNA", tile("HINDI-ACTOR RAJESH KHANNA HD", "HINDI | MOVIES & ACTORS 24/7").title)
        assertEquals(LiveTvTilePresentation("HOSTEL DAZE", "Hindi · 24x7 Web Series", "HD"), tile("HINDI-WEBSERIES HOSTEL DAZE HD", "HINDI | WEB SERIES 24/7"))
        assertEquals(LiveTvTilePresentation("HOUSE OF CARD", "English · 24x7 Web Series"), tile("ENGLISH-WEBSERIES HOUSE OF CARD", "ENGLISH Movies 24/7"))
        assertEquals(LiveTvTilePresentation("MOVIES 12", "English · 24x7 Movies", "HD"), tile("ENGLISH-MOVIES 12 HD", "ENGLISH Movies 24/7"))
    }

    @Test fun recognizesQualityInObservedProviderFormats() {
        assertEquals(LiveTvTilePresentation("SONY", "India · Entertainment", "4K"), tile("IN: SONY (4K).", "INDIA | ENTERTAINMENT"))
        assertEquals(LiveTvTilePresentation("TNT SPORTS 3", "Cricket", "FHD"), tile("CRIC || TNT SPORTS 3 ᶠᴴᴰ", "SPORTS | CRICKET"))
        assertEquals("4K", tile("CRIC || SKY SPORTS CRIC ⁴ᵏ", "SPORTS | CRICKET").quality)
        assertEquals("HD", tile("CRIC || FOX CRIC 501 ᴴᴰ", "SPORTS | CRICKET").quality)
        assertEquals(LiveTvTilePresentation("SKY SPORTS F1", "UK · 2160P", "UHD"), tile("UHD ▎SKY SPORTS F1 [UK]", "REAL 4K | 2160P"))
        assertEquals("FHD", tile("UK FHD : TNT Box Office", "PPV | LIVE EVENTS").quality)
        assertEquals("TNT Box Office", tile("UK FHD : TNT Box Office", "PPV | LIVE EVENTS").title)
    }

    @Test fun retainsChannelNumbersAliasesAndUnknownProviderLabels() {
        assertEquals("Fox Sport 1 (FS1)", tile("USA: Fox Sport 1 HD (FS1)", "USA | TV").title)
        assertEquals("AMC+ (S)", tile("US: AMC+  (S)", "USA | TV").title)
        assertEquals("CN HD +", tile("IN: CN HD + (4K)", "INDIA | KIDS").title)
        assertEquals(LiveTvTilePresentation("AASTHA (E)", "Canada · Asian"), tile("CA (Asian) AASTHA (E)", "CANADA | TV"))
        assertEquals("NEW: Mystery Channel", tile("NEW: Mystery Channel", "Other").title)
    }

    @Test fun preservesEventTimeAndColonInActualTitle() {
        assertEquals(LiveTvTilePresentation("UFC 322 Maddalena vs. Makhachev (11.15 10:00 PM ET)", "Event 05 · Live Events"),
            tile("PPV EVENT 05: UFC 322 Maddalena vs. Makhachev (11.15 10:00 PM ET)", "PPV | LIVE EVENTS"))
        assertEquals(LiveTvTilePresentation("Star Wars: The Clone Wars", "USA · 24x7"), tile("24/7 Star Wars: The Clone Wars", "USA | 24/7"))
        assertEquals("PPV EVENT 68:", tile("PPV EVENT 68:", "PPV | LIVE EVENTS").title)
    }

    @Test fun categoryRowsSplitLanguageEvenWithoutPipe() {
        assertEquals(listOf("English", "24x7 Movies"), liveCategoryLabelParts("ENGLISH Movies 24/7"))
        assertEquals(listOf("Hindi", "24x7 Web Series"), liveCategoryLabelParts("HINDI | WEB SERIES 24/7"))
        assertEquals(listOf("English", "24x7 Movies"), liveCategoryLabelParts("English | 24x7 Movies"))
        assertNull(liveCategoryLabelParts("BIGG BOSS 24X7"))
    }

    @Test fun stalkerKeepsExistingWioPresentation() {
        assertEquals(LiveTvTilePresentation("Chris Hemsworth", quality = "HD"),
            liveTvTilePresentation("English Chris Hemsworth MOVIES (HD)", "English | 24x7 Movies", xtream = false))
        assertEquals(LiveTvTilePresentation("UK: Cbeebies"), liveTvTilePresentation("UK: Cbeebies", "Kids", xtream = false))
    }

    @Test fun multiDayProviderEntriesShowDatesInsteadOfMisleadingClockTimes() {
        val original = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            val zone = java.util.TimeZone.getTimeZone("America/Toronto")
            fun timestamp(value: String) = java.time.Instant.parse(value).toEpochMilli()
            assertEquals("Sep 23–Sep 25", liveTileScheduleText(timestamp("2026-09-23T19:10:00Z"), timestamp("2026-09-25T20:30:00Z"), zone))
            assertEquals("11:00 AM–1:00 PM", liveTileScheduleText(timestamp("2026-09-24T15:00:00Z"), timestamp("2026-09-24T17:00:00Z"), zone))
            assertNull(liveTileScheduleText(null, null, zone))
        } finally {
            java.util.Locale.setDefault(original)
        }
    }
}
