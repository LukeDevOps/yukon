package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves omission probes (ADR 0021) through the real transform pipeline, on the
 * `DefaultArgumentTarget` fixture, rather than only through
 * [io.github.lukedevops.yukon.instrumentation.branch.BranchSiteAnalyzerTest]'s direct bytecode
 * checks.
 */
class OptionalArgumentInstrumentationTest {
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedYukon: YukonInstrumentation? = null

    private fun fixtureLoader() = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)

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

    @Test
    fun `a final class's default method gets one manifest entry per optional parameter, targeting the real method`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        Class.forName("com.example.target.DefaultArgumentTarget", true, fixtureLoader())

        val probes =
            registry
                .manifest("test", null, "instance-1")
                .probes
                .filter { it.className == "com.example.target.DefaultArgumentTarget" && it.kind == ProbeKind.OPTIONAL_ARGUMENT }

        assertEquals(3, probes.size, "b, c, and d are optional; a is required")
        for (probe in probes) {
            assertEquals("f", probe.methodName, "the target's name, not f\$default")
            assertEquals("(IILjava/lang/String;J)I", probe.methodDescriptor)
            assertFalse(probe.overridable, "f is final, on a final class")
        }
        assertEquals(setOf(1, 2, 3), probes.map { it.parameterIndex }.toSet())
        assertEquals(setOf("b", "c", "d"), probes.map { it.parameterName }.toSet())
    }

    @Test
    fun `omission counts match the number of real calls that omitted each parameter`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val loader = fixtureLoader()
        val callerClass = Class.forName("com.example.target.Caller", true, loader)
        val caller = callerClass.getField("INSTANCE").get(null)
        val targetClass = Class.forName("com.example.target.DefaultArgumentTarget", true, loader)
        val target = targetClass.getDeclaredConstructor().newInstance()
        callerClass.getMethod("callF", targetClass).invoke(caller, target)

        val probes =
            registry
                .manifest("test", null, "instance-1")
                .probes
                .filter { it.className == "com.example.target.DefaultArgumentTarget" && it.kind == ProbeKind.OPTIONAL_ARGUMENT }
        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).batch.deltas

        fun hitsFor(parameterIndex: Int): Long {
            val probe = probes.single { it.parameterIndex == parameterIndex }
            return deltas.singleOrNull { it.probeIndex == probe.probeIndex }?.hitsTotal ?: 0L
        }

        // callF makes four calls: f(1), f(1,2), f(1,2,"y"), f(1,2,"y",3L) - omitting b, c, d
        // three, two, and one times respectively.
        assertEquals(1L, hitsFor(1), "b is omitted by only the first call")
        assertEquals(2L, hitsFor(2), "c is omitted by the first two calls")
        assertEquals(3L, hitsFor(3), "d is omitted by the first three calls")
    }

    @Test
    fun `an open method's omissions land on the base class's probes, since the default lives there`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val loader = fixtureLoader()
        val callerClass = Class.forName("com.example.target.Caller", true, loader)
        val caller = callerClass.getField("INSTANCE").get(null)
        val baseClass = Class.forName("com.example.target.OpenBase", true, loader)
        val subClass = Class.forName("com.example.target.OpenSub", true, loader)
        val sub = subClass.getDeclaredConstructor().newInstance()
        callerClass.getMethod("callOpen", baseClass).invoke(caller, sub)

        val probe =
            registry
                .manifest("test", null, "instance-1")
                .probes
                .single { it.className == "com.example.target.OpenBase" && it.kind == ProbeKind.OPTIONAL_ARGUMENT }
        assertTrue(probe.overridable, "greet is open, on a non-final class")

        val hits =
            registry
                .computeDeltaBatch(ResourceAttributes("test", null, "i-1", null))
                .batch.deltas
                .single { it.classId == probe.classId && it.probeIndex == probe.probeIndex }
                .hitsTotal
        assertEquals(1L, hits)
    }

    @Test
    fun `an interface method's omissions land on the interface's own probes`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val loader = fixtureLoader()
        val callerClass = Class.forName("com.example.target.Caller", true, loader)
        val caller = callerClass.getField("INSTANCE").get(null)
        val greeterClass = Class.forName("com.example.target.Greeter", true, loader)
        val implClass = Class.forName("com.example.target.GreeterImpl", true, loader)
        val impl = implClass.getDeclaredConstructor().newInstance()
        callerClass.getMethod("callInterface", greeterClass).invoke(caller, impl)

        val probe =
            registry
                .manifest("test", null, "instance-1")
                .probes
                .single { it.className == "com.example.target.Greeter" && it.kind == ProbeKind.OPTIONAL_ARGUMENT }
        assertTrue(probe.overridable, "an interface target is overridable")

        val hits =
            registry
                .computeDeltaBatch(ResourceAttributes("test", null, "i-1", null))
                .batch.deltas
                .single { it.classId == probe.classId && it.probeIndex == probe.probeIndex }
                .hitsTotal
        assertEquals(1L, hits)
    }

    @Test
    fun `an inline function's default site is flagged inline`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        Class.forName("com.example.target.DefaultArgumentTargetKt", true, fixtureLoader())

        val probes =
            registry
                .manifest("test", null, "instance-1")
                .probes
                .filter { it.className == "com.example.target.DefaultArgumentTargetKt" && it.methodName == "inlineWithDefault" }
        assertTrue(probes.isNotEmpty())
        assertTrue(probes.all { it.inline }, "both the method probe and its omission probe carry the target's inline flag")
        assertTrue(probes.any { it.kind == ProbeKind.OPTIONAL_ARGUMENT })
    }
}
