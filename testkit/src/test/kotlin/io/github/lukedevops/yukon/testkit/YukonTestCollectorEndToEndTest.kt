package io.github.lukedevops.yukon.testkit

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.BranchRole
import io.github.lukedevops.yukon.export.ExportScheduler
import io.github.lukedevops.yukon.export.HttpOtlpStyleExporter
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
                "com.example.testkittarget.LegacyCalculator#<init>",
                "com.example.testkittarget.LegacyCalculator#apply",
                "com.example.testkittarget.LegacyFees#<clinit>",
                "com.example.testkittarget.LegacyFees#<init>",
            ),
            cluster.members.map { "${it.className}#${it.methodName}" },
        )
    }
}
