package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.CallEdge
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves through the real pipeline that call edges and supertypes (ADR 0024) reach the manifest:
 * a METHOD probe carries its own in-scope call edges, a BRANCH probe carries none, and the class
 * gets its own [io.github.lukedevops.yukon.export.ClassSupertypes] record.
 */
class CallEdgeInstrumentationTest {
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
    fun `a METHOD probe carries its calls, a BRANCH probe carries none, and the class gets a supertypes record`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val loader = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)
        val targetClass = Class.forName("com.example.target.CallEdgeTarget", true, loader)
        val target = targetClass.getDeclaredConstructor().newInstance()
        targetClass.getMethod("callsPrivateMethod").invoke(target)
        targetClass.getMethod("callsSelfRecursively", Int::class.java).invoke(target, 1)

        val manifest = registry.manifest("test", null, "instance-1")
        val classEntry = manifest.probes.filter { it.className == "com.example.target.CallEdgeTarget" }

        val callsPrivateMethodProbe = classEntry.single { it.methodName == "callsPrivateMethod" && it.kind == ProbeKind.METHOD }
        assertEquals(
            listOf(CallEdge("com.example.target.CallEdgeTarget", "privateHelper", "()I", virtual = false)),
            callsPrivateMethodProbe.calls,
        )

        val branchProbes = classEntry.filter { it.kind == ProbeKind.BRANCH }
        assertTrue(branchProbes.isNotEmpty(), "callsSelfRecursively's if/else contributes branch probes")
        assertTrue(branchProbes.all { it.calls.isEmpty() }, "a BRANCH probe never carries call edges")

        val supertypes = manifest.classSupertypes.single { it.classId == callsPrivateMethodProbe.classId }
        assertEquals("java.lang.Object", supertypes.superClassName)
        assertEquals(emptyList(), supertypes.interfaceNames)
    }

    @Test
    fun `through the real matcher, a template method's edge to its abstract step survives`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val loader = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)
        Class.forName("com.example.target.TemplateTarget", true, loader)

        val runProbe =
            registry.manifest("test", null, "instance-1").probes.single {
                it.className == "com.example.target.TemplateTarget" && it.methodName == "run" && it.kind == ProbeKind.METHOD
            }
        assertEquals(listOf(CallEdge("com.example.target.TemplateTarget", "step", "()I", virtual = true)), runProbe.calls)
    }
}
