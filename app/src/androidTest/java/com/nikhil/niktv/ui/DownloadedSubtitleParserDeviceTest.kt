package com.nikhil.niktv.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Android uses ICU regex; JVM-only parser tests cannot catch all pattern failures. */
class DownloadedSubtitleParserDeviceTest {
    @Test fun assOverridesAndMarkupCanInitializeAndParseOnAndroid() {
        val cues = parseDownloadedSubtitleText(
            "\uFEFF1\r\n00:00:00,500 --> 00:00:07,000\r\n" +
                "{\\an8}<i>CaseAndroid &amp; हिन्दी</i><br/>Second line"
        )
        assertEquals(
            listOf(DownloadedSubtitleCue(500L, 7_000L, "CaseAndroid & हिन्दी\nSecond line")),
            cues
        )
    }
}
