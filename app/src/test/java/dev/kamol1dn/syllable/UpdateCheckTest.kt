package dev.kamol1dn.syllable

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckTest {
    @Test
    fun comparesVersionsByTheirNumbers() {
        assertTrue(UpdateCheck.isNewer("1.2.10", "1.2.9"))
        assertTrue(UpdateCheck.isNewer("v1.3", "1.2.1"))
        assertTrue(UpdateCheck.isNewer("2.0.0", "1.9.9"))
        assertFalse(UpdateCheck.isNewer("1.2.1", "1.2.1"))
        assertFalse(UpdateCheck.isNewer("1.2", "1.2.0"))
        assertFalse(UpdateCheck.isNewer("1.2.0", "1.2.1"))
        assertFalse(UpdateCheck.isNewer("1.2.1-preview", "1.2.1"))
    }
}
