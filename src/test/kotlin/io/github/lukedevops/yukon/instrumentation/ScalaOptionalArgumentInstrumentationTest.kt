package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.instrumentation.branch.ScalaFixtures
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves Scala default-getter re-kinding (ADR 0023) through the real transform pipeline, on the
 * `:fixtures-scala3` and `:fixtures-scala2` fixtures, the same way
 * [OptionalArgumentInstrumentationTest] proves the Kotlin `$default` tier. [ProbeRegistry.manifest]
 * is enough for every assertion here; nothing goes over the wire.
 */
class ScalaOptionalArgumentInstrumentationTest {
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedYukon: YukonInstrumentation? = null

    private fun install(
        registry: ProbeRegistry,
        config: AgentConfig,
    ) {
        val instrumentation = ByteBuddyAgent.install()
        val yukon = YukonInstrumentation(config, registry)
        installedYukon = yukon
        installedTransformer = yukon.install(instrumentation)
    }

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { installedYukon?.uninstall(ByteBuddyAgent.install(), it) }
        installedTransformer = null
        installedYukon = null
    }

    private fun newConfig() = AgentConfig.parse("includePackages=com.example.scalatarget")

    private fun callDriver(
        loader: ClassLoader,
        methodName: String,
        times: Int = 1,
    ) {
        val driver = Class.forName("com.example.scalatarget.Driver", true, loader)
        val method = driver.getMethod(methodName)
        repeat(times) { method.invoke(null) }
    }

    private fun `resolved getters get one OPTIONAL_ARGUMENT probe each and no METHOD probe of their own`(module: String) {
        val registry = ProbeRegistry()
        install(registry, newConfig())
        val loader = ScalaFixtures.classLoader(module, javaClass.classLoader)

        callDriver(loader, "callSimpleAllOmitted")

        val probes = registry.manifest("test", null, "instance-1").probes.filter { it.className == "com.example.scalatarget.Simple" }
        val optionalProbes = probes.filter { it.kind == ProbeKind.OPTIONAL_ARGUMENT }

        assertEquals(2, optionalProbes.size, "b and c are optional; a is required")
        for (probe in optionalProbes) {
            assertEquals("f", probe.methodName, "the target's name, not the getter's")
            assertEquals("(IILjava/lang/String;)I", probe.methodDescriptor)
            assertTrue(probe.overridable, "f is not final, on a non-final class")
        }
        assertEquals(setOf(1, 2), optionalProbes.map { it.parameterIndex }.toSet())
        assertEquals(setOf("b", "c"), optionalProbes.map { it.parameterName }.toSet())

        assertTrue(
            probes.none { it.kind == ProbeKind.METHOD && it.methodName.startsWith("f\$default\$") },
            "the getter's own slot is reported as OPTIONAL_ARGUMENT, never also as a METHOD probe",
        )
        assertTrue(
            probes.any { it.kind == ProbeKind.METHOD && it.methodName == "f" },
            "f itself keeps its ordinary method probe",
        )
    }

    @Test
    fun `scala 3 - resolved getters get one OPTIONAL_ARGUMENT probe each and no METHOD probe of their own`() =
        `resolved getters get one OPTIONAL_ARGUMENT probe each and no METHOD probe of their own`("scala3")

    @Test
    fun `scala 2 - resolved getters get one OPTIONAL_ARGUMENT probe each and no METHOD probe of their own`() =
        `resolved getters get one OPTIONAL_ARGUMENT probe each and no METHOD probe of their own`("scala2")

    private fun `omission counts match real omitting calls, and the target's own method hit count is unaffected`(module: String) {
        val registry = ProbeRegistry()
        install(registry, newConfig())
        val loader = ScalaFixtures.classLoader(module, javaClass.classLoader)

        callDriver(loader, "callSimpleAllOmitted", times = 3)
        callDriver(loader, "callSimpleNoneOmitted", times = 2)

        val probes = registry.manifest("test", null, "instance-1").probes.filter { it.className == "com.example.scalatarget.Simple" }
        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).batch.deltas

        fun hitsFor(
            classId: Int,
            probeIndex: Int,
        ): Long = deltas.singleOrNull { it.classId == classId && it.probeIndex == probeIndex }?.hitsTotal ?: 0L

        val bProbe = probes.single { it.kind == ProbeKind.OPTIONAL_ARGUMENT && it.parameterIndex == 1 }
        val cProbe = probes.single { it.kind == ProbeKind.OPTIONAL_ARGUMENT && it.parameterIndex == 2 }
        val fProbe =
            probes.single {
                it.kind == ProbeKind.METHOD && it.methodName == "f" && it.methodDescriptor == "(IILjava/lang/String;)I"
            }

        assertEquals(3L, hitsFor(bProbe.classId, bProbe.probeIndex), "b is omitted by the three all-omitted calls only")
        assertEquals(3L, hitsFor(cProbe.classId, cProbe.probeIndex), "c is omitted by the three all-omitted calls only")
        assertEquals(5L, hitsFor(fProbe.classId, fProbe.probeIndex), "f itself is called by all five driver calls, omitted or not")
    }

    @Test
    fun `scala 3 - omission counts match real omitting calls`() =
        `omission counts match real omitting calls, and the target's own method hit count is unaffected`("scala3")

    @Test
    fun `scala 2 - omission counts match real omitting calls`() =
        `omission counts match real omitting calls, and the target's own method hit count is unaffected`("scala2")

    /**
     * `Plain.f`'s default is resolved statically, so a call through a `Plain`-typed reference
     * whose runtime class only overrides `f` (not its default) still counts against `Plain`'s own
     * getter probe, exactly the way Scala itself resolves the omitted argument.
     */
    private fun `a receiver whose class overrides only f is counted on the base class's getter probe`(module: String) {
        val registry = ProbeRegistry()
        install(registry, newConfig())
        val loader = ScalaFixtures.classLoader(module, javaClass.classLoader)

        callDriver(loader, "callThroughPlainOverridesOnly")

        val probe =
            registry
                .manifest("test", null, "instance-1")
                .probes
                .single { it.className == "com.example.scalatarget.Plain" && it.kind == ProbeKind.OPTIONAL_ARGUMENT }
        assertEquals("f", probe.methodName)
        assertTrue(probe.overridable, "Plain.f is not final, on a non-final class")

        val hits =
            registry
                .computeDeltaBatch(ResourceAttributes("test", null, "i-1", null))
                .batch.deltas
                .single { it.classId == probe.classId && it.probeIndex == probe.probeIndex }
                .hitsTotal
        assertEquals(1L, hits)

        assertTrue(
            registry.manifest("test", null, "instance-1").probes.none {
                it.className == "com.example.scalatarget.PlainOverridesOnly" && it.kind == ProbeKind.OPTIONAL_ARGUMENT
            },
            "PlainOverridesOnly declares no default of its own, so it has no omission probe to hit",
        )
    }

    @Test
    fun `scala 3 - a receiver whose class overrides only f is counted on the base class's getter probe`() =
        `a receiver whose class overrides only f is counted on the base class's getter probe`("scala3")

    @Test
    fun `scala 2 - a receiver whose class overrides only f is counted on the base class's getter probe`() =
        `a receiver whose class overrides only f is counted on the base class's getter probe`("scala2")

    /**
     * Scala 2.13's case-class companion gets its own `apply$default$N`, resolved by the same
     * general same-class rule as any other getter, onto `Cc$.apply` in the same class. Scala 3
     * has no such method (see [io.github.lukedevops.yukon.instrumentation.branch.ScalaGetterResolutionTest]),
     * so this is Scala-2-specific.
     */
    @Test
    fun `scala 2 - apply defaults re-kind onto Cc dollar's own apply in the same class`() {
        val registry = ProbeRegistry()
        install(registry, newConfig())
        val loader = ScalaFixtures.classLoader("scala2", javaClass.classLoader)

        callDriver(loader, "callCaseClassApply", times = 2)

        // Cc$ also carries the constructor default getters $lessinit$greater$default$1/2 (ADR
        // 0023), which resolve separately, cross-class, against Cc's own <init>; isolating by
        // methodName keeps this test to apply's own same-class getters.
        val probes = registry.manifest("test", null, "instance-1").probes.filter { it.className == "com.example.scalatarget.Cc\$" }
        val optionalProbes = probes.filter { it.kind == ProbeKind.OPTIONAL_ARGUMENT && it.methodName == "apply" }
        assertEquals(2, optionalProbes.size)
        for (probe in optionalProbes) {
            assertEquals("apply", probe.methodName)
            assertEquals("(II)Lcom/example/scalatarget/Cc;", probe.methodDescriptor)
            assertFalse(probe.overridable, "Cc\$ is a module; its class is final")
        }

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).batch.deltas

        fun hitsFor(parameterIndex: Int): Long {
            val probe = optionalProbes.single { it.parameterIndex == parameterIndex }
            return deltas.singleOrNull { it.classId == probe.classId && it.probeIndex == probe.probeIndex }?.hitsTotal ?: 0L
        }

        // Driver.callCaseClassApply calls Cc.apply(1), supplying a explicitly and omitting b.
        assertEquals(0L, hitsFor(0), "a is always supplied explicitly")
        assertEquals(2L, hitsFor(1), "b is omitted by both calls")
    }

    /**
     * `Cc$`'s constructor default getters resolve across the class boundary to `Cc`'s own
     * `<init>`, per ADR 0023. `Cc`'s own `<init>` method probe counts every constructor call
     * regardless of which arguments were omitted, since it is a separate probe on a separate class.
     */
    private fun `constructor default getters resolve across the class boundary, and Cc's own init hit count is unaffected`(
        module: String,
    ) {
        val registry = ProbeRegistry()
        install(registry, newConfig())
        val loader = ScalaFixtures.classLoader(module, javaClass.classLoader)

        callDriver(loader, "callCaseClassConstructor") // new Cc(9): supplies a, omits b
        callDriver(loader, "callCaseClassConstructorBothOmitted") // new Cc(): omits both

        // Scala 2's Cc$ also carries apply$default$1/2, its own same-class getters for apply; this
        // isolates the constructor getters by target name so both fixture modules assert the same
        // count regardless of that difference.
        val probes = registry.manifest("test", null, "instance-1").probes
        val optionalProbes =
            probes.filter {
                it.kind == ProbeKind.OPTIONAL_ARGUMENT && it.className == "com.example.scalatarget.Cc\$" && it.methodName == "<init>"
            }
        assertEquals(2, optionalProbes.size)
        for (probe in optionalProbes) {
            assertEquals("<init>", probe.methodName, "the target's name, not the getter's")
            assertEquals("(II)V", probe.methodDescriptor)
            assertEquals("com.example.scalatarget.Cc", probe.targetClassName, "the target lives on Cc, not Cc\$")
            assertFalse(probe.overridable, "a constructor is never overridable")
        }

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).batch.deltas

        fun hitsFor(parameterIndex: Int): Long {
            val probe = optionalProbes.single { it.parameterIndex == parameterIndex }
            return deltas.singleOrNull { it.classId == probe.classId && it.probeIndex == probe.probeIndex }?.hitsTotal ?: 0L
        }

        assertEquals(1L, hitsFor(0), "a is omitted only by the both-omitted call")
        assertEquals(2L, hitsFor(1), "b is omitted by both calls")

        val initProbe =
            probes.single {
                it.kind == ProbeKind.METHOD && it.className == "com.example.scalatarget.Cc" && it.methodName == "<init>"
            }
        val initHits = deltas.singleOrNull { it.classId == initProbe.classId && it.probeIndex == initProbe.probeIndex }?.hitsTotal ?: 0L
        assertEquals(2L, initHits, "Cc's own <init> is called by both driver calls, however many arguments were omitted")
    }

    @Test
    fun `scala 3 - constructor default getters resolve across the class boundary`() =
        `constructor default getters resolve across the class boundary, and Cc's own init hit count is unaffected`("scala3")

    @Test
    fun `scala 2 - constructor default getters resolve across the class boundary`() =
        `constructor default getters resolve across the class boundary, and Cc's own init hit count is unaffected`("scala2")

    /**
     * `Cc`'s own static forwarder for the constructor default getter resolves in class to
     * `Cc.<init>`, alongside the module getter on `Cc$` that resolves to the same target across
     * the class boundary: one parameter, two omission probes, an intended and known shape. A
     * consumer that judges each probe on its own instead of summing them, as ADR 0023's
     * consequences describe, can find the forwarder's own zero reading as "always supplied"
     * beside the module getter's "never supplied" for the same parameter.
     */
    private fun `Cc's constructor default getter for parameter 0 has both a module probe and its own forwarder probe`(module: String) {
        val registry = ProbeRegistry()
        install(registry, newConfig())
        val loader = ScalaFixtures.classLoader(module, javaClass.classLoader)

        callDriver(loader, "callCaseClassConstructor") // new Cc(9): supplies a, omits b

        val probes = registry.manifest("test", null, "instance-1").probes
        val parameterZeroProbes =
            probes.filter {
                it.kind == ProbeKind.OPTIONAL_ARGUMENT &&
                    it.methodName == "<init>" &&
                    it.parameterIndex == 0 &&
                    it.methodDescriptor == "(II)V"
            }

        assertEquals(2, parameterZeroProbes.size, "the module getter and Cc's own forwarder both resolve to <init>'s parameter 0")

        val moduleGetter = parameterZeroProbes.single { it.className == "com.example.scalatarget.Cc\$" }
        assertEquals(
            "com.example.scalatarget.Cc",
            moduleGetter.targetClassName,
            "the module getter on Cc\$ resolves across the class boundary",
        )

        val forwarder = parameterZeroProbes.single { it.className == "com.example.scalatarget.Cc" }
        assertEquals(null, forwarder.targetClassName, "Cc's own static forwarder resolves in class")
    }

    @Test
    fun `scala 3 - Cc's constructor default getter for parameter 0 has both a module probe and its own forwarder probe`() =
        `Cc's constructor default getter for parameter 0 has both a module probe and its own forwarder probe`("scala3")

    @Test
    fun `scala 2 - Cc's constructor default getter for parameter 0 has both a module probe and its own forwarder probe`() =
        `Cc's constructor default getter for parameter 0 has both a module probe and its own forwarder probe`("scala2")

    /**
     * Scala 3 resolves `Cc.apply(...)` through the same constructor default getters as `new
     * Cc(...)`, per ADR 0023's consequences: unlike Scala 2, `Cc$` has no `apply$default$N` of its
     * own for Scala 3 to fall back to.
     */
    @Test
    fun `scala 3 - Cc apply also routes through the constructor getters`() {
        val registry = ProbeRegistry()
        install(registry, newConfig())
        val loader = ScalaFixtures.classLoader("scala3", javaClass.classLoader)

        callDriver(loader, "callCaseClassApply") // Cc.apply(1): supplies a, omits b

        val bProbe =
            registry
                .manifest("test", null, "instance-1")
                .probes
                .single {
                    it.kind == ProbeKind.OPTIONAL_ARGUMENT && it.className == "com.example.scalatarget.Cc\$" && it.parameterIndex == 1
                }
        assertEquals("<init>", bProbe.methodName)
        assertEquals("com.example.scalatarget.Cc", bProbe.targetClassName)

        val hits =
            registry
                .computeDeltaBatch(ResourceAttributes("test", null, "i-1", null))
                .batch.deltas
                .single { it.classId == bProbe.classId && it.probeIndex == bProbe.probeIndex }
                .hitsTotal
        assertEquals(1L, hits)
    }
}
