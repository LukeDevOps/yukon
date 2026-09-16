package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.instrumentation.branch.ScalaFixtures
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScalaClassDetectorTest {
    @Test
    fun `a plain Java class carries neither Scala class attribute`() {
        val bytes = File("build/classes/java/test/com/example/target/SampleTarget.class").readBytes()

        assertFalse(ScalaClassDetector.isScalaClass(bytes))
    }

    @Test
    fun `a Scala 3 module class carries the Scala attribute`() {
        val bytes = ScalaFixtures.classBytes("scala3", "LambdaHost\$")

        assertTrue(ScalaClassDetector.isScalaClass(bytes))
    }

    @Test
    fun `a Scala 2 module class carries the Scala attribute`() {
        val bytes = ScalaFixtures.classBytes("scala2", "LambdaHost\$")

        assertTrue(ScalaClassDetector.isScalaClass(bytes))
    }

    @Test
    fun `a Scala 2 class carries the ScalaSig attribute`() {
        val bytes = ScalaFixtures.classBytes("scala2", "Simple")

        assertTrue(ScalaClassDetector.isScalaClass(bytes))
    }
}
