package dev.otherlode.testkit

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.ExportScheduler
import dev.otherlode.export.HttpOtlpStyleExporter
import dev.otherlode.instrumentation.OtherlodeInstrumentation
import dev.otherlode.registry.EndpointRegistry
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves that [OtherlodeTestCollector.neverHit] leaves Scala's generated methods out, over the wire
 * from a real agent: the static forwarders on `Driver` and `Cc` and the case-class and companion
 * plumbing are no rows, a field accessor nobody called is one, and each uncalled method of the
 * object `Driver$` is a row once.
 */
class ScalaNeverHitEndToEndTest {
    private companion object {
        const val PACKAGE = "com.example.scalatarget"

        /** The methods a case class or its companion gets from scalac, by name. */
        val PLUMBING =
            setOf(
                "canEqual",
                "copy",
                "equals",
                "hashCode",
                "toString",
                "productArity",
                "productElement",
                "productElementName",
                "productElementNames",
                "productIterator",
                "productPrefix",
                "_1",
                "_2",
                "apply",
                "unapply",
                "fromProduct",
                "writeReplace",
            )
    }

    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedOtherlode: OtherlodeInstrumentation? = null
    private var scheduler: ExportScheduler? = null
    private var collector: OtherlodeTestCollector? = null

    @AfterTest
    fun tearDown() {
        scheduler?.stop()
        installedTransformer?.let { installedOtherlode?.uninstall(ByteBuddyAgent.install(), it) }
        installedTransformer = null
        installedOtherlode = null
        collector?.close()
    }

    /** A child-first loader over fixture module [module]'s output and its Scala library jars. */
    private fun fixtureLoader(module: String): FixtureClassLoader {
        fun property(name: String) = System.getProperty(name) ?: error("$name is not set; run tests through the Gradle build")
        val files =
            listOf(File(property("otherlode.fixtures.$module.dir"))) +
                property("otherlode.fixtures.$module.classpath").split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)
        return FixtureClassLoader(files.map { it.toURI().toURL() }.toTypedArray(), javaClass.classLoader, "$PACKAGE.")
    }

    private fun `neverHit leaves out Scala's generated methods and lists each object method once`(module: String) {
        val target = OtherlodeTestCollector.start()
        collector = target
        val registry = ProbeRegistry()
        val config =
            AgentConfig.parse(
                "includePackages=$PACKAGE," +
                    "endpoint=${target.endpoint}," +
                    "flushIntervalSeconds=1," +
                    "serviceName=testkit-scala-$module," +
                    "serviceInstanceId=scala-$module",
            )
        val otherlode = OtherlodeInstrumentation(config, registry)
        installedOtherlode = otherlode
        installedTransformer = otherlode.install(ByteBuddyAgent.install())

        val loader = fixtureLoader(module)
        val driver = Class.forName("$PACKAGE.Driver", true, loader)
        driver.getMethod("callCaseClassApply").invoke(null)
        driver.getMethod("callSimpleAllOmitted").invoke(null)

        val exportScheduler =
            ExportScheduler(config, TestResources.forConfig(config), registry, EndpointRegistry(), HttpOtlpStyleExporter(target.endpoint))
        scheduler = exportScheduler
        exportScheduler.start()
        target.awaitProbe("$PACKAGE.Driver\$", "callSimpleNoneOmitted", Duration.ofSeconds(10))
        target.awaitSettled(Duration.ofSeconds(10))

        val rows = target.neverHit()
        assertTrue(
            rows.none {
                it.className == "$PACKAGE.Driver"
            },
            "a static forwarder is no row: ${rows.filter { it.className == "$PACKAGE.Driver" }}",
        )
        assertEquals(
            setOf("a", "b"),
            rows.filter { it.className == "$PACKAGE.Cc" }.mapTo(mutableSetOf()) { it.methodName },
            "of Cc's methods, only its uncalled field accessors are rows; its static forwarders and plumbing are not",
        )
        assertTrue(
            rows.none { it.className == "$PACKAGE.Cc\$" && it.methodName in PLUMBING },
            "the companion's plumbing is no row: ${rows.filter { it.className == "$PACKAGE.Cc\$" }}",
        )

        val uncalled =
            driver.declaredMethods.map { it.name }.filter { it.startsWith("call") } - setOf("callCaseClassApply", "callSimpleAllOmitted")
        assertTrue(uncalled.isNotEmpty())
        for (name in uncalled) {
            assertEquals(1, rows.count { it.methodName == name }, "$name is one row, on Driver\$")
            assertEquals("$PACKAGE.Driver\$", rows.single { it.methodName == name }.className)
        }
    }

    @Test
    fun `scala 3 - neverHit leaves out Scala's generated methods and lists each object method once`() =
        `neverHit leaves out Scala's generated methods and lists each object method once`("scala3")

    @Test
    fun `scala 2 - neverHit leaves out Scala's generated methods and lists each object method once`() =
        `neverHit leaves out Scala's generated methods and lists each object method once`("scala2")
}
