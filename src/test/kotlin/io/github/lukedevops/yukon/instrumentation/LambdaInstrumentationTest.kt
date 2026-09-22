package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.instrumentation.branch.ScalaFixtures
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves through the real pipeline that a lambda body gets probed like any other method, method
 * tier and branch tier alike: javac's `lambda$...` and scalac's `$anonfun$...` inside a class
 * scalac itself compiled (ADR 0015). Scala 2's `$adapted` boxing forwarder gets no probe of its
 * own, matching Scala 3, whose adapter is a bridge.
 */
class LambdaInstrumentationTest {
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

    @Test
    fun `a javac lambda body gets a method probe and its own conditional gets branch probes`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val loader = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        val targetClass = Class.forName("com.example.target.LambdaTarget", true, loader)
        val target = targetClass.getDeclaredConstructor().newInstance()
        val classifyViaLambda = targetClass.getMethod("classifyViaLambda", Int::class.java)
        repeat(5) { classifyViaLambda.invoke(target, 5) }

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val lambdaMethodProbe =
            manifest.probes.single { it.methodName == "lambda\$classifyViaLambda\$0" && it.kind == ProbeKind.METHOD }
        val lambdaBranchIndices =
            manifest.probes.filter { it.methodName == "lambda\$classifyViaLambda\$0" && it.kind == ProbeKind.BRANCH }.map { it.probeIndex }
        assertEquals(2, lambdaBranchIndices.size, "the lambda body's own if/else is a two-outcome conditional")

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1")).batch.deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        assertEquals(5L, byIndex.getValue(lambdaMethodProbe.probeIndex).hitsTotal, "called once per classifyViaLambda invocation")
        val hitBranches = lambdaBranchIndices.filter { it in byIndex }
        assertEquals(1, hitBranches.size, "value 5 only ever takes the positive outcome")
        assertEquals(5L, byIndex.getValue(hitBranches.single()).hitsTotal)
    }

    @Test
    fun `a method reference's target keeps behaving as an ordinary probed method`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val loader = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        val targetClass = Class.forName("com.example.target.LambdaTarget", true, loader)
        val target = targetClass.getDeclaredConstructor().newInstance()
        repeat(4) { targetClass.getMethod("shipViaMethodReference").invoke(target) }

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val shipProbe = manifest.probes.single { it.methodName == "ship" && it.kind == ProbeKind.METHOD }

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1")).batch.deltas
        assertEquals(4L, deltas.single { it.probeIndex == shipProbe.probeIndex }.hitsTotal)
    }

    @Test
    fun `a scalac lambda body inside a class carrying the Scala attribute gets probed too`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.scalatarget")
        install(registry, config)

        val loader = ScalaFixtures.classLoader("scala2", javaClass.classLoader)
        val moduleClass = Class.forName("com.example.scalatarget.LambdaHost\$", true, loader)
        val module = moduleClass.getField("MODULE\$").get(null)
        val classify = moduleClass.getMethod("classify", Int::class.java)
        classify.invoke(module, 7)
        classify.invoke(module, 8)

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val anonfunMethodProbes = manifest.probes.filter { it.methodName == "\$anonfun\$classify\$1" && it.kind == ProbeKind.METHOD }
        assertTrue(anonfunMethodProbes.isNotEmpty(), "the scalac lambda body must be probed inside a Scala class")

        assertTrue(
            manifest.probes.none { it.methodName.endsWith("\$adapted") },
            "Scala 2's boxing forwarder beside the body is excluded, so a lambda yields one method probe",
        )

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1")).batch.deltas
        val byIndex = deltas.associateBy { it.probeIndex }
        assertEquals(2L, byIndex.getValue(anonfunMethodProbes.single().probeIndex).hitsTotal)
    }

    @Test
    fun `a Scala 3 lambda body is probed and its bridge adapter is not`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.scalatarget")
        install(registry, config)

        val loader = ScalaFixtures.classLoader("scala3", javaClass.classLoader)
        val moduleClass = Class.forName("com.example.scalatarget.LambdaHost\$", true, loader)
        val module = moduleClass.getField("MODULE\$").get(null)
        val classify = moduleClass.getMethod("classify", Int::class.java)
        classify.invoke(module, 7)
        classify.invoke(module, -1)
        classify.invoke(module, 3)

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val bodyProbe = manifest.probes.single { it.methodName == "\$anonfun\$1" && it.kind == ProbeKind.METHOD }
        assertTrue(manifest.probes.none { it.methodName.startsWith("\$anonfun\$adapted") }, "the bridge adapter is not a node")
        val branchIndices = manifest.probes.filter { it.methodName == "\$anonfun\$1" && it.kind == ProbeKind.BRANCH }.map { it.probeIndex }
        assertEquals(2, branchIndices.size, "the body's if/else is one two-outcome conditional")

        val byIndex =
            registry
                .computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1"))
                .batch.deltas
                .associateBy { it.probeIndex }
        assertEquals(3L, byIndex.getValue(bodyProbe.probeIndex).hitsTotal)
        assertEquals(setOf(2L, 1L), branchIndices.map { byIndex.getValue(it).hitsTotal }.toSet(), "two positive, one non-positive")
    }
}
