package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.registry.ProbeMeta
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import java.lang.instrument.Instrumentation
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.logging.Level as JulLevel
import java.util.logging.Logger as JulLogger

/**
 * Pins the reverse direction ADR 0028 adds to the sweep: reconciling a registered class's name
 * against the JVM's own loaded set, as opposed to the forward, unreported-class direction ADR 0027
 * already covers in [DeflectedClassLoadTest].
 */
class LoadedClassSweepTest {
    private val instrumentation: Instrumentation = ByteBuddyAgent.install()
    private val defaultConfig = AgentConfig.parse(null)

    private fun oneMethodProbe(): List<ProbeMeta> = listOf(ProbeMeta(ProbeKind.METHOD, "m", "()V", line = 1))

    /**
     * Captures the records a [java.lang.System.Logger] obtained for [loggerName] emits, through
     * its default `java.util.logging` backend, mirroring `ExportSchedulerTest`'s own helper.
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
    fun `confirmFrom sees every loaded class's name, not the forward direction's filtered candidates`() {
        // java.lang.String's classloader is null, so isCandidate rejects it outright: it is on the
        // bootstrap loader, the same gate that turns away the entire JDK for the forward
        // direction. Registering it here proves the confirmation pass reconciles against the raw
        // loaded set rather than that same filtered list.
        val registry = ProbeRegistry(confirmsDefinitions = true)
        registry.register("java.lang.String", layoutHash = 1L, probes = oneMethodProbe())
        val sweep = LoadedClassSweep(instrumentation, registry, defaultConfig)

        sweep.run(runForwardPass = true)

        assertEquals(
            0,
            registry.unconfirmedClassCount(),
            "a class isCandidate would reject must still be confirmed from the JVM's raw loaded-class names",
        )
        assertEquals(0, registry.withheldForGoodClassCount())
    }

    @Test
    fun `a registered class the JVM has loaded is confirmed by a sweep`() {
        val registry = ProbeRegistry(confirmsDefinitions = true)
        registry.register(LoadedClassSweepTest::class.java.name, layoutHash = 1L, probes = oneMethodProbe())
        val sweep = LoadedClassSweep(instrumentation, registry, defaultConfig)

        sweep.run(runForwardPass = false)

        assertEquals(0, registry.unconfirmedClassCount())
    }

    @Test
    fun `one WARNING is logged per class newly withheld for good, and none again for the same class`() {
        val registry = ProbeRegistry(confirmsDefinitions = true)
        registry.register("com.example.never.Loaded", layoutHash = 1L, probes = oneMethodProbe())
        val sweep = LoadedClassSweep(instrumentation, registry, defaultConfig)

        val records =
            captureLogRecords(LoadedClassSweep::class.java.name) {
                sweep.run(runForwardPass = false) // first miss, not yet withheld
                sweep.run(runForwardPass = false) // second miss, withheld for good
                sweep.run(runForwardPass = false) // already withheld, must not warn again
            }

        val warnings = records.filter { it.level == JulLevel.WARNING && it.message.contains("com.example.never.Loaded") }
        assertEquals(1, warnings.size, "confirmFrom returns a withheld class's name once and never again")
        assertEquals(1, registry.withheldForGoodClassCount())
    }

    @Test
    fun `the shutdown summary is logged only on the final flush and only when a class was withheld`() {
        val registry = ProbeRegistry(confirmsDefinitions = true)
        registry.register("com.example.never.Loaded", layoutHash = 1L, probes = oneMethodProbe())
        val sweep = LoadedClassSweep(instrumentation, registry, defaultConfig)

        val beforeFinal =
            captureLogRecords(LoadedClassSweep::class.java.name) {
                sweep.run(runForwardPass = false) // first miss
                sweep.run(runForwardPass = false, final = false) // second miss, withheld, but not final
            }
        assertTrue(
            beforeFinal.none { it.message.contains("withheld") && it.message.contains("were") },
            "no summary line before the final flush, even once a class is withheld",
        )
        assertEquals(1, registry.withheldForGoodClassCount())

        val onFinal =
            captureLogRecords(LoadedClassSweep::class.java.name) {
                sweep.run(runForwardPass = false, final = true)
            }
        val summary = onFinal.filter { it.level == JulLevel.WARNING && it.message.contains("1") && it.message.contains("never confirmed") }
        assertEquals(1, summary.size)
    }

    @Test
    fun `the shutdown summary is not logged when no class was withheld`() {
        val registry = ProbeRegistry(confirmsDefinitions = true)
        val sweep = LoadedClassSweep(instrumentation, registry, defaultConfig)

        val records = captureLogRecords(LoadedClassSweep::class.java.name) { sweep.run(runForwardPass = false, final = true) }

        assertTrue(records.none { it.message.contains("never confirmed") })
    }
}
