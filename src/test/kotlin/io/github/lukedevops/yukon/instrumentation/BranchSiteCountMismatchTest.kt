package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
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
import kotlin.test.assertTrue

/**
 * Proves the mismatch path end to end, without [ClassBytesCapture] in the way: with
 * `captureClassBytes = false`, the branch analysis falls back to reading the class as a
 * classloader resource (the on-disk `.class` file), while a fake earlier transformer, the same
 * one [BranchBytesCaptureTest] uses, swaps in a version of `BranchTarget` whose `classify` has an
 * extra conditional. The two byte streams disagree on how many branch slots `classify` needs, so
 * the transform must fail, and the class must still load and run from its original, unrewritten
 * bytes.
 */
class BranchSiteCountMismatchTest {
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
    fun `a class whose rewritten bytes disagree with its analysed bytes is skipped and still runs uninstrumented`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        instrumentation.addTransformer(earlierAgent, false)
        val yukon = YukonInstrumentation(config, registry, captureClassBytes = false)
        this.yukon = yukon
        installedTransformer = yukon.install(instrumentation)

        val loader = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        val targetClass = Class.forName("com.example.target.BranchTarget", true, loader)
        val target = targetClass.getDeclaredConstructor().newInstance()
        val classify = targetClass.getMethod("classify", Int::class.java)
        assertEquals("large", classify.invoke(target, 500), "the class still loads and runs, from the swapped, unrewritten bytes")

        assertTrue(
            "com.example.target.BranchTarget" !in registry.registeredClassNames(),
            "the speculative registration is rolled back",
        )
        val manifest = registry.manifest("test", null, "instance-1")
        assertTrue(manifest.probes.none { it.className == "com.example.target.BranchTarget" }, "no probe exists for the skipped class")
        val skipped = manifest.skippedClasses.filter { it.className == "com.example.target.BranchTarget" }
        assertEquals(1, skipped.size, "the class appears once in skippedClasses")
        assertTrue("9" in skipped.single().reason, "reason names the slot count sized from the analysed bytes")
        assertTrue("11" in skipped.single().reason, "reason names the slot count wanted at rewrite time")
    }
}
