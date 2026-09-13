package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.commons.ClassRemapper
import net.bytebuddy.jar.asm.commons.SimpleRemapper
import java.io.File
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.security.ProtectionDomain
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves the branch tier analyses the bytes ByteBuddy actually rewrites, not the `.class` file on
 * disk. A transformer registered ahead of Yukon's swaps `BranchTarget` for a version whose
 * `classify` has two conditionals instead of one, the way another agent earlier in the chain
 * would leave the class. Without [ClassBytesCapture], the analysis would size the array from the
 * on-disk bytes (one site) while the rewrite planted probes for two.
 */
class BranchBytesCaptureTest {
    private val instrumentation: Instrumentation = ByteBuddyAgent.install()
    private var yukon: YukonInstrumentation? = null
    private var installedTransformer: ResettableClassFileTransformer? = null

    /** Stands in for an earlier agent: replaces BranchTarget's bytes with BranchTargetWithExtraBranches, renamed. */
    private val earlierAgent =
        object : ClassFileTransformer {
            override fun transform(
                loader: ClassLoader?,
                className: String?,
                classBeingRedefined: Class<*>?,
                protectionDomain: ProtectionDomain?,
                classfileBuffer: ByteArray,
            ): ByteArray? {
                if (className != "com/example/target/BranchTarget") return null
                val source = File("build/classes/java/test/com/example/target/BranchTargetWithExtraBranches.class").readBytes()
                val writer = ClassWriter(0)
                val remapper = SimpleRemapper("com/example/target/BranchTargetWithExtraBranches", "com/example/target/BranchTarget")
                ClassReader(source).accept(ClassRemapper(writer, remapper), 0)
                return writer.toByteArray()
            }
        }

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { yukon?.uninstall(instrumentation, it) }
        instrumentation.removeTransformer(earlierAgent)
    }

    @Test
    fun `branch probes are sized and planted from the bytes an earlier transformer produced`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        instrumentation.addTransformer(earlierAgent, false)
        val yukon = YukonInstrumentation(config, registry)
        this.yukon = yukon
        installedTransformer = yukon.install(instrumentation)

        val loader = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        val targetClass = Class.forName("com.example.target.BranchTarget", true, loader)
        val target = targetClass.getDeclaredConstructor().newInstance()
        val classify = targetClass.getMethod("classify", Int::class.java)
        assertEquals("large", classify.invoke(target, 500), "the swapped-in bytes are the ones that loaded")
        repeat(3) { classify.invoke(target, 5) }

        val manifest = registry.manifest("test", null, "instance-1")
        val classifyBranches = manifest.probes.filter { it.methodName == "classify" && it.kind == ProbeKind.BRANCH }
        assertEquals(4, classifyBranches.size, "two conditionals, two outcomes each, as in the rewritten bytes")

        val byIndex =
            registry
                .computeDeltaBatch(ResourceAttributes("test", null, "i-1", null))
                .batch.deltas
                .associateBy { it.probeIndex }
        val hits = classifyBranches.map { byIndex[it.probeIndex]?.hitsTotal ?: 0L }.sorted()
        // value=500 takes the first jump's "taken" edge once; value=5 falls through the first
        // jump three times and takes the second jump's edge three times.
        assertEquals(listOf(0L, 1L, 3L, 3L), hits)
    }
}
