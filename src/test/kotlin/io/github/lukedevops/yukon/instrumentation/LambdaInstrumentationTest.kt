package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.CallEdge
import io.github.lukedevops.yukon.export.CallEdgeKind
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
 * own, matching Scala 3, whose adapter is a bridge. Each scalac body is also flagged as a lambda
 * body and reached by a creation edge through that forwarder (ADR 0034).
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

    @Test
    fun `a lambda written inside a method is probed, flagged and created from that method, in Scala 2 and Scala 3`() {
        val host = "com.example.scalatarget.InlineLambdaHost\$"
        for ((module, bodies) in listOf(
            "scala2" to listOf("\$anonfun\$label\$1", "\$anonfun\$nested\$1", "\$anonfun\$nested\$2", "\$anonfun\$viaHelper\$1"),
            "scala3" to
                listOf("label\$\$anonfun\$1", "nested\$\$anonfun\$1", "nested\$\$anonfun\$1\$\$anonfun\$1", "viaHelper\$\$anonfun\$1"),
        )) {
            val registry = ProbeRegistry()
            install(registry, AgentConfig.parse("includePackages=com.example.scalatarget"))
            try {
                val loader = ScalaFixtures.classLoader(module, javaClass.classLoader)
                Class.forName(host, true, loader)

                val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                val methodProbes = manifest.probes.filter { it.kind == ProbeKind.METHOD && it.className == host }

                assertEquals(bodies.toSet(), methodProbes.filter { it.lambdaBody }.map { it.methodName }.toSet(), module)
                assertTrue(methodProbes.none { "adapted" in it.methodName }, "$module: a boxing forwarder is never a node")
                assertEquals(
                    listOf(bodies[1]),
                    methodProbes
                        .single { it.methodName == "nested" }
                        .calls
                        .filter { it.kind == CallEdgeKind.CREATES }
                        .map { it.methodName },
                    "$module: nested creates only the outer lambda; the inner one is created by the outer",
                )
                assertEquals(
                    listOf(bodies[2]),
                    methodProbes
                        .single { it.methodName == bodies[1] }
                        .calls
                        .filter { it.kind == CallEdgeKind.CREATES }
                        .map { it.methodName },
                    "$module: the outer lambda creates the inner one",
                )
                assertEquals(
                    listOf(bodies[0]),
                    methodProbes
                        .single { it.methodName == "label" }
                        .calls
                        .filter { it.kind == CallEdgeKind.CREATES }
                        .map { it.methodName },
                    "$module: label creates its lambda, through the boxing forwarder",
                )
                assertEquals(
                    2,
                    manifest.probes.count { it.kind == ProbeKind.BRANCH && it.className == host && it.methodName == bodies[0] },
                    "$module: the lambda's if/else is probed as one two-outcome conditional",
                )
            } finally {
                tearDown()
            }
        }
    }

    @Test
    fun `a lambda in a nested class is flagged where its body lives and created from the nested class, in Scala 2 and Scala 3`() {
        val outer = "com.example.scalatarget.NestedLambdaHost\$"
        val inner = "com.example.scalatarget.NestedLambdaHost\$Inner"
        for ((module, bodyClass, bodies) in listOf(
            Triple("scala2", inner, listOf("\$anonfun\$bump\$1", "\$anonfun\$show\$1")),
            Triple(
                "scala3",
                outer,
                listOf("com\$example\$scalatarget\$NestedLambdaHost\$Inner\$\$_\$bump\$\$anonfun\$1", "show\$\$anonfun\$1"),
            ),
        )) {
            val registry = ProbeRegistry()
            install(registry, AgentConfig.parse("includePackages=com.example.scalatarget"))
            try {
                val loader = ScalaFixtures.classLoader(module, javaClass.classLoader)
                Class.forName(outer, true, loader)
                Class.forName(inner, true, loader)

                val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                val methodProbes =
                    manifest.probes.filter {
                        it.kind == ProbeKind.METHOD && (it.className == outer || it.className == inner)
                    }

                assertEquals(
                    bodies.map { bodyClass to it }.toSet(),
                    methodProbes.filter { it.lambdaBody }.map { it.className to it.methodName }.toSet(),
                    module,
                )
                for ((creator, body) in listOf("bump", "show").zip(bodies)) {
                    // Scala 3's show reaches its body through the lifted boxing bridge on the
                    // top-level class, a cross-class pass-through, and calling one is a use of its
                    // owner, so that owner's <clinit> joins as well (ADR 0024).
                    val throughBridge = module == "scala3" && creator == "show"
                    assertEquals(
                        listOf(bodyClass to body) + if (throughBridge) listOf(bodyClass to "<clinit>") else emptyList(),
                        methodProbes
                            .single { it.className == inner && it.methodName == creator }
                            .calls
                            .filter { it.kind == CallEdgeKind.CREATES }
                            .map { it.className to it.methodName },
                        "$module: $creator creates its lambda, through the boxing bridge for show",
                    )
                }
                assertEquals(
                    2,
                    manifest.probes.count { it.kind == ProbeKind.BRANCH && it.className == bodyClass && it.methodName == bodies[0] },
                    "$module: the lambda's if/else is probed",
                )
            } finally {
                tearDown()
            }
        }
    }

    @Test
    fun `a by-name argument's closure is flagged as a lambda body, in Scala 2 and Scala 3`() {
        val driver = "com.example.scalatarget.Driver\$"
        for ((module, body) in listOf("scala2" to "\$anonfun\$callByName\$1", "scala3" to "callByName\$\$anonfun\$1")) {
            val registry = ProbeRegistry()
            install(registry, AgentConfig.parse("includePackages=com.example.scalatarget"))
            try {
                val loader = ScalaFixtures.classLoader(module, javaClass.classLoader)
                Class.forName(driver, true, loader)

                val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                val methodProbes = manifest.probes.filter { it.kind == ProbeKind.METHOD && it.className == driver }

                assertEquals(listOf(body), methodProbes.filter { it.lambdaBody }.map { it.methodName }, module)
                assertTrue(
                    methodProbes.single { it.methodName == "callByName" }.calls.any {
                        it.methodName == body &&
                            it.kind == CallEdgeKind.CREATES
                    },
                    module,
                )
            } finally {
                tearDown()
            }
        }
    }

    @Test
    fun `a scalac lambda body is flagged and created through its boxing forwarder, in Scala 2 and Scala 3`() {
        for ((module, bodyName) in listOf("scala2" to "\$anonfun\$classify\$1", "scala3" to "\$anonfun\$1")) {
            val registry = ProbeRegistry()
            install(registry, AgentConfig.parse("includePackages=com.example.scalatarget"))
            try {
                val loader = ScalaFixtures.classLoader(module, javaClass.classLoader)
                Class.forName("com.example.scalatarget.LambdaHost\$", true, loader)

                val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
                val methodProbes =
                    manifest.probes.filter {
                        it.kind == ProbeKind.METHOD &&
                            it.className == "com.example.scalatarget.LambdaHost\$"
                    }

                assertEquals(listOf(bodyName), methodProbes.filter { it.lambdaBody }.map { it.methodName }, module)
                assertEquals(
                    listOf(
                        CallEdge(
                            "com.example.scalatarget.LambdaHost\$",
                            bodyName,
                            "(I)Ljava/lang/String;",
                            virtual = false,
                            kind = CallEdgeKind.CREATES,
                            implementedInterface = "scala.Function1",
                        ),
                    ),
                    methodProbes.single { it.methodName == "classify" }.calls,
                    "$module names the forwarder in the invokedynamic, and the forwarder's call keeps the CREATES kind and its interface",
                )
                assertEquals("Targets.scala", manifest.classLocations.single { it.classId == methodProbes.first().classId }.sourceFile)
            } finally {
                tearDown()
            }
        }
    }
}
