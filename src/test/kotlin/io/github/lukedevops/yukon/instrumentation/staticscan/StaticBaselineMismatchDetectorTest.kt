package io.github.lukedevops.yukon.instrumentation.staticscan

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticBaselineMismatchDetectorTest {
    @Test
    fun `never warns before the static baseline has been computed`() {
        val detector = StaticBaselineMismatchDetector()

        assertFalse(detector.shouldWarnAbout("com.example.Foo"))
    }

    @Test
    fun `does not warn about a class present in the known static baseline`() {
        val detector = StaticBaselineMismatchDetector()
        detector.knownClassNames = setOf("com.example.Foo")

        assertFalse(detector.shouldWarnAbout("com.example.Foo"))
    }

    @Test
    fun `warns exactly once about a class absent from the known static baseline`() {
        val detector = StaticBaselineMismatchDetector()
        detector.knownClassNames = emptySet()

        assertTrue(detector.shouldWarnAbout("com.example.Foo"))
        assertFalse(detector.shouldWarnAbout("com.example.Foo"))
    }
}
