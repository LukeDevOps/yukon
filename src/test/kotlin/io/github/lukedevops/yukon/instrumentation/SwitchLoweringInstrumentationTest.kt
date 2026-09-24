package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.BranchRole
import io.github.lukedevops.yukon.export.BranchSite
import io.github.lukedevops.yukon.export.ConditionPart
import io.github.lukedevops.yukon.export.ConditionPartKind
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ProbeLocation
import io.github.lukedevops.yukon.export.ProbeManifest
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves through the real transform that a switch read back to its source cases reaches the
 * manifest as one site with its labels, that the lowering's own jumps and a throwing default get
 * no probe, and that the woven code still runs every case. See ADR 0038.
 */
class SwitchLoweringInstrumentationTest {
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedYukon: YukonInstrumentation? = null

    private val resource = ResourceAttributes("test", null, "instance-1", null, "run-1")

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { installedYukon?.uninstall(ByteBuddyAgent.install(), it) }
        installedTransformer = null
        installedYukon = null
    }

    private fun installed(): ProbeRegistry {
        val registry = ProbeRegistry()
        val yukon = YukonInstrumentation(AgentConfig.parse("includePackages=com.example.target"), registry)
        installedYukon = yukon
        installedTransformer = yukon.install(ByteBuddyAgent.install())
        return registry
    }

    private fun fixtureLoader() =
        FixtureClassLoader(
            arrayOf(File("build/classes/kotlin/test").toURI().toURL(), File("build/classes/java/test").toURI().toURL()),
            javaClass.classLoader,
        )

    private fun methodProbe(
        manifest: ProbeManifest,
        className: String,
        methodName: String,
    ): ProbeLocation = manifest.probes.single { it.className == className && it.kind == ProbeKind.METHOD && it.methodName == methodName }

    private fun branchProbes(
        manifest: ProbeManifest,
        className: String,
        methodName: String,
    ): List<ProbeLocation> =
        manifest.probes.filter {
            it.className == className && it.kind == ProbeKind.BRANCH &&
                it.methodName == methodName
        }

    /** Hits per BRANCH probe's branch index, over [branches]. */
    private fun hitsByBranchIndex(
        registry: ProbeRegistry,
        branches: List<ProbeLocation>,
    ): Map<Int, Long> {
        val hitsByProbe =
            registry
                .computeDeltaBatch(resource)
                .batch.deltas
                .filter { it.classId == branches.first().classId }
                .associate { it.probeIndex to it.hitsTotal }
        return branches.associate { it.branchIndex!! to (hitsByProbe[it.probeIndex] ?: 0L) }
    }

    private fun labelOf(site: BranchSite): List<String> = site.outcomes.map { it.caseLabel.singleOrNull()?.text ?: it.role.name }

    @Test
    fun `a throwing default is not listed and gets no probe, and the woven code still runs every case`() {
        val registry = installed()
        val loader = fixtureLoader()
        val javaTarget = Class.forName("com.example.target.SwitchJavaTarget", true, loader)
        val kotlinTarget = Class.forName("com.example.target.SwitchTarget", true, loader)
        val color = Class.forName("com.example.target.SwitchColor", true, loader)
        val tint = Class.forName("com.example.target.Tint", true, loader)
        val java = javaTarget.getDeclaredConstructor().newInstance()
        val kotlin = kotlinTarget.getDeclaredConstructor().newInstance()

        val colors = color.enumConstants.associateBy { (it as Enum<*>).name }
        val tints = tint.enumConstants.associateBy { (it as Enum<*>).name }
        val enumExpression = javaTarget.getMethod("enumExpression", color)
        assertEquals(listOf(1, 2, 3), listOf("RED", "GREEN", "BLUE").map { enumExpression.invoke(java, colors[it]) })
        val circle = Class.forName("com.example.target.SwitchJavaTarget\$Circle", true, loader)
        val square = Class.forName("com.example.target.SwitchJavaTarget\$Square", true, loader)
        val sealedPattern = javaTarget.getMethod("sealedPattern", Class.forName("com.example.target.SwitchJavaTarget\$Shape", true, loader))
        assertEquals(1, sealedPattern.invoke(java, circle.getConstructor(Int::class.java).newInstance(1)))
        assertEquals(2, sealedPattern.invoke(java, square.getConstructor(Int::class.java).newInstance(2)))
        val enumExhaustive = kotlinTarget.getMethod("enumExhaustive", tint)
        val enumStatement = kotlinTarget.getMethod("enumStatement", tint)
        assertEquals(listOf(1, 2, 3), listOf("RED", "GREEN", "BLUE").map { enumExhaustive.invoke(kotlin, tints[it]) })
        assertEquals(listOf(1, 2, 3), listOf("RED", "GREEN", "BLUE").map { enumStatement.invoke(kotlin, tints[it]) })

        val manifest = registry.manifest(resource)
        for ((className, method, labels) in listOf(
            Triple(javaTarget.name, "enumExpression", listOf("RED", "BLUE", "GREEN")),
            Triple(javaTarget.name, "sealedPattern", listOf("Circle", "Square")),
            Triple(kotlinTarget.name, "enumExhaustive", listOf("RED", "BLUE", "GREEN")),
            Triple(kotlinTarget.name, "enumStatement", listOf("RED", "BLUE", "GREEN")),
        )) {
            val site = methodProbe(manifest, className, method).branchSites.single()
            val branches = branchProbes(manifest, className, method)
            assertEquals(labels, labelOf(site), "$method lists its cases and no default")
            assertEquals(site.outcomes.map { it.branchIndex }, branches.map { it.branchIndex!! }.sorted(), "$method probes each case only")
            val hits = hitsByBranchIndex(registry, branches)
            assertTrue(site.outcomes.all { (hits[it.branchIndex] ?: 0L) == 1L }, "$method counts each case once: $hits")
        }
    }

    @Test
    fun `a rebuilt string switch is one site with its literals, and its lowering is not probed`() {
        val registry = installed()
        val loader = fixtureLoader()
        val targetClass = Class.forName("com.example.target.SwitchJavaTarget", true, loader)
        val target = targetClass.getDeclaredConstructor().newInstance()
        val stringStatement = targetClass.getMethod("stringStatement", String::class.java)
        assertEquals(
            listOf(1, 2, 2, 3, 4, 0),
            listOf("open", "closed", "done", "Aa", "BB", "other").map { stringStatement.invoke(target, it) },
        )

        val manifest = registry.manifest(resource)
        val site = methodProbe(manifest, targetClass.name, "stringStatement").branchSites.single()
        val branches = branchProbes(manifest, targetClass.name, "stringStatement")
        assertEquals(listOf("open", "closed", "done", "Aa", "BB", BranchRole.DEFAULT.name), labelOf(site))
        assertTrue(site.outcomes.dropLast(1).all { it.caseLabel.single().kind == ConditionPartKind.STRING_LITERAL && it.caseKey == null })
        assertEquals(listOf(ConditionPart(ConditionPartKind.CODE, "status")), site.condition)
        assertEquals(
            site.outcomes.map {
                it.branchIndex
            },
            branches.map { it.branchIndex!! }.sorted(),
            "the hash switch and equals checks get no probe",
        )
        val hits = hitsByBranchIndex(registry, branches)
        assertTrue(site.outcomes.all { hits[it.branchIndex] == 1L }, "each literal and the default counted once: $hits")
    }
}
