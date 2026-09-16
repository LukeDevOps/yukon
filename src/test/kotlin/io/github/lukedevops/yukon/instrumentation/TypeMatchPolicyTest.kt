package io.github.lukedevops.yukon.instrumentation

import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.modifier.Ownership
import net.bytebuddy.description.modifier.SyntheticState
import net.bytebuddy.description.modifier.Visibility
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.implementation.FixedValue
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

        assertTrue(TypeMatchPolicy.isIncluded("com.acme.Foo", prefixes, emptyList()))
        assertTrue(TypeMatchPolicy.isIncluded("com.acme.deep.Foo", prefixes, emptyList()))
        assertFalse(
            TypeMatchPolicy.isIncluded("com.acmeinternal.Foo", prefixes, emptyList()),
            "com.acme must not widen to com.acmeinternal",
        )
        assertFalse(TypeMatchPolicy.isIncluded("com.acm", prefixes, emptyList()))
    }

    @Test
    fun `a prefix naming a class matches that class and its nested classes`() {
        val prefixes = listOf("com.acme.Foo")

        assertTrue(TypeMatchPolicy.isIncluded("com.acme.Foo", prefixes, emptyList()))
        assertTrue(TypeMatchPolicy.isIncluded("com.acme.Foo\$Inner", prefixes, emptyList()))
        assertFalse(TypeMatchPolicy.isIncluded("com.acme.FooBar", prefixes, emptyList()))
    }

    @Test
    fun `the agent's own package is excluded even with no prefixes at all`() {
        assertFalse(TypeMatchPolicy.isIncluded("io.github.lukedevops.yukon.Agent", emptyList(), emptyList()))
        assertTrue(TypeMatchPolicy.isIncluded("com.anything.Else", emptyList(), emptyList()))
    }

    @Test
    fun `an excluded prefix wins over a matching include prefix`() {
        val includes = listOf("com.acme")
        val excludes = listOf("com.acme.internal")

        assertTrue(TypeMatchPolicy.isIncluded("com.acme.Foo", includes, excludes))
        assertFalse(TypeMatchPolicy.isIncluded("com.acme.internal.Bar", includes, excludes))
        assertFalse(TypeMatchPolicy.isIncluded("com.acme.internal", includes, excludes))
    }

    @Test
    fun `an excluded prefix matches on the same package boundary rule as an included one`() {
        val includes = listOf("com.acme")
        val excludes = listOf("com.acme.internal")

        assertTrue(
            TypeMatchPolicy.isIncluded("com.acme.internalfoo.Baz", includes, excludes),
            "com.acme.internal must not widen to com.acme.internalfoo",
        )
    }

    @Test
    fun `an empty exclude list changes nothing`() {
        val includes = listOf("com.acme")

        assertTrue(TypeMatchPolicy.isIncluded("com.acme.Foo", includes, emptyList()))
        assertFalse(TypeMatchPolicy.isIncluded("com.other.Foo", includes, emptyList()))
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

        assertTrue(TypeMatchPolicy.typeNameMatcher(listOf("com.example.target"), emptyList()).matches(sampleTarget))
        assertFalse(TypeMatchPolicy.typeNameMatcher(listOf("com.example.targ"), emptyList()).matches(sampleTarget))
        assertFalse(
            TypeMatchPolicy.typeNameMatcher(listOf("com.example.target"), listOf("com.example.target")).matches(sampleTarget),
            "an exclude covering the same prefix must win",
        )
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

        val matched = nativeTarget.declaredMethods.filter(TypeMatchPolicy.methodMatcher(isScalaClass = false)).map { it.internalName }

        assertEquals(setOf("<init>", "normalThing"), matched.toSet())
    }

    @Test
    fun `a javac lambda body is eligible whatever isScalaClass says`() {
        val pool =
            TypePool.Default.of(
                ClassFileLocator.Compound(
                    ClassFileLocator.ForFolder(File("build/classes/java/test")),
                    ClassFileLocator.ForClassLoader.ofSystemLoader(),
                ),
            )
        val lambdaTarget = pool.describe("com.example.target.LambdaTarget").resolve()

        val matchedNotScala = lambdaTarget.declaredMethods.filter(TypeMatchPolicy.methodMatcher(isScalaClass = false)).map { it.name }
        val matchedScala = lambdaTarget.declaredMethods.filter(TypeMatchPolicy.methodMatcher(isScalaClass = true)).map { it.name }

        assertTrue("lambda\$classifyViaLambda\$0" in matchedNotScala, "a lambda body is eligible even outside a Scala class")
        assertTrue("lambda\$classifyViaLambda\$0" in matchedScala)
        assertTrue("ship" in matchedNotScala, "a method reference's own target is an ordinary method, untouched by the rule")
    }

    /** Builds a type with one static synthetic method named [name], returning `int`, taking no arguments. */
    private fun typeWithSyntheticMethod(
        typeName: String,
        name: String,
    ): TypeDescription =
        ByteBuddy()
            .subclass(Any::class.java)
            .name(typeName)
            .defineMethod(name, Int::class.javaPrimitiveType, Visibility.PUBLIC, Ownership.STATIC, SyntheticState.SYNTHETIC)
            .intercept(FixedValue.value(1))
            .make()
            .typeDescription

    @Test
    fun `a dollar-anonfun-named synthetic method is only eligible inside a Scala class`() {
        val type = typeWithSyntheticMethod("com.example.target.GeneratedAnonfunHost", "\$anonfun\$notScala\$1")

        val matchedNotScala = type.declaredMethods.filter(TypeMatchPolicy.methodMatcher(isScalaClass = false)).map { it.name }
        val matchedScala = type.declaredMethods.filter(TypeMatchPolicy.methodMatcher(isScalaClass = true)).map { it.name }

        assertTrue(
            "\$anonfun\$notScala\$1" !in matchedNotScala,
            "an unrelated synthetic method named like a Scala lambda body must stay excluded outside a Scala class",
        )
        assertTrue("\$anonfun\$notScala\$1" in matchedScala)
    }

    @Test
    fun `an ordinary synthetic method, such as a bridge-shaped accessor, is excluded regardless of isScalaClass`() {
        val type = typeWithSyntheticMethod("com.example.target.GeneratedAccessorHost", "access\$000")

        val matchedNotScala = type.declaredMethods.filter(TypeMatchPolicy.methodMatcher(isScalaClass = false)).map { it.name }
        val matchedScala = type.declaredMethods.filter(TypeMatchPolicy.methodMatcher(isScalaClass = true)).map { it.name }

        assertTrue("access\$000" !in matchedNotScala)
        assertTrue("access\$000" !in matchedScala, "isScalaClass only widens eligibility for \$anonfun\$-named methods, nothing else")
    }

    @Test
    fun `a Scala 2 dollar-adapted boxing forwarder is excluded even inside a Scala class`() {
        val type = typeWithSyntheticMethod("com.example.target.GeneratedAdaptedHost", "\$anonfun\$classify\$1\$adapted")

        val matchedScala = type.declaredMethods.filter(TypeMatchPolicy.methodMatcher(isScalaClass = true)).map { it.name }

        assertTrue(
            "\$anonfun\$classify\$1\$adapted" !in matchedScala,
            "the forwarder only unboxes and calls the body, which has its own probe",
        )
    }
}
