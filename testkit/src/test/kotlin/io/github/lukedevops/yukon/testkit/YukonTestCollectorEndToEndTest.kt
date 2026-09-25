package io.github.lukedevops.yukon.testkit

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.BranchRole
import io.github.lukedevops.yukon.export.ExportScheduler
import io.github.lukedevops.yukon.export.HttpOtlpStyleExporter
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.instrumentation.YukonInstrumentation
import io.github.lukedevops.yukon.registry.EndpointRegistry
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Proves [YukonTestCollector] against a real agent, not just hand-built payloads: a fixture class
 * is instrumented in process by [YukonInstrumentation], exercised through one method, and flushed
 * over the wire by a real [ExportScheduler]/[HttpOtlpStyleExporter] pair.
 */
class YukonTestCollectorEndToEndTest {
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedYukon: YukonInstrumentation? = null
    private var scheduler: ExportScheduler? = null
    private var collector: YukonTestCollector? = null

    private fun fixtureLoader() = FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader)

    @AfterTest
    fun tearDown() {
        scheduler?.stop()
        installedTransformer?.let { installedYukon?.uninstall(ByteBuddyAgent.install(), it) }
        installedTransformer = null
        installedYukon = null
        collector?.close()
    }

    @Test
    fun `wasHit reflects which fixture method actually ran, observed only through the wire protocol`() {
        val target = YukonTestCollector.start()
        collector = target

        val registry = ProbeRegistry()
        val config =
            AgentConfig.parse(
                "includePackages=com.example.testkittarget," +
                    "endpoint=${target.endpoint}," +
                    "flushIntervalSeconds=1," +
                    "serviceName=testkit-e2e," +
                    "serviceInstanceId=e2e-1",
            )

        val instrumentation = ByteBuddyAgent.install()
        val yukon = YukonInstrumentation(config, registry)
        installedYukon = yukon
        installedTransformer = yukon.install(instrumentation)

        val fixtureClass = Class.forName("com.example.testkittarget.SampleTarget", true, fixtureLoader())
        val fixture = fixtureClass.getDeclaredConstructor().newInstance()
        fixtureClass.getMethod("exercised").invoke(fixture)

        val exporter = HttpOtlpStyleExporter(target.endpoint)
        val exportScheduler = ExportScheduler(config, TestResources.forConfig(config), registry, EndpointRegistry(), exporter)
        scheduler = exportScheduler
        exportScheduler.start()

        target.awaitProbe("com.example.testkittarget.SampleTarget", "exercised", Duration.ofSeconds(10))
        target.awaitNextFlush(Duration.ofSeconds(10))

        assertTrue(target.wasHit("com.example.testkittarget.SampleTarget", "exercised"))
        assertFalse(target.wasHit("com.example.testkittarget.SampleTarget", "neverCalled"))
    }

    @Test
    fun `unreachedClusters reports a two-method cluster observed only through the wire protocol`() {
        val target = YukonTestCollector.start()
        collector = target

        val registry = ProbeRegistry()
        val config =
            AgentConfig.parse(
                "includePackages=com.example.testkittarget," +
                    "endpoint=${target.endpoint}," +
                    "flushIntervalSeconds=1," +
                    "serviceName=testkit-e2e," +
                    "serviceInstanceId=e2e-2",
            )

        val instrumentation = ByteBuddyAgent.install()
        val yukon = YukonInstrumentation(config, registry)
        installedYukon = yukon
        installedTransformer = yukon.install(instrumentation)

        val fixtureClass = Class.forName("com.example.testkittarget.SampleTarget", true, fixtureLoader())
        val fixture = fixtureClass.getDeclaredConstructor().newInstance()
        fixtureClass.getMethod("exercised").invoke(fixture)

        val exporter = HttpOtlpStyleExporter(target.endpoint)
        val exportScheduler = ExportScheduler(config, TestResources.forConfig(config), registry, EndpointRegistry(), exporter)
        scheduler = exportScheduler
        exportScheduler.start()

        target.awaitProbe("com.example.testkittarget.SampleTarget", "neverCalledHelper2", Duration.ofSeconds(10))
        target.awaitNextFlush(Duration.ofSeconds(10))

        val cluster =
            target.unreachedClusters().single {
                it.root.className == "com.example.testkittarget.SampleTarget" && it.root.methodName == "neverCalledHelper"
            }
        assertEquals(RootKind.UNCALLED, cluster.rootKind)
        assertEquals(
            listOf("neverCalledHelper", "neverCalledHelper2"),
            cluster.members.map { it.methodName }.sorted(),
        )
    }

    @Test
    fun `unreachedClusters roots the methods behind an untaken if at that if, observed only through the wire protocol`() {
        val target = YukonTestCollector.start()
        collector = target

        val registry = ProbeRegistry()
        val config =
            AgentConfig.parse(
                "includePackages=com.example.testkittarget," +
                    "endpoint=${target.endpoint}," +
                    "flushIntervalSeconds=1," +
                    "serviceName=testkit-e2e," +
                    "serviceInstanceId=e2e-3",
            )

        val instrumentation = ByteBuddyAgent.install()
        val yukon = YukonInstrumentation(config, registry)
        installedYukon = yukon
        installedTransformer = yukon.install(instrumentation)

        val loader = fixtureLoader()
        val checkoutClass = Class.forName("com.example.testkittarget.LegacyCheckout", true, loader)
        val checkout = checkoutClass.getDeclaredConstructor().newInstance()
        checkoutClass.getMethod("total", Double::class.java, Boolean::class.java).invoke(checkout, 10.0, false)
        // Loaded without being initialised, so both classes have probes and none of them ran. In
        // the demo a complete static baseline gives the same nodes for classes that never load.
        Class.forName("com.example.testkittarget.LegacyCalculator", false, loader)
        Class.forName("com.example.testkittarget.LegacyFees", false, loader)

        val exporter = HttpOtlpStyleExporter(target.endpoint)
        val exportScheduler = ExportScheduler(config, TestResources.forConfig(config), registry, EndpointRegistry(), exporter)
        scheduler = exportScheduler
        exportScheduler.start()

        target.awaitProbe("com.example.testkittarget.LegacyFees", "<clinit>", Duration.ofSeconds(10))
        target.awaitProbe("com.example.testkittarget.LegacyCalculator", "apply", Duration.ofSeconds(10))
        target.awaitNextFlush(Duration.ofSeconds(10))

        val clusters = target.unreachedClusters()
        val cluster = clusters.single()
        assertEquals(RootKind.UNTAKEN_OUTCOME, cluster.rootKind)
        assertEquals("com.example.testkittarget.LegacyCheckout" to "total", cluster.root.className to cluster.root.methodName)
        val site = assertNotNull(cluster.rootSite)
        assertEquals("legacy", site.condition.joinToString("") { it.text })
        assertEquals(BranchRole.FALL_THROUGH, site.outcomes.single { it.branchIndex == cluster.root.branchIndex }.role)
        assertEquals(
            listOf(
                "com.example.testkittarget.LegacyCalculator" to ClassFinding.NEVER_INSTANTIATED,
                "com.example.testkittarget.LegacyFees" to ClassFinding.NEVER_INITIALISED,
            ),
            cluster.wholeClasses.map { it.className to it.finding },
        )
        assertEquals(
            listOf(
                "com.example.testkittarget.LegacyCalculator#<init>",
                "com.example.testkittarget.LegacyCalculator#apply",
                "com.example.testkittarget.LegacyFees#<init>",
            ),
            cluster.methods.map { "${it.className}#${it.methodName}" },
        )
    }

    /**
     * Runs the shapes in `ClassFindingShapes.kt` under a real agent that reports to a fresh
     * collector, and returns that collector once the probes the tests read have arrived.
     */
    private fun collectClassFindingShapes(instanceId: String): YukonTestCollector {
        val target = YukonTestCollector.start()
        collector = target

        val registry = ProbeRegistry()
        val config =
            AgentConfig.parse(
                "includePackages=com.example.testkittarget," +
                    "endpoint=${target.endpoint}," +
                    "flushIntervalSeconds=1," +
                    "serviceName=testkit-e2e," +
                    "serviceInstanceId=$instanceId",
            )

        val instrumentation = ByteBuddyAgent.install()
        val yukon = YukonInstrumentation(config, registry)
        installedYukon = yukon
        installedTransformer = yukon.install(instrumentation)

        val loader = fixtureLoader()
        fun load(simpleName: String, initialise: Boolean) = Class.forName("$FIXTURES.$simpleName", initialise, loader)
        load("AuditTrail", initialise = false)
        load("LinePrinter", initialise = false)
        load("Greeter", initialise = false)
        load("ReportWriter", initialise = true)

        val textUtil = load("TextUtil", initialise = true)
        textUtil.getMethod("trim", String::class.java).invoke(textUtil.getDeclaredConstructor().newInstance(), " x ")
        val amount = load("Amount", initialise = true)
        amount.getMethod("inPounds").invoke(amount.getDeclaredConstructor(Long::class.java).newInstance(250L))
        load("Scaled", initialise = true).getDeclaredConstructor(Double::class.java, Int::class.java).newInstance(1.5, 2)
        load("Utils", initialise = true).getMethod("name").invoke(null)
        val counters = load("Counters", initialise = true)
        counters.getMethod("size").invoke(counters.getField("INSTANCE").get(null))

        val exporter = HttpOtlpStyleExporter(target.endpoint)
        val exportScheduler = ExportScheduler(config, TestResources.forConfig(config), registry, EndpointRegistry(), exporter)
        scheduler = exportScheduler
        exportScheduler.start()

        for ((simpleName, methodName) in listOf(
            "AuditTrail" to "<clinit>",
            "LinePrinter" to "print",
            "Greeter" to "greet",
            "ReportWriter" to "write",
            "TextUtil" to "pad",
            "Amount" to "inPounds",
            "Scaled" to "<init>",
            "Utils" to "name",
            "Counters" to "unused",
        )) {
            target.awaitProbe("$FIXTURES.$simpleName", methodName, Duration.ofSeconds(10))
        }
        target.awaitSettled(Duration.ofSeconds(10))
        return target
    }

    @Test
    fun `class findings name a class loaded and never initialised and one never instantiated, observed only through the wire protocol`() {
        val target = collectClassFindingShapes("e2e-4")

        // A class literal loads AuditTrail without running its static initialiser. It is a Kotlin
        // object, so it is never initialised, and a stronger finding rules out never instantiated.
        val neverInitialised = target.neverInitialised()
        assertEquals(listOf("$FIXTURES.AuditTrail"), neverInitialised.map { it.className })
        assertEquals(listOf("<init>", "record"), neverInitialised.single().methods)
        assertEquals(1, neverInitialised.single().instancesLoading)

        // LinePrinter has no static initialiser and ReportWriter's ran. Neither was created. Utils
        // holds only statics, Greeter has no constructor, and Counters is a used object, so none of
        // those three is judged.
        assertEquals(
            listOf("$FIXTURES.LinePrinter", "$FIXTURES.ReportWriter"),
            target.neverInstantiated().map { it.className },
        )
    }

    @Test
    fun `neverHit folds class findings, keeps static methods and lists only unused overloads, observed only through the wire protocol`() {
        val target = collectClassFindingShapes("e2e-5")

        val rows = target.neverHit().filter { it.kind == ProbeKind.METHOD }.map { "${it.className.removePrefix("$FIXTURES.")}#${it.methodName}${it.methodDescriptor}" }
        assertTrue("Amount#<init>(II)V" in rows, "Amount's unused overload is a row: $rows")
        assertTrue("ReportWriter#footer()Ljava/lang/String;" in rows, "a never-instantiated class keeps its static methods: $rows")
        assertTrue("Counters#unused()I" in rows, "an initialised object's never-hit method is a row: $rows")
        assertTrue("Greeter#greet()Ljava/lang/String;" in rows, "an interface's default method is a row of its own: $rows")
        assertTrue("TextUtil#pad(Ljava/lang/String;I)Ljava/lang/String;" in rows, rows.toString())
        assertTrue(rows.none { it.contains("#<clinit>") }, "<clinit> is never a row: $rows")
        assertTrue(rows.none { it.startsWith("AuditTrail#") }, "a never-initialised class folds every method: $rows")
        assertTrue(
            rows.none { it.startsWith("LinePrinter#") || it.startsWith("ReportWriter#<init>") || it.startsWith("ReportWriter#write") },
            "a never-instantiated class folds its constructors and instance methods: $rows",
        )
        assertTrue(rows.none { it.startsWith("Utils#<init>") }, "a lone constructor that never ran is not a row: $rows")
        assertTrue(rows.none { it.startsWith("Scaled#<init>") }, "a @JvmOverloads forwarder is generated, never an unused overload: $rows")
    }

    @Test
    fun `a class finding roots a cluster only when it reaches beyond its own methods, observed only through the wire protocol`() {
        val target = collectClassFindingShapes("e2e-6")

        val clusters = target.unreachedClusters()
        val classRoots = clusters.filter { it.rootKind == RootKind.CLASS_FINDING }
        assertEquals(listOf("$FIXTURES.ReportWriter"), classRoots.map { it.root.className })
        val cluster = classRoots.single()
        assertEquals(ClassFinding.NEVER_INSTANTIATED, cluster.rootFinding)
        assertEquals(emptyList(), cluster.reachedFrom)
        // The class's folded methods are members too. It is not held whole, since its static footer
        // and its initialiser, which ran, stay outside the class finding.
        assertEquals(
            listOf("$FIXTURES.ReportWriter#<init>", "$FIXTURES.ReportWriter#write", "$FIXTURES.TextUtil#pad"),
            cluster.members.map { "${it.className}#${it.methodName}" },
        )
        assertEquals(emptyList(), cluster.wholeClasses)
        assertTrue(clusters.none { it.root.className in setOf("$FIXTURES.AuditTrail", "$FIXTURES.LinePrinter") })
        assertTrue(clusters.all { c -> c.methods.none { it.methodName == "<clinit>" } })
    }

    private companion object {
        const val FIXTURES = "com.example.testkittarget"
    }
}
