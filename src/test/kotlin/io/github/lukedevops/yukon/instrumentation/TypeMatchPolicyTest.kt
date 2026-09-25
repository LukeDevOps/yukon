package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.export.KotlinKind
import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.modifier.Ownership
import net.bytebuddy.description.modifier.SyntheticState
import net.bytebuddy.description.modifier.Visibility
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.implementation.FixedValue
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Opcodes
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
    fun `the agent's own package is excluded even when an include prefix covers it`() {
        assertFalse(TypeMatchPolicy.isIncluded("io.github.lukedevops.yukon.Agent", listOf("io.github.lukedevops"), emptyList()))
        assertFalse(TypeMatchPolicy.isIncluded("io.github.lukedevops.yukon.Agent", emptyList(), emptyList()))
    }

    @Test
    fun `an empty include list includes nothing`() {
        assertFalse(TypeMatchPolicy.isIncluded("com.example.Foo", emptyList(), emptyList()))
        assertFalse(TypeMatchPolicy.isIncluded("com.sun.net.httpserver.HttpServer", emptyList(), emptyList()))
    }

    @Test
    fun `an empty include list includes nothing when excludes are set`() {
        val excludes = listOf("com.example.internal")

        assertFalse(TypeMatchPolicy.isIncluded("com.example.Foo", emptyList(), excludes))
        assertFalse(TypeMatchPolicy.isIncluded("com.sun.net.httpserver.HttpServer", emptyList(), excludes))
    }

    @Test
    fun `the type matcher matches nothing for an empty include list`() {
        val pool =
            TypePool.Default.of(
                ClassFileLocator.Compound(
                    ClassFileLocator.ForFolder(File("build/classes/java/test")),
                    ClassFileLocator.ForClassLoader.ofSystemLoader(),
                ),
            )
        val sampleTarget: TypeDescription = pool.describe("com.example.target.SampleTarget").resolve()

        assertFalse(TypeMatchPolicy.typeNameMatcher(emptyList(), emptyList()).matches(sampleTarget))
        assertFalse(TypeMatchPolicy.typeNameMatcher(emptyList(), listOf("com.other")).matches(sampleTarget))
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

    @Test
    fun `each compiler's lambda body name passes the lambda-body name rule`() {
        assertTrue(TypeMatchPolicy.isLambdaBodyName("lambda\$classifyViaLambda\$0"), "javac")
        assertTrue(TypeMatchPolicy.isLambdaBodyName("\$anonfun\$classify\$1"), "Scala 2")
        assertTrue(TypeMatchPolicy.isLambdaBodyName("\$anonfun\$1"), "Scala 3")
        assertTrue(TypeMatchPolicy.isLambdaBodyName("main\$lambda\$0"), "kotlinc")
        assertTrue(TypeMatchPolicy.isLambdaBodyName("main\$lambda\$0\$0"), "kotlinc, nested")
        assertTrue(TypeMatchPolicy.isLambdaBodyName("main\$lambda\$12\$3\$0"), "kotlinc, nested twice")
    }

    @Test
    fun `a forwarder or a name a person could write fails the lambda-body name rule`() {
        assertFalse(TypeMatchPolicy.isLambdaBodyName("\$anonfun\$classify\$1\$adapted"), "Scala 2's boxing forwarder")
        assertFalse(TypeMatchPolicy.isLambdaBodyName("twice"))
        assertFalse(TypeMatchPolicy.isLambdaBodyName("main\$lambda"), "no number after the marker")
        assertFalse(TypeMatchPolicy.isLambdaBodyName("\$lambda\$0"), "no method name before the marker")
        assertFalse(TypeMatchPolicy.isLambdaBodyName("main\$lambda\$0x"))
        assertFalse(TypeMatchPolicy.isLambdaBodyName("access\$000"))
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

    @Test
    fun `a suspend function's own continuation class is rejected by the type matcher`() {
        val pool =
            TypePool.Default.of(
                ClassFileLocator.Compound(
                    ClassFileLocator.ForFolder(File("build/classes/kotlin/test")),
                    ClassFileLocator.ForClassLoader.ofSystemLoader(),
                ),
            )
        val continuation = pool.describe("com.example.target.CoroutineTargetKt\$twoPoints\$1").resolve()

        assertFalse(TypeMatchPolicy.typeNameMatcher(listOf("com.example"), emptyList()).matches(continuation))
    }

    @Test
    fun `a suspend lambda's own class, extending SuspendLambda directly, is not rejected`() {
        val pool =
            TypePool.Default.of(
                ClassFileLocator.Compound(
                    ClassFileLocator.ForFolder(File("build/classes/kotlin/test")),
                    ClassFileLocator.ForClassLoader.ofSystemLoader(),
                ),
            )
        val lambda = pool.describe("com.example.target.CoroutineTargetKt\$runLambda\$1").resolve()

        assertTrue(
            TypeMatchPolicy.typeNameMatcher(listOf("com.example"), emptyList()).matches(lambda),
            "SuspendLambda itself extends ContinuationImpl, but a suspend lambda's own direct superclass is SuspendLambda",
        )
    }

    /** A minimal class file naming [superInternalName] as its superclass, with no other content. */
    private fun classWithSuperclass(
        internalName: String,
        superInternalName: String,
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, internalName, null, superInternalName, null)
        writer.visitEnd()
        return writer.toByteArray()
    }

    @Test
    fun `a continuation superclass name is rejected when only a minimal, member-less stub of it can be resolved`() {
        // TypeDescription.getSuperClass() resolves the raw superclass reference's erasure, so some
        // locator must be able to name it; it does not need the stub's own fields or methods,
        // proving the check reads the name only. Confirmed directly: a locator with nothing at all
        // for the superclass throws TypePool.Resolution.NoSuchTypeException out of getSuperClass()
        // itself, which the fail-open test below covers instead.
        val name = "com.example.target.FakeContinuation"
        val superName = "kotlin.coroutines.jvm.internal.ContinuationImpl"
        val bytes = classWithSuperclass(name.replace('.', '/'), superName.replace('.', '/'))
        val stub = classWithSuperclass(superName.replace('.', '/'), "java/lang/Object")
        val pool = TypePool.Default.of(ClassFileLocator.Simple(mapOf(name to bytes, superName to stub)))

        val type = pool.describe(name).resolve()

        assertFalse(TypeMatchPolicy.typeNameMatcher(listOf("com.example"), emptyList()).matches(type))
    }

    @Test
    fun `a RestrictedContinuationImpl superclass is rejected the same way`() {
        val name = "com.example.target.FakeRestrictedContinuation"
        val superName = "kotlin.coroutines.jvm.internal.RestrictedContinuationImpl"
        val bytes = classWithSuperclass(name.replace('.', '/'), superName.replace('.', '/'))
        val stub = classWithSuperclass(superName.replace('.', '/'), "java/lang/Object")
        val pool = TypePool.Default.of(ClassFileLocator.Simple(mapOf(name to bytes, superName to stub)))

        val type = pool.describe(name).resolve()

        assertFalse(TypeMatchPolicy.typeNameMatcher(listOf("com.example"), emptyList()).matches(type))
    }

    @Test
    fun `an unrelated superclass is not mistaken for a continuation`() {
        val name = "com.example.target.FakePlain"
        val bytes = classWithSuperclass(name.replace('.', '/'), "java/lang/Object")
        val pool = TypePool.Default.of(ClassFileLocator.Simple.of(name, bytes))

        val type = pool.describe(name).resolve()

        assertTrue(TypeMatchPolicy.typeNameMatcher(listOf("com.example"), emptyList()).matches(type))
    }

    @Test
    fun `a continuation superclass no locator can find is still rejected under a lazily resolving pool`() {
        // The fat-jar case: the Kotlin stdlib sits under BOOT-INF/lib, which the static scan never
        // opens, so nothing can resolve ContinuationImpl. A lazily resolving pool still answers
        // the superclass's name, which is all the check reads; the scanner builds exactly this
        // kind of pool, and ByteBuddy's AgentBuilder uses one by default.
        val name = "com.example.target.FakeOrphanContinuation"
        val bytes = classWithSuperclass(name.replace('.', '/'), "kotlin/coroutines/jvm/internal/ContinuationImpl")
        val pool = TypePool.Default.WithLazyResolution.of(ClassFileLocator.Simple.of(name, bytes))

        val type = pool.describe(name).resolve()

        assertFalse(TypeMatchPolicy.typeNameMatcher(listOf("com.example"), emptyList()).matches(type))
    }

    @Test
    fun `under an eagerly resolving pool an unresolvable superclass reads as not a continuation rather than throwing`() {
        val name = "com.example.target.FakeOrphanContinuation"
        val bytes = classWithSuperclass(name.replace('.', '/'), "kotlin/coroutines/jvm/internal/ContinuationImpl")
        val pool = TypePool.Default.of(ClassFileLocator.Simple.of(name, bytes))

        val type = pool.describe(name).resolve()

        assertTrue(TypeMatchPolicy.typeNameMatcher(listOf("com.example"), emptyList()).matches(type))
    }

    /**
     * Spring 6 and 7 name an enhanced configuration class and its fast-class helpers with this
     * marker, confirmed by reading `SpringNamingPolicy.getClassName` out of spring-core 6.2.19 and
     * 7.0.9, and by the classes Spring actually generated for `demo-spring`'s `PricingConfiguration`.
     */
    @Test
    fun `a Spring CGLIB proxy name is rejected by the type matcher`() {
        for (
        name in
        listOf(
            "com.example.target.Config\$\$SpringCGLIB\$\$0",
            "com.example.target.Config\$\$SpringCGLIB\$\$FastClass\$\$0",
        )
        ) {
            val bytes = classWithSuperclass(name.replace('.', '/'), "java/lang/Object")
            val pool = TypePool.Default.of(ClassFileLocator.Simple.of(name, bytes))

            assertFalse(
                TypeMatchPolicy.typeNameMatcher(listOf("com.example"), emptyList()).matches(pool.describe(name).resolve()),
                name,
            )
        }
    }

    /**
     * Spring 5.3 spells the same thing differently: `SpringNamingPolicy.getTag()` returns
     * `BySpringCGLIB` and `DefaultNamingPolicy.getClassName` puts the generating class's simple
     * name in front of it, confirmed by reading both out of spring-core 5.3.39. The
     * `endpoints-spring-webmvc` module supports 5.3, so both spellings have to be rejected.
     */
    @Test
    fun `a Spring 5 CGLIB proxy name is rejected by the type matcher`() {
        for (
        name in
        listOf(
            "com.example.target.Config\$\$EnhancerBySpringCGLIB\$\$1a2b3c4d",
            "com.example.target.Config\$\$FastClassBySpringCGLIB\$\$1a2b3c4d",
        )
        ) {
            val bytes = classWithSuperclass(name.replace('.', '/'), "java/lang/Object")
            val pool = TypePool.Default.of(ClassFileLocator.Simple.of(name, bytes))

            assertFalse(
                TypeMatchPolicy.typeNameMatcher(listOf("com.example"), emptyList()).matches(pool.describe(name).resolve()),
                name,
            )
        }
    }

    /**
     * Every class Hibernate generates next to an entity, in each spelling it has used. 6.6 and 7.4
     * name a proxy, basic proxy and instantiator with a fixed suffix and nothing after it
     * (`ByteBuddyProxyHelper.buildProxy`, `BasicProxyFactoryImpl`, `BytecodeProviderImpl`, all
     * through `ByteBuddyState.FixedNamingStrategy`), and an access optimizer or its bridge with the
     * suffix followed by `encodeName`'s hex digit and property names, or by ByteBuddy's
     * `SuffixingRandom` `$<random>` when that name would pass 64K. 5.6 used `SuffixingRandom` for
     * all four, and `hibernate.bytecode.enforce_legacy_proxy_classnames` adds a second `$` to the
     * two proxy suffixes. Read out of hibernate-core 5.6.15, 6.6.58 and 7.4.10, and ByteBuddy
     * 1.18.12's `NamingStrategy`.
     */
    @Test
    fun `a Hibernate generated class name is rejected by the type matcher`() {
        for (
        name in
        listOf(
            "com.example.target.Order\$HibernateProxy",
            "com.example.target.Order\$HibernateBasicProxy",
            "com.example.target.Order\$HibernateInstantiator",
            "com.example.target.Order\$HibernateAccessOptimizer",
            "com.example.target.Order\$HibernateAccessOptimizer9id5total",
            "com.example.target.Order\$HibernateAccessOptimizer\$Ab3dEf9h",
            "com.example.target.Order\$HibernateAccessOptimizerBridge9id",
            "com.example.target.Order\$HibernateProxy\$Ab3dEf9h",
            "com.example.target.Order\$HibernateProxy\$\$Ab3dEf9h",
            "com.example.target.Order\$HibernateBasicProxy\$\$Ab3dEf9h",
            "com.example.target.Order\$HibernateInstantiator\$Ab3dEf9h",
            "com.example.target.Outer\$Order\$HibernateProxy",
        )
        ) {
            assertTrue(TypeMatchPolicy.isRuntimeGenerated(name), name)
            val bytes = classWithSuperclass(name.replace('.', '/'), "java/lang/Object")
            val pool = TypePool.Default.of(ClassFileLocator.Simple.of(name, bytes))

            assertFalse(
                TypeMatchPolicy.typeNameMatcher(listOf("com.example"), emptyList()).matches(pool.describe(name).resolve()),
                name,
            )
        }
    }

    /**
     * Hibernate's markers are matched as whole `$`-separated parts of the name, never as
     * substrings, since a class the adopter wrote can carry the same words. A top-level class
     * named `HibernateProxy` is the adopter's too: Hibernate always appends its suffix to an
     * existing class name.
     */
    @Test
    fun `an adopter class named like a Hibernate suffix is not mistaken for a generated class`() {
        for (
        name in
        listOf(
            "com.example.target.HibernateProxy",
            "com.example.target.HibernateAccessOptimizer",
            "com.example.target.Util\$HibernateProxyUnwrapper",
            "com.example.target.Util\$HibernateInstantiatorConfig",
            "com.example.target.Util\$HibernateAccessOptimizerSettings",
            "com.example.target.Util\$HibernateAccessOptimizerBridgeSettings",
            "com.example.target.Util\$MyHibernateProxy",
        )
        ) {
            assertFalse(TypeMatchPolicy.isRuntimeGenerated(name), name)
        }
    }

    /**
     * Why the rule names its markers rather than turning away any `$$` in a class name: kotlinc
     * puts `$$` in the name of the class it generates for a lambda passed to an inlined stdlib
     * function, and that class holds the adopter's own body.
     */
    @Test
    fun `a Kotlin inlined-lambda class name is not mistaken for a generated proxy`() {
        val name = "com.example.target.OrdersKt\$special\$\$inlined\$sortedBy\$1"
        val bytes = classWithSuperclass(name.replace('.', '/'), "java/lang/Object")
        val pool = TypePool.Default.of(ClassFileLocator.Simple.of(name, bytes))

        assertTrue(TypeMatchPolicy.typeNameMatcher(listOf("com.example"), emptyList()).matches(pool.describe(name).resolve()))
    }

    private fun turnedAway(
        className: String,
        isSynthetic: Boolean,
        kotlinKind: KotlinKind = KotlinKind.NONE,
        superClassName: () -> String? = { "java.lang.Object" },
    ): Boolean = TypeMatchPolicy.isTurnedAwayByShape(className, isSynthetic, superClassName) { kotlinKind }

    @Test
    fun `the shape test turns away a synthetic class, a runtime-generated one and a continuation, and nothing else`() {
        assertTrue(turnedAway("com.acme.Foo\$bar\$1", isSynthetic = true))
        assertTrue(turnedAway("com.acme.Foo\$\$SpringCGLIB\$\$0", isSynthetic = false))
        assertTrue(turnedAway("com.acme.FooKt\$bar\$1", isSynthetic = false) { "kotlin.coroutines.jvm.internal.ContinuationImpl" })
        assertFalse(
            turnedAway("com.acme.Foo\$bar\$1", isSynthetic = false) { "kotlin.coroutines.jvm.internal.SuspendLambda" },
            "a suspend lambda holds the adopter's own body",
        )
        assertFalse(turnedAway("com.acme.Foo", isSynthetic = false))
        assertFalse(turnedAway("com.acme.Foo", isSynthetic = false) { null })
    }

    @Test
    fun `the shape test reads no superclass when the class is already turned away`() {
        assertTrue(turnedAway("com.acme.Foo", isSynthetic = true) { error("superclass read") })
    }

    @Test
    fun `a synthetic multi-file part is not turned away, and any other synthetic Kotlin class is`() {
        assertFalse(turnedAway("com.acme.Text__GreetingKt", isSynthetic = true, KotlinKind.MULTIFILE_CLASS_PART))
        assertTrue(turnedAway("com.acme.Foo\$bar\$1", isSynthetic = true, KotlinKind.SYNTHETIC_CLASS))
        assertTrue(turnedAway("com.acme.Text", isSynthetic = true, KotlinKind.MULTIFILE_CLASS_FACADE))
    }

    @Test
    fun `the shape test reads no Kotlin kind for a class that is not synthetic`() {
        assertFalse(
            TypeMatchPolicy.isTurnedAwayByShape("com.acme.Foo", isSynthetic = false, { "java.lang.Object" }) { error("kind read") },
        )
    }

    @Test
    fun `the type matcher takes kotlinc's multi-file part, which is synthetic, and reads each fixture's Kotlin kind`() {
        val pool = TypePool.Default.of(ClassFileLocator.ForClassLoader.of(javaClass.classLoader))
        fun describe(name: String) = pool.describe(name).resolve()

        val part = describe("com.example.target.MultifileText__MultifileGreetingKt")
        assertTrue(part.isSynthetic, "kotlinc marks a part synthetic")
        assertTrue(TypeMatchPolicy.typeNameMatcher(listOf("com.example.target"), emptyList()).matches(part))
        assertEquals(KotlinKind.MULTIFILE_CLASS_PART, TypeMatchPolicy.kotlinKindOf(part))
        assertEquals(KotlinKind.MULTIFILE_CLASS_FACADE, TypeMatchPolicy.kotlinKindOf(describe("com.example.target.MultifileText")))
        assertEquals(KotlinKind.FILE_FACADE, TypeMatchPolicy.kotlinKindOf(describe("com.example.target.KotlinKindTargetKt")))
        assertEquals(KotlinKind.KOTLIN_CLASS, TypeMatchPolicy.kotlinKindOf(describe("com.example.target.KindClass")))
        assertEquals(KotlinKind.NONE, TypeMatchPolicy.kotlinKindOf(describe("com.example.target.SampleTarget")))
    }
}
