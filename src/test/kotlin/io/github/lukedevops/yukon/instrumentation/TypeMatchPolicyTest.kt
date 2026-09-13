package io.github.lukedevops.yukon.instrumentation

import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.pool.TypePool
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypeMatchPolicyTest {
    @Test
    fun `a prefix matches on a package boundary, not as a raw string prefix`() {
        val prefixes = listOf("com.acme")

        assertTrue(TypeMatchPolicy.isIncluded("com.acme.Foo", prefixes))
        assertTrue(TypeMatchPolicy.isIncluded("com.acme.deep.Foo", prefixes))
        assertFalse(TypeMatchPolicy.isIncluded("com.acmeinternal.Foo", prefixes), "com.acme must not widen to com.acmeinternal")
        assertFalse(TypeMatchPolicy.isIncluded("com.acm", prefixes))
    }

    @Test
    fun `a prefix naming a class matches that class and its nested classes`() {
        val prefixes = listOf("com.acme.Foo")

        assertTrue(TypeMatchPolicy.isIncluded("com.acme.Foo", prefixes))
        assertTrue(TypeMatchPolicy.isIncluded("com.acme.Foo\$Inner", prefixes))
        assertFalse(TypeMatchPolicy.isIncluded("com.acme.FooBar", prefixes))
    }

    @Test
    fun `the agent's own package is excluded even with no prefixes at all`() {
        assertFalse(TypeMatchPolicy.isIncluded("io.github.lukedevops.yukon.Agent", emptyList()))
        assertTrue(TypeMatchPolicy.isIncluded("com.anything.Else", emptyList()))
    }

    @Test
    fun `the type matcher agrees with the string check`() {
        val pool =
            TypePool.Default.of(
                ClassFileLocator.Compound(
                    ClassFileLocator.ForFolder(File("build/classes/java/test")),
                    ClassFileLocator.ForClassLoader.ofSystemLoader(),
                ),
            )
        val sampleTarget: TypeDescription = pool.describe("com.example.target.SampleTarget").resolve()

        assertTrue(TypeMatchPolicy.typeNameMatcher(listOf("com.example.target")).matches(sampleTarget))
        assertFalse(TypeMatchPolicy.typeNameMatcher(listOf("com.example.targ")).matches(sampleTarget))
    }

    @Test
    fun `native methods are excluded from the method matcher along with abstract ones`() {
        val pool =
            TypePool.Default.of(
                ClassFileLocator.Compound(
                    ClassFileLocator.ForFolder(File("build/classes/java/test")),
                    ClassFileLocator.ForClassLoader.ofSystemLoader(),
                ),
            )
        val nativeTarget = pool.describe("com.example.target.NativeTarget").resolve()

        val matched = nativeTarget.declaredMethods.filter(TypeMatchPolicy.methodMatcher()).map { it.internalName }

        assertEquals(setOf("<init>", "normalThing"), matched.toSet())
    }
}
