package dev.otherlode.testkit.junit5

import dev.otherlode.testkit.OtherlodeTestCollector
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ParameterContext
import org.junit.jupiter.api.extension.ParameterResolver
import java.io.IOException
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * A JUnit 5 extension that starts one [OtherlodeTestCollector] for the life of the test JVM and
 * hands it to any test that asks for it.
 *
 * This extension never self-attaches the agent: JUnit's own discovery can load test classes,
 * and the classes under test, before any extension runs, so the agent has to be attached with
 * the ordinary `-javaagent` flag on the test task itself.
 *
 * ```kotlin
 * tasks.test {
 *     jvmArgs("-javaagent:/path/to/otherlode-agent.jar=endpoint=http://localhost:4319,flushIntervalSeconds=1,includePackages=com.acme")
 * }
 * ```
 *
 * Register the extension on a test class and ask for the collector as a parameter:
 *
 * ```kotlin
 * @ExtendWith(OtherlodeExtension::class)
 * class CheckoutTest {
 *     @Test
 *     fun `checkout is called`(collector: OtherlodeTestCollector) {
 *         // exercise the app under test
 *         collector.awaitSettled(Duration.ofSeconds(10))
 *         assertTrue(collector.wasCalled("POST", "/checkout"))
 *     }
 * }
 * ```
 *
 * The collector binds to the port named by the `otherlode.testkit.port` system property, defaulting
 * to 4319, the agent's own default `endpoint` port, so a `-javaagent` flag with no explicit
 * `endpoint` option works without further wiring. The agent always needs `includePackages`: without
 * it the agent refuses to start and no heartbeat ever arrives. [beforeAll] waits for the agent's
 * first liveness heartbeat once per test JVM, with a timeout named by the
 * `otherlode.testkit.startupTimeoutSeconds` system property, defaulting to 15 seconds.
 */
class OtherlodeExtension :
    BeforeAllCallback,
    ParameterResolver {
    override fun beforeAll(context: ExtensionContext) {
        val store = context.root.getStore(NAMESPACE)
        val collector = collectorIn(context)

        val heartbeatSeen = store.get(HEARTBEAT_SEEN_KEY, Boolean::class.javaObjectType) ?: false
        if (heartbeatSeen) return
        val timeoutSeconds = resolveStartupTimeoutSeconds()
        try {
            collector.awaitNextFlush(Duration.ofSeconds(timeoutSeconds))
        } catch (e: TimeoutException) {
            throw IllegalStateException(startupTimeoutMessage(collector, timeoutSeconds), e)
        }
        store.put(HEARTBEAT_SEEN_KEY, true)
    }

    override fun supportsParameter(
        parameterContext: ParameterContext,
        extensionContext: ExtensionContext,
    ): Boolean = parameterContext.parameter.type == OtherlodeTestCollector::class.java

    /**
     * Starts the collector in the root store if [beforeAll] has not yet: under
     * `@TestInstance(PER_CLASS)` JUnit resolves a constructor parameter before it calls
     * [beforeAll].
     */
    override fun resolveParameter(
        parameterContext: ParameterContext,
        extensionContext: ExtensionContext,
    ): Any = collectorIn(extensionContext)

    /** The test JVM's one collector, kept in the root store of [context] and started on first use. */
    private fun collectorIn(context: ExtensionContext): OtherlodeTestCollector {
        val collector =
            context.root
                .getStore(NAMESPACE)
                .getOrComputeIfAbsent(
                    COLLECTOR_KEY,
                    { CollectorHolder(startCollector()) },
                    CollectorHolder::class.java,
                ).collector
        sharedCollector = collector
        return collector
    }

    /**
     * What the root store holds instead of the collector itself. [OtherlodeTestCollector] is
     * `AutoCloseable`, and from JUnit 5.13 the store closes every `AutoCloseable` value it holds
     * when it closes, at the end of the test run and before the JVM exits. Wrapping the collector
     * in a class that is not `AutoCloseable` keeps it listening for the agent's shutdown-hook
     * flush on every JUnit version, which is what [startCollector]'s note relies on.
     */
    private class CollectorHolder(
        val collector: OtherlodeTestCollector,
    )

    companion object {
        private val NAMESPACE = ExtensionContext.Namespace.create(OtherlodeExtension::class.java)
        private const val COLLECTOR_KEY = "collector"
        private const val HEARTBEAT_SEEN_KEY = "heartbeat-seen"
        private const val PORT_PROPERTY = "otherlode.testkit.port"
        private const val STARTUP_TIMEOUT_PROPERTY = "otherlode.testkit.startupTimeoutSeconds"
        private const val DEFAULT_PORT = 4319
        private const val DEFAULT_STARTUP_TIMEOUT_SECONDS = 15L

        @Volatile
        private var sharedCollector: OtherlodeTestCollector? = null

        /**
         * The collector [beforeAll] started for this test JVM.
         *
         * Throws [IllegalStateException] if no test class has run with [OtherlodeExtension]
         * registered yet.
         */
        fun collector(): OtherlodeTestCollector =
            sharedCollector
                ?: throw IllegalStateException(
                    "OtherlodeExtension has not run yet: add @ExtendWith(OtherlodeExtension::class) to a test class first",
                )

        /**
         * The collector started here is deliberately never stopped when JUnit's root store
         * closes at the end of the test run. The agent's own shutdown-hook flush needs the
         * collector to still be listening at JVM exit, so the store entry is a [CollectorHolder]
         * that implements neither `CloseableResource` nor `AutoCloseable`. Leaving the collector
         * running cannot itself keep the test JVM alive: [OtherlodeTestCollector.start] already runs
         * its HTTP server's dispatcher thread as a daemon thread.
         */
        private fun startCollector(): OtherlodeTestCollector {
            val port = resolvePort()
            return try {
                OtherlodeTestCollector.startForOneJvm(port)
            } catch (e: IOException) {
                throw IllegalStateException(
                    "otherlode-testkit: could not bind the collector to port $port. Another process may already be " +
                        "using it, or a previous test run's collector is still listening. Override the port with " +
                        "the '$PORT_PROPERTY' system property.",
                    e,
                )
            }
        }

        private fun resolvePort(): Int {
            val raw = System.getProperty(PORT_PROPERTY)?.trim()?.ifBlank { null } ?: return DEFAULT_PORT
            return raw.toIntOrNull()
                ?: throw IllegalStateException("system property '$PORT_PROPERTY' must be an integer port, got '$raw'")
        }

        private fun resolveStartupTimeoutSeconds(): Long {
            val raw = System.getProperty(STARTUP_TIMEOUT_PROPERTY)?.trim()?.ifBlank { null } ?: return DEFAULT_STARTUP_TIMEOUT_SECONDS
            return raw.toLongOrNull()?.takeIf { it > 0 }
                ?: throw IllegalStateException(
                    "system property '$STARTUP_TIMEOUT_PROPERTY' must be a positive integer, got '$raw'",
                )
        }

        private fun startupTimeoutMessage(
            collector: OtherlodeTestCollector,
            timeoutSeconds: Long,
        ): String =
            "otherlode-testkit: no delta batch arrived from the agent within ${timeoutSeconds}s. Add " +
                "\"-javaagent:<path to otherlode-agent.jar>=endpoint=${collector.endpoint},flushIntervalSeconds=1," +
                "includePackages=<your package>\" to the test task's JVM arguments. Without includePackages " +
                "the agent refuses to start and sends nothing; its ERROR line on the test JVM's standard " +
                "error names a package to use when it can find one. The agent's default flush interval is " +
                "60 seconds, longer than this timeout, which is the most likely cause if the flag is already " +
                "present with includePackages set."
    }
}
