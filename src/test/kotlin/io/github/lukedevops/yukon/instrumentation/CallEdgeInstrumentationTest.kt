package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.BodyKind
import io.github.lukedevops.yukon.export.CallEdge
import io.github.lukedevops.yukon.export.CallEdgeKind
import io.github.lukedevops.yukon.export.ProbeKind
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
 * Proves through the real pipeline that call edges and supertypes (ADR 0024) reach the manifest:
 * a METHOD probe carries its own in-scope call edges, a BRANCH probe carries none, and the class
 * gets its own [io.github.lukedevops.yukon.export.ClassLocation] record. Also proves the ADR 0034
 * facts on the same path: an edge's kind and captured count, a method's lambda body flag, and the
 * class's source file, body kind and source name.
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
    fun `a METHOD probe carries its calls, a BRANCH probe carries none, and the class gets a class location record`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val loader = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)
        val targetClass = Class.forName("com.example.target.CallEdgeTarget", true, loader)
        val target = targetClass.getDeclaredConstructor().newInstance()
        targetClass.getMethod("callsPrivateMethod").invoke(target)
        targetClass.getMethod("callsSelfRecursively", Int::class.java).invoke(target, 1)

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val classEntry = manifest.probes.filter { it.className == "com.example.target.CallEdgeTarget" }

        val callsPrivateMethodProbe = classEntry.single { it.methodName == "callsPrivateMethod" && it.kind == ProbeKind.METHOD }
        assertEquals(
            listOf(CallEdge("com.example.target.CallEdgeTarget", "privateHelper", "()I", virtual = false)),
            callsPrivateMethodProbe.calls,
        )

        val branchProbes = classEntry.filter { it.kind == ProbeKind.BRANCH }
        assertTrue(branchProbes.isNotEmpty(), "callsSelfRecursively's if/else contributes branch probes")
        assertTrue(branchProbes.all { it.calls.isEmpty() }, "a BRANCH probe never carries call edges")

        val supertypes = manifest.classLocations.single { it.classId == callsPrivateMethodProbe.classId }
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
            registry
                .manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                .probes
                .single { it.className == "com.example.target.TemplateTarget" && it.methodName == "run" && it.kind == ProbeKind.METHOD }
        assertEquals(listOf(CallEdge("com.example.target.TemplateTarget", "step", "()I", virtual = true)), runProbe.calls)
    }

    @Test
    fun `a static read of another class's enum constant carries an edge to that class's clinit on the manifest`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val loader = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)
        val targetClass = Class.forName("com.example.target.StaticUseTarget", true, loader)
        val target = targetClass.getDeclaredConstructor().newInstance()
        targetClass.getMethod("readEnumConstant").invoke(target)

        val readEnumConstantProbe =
            registry
                .manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                .probes
                .single {
                    it.className == "com.example.target.StaticUseTarget" && it.methodName == "readEnumConstant" &&
                        it.kind == ProbeKind.METHOD
                }
        assertEquals(listOf(CallEdge("com.example.target.Suit", "<clinit>", "()V", virtual = false)), readEnumConstantProbe.calls)
    }

    @Test
    fun `a bound function reference passes through to the function it names on the manifest, since kotlinc marks its class synthetic`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val loader = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)
        val targetClass = Class.forName("com.example.target.FunctionReferenceTarget", true, loader)
        val target = targetClass.getDeclaredConstructor().newInstance()
        targetClass.getMethod("viaReference").invoke(target)

        val viaReferenceProbe =
            registry
                .manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                .probes
                .single {
                    it.className == "com.example.target.FunctionReferenceTarget" && it.methodName == "viaReference" &&
                        it.kind == ProbeKind.METHOD
                }
        assertEquals(
            listOf(
                CallEdge("com.example.target.FunctionReferenceTarget", "secret", "()I", virtual = false, kind = CallEdgeKind.CREATES),
            ),
            viaReferenceProbe.calls,
        )
    }

    @Test
    fun `through the real matcher, a Kotlin lambda's creation edge, its lambda body flag and the class's source file reach the manifest`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val loader = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)
        val targetClass = Class.forName("com.example.target.CreationEdgeTarget", true, loader)
        val target = targetClass.getDeclaredConstructor().newInstance()
        targetClass.getMethod("capturing", Int::class.java).invoke(target, 1)
        targetClass.getMethod("callsDefault").invoke(target)

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val methodProbes =
            manifest.probes.filter { it.className == "com.example.target.CreationEdgeTarget" && it.kind == ProbeKind.METHOD }

        assertTrue(
            CallEdge(
                "com.example.target.CreationEdgeTarget",
                "capturing\$lambda\$0",
                "(II)I",
                virtual = false,
                kind = CallEdgeKind.CREATES,
                capturedCount = 1,
            ) in methodProbes.single { it.methodName == "capturing" }.calls,
        )
        assertEquals(
            listOf(
                CallEdge("com.example.target.CreationEdgeTarget", "withDefault\$lambda\$0", "(I)I", virtual = false, kind = CallEdgeKind.CREATES),
                CallEdge("com.example.target.CreationEdgeTarget", "withDefault", "(Lkotlin/jvm/functions/Function1;)I", virtual = false),
            ),
            methodProbes.single { it.methodName == "callsDefault" }.calls,
            "the real matcher leaves withDefault\$default unprobed, so its lambda is created by the method that calls it",
        )
        assertEquals(
            setOf(
                "plain\$lambda\$0",
                "capturing\$lambda\$0",
                "capturingThis\$lambda\$0",
                "nested\$lambda\$0",
                "nested\$lambda\$0\$0",
                "withDefault\$lambda\$0",
            ),
            methodProbes.filter { it.lambdaBody }.map { it.methodName }.toSet(),
        )
        assertEquals(
            "CreationEdgeTarget.kt",
            manifest.classLocations.single { it.classId == methodProbes.first().classId }.sourceFile,
        )
    }

    @Test
    fun `through the real matcher, a javac lambda body is flagged and a method reference's target is not`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val loader = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        Class.forName("com.example.target.CreationEdgeJavaTarget", true, loader)

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val methodProbes =
            manifest.probes.filter { it.className == "com.example.target.CreationEdgeJavaTarget" && it.kind == ProbeKind.METHOD }

        assertEquals(
            setOf("lambda\$capturing\$0", "lambda\$capturingThis\$1", "lambda\$innerConstructorReference\$2"),
            methodProbes.filter { it.lambdaBody }.map { it.methodName }.toSet(),
        )
        assertEquals(
            listOf(
                CallEdge("com.example.target.CreationEdgeJavaTarget", "name", "()Ljava/lang/String;", virtual = true, kind = CallEdgeKind.CREATES),
            ),
            methodProbes.single { it.methodName == "boundReference" }.calls,
        )
        assertEquals(
            "CreationEdgeJavaTarget.java",
            manifest.classLocations.single { it.classId == methodProbes.first().classId }.sourceFile,
        )
    }

    @Test
    fun `through the real pipeline, each class location carries its body kind, and a reference class passes through to its accessors`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target")
        install(registry, config)

        val kotlinLoader = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)
        val javaLoader = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)
        val kotlinClasses =
            listOf(
                "BodyKindTarget",
                "BodyKindTarget\$localClass\$Local",
                "BodyKindTarget\$serializableLambda\$1",
                "ObjectExpressionTarget\$makeHandler\$1",
            )
        val javaClasses = listOf("BodyKindJavaTarget\$1", "BodyKindJavaTarget\$1Local")
        kotlinClasses.forEach { Class.forName("com.example.target.$it", false, kotlinLoader) }
        Class.forName("com.example.target.BodyKindTarget\$mutablePropertyReference\$1", false, kotlinLoader)
        javaClasses.forEach { Class.forName("com.example.target.$it", false, javaLoader) }

        val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
        val classNames = manifest.probes.associate { it.classId to it.className.removePrefix("com.example.target.") }
        val kinds = manifest.classLocations.associate { classNames.getValue(it.classId) to (it.bodyKind to it.sourceName) }

        assertEquals(
            mapOf(
                "BodyKindTarget" to (BodyKind.NONE to null),
                "BodyKindTarget\$localClass\$Local" to (BodyKind.LOCAL_CLASS to "Local"),
                "BodyKindTarget\$serializableLambda\$1" to (BodyKind.LAMBDA_CLASS to null),
                "ObjectExpressionTarget\$makeHandler\$1" to (BodyKind.OBJECT_EXPRESSION to null),
                "BodyKindJavaTarget\$1" to (BodyKind.ANONYMOUS_CLASS to null),
                "BodyKindJavaTarget\$1Local" to (BodyKind.LOCAL_CLASS to "Local"),
            ),
            kinds.filterKeys { it.startsWith("BodyKind") || it.startsWith("ObjectExpressionTarget") },
            "kotlinc marks the property reference class synthetic, so the type matcher turns it away and it has no class location",
        )
        assertEquals(
            listOf(
                CallEdge("com.example.target.BodyKindTarget", "getCounter", "()I", virtual = false, kind = CallEdgeKind.CREATES),
                CallEdge("com.example.target.BodyKindTarget", "setCounter", "(I)V", virtual = false, kind = CallEdgeKind.CREATES),
            ),
            manifest.probes
                .single {
                    it.className == "com.example.target.BodyKindTarget" && it.methodName == "mutablePropertyReference" &&
                        it.kind == ProbeKind.METHOD
                }.calls,
        )
    }

    @Test
    fun `transforms on one classloader share a parsed-table cache, and another loader has none`() {
        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target;com.example.other")
        install(registry, config)
        val loader = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)

        assertEquals(0, installedYukon!!.cachedTableCount(loader))
        Class.forName("com.example.target.CallEdgeTarget", true, loader)
        val afterFirst = installedYukon!!.cachedTableCount(loader)
        Class.forName("com.example.target.StaticUseTarget", true, loader)
        val afterSecond = installedYukon!!.cachedTableCount(loader)

        assertTrue(afterFirst > 0, "the fixture references other in-scope classes, so their tables must be cached")
        assertTrue(afterSecond >= afterFirst, "a later transform on the same loader must reuse the same cache")
        assertEquals(
            0,
            installedYukon!!.cachedTableCount(
                FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader),
            ),
        )
    }
}
