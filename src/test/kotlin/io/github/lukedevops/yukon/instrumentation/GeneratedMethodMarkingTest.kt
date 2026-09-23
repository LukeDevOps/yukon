package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.GeneratedBy
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
 * Proves generated-method marking (ADR 0026) through the real transform pipeline, on the
 * `GeneratedTarget.kt` and `RecordTarget.java` fixtures and the `-jvm-default=disable` fixture
 * module, rather than only through
 * [io.github.lukedevops.yukon.instrumentation.branch.BranchSiteAnalyzerTest]'s direct bytecode
 * checks.
 */
class GeneratedMethodMarkingTest {
    private companion object {
        const val DISABLED_DEFAULT_IMPLS = "com.example.target.jvmdefaultdisable.DisabledDefaultInterface\$DefaultImpls"
    }

    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedYukon: YukonInstrumentation? = null

    private fun fixtureLoader() =
        FixtureClassLoader(
            arrayOf(
                File("build/classes/kotlin/test").toURI().toURL(),
                File("build/classes/java/test").toURI().toURL(),
            ),
            javaClass.classLoader,
        )

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
    fun `a data class's component, copy, equals, hashCode, and toString are DATA_CLASS, its constructor and getters are not`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val target = Class.forName("com.example.target.GeneratedPoint", true, fixtureLoader())
        target.getDeclaredConstructor(Int::class.java, String::class.java).newInstance(1, "a")

        val probes =
            registry
                .manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                .probes
                .filter { it.className == "com.example.target.GeneratedPoint" && it.kind == ProbeKind.METHOD }

        assertEquals(GeneratedBy.DATA_CLASS, probes.single { it.methodName == "component1" }.generatedBy)
        assertEquals(GeneratedBy.DATA_CLASS, probes.single { it.methodName == "component2" }.generatedBy)
        assertEquals(GeneratedBy.DATA_CLASS, probes.single { it.methodName == "copy" }.generatedBy)
        assertEquals(GeneratedBy.DATA_CLASS, probes.single { it.methodName == "equals" }.generatedBy)
        assertEquals(GeneratedBy.DATA_CLASS, probes.single { it.methodName == "hashCode" }.generatedBy)
        assertEquals(GeneratedBy.DATA_CLASS, probes.single { it.methodName == "toString" }.generatedBy)
        assertEquals(GeneratedBy.NONE, probes.single { it.methodName == "<init>" }.generatedBy)
        assertEquals(GeneratedBy.NONE, probes.single { it.methodName == "getX" }.generatedBy)
        assertEquals(GeneratedBy.NONE, probes.single { it.methodName == "getY" }.generatedBy)
        assertEquals(GeneratedBy.NONE, probes.single { it.methodName == "custom" }.generatedBy)
    }

    @Test
    fun `a data class's hand-written equals and hashCode are NONE, while its generated toString, componentN, and copy are DATA_CLASS`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        Class.forName("com.example.target.GeneratedPointCustomEquals", true, fixtureLoader())

        val probes =
            registry
                .manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                .probes
                .filter { it.className == "com.example.target.GeneratedPointCustomEquals" && it.kind == ProbeKind.METHOD }

        assertEquals(GeneratedBy.NONE, probes.single { it.methodName == "equals" }.generatedBy)
        assertEquals(GeneratedBy.NONE, probes.single { it.methodName == "hashCode" }.generatedBy)
        assertEquals(GeneratedBy.DATA_CLASS, probes.single { it.methodName == "toString" }.generatedBy)
        assertEquals(GeneratedBy.DATA_CLASS, probes.single { it.methodName == "component1" }.generatedBy)
        assertEquals(GeneratedBy.DATA_CLASS, probes.single { it.methodName == "component2" }.generatedBy)
        assertEquals(GeneratedBy.DATA_CLASS, probes.single { it.methodName == "copy" }.generatedBy)
        assertEquals(GeneratedBy.NONE, probes.single { it.methodName == "<init>" }.generatedBy)
    }

    @Test
    fun `calling a data class's copy still increments its probe, since a generated method's hit count is still evidence`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val target = Class.forName("com.example.target.GeneratedPoint", true, fixtureLoader())
        val instance = target.getDeclaredConstructor(Int::class.java, String::class.java).newInstance(1, "a")
        target.getMethod("copy", Int::class.java, String::class.java).invoke(instance, 2, "b")

        val copyProbe =
            registry
                .manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                .probes
                .single { it.className == "com.example.target.GeneratedPoint" && it.methodName == "copy" && it.kind == ProbeKind.METHOD }
        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1")).batch.deltas
        assertEquals(1L, deltas.single { it.probeIndex == copyProbe.probeIndex }.hitsTotal)
    }

    @Test
    fun `an enum's values, valueOf, and getEntries are marked ENUM`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        Class.forName("com.example.target.GeneratedColour", true, fixtureLoader())

        val probes =
            registry
                .manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                .probes
                .filter { it.className == "com.example.target.GeneratedColour" && it.kind == ProbeKind.METHOD }
        assertEquals(GeneratedBy.ENUM, probes.single { it.methodName == "values" }.generatedBy)
        assertEquals(GeneratedBy.ENUM, probes.single { it.methodName == "valueOf" }.generatedBy)
        assertEquals(GeneratedBy.ENUM, probes.single { it.methodName == "getEntries" }.generatedBy)
    }

    @Test
    fun `a default-mode DefaultImpls method that only forwards to the interface is marked DEFAULT_IMPLS`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        Class.forName("com.example.target.GeneratedInterface\$DefaultImpls", true, fixtureLoader())

        val probes =
            registry
                .manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                .probes
                .filter { it.className == "com.example.target.GeneratedInterface\$DefaultImpls" && it.kind == ProbeKind.METHOD }
        assertEquals(GeneratedBy.DEFAULT_IMPLS, probes.single { it.methodName == "withBody" }.generatedBy)
        assertEquals(GeneratedBy.DEFAULT_IMPLS, probes.single { it.methodName == "getLabel" }.generatedBy)
    }

    @Test
    fun `a disable-mode DefaultImpls method holding the interface method's real body is NONE on its METHOD probe`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        Class.forName(DISABLED_DEFAULT_IMPLS, true, JvmDefaultDisableFixtures.classLoader(javaClass.classLoader))

        val probes =
            registry
                .manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                .probes
                .filter { it.className == DISABLED_DEFAULT_IMPLS && it.kind == ProbeKind.METHOD }
        assertEquals(GeneratedBy.NONE, probes.single { it.methodName == "withBranch" }.generatedBy)
        assertEquals(GeneratedBy.NONE, probes.single { it.methodName == "withBody" }.generatedBy)
        assertEquals(GeneratedBy.NONE, probes.single { it.methodName == "getLabel" }.generatedBy)
        assertEquals(GeneratedBy.NONE, probes.single { it.methodName == "callsPrivate" }.generatedBy)
    }

    @Test
    fun `a disable-mode DefaultImpls method's conditional gets branch probes that count each outcome`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val loader = JvmDefaultDisableFixtures.classLoader(javaClass.classLoader)
        val impl = Class.forName("${JvmDefaultDisableFixtures.PACKAGE_PREFIX}DisabledDefaultInterfaceImpl", true, loader)
        val instance = impl.getDeclaredConstructor().newInstance()
        val withBranch = impl.getMethod("withBranch", Int::class.java)
        repeat(2) { withBranch.invoke(instance, 5) }
        repeat(3) { withBranch.invoke(instance, 1) }

        val branchProbes =
            registry
                .manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                .probes
                .filter { it.className == DISABLED_DEFAULT_IMPLS && it.methodName == "withBranch" && it.kind == ProbeKind.BRANCH }
        assertEquals(2, branchProbes.size, "the one if in withBranch's body is a two-outcome site")
        branchProbes.forEach { assertEquals(GeneratedBy.NONE, it.generatedBy) }

        val deltas = registry.computeDeltaBatch(ResourceAttributes("test", null, "i-1", null, "run-1")).batch.deltas
        val counts =
            branchProbes
                .map { probe -> deltas.single { it.classId == probe.classId && it.probeIndex == probe.probeIndex }.hitsTotal }
                .sorted()
        assertEquals(listOf(2L, 3L), counts)
    }

    @Test
    fun `a Java record's equals, hashCode, and toString are marked RECORD, and its accessors and extra method are not`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        Class.forName("com.example.target.RecordTarget", true, fixtureLoader())

        val probes =
            registry
                .manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                .probes
                .filter { it.className == "com.example.target.RecordTarget" && it.kind == ProbeKind.METHOD }
        assertEquals(GeneratedBy.RECORD, probes.single { it.methodName == "equals" }.generatedBy)
        assertEquals(GeneratedBy.RECORD, probes.single { it.methodName == "hashCode" }.generatedBy)
        assertEquals(GeneratedBy.RECORD, probes.single { it.methodName == "toString" }.generatedBy)
        assertEquals(GeneratedBy.NONE, probes.single { it.methodName == "x" }.generatedBy)
        assertEquals(GeneratedBy.NONE, probes.single { it.methodName == "y" }.generatedBy)
        assertEquals(GeneratedBy.NONE, probes.single { it.methodName == "extra" }.generatedBy)
    }

    @Test
    fun `every branch probe inside a data class's generated equals carries DATA_CLASS`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        Class.forName("com.example.target.GeneratedPoint", true, fixtureLoader())

        val branchProbes =
            registry
                .manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                .probes
                .filter { it.className == "com.example.target.GeneratedPoint" && it.methodName == "equals" && it.kind == ProbeKind.BRANCH }
        assert(branchProbes.isNotEmpty()) { "equals is expected to compile to at least one conditional jump" }
        branchProbes.forEach { assertEquals(GeneratedBy.DATA_CLASS, it.generatedBy) }
    }

    @Test
    fun `every branch probe inside a data class's hand-written equals carries NONE`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        Class.forName("com.example.target.GeneratedPointCustomEquals", true, fixtureLoader())

        val branchProbes =
            registry
                .manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                .probes
                .filter {
                    it.className == "com.example.target.GeneratedPointCustomEquals" &&
                        it.methodName == "equals" &&
                        it.kind == ProbeKind.BRANCH
                }
        assertEquals(4, branchProbes.size, "the instanceof test and the x comparison are two two-outcome sites")
        branchProbes.forEach { assertEquals(GeneratedBy.NONE, it.generatedBy) }
    }

    @Test
    fun `a branch probe inside an ordinary method of a data class carries NONE beside the generated methods`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        Class.forName("com.example.target.GeneratedPoint", true, fixtureLoader())

        val branchProbes =
            registry
                .manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                .probes
                .filter { it.className == "com.example.target.GeneratedPoint" && it.methodName == "custom" && it.kind == ProbeKind.BRANCH }
        assertEquals(2, branchProbes.size, "custom's one if is a two-outcome site")
        branchProbes.forEach { assertEquals(GeneratedBy.NONE, it.generatedBy) }
    }

    @Test
    fun `an omission probe on a generated copy's parameter carries the target's own DATA_CLASS mark`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        Class.forName("com.example.target.GeneratedPointWithDefault", true, fixtureLoader())

        val omissionProbes =
            registry
                .manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                .probes
                .filter {
                    it.className == "com.example.target.GeneratedPointWithDefault" &&
                        it.methodName == "copy" &&
                        it.kind == ProbeKind.OPTIONAL_ARGUMENT
                }
        assert(omissionProbes.isNotEmpty()) { "copy is expected to carry omission probes for both components" }
        omissionProbes.forEach { assertEquals(GeneratedBy.DATA_CLASS, it.generatedBy) }
    }
}
