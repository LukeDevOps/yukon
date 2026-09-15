package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
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
 * Proves inline marking (ADR 0022) through the real transform pipeline, on the Kotlin
 * `InlineTarget` fixture, rather than only through [io.github.lukedevops.yukon.instrumentation.branch.BranchSiteAnalyzerTest]'s
 * direct bytecode checks.
 */
class InlineMarkingTest {
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedYukon: YukonInstrumentation? = null

    /** `InlineTarget.kt` is a Kotlin fixture, so it compiles under the Kotlin test output directory, not the Java one. */
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
    fun `inline methods are marked inline, and a plain method and a non-inline overload are not`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val target = Class.forName("com.example.target.InlineTarget", true, fixtureLoader())
        target.getDeclaredConstructor().newInstance()

        val probes = registry.manifest("test", null, "instance-1").probes.filter { it.className == "com.example.target.InlineTarget" }

        assertTrue(probes.single { it.methodName == "member" }.inline, "member is declared inline")
        assertFalse(probes.single { it.methodName == "plain" }.inline, "plain is not inline")
        assertFalse(
            probes.single { it.methodName == "same" && it.methodDescriptor == "(I)I" }.inline,
            "the non-inline overload merely calls its inline sibling, it is not inline itself",
        )
        assertTrue(probes.single { it.methodName == "same" && it.methodDescriptor == "(II)I" }.inline, "this overload is declared inline")
    }

    @Test
    fun `a top-level inline function's own file-facade class is also marked inline`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        Class.forName("com.example.target.InlineTargetKt", true, fixtureLoader())

        val probes = registry.manifest("test", null, "instance-1").probes.filter { it.className == "com.example.target.InlineTargetKt" }
        assertTrue(probes.single { it.methodName == "topLevelInline" }.inline)
        assertFalse(probes.single { it.methodName == "topLevelPlain" }.inline)
    }

    @Test
    fun `calling an inline function through its own compiled method, as Java or reflection would, still increments its slot`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val target = Class.forName("com.example.target.InlineTarget", true, fixtureLoader())
        val instance = target.getDeclaredConstructor().newInstance()
        // Reflection always invokes the compiled method directly, the same path a Java caller
        // takes. A Kotlin caller would instead have copied the body into its own call site and
        // never reached this method at all.
        target.getMethod("member", Int::class.java, Int::class.java).invoke(instance, 1, 2)

        val memberProbe =
            registry
                .manifest("test", null, "instance-1")
                .probes
                .single { it.className == "com.example.target.InlineTarget" && it.methodName == "member" }
        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).batch.deltas
        assertEquals(1L, deltas.single { it.probeIndex == memberProbe.probeIndex }.hitsTotal)
    }
}
