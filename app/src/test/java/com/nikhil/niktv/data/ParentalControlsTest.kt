package com.nikhil.niktv.data
import org.junit.Assert.*
import org.junit.Test

class ParentalControlsTest {
    @Test fun defaultPinAndOverride() {
        assertTrue(acceptsParentalPin("0000", null))
        assertTrue(acceptsParentalPin("2564", null))
        assertFalse(acceptsParentalPin("1234", null))
    }
    @Test fun changingPinKeepsAdminOverride() {
        val changed = parentalPinHash("9876")
        assertTrue(acceptsParentalPin("9876", changed))
        assertTrue(acceptsParentalPin("2564", changed))
        assertFalse(acceptsParentalPin("0000", changed))
        assertFalse(acceptsParentalPin("", changed))
        assertFalse(acceptsParentalPin("25640", changed))
    }
    @Test fun adultLabelsAndOrdinaryTitles() {
        listOf("ADULT | Hindi", "XXX Movies", "18+ Series", "18 PLUS", "Erotica").forEach { assertTrue(it, adultContentLabel(it)) }
        listOf("Movies 2018", "Extra channels", "Adventure", "Friends", "Scunthorpe News").forEach { assertFalse(it, adultContentLabel(it)) }
    }
}
