package com.nikhil.niktv.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveProgrammeTitleTest {
    @Test fun providerPlaceholderIsNotAProgramme() {
        assertTrue(isMissingLiveProgrammeTitle("This Channel has No Guide"))
        assertTrue(isMissingLiveProgrammeTitle("H:i [No EPG]"))
        assertFalse(isMissingLiveProgrammeTitle("The Big Bang Theory S01"))
    }
}
