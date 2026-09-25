package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ConditionPart
import io.github.lukedevops.yukon.export.ConditionPartKind
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves through the real transform that the demo's checkout handler sends each site's condition,
 * code parts around literal parts. The demo's compiled classes come from the benchmark corpus the
 * root build already passes to tests. See ADR 0037.
 */
class ConditionInstrumentationTest {
    private fun code(text: String) = ConditionPart(ConditionPartKind.CODE, text)

    private fun literal(text: String) = ConditionPart(ConditionPartKind.STRING_LITERAL, text)

    /**
     * The 1-based line of the demo's server source that holds [fragment], so the expected lines
     * follow the source rather than being pinned. The fragment must appear on exactly one line.
     */
    private fun demoLineOf(fragment: String): Int {
        val source =
            File(
                System.getProperty("yukon.demo.serverMainSource")
                    ?: error("system property yukon.demo.serverMainSource is not set; run tests through the root Gradle build"),
            )
        val lines = source.readLines().withIndex().filter { fragment in it.value }
        check(lines.size == 1) { "expected \"$fragment\" on one line of ${source.name}, found ${lines.size}" }
        return lines.single().index + 1
    }

    @Test
    fun `the demo's checkout handler sends each condition at its source line`() {
        val demoClasses =
            File(
                System.getProperty("yukon.benchmark.corpus.demo.main")
                    ?: error("system property yukon.benchmark.corpus.demo.main is not set; run tests through the root Gradle build"),
            )
        val registry = ProbeRegistry()
        val instrumentation = ByteBuddyAgent.install()
        val yukon = YukonInstrumentation(AgentConfig.parse("includePackages=io.github.lukedevops.demo"), registry)
        val transformer = yukon.install(instrumentation)
        try {
            val loader = FixtureClassLoader(arrayOf(demoClasses.toURI().toURL()), javaClass.classLoader, "io.github.lukedevops.demo.")
            Class.forName("io.github.lukedevops.demo.server.DemoServerMainKt", true, loader)

            val manifest = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1"))
            val handler =
                manifest.probes.single {
                    it.className == "io.github.lukedevops.demo.server.DemoServerMainKt" &&
                        it.kind == ProbeKind.METHOD &&
                        it.methodName == "handleCheckout"
                }

            assertEquals(
                mapOf(
                    demoLineOf("if (System.getenv(\"ENABLE_LEGACY_DISCOUNT\") == \"true\")") to
                        listOf(code("System.getenv("), literal("ENABLE_LEGACY_DISCOUNT"), code(") == "), literal("true")),
                    demoLineOf("if (discounted > FREE_SHIPPING_THRESHOLD)") to listOf(code("discounted > 100.0")),
                ),
                handler.branchSites.associate { it.line to it.condition },
            )
        } finally {
            yukon.uninstall(instrumentation, transformer)
        }
    }
}
