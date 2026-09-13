package io.github.lukedevops.yukon.testkit

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ExportScheduler
import io.github.lukedevops.yukon.export.HttpOtlpStyleExporter
import io.github.lukedevops.yukon.instrumentation.YukonInstrumentation
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
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
        val exportScheduler = ExportScheduler(config, registry, exporter)
        scheduler = exportScheduler
        exportScheduler.start()

        target.awaitProbe("com.example.testkittarget.SampleTarget", "exercised", Duration.ofSeconds(10))
        target.awaitNextFlush(Duration.ofSeconds(10))

        assertTrue(target.wasHit("com.example.testkittarget.SampleTarget", "exercised"))
        assertFalse(target.wasHit("com.example.testkittarget.SampleTarget", "neverCalled"))
    }
}
