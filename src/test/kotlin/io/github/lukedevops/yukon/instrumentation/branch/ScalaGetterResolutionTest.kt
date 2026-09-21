package io.github.lukedevops.yukon.instrumentation.branch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves [BranchSiteAnalyzer]'s Scala default-getter resolution (ADR 0023) directly against real
 * `scalac` output, for both Scala 3 and Scala 2.13 fixtures compiled by the sibling
 * `:fixtures-scala3` and `:fixtures-scala2` modules. A getter is found regardless of the method
 * filter, the same way a Kotlin `$default` method is: see [analyzeFixture].
 */
class ScalaGetterResolutionTest {
    private fun analyzeFixture(
        module: String,
        simpleName: String,
    ) = BranchSiteAnalyzer.analyze(ScalaFixtures.classBytes(module, simpleName)) { _, _ -> false }

    /** A lookup over the fixture module's own compiled output, resolving an internal name to its class's bytes. */
    private fun lookupFor(module: String): (String) -> ByteArray? =
        { internalName ->
            try {
                ScalaFixtures.classBytes(module, internalName.substringAfterLast('/'))
            } catch (_: Exception) {
                null
            }
        }

    private fun analyzeFixtureWithLookup(
        module: String,
        simpleName: String,
        lookup: (String) -> ByteArray? = lookupFor(module),
    ) = BranchSiteAnalyzer.analyze(ScalaFixtures.classBytes(module, simpleName), lookup) { _, _ -> false }

    // --- Shared resolution rules, proven once on the Scala 3 fixture and once on Scala 2.13's. ---

    private fun `plain overload disambiguation by return type`(module: String) {
        val analysis = analyzeFixture(module, "Simple")

        val bSite = analysis.scalaGetterSites.single { it.getterName == "f\$default\$2" }
        assertEquals("f", bSite.targetName)
        assertEquals("(IILjava/lang/String;)I", bSite.targetDescriptor, "the three-parameter f, not the f(I J) overload")
        assertEquals(1, bSite.parameterIndex)
        assertEquals("b", bSite.parameterName)
        assertTrue(bSite.overridable, "f is not final, on a non-final class")

        val cSite = analysis.scalaGetterSites.single { it.getterName == "f\$default\$3" }
        assertEquals("f", cSite.targetName)
        assertEquals("(IILjava/lang/String;)I", cSite.targetDescriptor)
        assertEquals(2, cSite.parameterIndex)
        assertEquals("c", cSite.parameterName)
    }

    @Test
    fun `scala 3 - plain overload disambiguation by return type`() = `plain overload disambiguation by return type`("scala3")

    @Test
    fun `scala 2 - plain overload disambiguation by return type`() = `plain overload disambiguation by return type`("scala2")

    private fun `curried default takes the prefix rule seriously`(module: String) {
        val analysis = analyzeFixture(module, "Curried")

        val site = analysis.scalaGetterSites.single()
        assertEquals("g\$default\$2", site.getterName)
        assertEquals("g", site.targetName)
        assertEquals("(II)I", site.targetDescriptor)
        assertEquals(1, site.parameterIndex, "N=2 counts across both parameter lists")
        assertEquals("b", site.parameterName)
        assertTrue(site.overridable)
    }

    @Test
    fun `scala 3 - curried default takes the prefix rule seriously`() = `curried default takes the prefix rule seriously`("scala3")

    @Test
    fun `scala 2 - curried default takes the prefix rule seriously`() = `curried default takes the prefix rule seriously`("scala2")

    private fun `arity filter picks the matching overload`(module: String) {
        val analysis = analyzeFixture(module, "SameNameDiffArityHost")

        val site = analysis.scalaGetterSites.single()
        assertEquals("sameNameDiffArity\$default\$3", site.getterName)
        assertEquals("sameNameDiffArity", site.targetName)
        assertEquals("(III)I", site.targetDescriptor, "the three-parameter overload, not the one-parameter one")
        assertEquals(2, site.parameterIndex)
        assertEquals("c", site.parameterName)
    }

    @Test
    fun `scala 3 - arity filter picks the matching overload`() = `arity filter picks the matching overload`("scala3")

    @Test
    fun `scala 2 - arity filter picks the matching overload`() = `arity filter picks the matching overload`("scala2")

    private fun `a by-name default's Function0 return type is accepted`(module: String) {
        val analysis = analyzeFixture(module, "ByNameHost")

        val site = analysis.scalaGetterSites.single()
        assertEquals("h", site.targetName)
        assertEquals("(Lscala/Function0;)I", site.targetDescriptor)
        assertEquals(0, site.parameterIndex)
        assertEquals("a", site.parameterName)
    }

    @Test
    fun `scala 3 - a by-name default's Function0 return type is accepted`() =
        `a by-name default's Function0 return type is accepted`("scala3")

    @Test
    fun `scala 2 - a by-name default's Function0 return type is accepted`() =
        `a by-name default's Function0 return type is accepted`("scala2")

    private fun `a generic default erased to Object is accepted`(module: String) {
        val analysis = analyzeFixture(module, "Generic")

        val site = analysis.scalaGetterSites.single()
        assertEquals("firstOrNull", site.targetName)
        assertEquals("(Ljava/lang/Object;)Ljava/lang/Object;", site.targetDescriptor)
        assertEquals(0, site.parameterIndex)
        assertEquals("a", site.parameterName)
    }

    @Test
    fun `scala 3 - a generic default erased to Object is accepted`() = `a generic default erased to Object is accepted`("scala3")

    @Test
    fun `scala 2 - a generic default erased to Object is accepted`() = `a generic default erased to Object is accepted`("scala2")

    private fun `overridable is false for a final method`(module: String) {
        val analysis = analyzeFixture(module, "FinalMethodHost")

        val site = analysis.scalaGetterSites.single()
        assertEquals("finalF", site.targetName)
        assertFalse(site.overridable, "finalF is declared final")
    }

    @Test
    fun `scala 3 - overridable is false for a final method`() = `overridable is false for a final method`("scala3")

    @Test
    fun `scala 2 - overridable is false for a final method`() = `overridable is false for a final method`("scala2")

    private fun `overridable is false for a private method`(module: String) {
        val analysis = analyzeFixture(module, "PrivateMethodHost")

        val site = analysis.scalaGetterSites.single()
        assertEquals("priv", site.targetName)
        assertFalse(site.overridable, "priv is private")
    }

    @Test
    fun `scala 3 - overridable is false for a private method`() = `overridable is false for a private method`("scala3")

    @Test
    fun `scala 2 - overridable is false for a private method`() = `overridable is false for a private method`("scala2")

    private fun `overridable is false for a method on a final class`(module: String) {
        val analysis = analyzeFixture(module, "FinalHost")

        val site = analysis.scalaGetterSites.single()
        assertEquals("f", site.targetName)
        assertFalse(site.overridable, "FinalHost is a final class")
    }

    @Test
    fun `scala 3 - overridable is false for a method on a final class`() = `overridable is false for a method on a final class`("scala3")

    @Test
    fun `scala 2 - overridable is false for a method on a final class`() = `overridable is false for a method on a final class`("scala2")

    private fun `overridable is false for every object method, since module classes are final`(module: String) {
        val analysis = analyzeFixture(module, "Obj\$")

        val site = analysis.scalaGetterSites.single()
        assertEquals("m", site.targetName)
        assertFalse(site.overridable, "Obj is a module; its class is final")
    }

    @Test
    fun `scala 3 - overridable is false for every object method`() =
        `overridable is false for every object method, since module classes are final`("scala3")

    @Test
    fun `scala 2 - overridable is false for every object method`() =
        `overridable is false for every object method, since module classes are final`("scala2")

    private fun `a two-list default carries every earlier list's parameters, even unread ones`(module: String) {
        val analysis = analyzeFixture(module, "TwoListsHost")

        val first = analysis.scalaGetterSites.single { it.getterName == "twoLists\$default\$1" }
        assertEquals(0, first.parameterIndex)
        assertEquals("a", first.parameterName)

        val second = analysis.scalaGetterSites.single { it.getterName == "twoLists\$default\$2" }
        assertEquals(1, second.parameterIndex)
        assertEquals("b", second.parameterName)
        assertEquals("(II)I", second.targetDescriptor)
    }

    @Test
    fun `scala 3 - a two-list default carries every earlier list's parameters`() =
        `a two-list default carries every earlier list's parameters, even unread ones`("scala3")

    @Test
    fun `scala 2 - a two-list default carries every earlier list's parameters`() =
        `a two-list default carries every earlier list's parameters, even unread ones`("scala2")

    private fun `the trait's own default method resolves with overridable true`(module: String) {
        val analysis = analyzeFixture(module, "Tr")

        val site = analysis.scalaGetterSites.single()
        assertEquals("t\$default\$1", site.getterName)
        assertEquals("t", site.targetName)
        assertEquals("(I)I", site.targetDescriptor)
        assertEquals(0, site.parameterIndex)
        assertTrue(site.overridable, "an interface target is overridable")
    }

    @Test
    fun `scala 3 - the trait's own default method resolves with overridable true`() =
        `the trait's own default method resolves with overridable true`("scala3")

    @Test
    fun `scala 2 - the trait's own default method resolves with overridable true`() =
        `the trait's own default method resolves with overridable true`("scala2")

    private fun `the trait's static self-parameter forwarder is not treated as a getter`(module: String) {
        val analysis = analyzeFixture(module, "Tr")

        assertTrue(
            analysis.scalaGetterSites.none { it.getterName == "t\$default\$1\$" },
            "t\$default\$1\$ has a trailing dollar and does not match the getter pattern",
        )
        assertTrue(
            analysis.unresolvedScalaGetterSites.none { it.first == "t\$default\$1\$" },
            "t\$default\$1\$ is not a getter candidate at all, so it is never reported as unresolved either",
        )
    }

    @Test
    fun `scala 3 - the trait's static self-parameter forwarder is not treated as a getter`() =
        `the trait's static self-parameter forwarder is not treated as a getter`("scala3")

    @Test
    fun `scala 2 - the trait's static self-parameter forwarder is not treated as a getter`() =
        `the trait's static self-parameter forwarder is not treated as a getter`("scala2")

    // --- Cases that genuinely differ across the two compilers; see the report for the "did not
    // survive contact" note this pins down. ---

    /**
     * Confirmed via `javap -v`: Scala 2.13.15 does not mark a trait's mixin forwarder methods
     * synthetic or bridge, unlike Scala 3. `TrImpl`'s own `t$default$1` is therefore a genuine,
     * independently resolvable getter under Scala 2, targeting `TrImpl`'s own forwarder `t`, not
     * only Tr's.
     */
    @Test
    fun `scala 2 - a bare trait implementer's own mixin forwarder is itself a resolvable getter`() {
        val analysis = analyzeFixture("scala2", "TrImpl")

        val site = analysis.scalaGetterSites.single()
        assertEquals("t\$default\$1", site.getterName)
        assertEquals("t", site.targetName)
        assertEquals("(I)I", site.targetDescriptor)
        assertTrue(site.overridable, "TrImpl's own forwarder t is not final, private, static, or on a final class")
    }

    /**
     * Scala 3's mixin forwarders are woven as synthetic bridges (`ACC_SYNTHETIC | ACC_BRIDGE`),
     * so `TrImpl` carries no candidate getter of its own; only `Tr`'s own default method does.
     */
    @Test
    fun `scala 3 - a bare trait implementer's mixin forwarder is synthetic and yields no getter`() {
        val analysis = analyzeFixture("scala3", "TrImpl")

        assertEquals(emptyList(), analysis.scalaGetterSites)
        assertEquals(emptyList(), analysis.unresolvedScalaGetterSites)
    }

    /**
     * An `inline def`'s getter is still emitted and called by every omitting caller, but the
     * function itself has no bytecode in Scala 3, so no same-class target ever resolves. Chunk
     * two's scope does not cover this either, since it is not a cross-class case: there is no
     * target at all.
     */
    @Test
    fun `scala 3 - an inline def with a default has no bytecode target, so its getter is unresolved`() {
        val analysis = analyzeFixture("scala3", "InlineHost\$")

        assertEquals(emptyList(), analysis.scalaGetterSites)
        assertEquals(listOf("inlineF\$default\$2" to "()I"), analysis.unresolvedScalaGetterSites)
    }

    /**
     * The extension receiver `n` is an ordinary leading JVM parameter, carried into the getter's
     * own parameter list the same way a curried list's parameters are. `width`, not the receiver,
     * is the optional parameter being resolved.
     */
    @Test
    fun `scala 3 - an extension method's default counts the receiver as an ordinary parameter`() {
        val analysis = analyzeFixture("scala3", "Extensions\$")

        val site = analysis.scalaGetterSites.single()
        assertEquals("pad\$default\$2", site.getterName)
        assertEquals("pad", site.targetName)
        assertEquals("(II)Ljava/lang/String;", site.targetDescriptor)
        assertEquals(1, site.parameterIndex, "the receiver n is JVM parameter 0; width is parameter 1")
        assertEquals("width", site.parameterName)
        assertFalse(site.overridable, "Extensions is a module; its class is final")
    }

    /** `copy$default$N` re-kinds onto `copy` the same way any other same-class getter does. */
    private fun `a case class's copy defaults resolve onto copy, like any other same-class getter`(module: String) {
        val analysis = analyzeFixture(module, "Cc")

        val first = analysis.scalaGetterSites.single { it.getterName == "copy\$default\$1" }
        assertEquals("copy", first.targetName)
        assertEquals("(II)Lcom/example/scalatarget/Cc;", first.targetDescriptor)
        assertEquals(0, first.parameterIndex)
        assertEquals("a", first.parameterName)
        assertTrue(first.overridable)

        val second = analysis.scalaGetterSites.single { it.getterName == "copy\$default\$2" }
        assertEquals(1, second.parameterIndex)
        assertEquals("b", second.parameterName)
    }

    @Test
    fun `scala 3 - a case class's copy defaults resolve onto copy`() =
        `a case class's copy defaults resolve onto copy, like any other same-class getter`("scala3")

    @Test
    fun `scala 2 - a case class's copy defaults resolve onto copy`() =
        `a case class's copy defaults resolve onto copy, like any other same-class getter`("scala2")

    /**
     * Scala 2.13 gives a case class companion module an `apply$default$N` accessor beside its own
     * `apply`, in the same class; the general same-class rule resolves it without any special
     * casing for case classes.
     */
    @Test
    fun `scala 2 - apply defaults re-kind onto Cc dollar's own apply`() {
        val analysis = analyzeFixture("scala2", "Cc\$")

        val first = analysis.scalaGetterSites.single { it.getterName == "apply\$default\$1" }
        assertEquals("apply", first.targetName)
        assertEquals("(II)Lcom/example/scalatarget/Cc;", first.targetDescriptor)
        assertEquals(0, first.parameterIndex)
        assertFalse(first.overridable, "Cc\$ is a module; its class is final")

        val second = analysis.scalaGetterSites.single { it.getterName == "apply\$default\$2" }
        assertEquals(1, second.parameterIndex)
    }

    /**
     * Scala 3 resolves both `Cc(...)` and `Cc.apply(...)` through the constructor's own default
     * getters instead of generating a separate `apply$default$N`, so `Cc$` carries no
     * `apply$default$N` at all; only `$lessinit$greater$default$N` and `copy$default$N` appear.
     */
    @Test
    fun `scala 3 - Cc dollar has no apply defaults of its own`() {
        val analysis = analyzeFixture("scala3", "Cc\$")

        assertTrue(analysis.scalaGetterSites.none { it.getterName.startsWith("apply\$default\$") })
    }

    // --- Constructor default getters cross the class boundary from the companion module to the
    // class the module compiles for; see ADR 0023's "cross-class target" shape.

    private fun `constructor getters on the companion resolve across the class boundary to Cc's own init`(module: String) {
        val analysis = analyzeFixtureWithLookup(module, "Cc\$")

        val first = analysis.scalaGetterSites.single { it.getterName == "\$lessinit\$greater\$default\$1" }
        assertEquals("<init>", first.targetName)
        assertEquals("(II)V", first.targetDescriptor)
        assertEquals(0, first.parameterIndex)
        assertEquals("a", first.parameterName)
        assertFalse(first.overridable, "a constructor is never overridable")
        assertEquals("com.example.scalatarget.Cc", first.targetClassName)

        val second = analysis.scalaGetterSites.single { it.getterName == "\$lessinit\$greater\$default\$2" }
        assertEquals(1, second.parameterIndex)
        assertEquals("b", second.parameterName)
        assertEquals("com.example.scalatarget.Cc", second.targetClassName)
    }

    @Test
    fun `scala 3 - constructor getters on the companion resolve across the class boundary`() =
        `constructor getters on the companion resolve across the class boundary to Cc's own init`("scala3")

    @Test
    fun `scala 2 - constructor getters on the companion resolve across the class boundary`() =
        `constructor getters on the companion resolve across the class boundary to Cc's own init`("scala2")

    private fun `the static forwarder on Cc itself resolves in class against its own init`(module: String) {
        val analysis = analyzeFixtureWithLookup(module, "Cc")

        val site = analysis.scalaGetterSites.single { it.getterName == "\$lessinit\$greater\$default\$1" }
        assertEquals("<init>", site.targetName)
        assertEquals("(II)V", site.targetDescriptor)
        assertEquals(0, site.parameterIndex)
        assertEquals("a", site.parameterName)
        assertFalse(site.overridable, "a constructor is never overridable")
        assertNull(site.targetClassName, "the forwarder's own target is in its own class")
    }

    @Test
    fun `scala 3 - the static forwarder on Cc resolves in class`() =
        `the static forwarder on Cc itself resolves in class against its own init`("scala3")

    @Test
    fun `scala 2 - the static forwarder on Cc resolves in class`() =
        `the static forwarder on Cc itself resolves in class against its own init`("scala2")

    private fun `a companion the lookup cannot read is asked for once, not once per constructor getter`(module: String) {
        val asked = mutableMapOf<String, Int>()
        val analysis =
            analyzeFixtureWithLookup(module, "Cc\$", lookup = { name ->
                asked.merge(name, 1, Int::plus)
                null
            })

        assertTrue(analysis.unresolvedScalaGetterSites.size > 1, "Cc has more than one constructor default, so more than one getter asks")
        // The call-edge pass asks the same lookup about other owners (java.lang.Object here, since
        // the include list is empty); only the companion's own count is under test.
        assertEquals(1, asked["com/example/scalatarget/Cc"], "asked $asked")
    }

    @Test
    fun `scala 3 - a companion the lookup cannot read is asked for once, not once per constructor getter`() =
        `a companion the lookup cannot read is asked for once, not once per constructor getter`("scala3")

    @Test
    fun `scala 2 - a companion the lookup cannot read is asked for once, not once per constructor getter`() =
        `a companion the lookup cannot read is asked for once, not once per constructor getter`("scala2")

    private fun `a lookup returning null leaves the companion's constructor getters unresolved`(module: String) {
        val analysis = analyzeFixtureWithLookup(module, "Cc\$", lookup = { null })

        assertTrue(analysis.scalaGetterSites.none { it.targetName == "<init>" })
        assertTrue(analysis.unresolvedScalaGetterSites.any { it.first.startsWith("\$lessinit\$greater\$default\$") })
    }

    @Test
    fun `scala 3 - a lookup returning null leaves the companion's constructor getters unresolved`() =
        `a lookup returning null leaves the companion's constructor getters unresolved`("scala3")

    @Test
    fun `scala 2 - a lookup returning null leaves the companion's constructor getters unresolved`() =
        `a lookup returning null leaves the companion's constructor getters unresolved`("scala2")

    @Test
    fun `scala 3 - a defaulted enum constructor parameter resolves across the class boundary`() {
        val analysis = analyzeFixtureWithLookup("scala3", "Color\$")

        val site = analysis.scalaGetterSites.single { it.getterName == "\$lessinit\$greater\$default\$1" }
        assertEquals("<init>", site.targetName)
        assertEquals("(I)V", site.targetDescriptor)
        assertEquals(0, site.parameterIndex)
        assertEquals("code", site.parameterName)
        assertEquals("com.example.scalatarget.Color", site.targetClassName)
    }
}
