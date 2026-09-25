package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.CallEdge
import io.github.lukedevops.yukon.export.GeneratedBy
import io.github.lukedevops.yukon.export.KotlinKind
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves ADR 0041 through the real transform pipeline: each loaded class's `ClassLocation` carries
 * its Kotlin kind, a multi-file part is probed though kotlinc marks it synthetic, the facade's
 * forwarder is marked `MULTIFILE_FACADE`, and a call to the facade names the part.
 */
class KotlinKindInstrumentationTest {
    private companion object {
        const val TARGET = "com.example.target"
        const val GREETING = "multifileGreeting"
        const val STRING_TO_STRING = "(Ljava/lang/String;)Ljava/lang/String;"
    }

    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedYukon: YukonInstrumentation? = null

    private val resource = ResourceAttributes("test", null, "instance-1", null, "run-1")

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { installedYukon?.uninstall(ByteBuddyAgent.install(), it) }
        installedTransformer = null
        installedYukon = null
    }

    private fun installAndRun(): ProbeRegistry {
        val registry = ProbeRegistry()
        val yukon = YukonInstrumentation(AgentConfig.parse("includePackages=$TARGET"), registry)
        installedYukon = yukon
        installedTransformer = yukon.install(ByteBuddyAgent.install())

        val loader =
            FixtureClassLoader(
                arrayOf(File("build/classes/kotlin/test").toURI().toURL(), File("build/classes/java/test").toURI().toURL()),
                javaClass.classLoader,
            )
        Class.forName("$TARGET.KotlinKindTargetKt", true, loader).getMethod("callsMultifileGreeting").invoke(null)
        Class.forName("$TARGET.KindClass", true, loader).getDeclaredConstructor().newInstance()
        Class.forName("$TARGET.KindObject", true, loader)
        Class.forName("$TARGET.SampleTarget", true, loader).getDeclaredConstructor().newInstance()
        return registry
    }

    @Test
    fun `the manifest carries each loaded class's Kotlin kind`() {
        val manifest = installAndRun().manifest(resource)

        val namesById = manifest.probes.associate { it.classId to it.className.removePrefix("$TARGET.") }
        val kinds = manifest.classLocations.associate { namesById.getValue(it.classId) to it.kotlinKind }
        assertEquals(KotlinKind.KOTLIN_CLASS, kinds["KindClass"])
        assertEquals(KotlinKind.KOTLIN_CLASS, kinds["KindObject"])
        assertEquals(KotlinKind.FILE_FACADE, kinds["KotlinKindTargetKt"])
        assertEquals(KotlinKind.MULTIFILE_CLASS_FACADE, kinds["MultifileText"])
        assertEquals(KotlinKind.MULTIFILE_CLASS_PART, kinds["MultifileText__MultifileGreetingKt"])
        assertEquals(KotlinKind.NONE, kinds["SampleTarget"])
    }

    @Test
    fun `the synthetic part is probed and counts its hit, and the facade's forwarder is MULTIFILE_FACADE`() {
        val registry = installAndRun()
        val probes = registry.manifest(resource).probes.filter { it.kind == ProbeKind.METHOD && it.methodName == GREETING }
        val hits =
            registry
                .computeDeltaBatch(resource)
                .batch.deltas
                .associate { (it.classId to it.probeIndex) to it.hitsTotal }

        val facade = probes.single { it.className == "$TARGET.MultifileText" }
        val part = probes.single { it.className == "$TARGET.MultifileText__MultifileGreetingKt" }
        assertEquals(GeneratedBy.MULTIFILE_FACADE, facade.generatedBy)
        assertEquals(GeneratedBy.NONE, part.generatedBy)
        assertEquals(1L, hits[part.classId to part.probeIndex])
        assertEquals(1L, hits[facade.classId to facade.probeIndex], "a generated forwarder that ran still counts its hit")
    }

    @Test
    fun `a call to the facade is an edge to the part's function, plus the facade's clinit`() {
        val caller =
            installAndRun()
                .manifest(resource)
                .probes
                .single { it.className == "$TARGET.KotlinKindTargetKt" && it.methodName == "callsMultifileGreeting" }

        assertEquals(
            listOf(
                CallEdge("$TARGET.MultifileText__MultifileGreetingKt", GREETING, STRING_TO_STRING, virtual = false),
                CallEdge("$TARGET.MultifileText", "<clinit>", "()V", virtual = false),
            ),
            caller.calls,
        )
    }
}
