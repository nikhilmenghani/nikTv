package com.nikhil.niktv.data
import org.junit.Assert.*
import org.junit.Test

class ParentalControlsTest {
    @Test fun onlyFactoryPinIsAcceptedBeforeSetup() {
        assertTrue(acceptsParentalPin("0000", null))
        assertFalse(acceptsParentalPin("2564", null))
        assertFalse(acceptsParentalPin("1234", null))
    }
    @Test fun onlyConfiguredPinIsAcceptedAfterSetup() {
        val changed = parentalPinHash("9876")
        assertTrue(acceptsParentalPin("9876", changed))
        assertFalse(acceptsParentalPin("2564", changed))
        assertTrue(acceptsParentalPin("2564", parentalPinHash("2564")))
        assertFalse(acceptsParentalPin("0000", changed))
        assertFalse(acceptsParentalPin("", changed))
        assertFalse(acceptsParentalPin("25640", changed))
    }
    @Test fun adultLabelsAndOrdinaryTitles() {
        listOf("ADULT | Hindi", "XXX Movies", "18+ Series", "18 PLUS", "Erotica").forEach { assertTrue(it, adultContentLabel(it)) }
        listOf("Movies 2018", "Extra channels", "Adventure", "Friends", "Scunthorpe News").forEach { assertFalse(it, adultContentLabel(it)) }
    }
}
