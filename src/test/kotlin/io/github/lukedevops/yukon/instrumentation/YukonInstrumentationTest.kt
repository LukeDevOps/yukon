package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.instrumentation.staticscan.StaticBaselineMismatchDetector
import io.github.lukedevops.yukon.registry.ProbeMeta
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.jar.asm.ClassReader
import net.bytebuddy.jar.asm.ClassWriter
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.logging.Level as JulLevel
import java.util.logging.Logger as JulLogger

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

    @Test
    fun `a transform that fails after registration is rolled back, reported as skipped, and the class runs uninstrumented`() {
        // register() succeeds and then the transform throws, the order a ByteBuddy validation
        // failure has: the registry already holds the class by the time make() rejects it.
        val registry =
            object : ProbeRegistry() {
                override fun register(
                    className: String,
                    layoutHash: Long,
                    probes: List<ProbeMeta>,
                    classLoader: ClassLoader?,
                    superClassName: String?,
                    interfaceNames: List<String>,
                ): LongArray {
                    val counts = super.register(className, layoutHash, probes, classLoader, superClassName, interfaceNames)
                    if (className == "com.example.target.SampleTarget") throw IllegalStateException("simulated transform failure")
                    return counts
                }
            }
        val config = AgentConfig.parse("includePackages=com.example.target")

        val target = install(registry, config)

        assertEquals("pong", target.javaClass.getMethod("ping").invoke(target), "the class must still load and run")
        assertTrue("com.example.target.SampleTarget" !in registry.registeredClassNames(), "the speculative registration is rolled back")
        val skipped = registry.manifest("test", null, "instance-1").skippedClasses.single()
        assertEquals("com.example.target.SampleTarget", skipped.className)
        assertTrue("simulated transform failure" in skipped.reason)
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
    fun `a type with its own clinit gets one METHOD probe, incremented once by the woven prelude`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)
        val loader = fixtureLoader()

        val target = Class.forName("com.example.target.StaticInitTarget", true, loader)
        // The JVM initialises a class at most once per loader, so a second forName here cannot
        // run <clinit> again. This pins that the woven prelude's own increment is not doubled.
        Class.forName("com.example.target.StaticInitTarget", true, loader)

        val manifest = registry.manifest("test", null, "instance-1")
        val clinitProbe =
            manifest.probes.single { it.className == "com.example.target.StaticInitTarget" && it.methodName == "<clinit>" }
        assertEquals(ProbeKind.METHOD, clinitProbe.kind)
        assertEquals("()V", clinitProbe.methodDescriptor)
        assertTrue(clinitProbe.line > 0, "expected a real line, got ${clinitProbe.line}")
        assertEquals(7, target.getField("TOUCHED").get(null))

        val byIndex =
            registry
                .computeDeltaBatch(ResourceAttributes("test", null, "i-1", null))
                .batch.deltas
                .associateBy { it.probeIndex }
        assertEquals(1L, byIndex.getValue(clinitProbe.probeIndex).hitsTotal)
    }

    @Test
    fun `a class loaded without being initialised has its clinit probe present at zero`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        Class.forName("com.example.target.StaticInitTarget", false, fixtureLoader())

        val manifest = registry.manifest("test", null, "instance-1")
        val clinitProbe =
            manifest.probes.single { it.className == "com.example.target.StaticInitTarget" && it.methodName == "<clinit>" }
        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null)).batch.deltas
        assertTrue(
            deltas.none { it.probeIndex == clinitProbe.probeIndex },
            "the class was defined, so its row exists, but <clinit> never ran, so nothing was counted",
        )
    }

    @Test
    fun `a type with no clinit of its own gets no clinit probe`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        Class.forName("com.example.target.SampleTarget", true, fixtureLoader())

        val manifest = registry.manifest("test", null, "instance-1")
        assertTrue(
            manifest.probes.none { it.className == "com.example.target.SampleTarget" && it.methodName == "<clinit>" },
            "SampleTarget declares no static initializer of its own",
        )
    }

    @Test
    fun `an interface with only abstract methods and no clinit still registers nothing`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        Class.forName("com.example.target.AbstractOnlyInterface", true, fixtureLoader())

        assertTrue("com.example.target.AbstractOnlyInterface" !in registry.registeredClassNames())
    }

    @Test
    fun `an interface with a default method and no static field initialiser gets no clinit row`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)
        val loader = fixtureLoader()

        val iface = Class.forName("com.example.target.DefaultMethodTarget", true, loader)
        val impl = Class.forName("com.example.target.DefaultMethodImpl", true, loader)
        impl.getDeclaredConstructor().newInstance()
        iface.getMethod("staticThing").invoke(null)

        val manifest = registry.manifest("test", null, "instance-1")
        assertTrue(
            manifest.probes.none { it.className == "com.example.target.DefaultMethodTarget" && it.methodName == "<clinit>" },
        )
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

    /** Installs the transformer without loading any fixture class, for a test that loads its own. */
    private fun installOnly(
        registry: ProbeRegistry,
        config: AgentConfig,
    ): YukonInstrumentation {
        val instrumentation = ByteBuddyAgent.install()
        val yukon = YukonInstrumentation(config, registry)
        installedYukon = yukon
        installedTransformer = yukon.install(instrumentation)
        return yukon
    }

    /** [original] with `LineNumberTable`, `LocalVariableTable`, and `SourceDebugExtension` dropped, annotations kept. */
    private fun stripDebugInfo(original: ByteArray): ByteArray {
        val writer = ClassWriter(0)
        ClassReader(original).accept(writer, ClassReader.SKIP_DEBUG)
        return writer.toByteArray()
    }

    /**
     * Serves [internalName] from [bytes] off a scratch directory, so a class can be loaded with
     * its debug info stripped while every other fixture class still resolves normally through
     * [FixtureClassLoader]'s own delegation.
     */
    private fun strippedFixtureLoader(
        internalName: String,
        bytes: ByteArray,
    ): FixtureClassLoader {
        val dir = Files.createTempDirectory("yukon-stripped-fixture").toFile()
        val classFile = File(dir, "$internalName.class")
        classFile.parentFile.mkdirs()
        classFile.writeBytes(bytes)
        return FixtureClassLoader(arrayOf(dir.toURI().toURL()), javaClass.classLoader)
    }

    /**
     * Records every [java.util.logging.LogRecord] [loggerName] emits while [block] runs. Yukon
     * logs through `System.Logger`, which maps to `java.util.logging` when no custom
     * `System.LoggerFinder` is installed, which is the case in this project's own tests.
     */
    private fun captureLogRecords(
        loggerName: String,
        block: () -> Unit,
    ): List<LogRecord> {
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() {}

                override fun close() {}
            }
        val julLogger = JulLogger.getLogger(loggerName)
        val originalLevel = julLogger.level
        julLogger.addHandler(handler)
        julLogger.level = JulLevel.ALL
        try {
            block()
        } finally {
            julLogger.removeHandler(handler)
            julLogger.level = originalLevel
        }
        return records
    }

    @Test
    fun `a stripped Kotlin class logs exactly one WARNING naming the class`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        installOnly(registry, config)

        val original = File("build/classes/kotlin/test/com/example/target/InlineTarget.class").readBytes()
        val loader = strippedFixtureLoader("com/example/target/InlineTarget", stripDebugInfo(original))

        val records =
            captureLogRecords(YukonInstrumentation::class.java.name) {
                Class.forName("com.example.target.InlineTarget", true, loader)
            }

        val warnings = records.filter { it.level == JulLevel.WARNING }
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().message.contains("com.example.target.InlineTarget"))
        assertTrue(warnings.single().message.contains("no line-number table"))
    }

    @Test
    fun `the unstripped Kotlin fixture logs no WARNING`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        installOnly(registry, config)

        val loader = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)

        val records =
            captureLogRecords(YukonInstrumentation::class.java.name) {
                Class.forName("com.example.target.InlineTarget", true, loader)
            }

        assertTrue(records.none { it.level == JulLevel.WARNING })
    }

    @Test
    fun `a stripped Java fixture logs no WARNING`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        installOnly(registry, config)

        val original = File("build/classes/java/test/com/example/target/SampleTarget.class").readBytes()
        val loader = strippedFixtureLoader("com/example/target/SampleTarget", stripDebugInfo(original))

        val records =
            captureLogRecords(YukonInstrumentation::class.java.name) {
                Class.forName("com.example.target.SampleTarget", true, loader)
            }

        assertTrue(records.none { it.level == JulLevel.WARNING })
    }
}
