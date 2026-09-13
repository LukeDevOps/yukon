package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.instrumentation.staticscan.StaticBaselineMismatchDetector
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YukonInstrumentationTest {
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedYukon: YukonInstrumentation? = null

    private fun loadFixtureFresh(): Any {
        val classesDir = File("build/classes/java/test")
        val loader = FixtureClassLoader(arrayOf(classesDir.toURI().toURL()), javaClass.classLoader)
        val targetClass = Class.forName("com.example.target.SampleTarget", true, loader)
        return targetClass.getDeclaredConstructor().newInstance()
    }

    /**
     * Each test loads its own fresh definition of the fixture class. But the underlying
     * [java.lang.instrument.Instrumentation] instance is process-wide. A transformer left
     * registered from a previous test would also fire on the next test's fixture class load,
     * and both would fight over the same synthetic field name. [tearDown] deregisters the
     * transformer afterwards, to prevent that.
     */
    private fun install(
        registry: ProbeRegistry,
        config: AgentConfig,
        staticBaselineMismatchDetector: StaticBaselineMismatchDetector = StaticBaselineMismatchDetector(),
    ): Any {
        val instrumentation = ByteBuddyAgent.install()
        val yukon = YukonInstrumentation(config, registry, staticBaselineMismatchDetector)
        installedYukon = yukon
        installedTransformer = yukon.install(instrumentation)
        return loadFixtureFresh()
    }

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { installedYukon?.uninstall(ByteBuddyAgent.install(), it) }
        installedTransformer = null
        installedYukon = null
    }

    @Test
    fun `only the methods that were actually called show up in the next delta batch`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")

        val target = install(registry, config)
        target.javaClass.getMethod("ping").invoke(target)

        val manifest = registry.manifest("test", null, "instance-1")
        val neverCalledProbeIndex = manifest.probes.single { it.methodName == "neverCalled" }.probeIndex
        val pingProbeIndex = manifest.probes.single { it.methodName == "ping" }.probeIndex

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).batch.deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        assertEquals(1L, byIndex.getValue(pingProbeIndex).hitsTotal)
        assertTrue(neverCalledProbeIndex !in byIndex)
    }

    @Test
    fun `hits to two different methods on the same instance land in independent counters`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")

        val target = install(registry, config)
        repeat(3) { target.javaClass.getMethod("ping").invoke(target) }
        repeat(2) { target.javaClass.getMethod("neverCalled").invoke(target) }

        val manifest = registry.manifest("test", null, "instance-1")
        val neverCalledProbeIndex = manifest.probes.single { it.methodName == "neverCalled" }.probeIndex
        val pingProbeIndex = manifest.probes.single { it.methodName == "ping" }.probeIndex

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).batch.deltas
        val byIndex = deltas.associateBy { it.probeIndex }

        assertEquals(3L, byIndex.getValue(pingProbeIndex).hitsTotal)
        assertEquals(2L, byIndex.getValue(neverCalledProbeIndex).hitsTotal)
    }

    @Test
    fun `a class under excludePackages is left uninstrumented even though it also matches includePackages`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target,excludePackages=com.example.target.SampleTarget")

        val target = install(registry, config)
        target.javaClass.getMethod("ping").invoke(target)

        assertTrue("com.example.target.SampleTarget" !in registry.registeredClassNames())
    }

    private fun fixtureLoader() = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)

    @Test
    fun `an interface's default and static methods are probed, and its abstract method is not`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)
        val loader = fixtureLoader()

        val iface = Class.forName("com.example.target.DefaultMethodTarget", true, loader)
        val impl = Class.forName("com.example.target.DefaultMethodImpl", true, loader)
        val instance = impl.getDeclaredConstructor().newInstance()
        repeat(2) { iface.getMethod("defaultThing").invoke(instance) }
        iface.getMethod("staticThing").invoke(null)

        val manifest = registry.manifest("test", null, "instance-1")
        val ifaceProbes = manifest.probes.filter { it.className == "com.example.target.DefaultMethodTarget" }
        assertEquals(setOf("defaultThing", "staticThing"), ifaceProbes.map { it.methodName }.toSet())
        assertTrue(manifest.skippedClasses.none { it.className == "com.example.target.DefaultMethodTarget" })

        // Two classes are loaded here, so key on the interface's own class id, not probe index alone.
        val ifaceClassId = ifaceProbes.first().classId
        val byIndex =
            registry
                .computeDeltaBatch(ResourceAttributes("test", null, "i-1", null))
                .batch.deltas
                .filter { it.classId == ifaceClassId }
                .associateBy { it.probeIndex }
        assertEquals(2L, byIndex.getValue(ifaceProbes.single { it.methodName == "defaultThing" }.probeIndex).hitsTotal)
        assertEquals(1L, byIndex.getValue(ifaceProbes.single { it.methodName == "staticThing" }.probeIndex).hitsTotal)
    }

    @Test
    fun `a native method gets no probe, since nothing could ever increment it`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val target = Class.forName("com.example.target.NativeTarget", true, fixtureLoader())
        target.getMethod("normalThing").invoke(target.getDeclaredConstructor().newInstance())

        val probes = registry.manifest("test", null, "instance-1").probes.filter { it.className == "com.example.target.NativeTarget" }
        assertEquals(setOf("<init>", "normalThing"), probes.map { it.methodName }.toSet())
    }

    @Test
    fun `method probes carry the method's first source line when the class has debug info`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val probes = registry.manifest("test", null, "instance-1").probes
        val ping = probes.single { it.methodName == "ping" }
        val neverCalled = probes.single { it.methodName == "neverCalled" }
        assertTrue(ping.line > 0, "expected a real line, got ${ping.line}")
        assertTrue(neverCalled.line > ping.line, "neverCalled is declared after ping in SampleTarget")
    }

    @Test
    fun `the probe array is in place before the class's own static initializer runs`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val target = Class.forName("com.example.target.StaticInitTarget", true, fixtureLoader())
        assertEquals(7, target.getField("TOUCHED").get(null))

        val manifest = registry.manifest("test", null, "instance-1")
        val pokeIndex =
            manifest.probes
                .single { it.className == "com.example.target.StaticInitTarget" && it.methodName == "poke" }
                .probeIndex
        val byIndex =
            registry
                .computeDeltaBatch(ResourceAttributes("test", null, "i-1", null))
                .batch.deltas
                .associateBy { it.probeIndex }
        assertEquals(1L, byIndex.getValue(pokeIndex).hitsTotal, "the call from <clinit> must be counted, not lost or crash")
    }

    @Test
    fun `the counts field is public static final and holds the registry's own array`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        val target = install(registry, config)

        val field = target.javaClass.getDeclaredField("\$yukonProbeCounts")
        val modifiers = field.modifiers
        assertTrue(Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers))
        target.javaClass.getMethod("ping").invoke(target)
        val counts = field.get(null) as LongArray
        val pingIndex =
            registry
                .manifest("test", null, "instance-1")
                .probes
                .single { it.methodName == "ping" }
                .probeIndex
        assertEquals(1L, counts[pingIndex], "the field's array saw the hit")
        // A miss in the bootstrap holder would hand the class a fresh array and leave the
        // registry's own at zero; both views agreeing proves they are the same array.
        val reported =
            registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).batch.deltas.single {
                it.probeIndex ==
                    pingIndex
            }
        assertEquals(1L, reported.hitsTotal, "the registry's array saw the same hit")
    }

    @Test
    fun `flags a class that registers dynamically but was absent from the static baseline`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        val detector = StaticBaselineMismatchDetector()
        detector.knownClassNames = emptySet()

        install(registry, config, detector)

        // shouldWarnAbout only returns true the first time a given class name is found missing.
        // If install() already consumed that for this class, this call must now return false.
        assertFalse(detector.shouldWarnAbout("com.example.target.SampleTarget"))
    }
}
