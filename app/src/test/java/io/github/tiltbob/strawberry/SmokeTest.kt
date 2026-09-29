package io.github.tiltbob.strawberry

import org.junit.Assert.assertEquals
import org.junit.Test

/** Plain JVM unit test (no Android framework). */
class SmokeTest {
    @Test
    fun arithmeticStillWorks() {
        assertEquals(4, 2 + 2)
    }
}
