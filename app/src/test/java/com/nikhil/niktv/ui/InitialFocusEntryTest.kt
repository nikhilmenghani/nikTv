package com.nikhil.niktv.ui
import org.junit.Assert.*
import org.junit.Test
class InitialFocusEntryTest {
    @Test fun recursiveEntryRedirectsOnlyOnceAndNeverStealsLaterUserFocus() {
        val entry = InitialFocusEntry(); var requests = 0
        assertTrue(entry.request { requests++; entry.request { error("Recursive focus redirection") } })
        assertTrue(entry.request { error("Late fallback must not move focus") })
        assertEquals(1, requests)
    }
    @Test fun anUnplacedTargetCanRetryWithoutLettingTheWrongChildTakeFocus() {
        val entry = InitialFocusEntry()
        assertFalse(entry.request { false })
        assertTrue(entry.request { true })
        assertTrue(entry.request { error("Already entered") })
    }
}
