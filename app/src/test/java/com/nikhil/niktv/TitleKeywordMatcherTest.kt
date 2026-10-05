package com.nikhil.niktv

import com.nikhil.niktv.model.titleKeywordMatcher
import org.junit.Assert.*
import org.junit.Test

class TitleKeywordMatcherTest {
    @Test fun compiledQueryMatchesProviderPunctuationAndAccentsWithoutChangingWordRules() {
        val match = titleKeywordMatcher("drishyam hindi")
        assertTrue(match("DRISHYAM (2013) | Hindi FHD"))
        assertTrue(match("Híndi · Dŕishyam 2"))
        assertFalse(match("Drishyam (Malayalam)"))
        assertFalse(match("Another Hindi film"))
    }
    @Test fun punctuationOnlyQueryNeverMatchesEverything() {
        assertFalse(titleKeywordMatcher(" | - ")("Drishyam"))
    }
}
