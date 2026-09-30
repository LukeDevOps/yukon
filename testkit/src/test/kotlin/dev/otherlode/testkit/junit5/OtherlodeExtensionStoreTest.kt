package dev.otherlode.testkit.junit5

import org.junit.jupiter.api.extension.ExtensionContext
import java.lang.reflect.Proxy
import java.util.function.Function
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins what [OtherlodeExtension] puts in JUnit's root store. From JUnit 5.13 the store closes every
 * `AutoCloseable` value it holds when the store itself closes, at the end of the test run and
 * before the JVM exits. [dev.otherlode.testkit.OtherlodeTestCollector] is `AutoCloseable`,
 * so storing it directly would stop the collector before the agent's shutdown-hook flush could
 * reach it, which is the outcome the extension exists to avoid. The store here is a fake that only
 * records what is put in it; JUnit's own lifecycle is not involved.
 */
class OtherlodeExtensionStoreTest {
    private val stored = mutableMapOf<Any, Any?>()
    private var previousPort: String? = null
    private var previousTimeout: String? = null

    @BeforeTest
    fun useAFreePortAndAShortTimeout() {
        previousPort = System.setProperty("otherlode.testkit.port", "0")
        previousTimeout = System.setProperty("otherlode.testkit.startupTimeoutSeconds", "1")
    }

    @AfterTest
    fun restoreProperties() {
        restore("otherlode.testkit.port", previousPort)
        restore("otherlode.testkit.startupTimeoutSeconds", previousTimeout)
        runCatching { OtherlodeExtension.collector().close() }
    }

    private fun restore(
        key: String,
        value: String?,
    ) {
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
    }

    @Test
    fun `the value kept in the root store is not AutoCloseable, so a store that closes such values cannot stop the collector`() {
        val context = fakeContext()

        // No agent is attached to this JVM, so the heartbeat wait times out; the store entry is
        // made before that wait, which is all this test needs.
        assertFailsWith<IllegalStateException> { OtherlodeExtension().beforeAll(context) }

        assertTrue(stored.isNotEmpty(), "beforeAll must have put the collector in the store")
        assertTrue(stored.values.none { it is AutoCloseable }, "stored ${stored.values.map { it?.javaClass?.name }}")
    }

    /**
     * An agent attached without `includePackages` refuses to start and sends no heartbeat
     * (ADR 0033), so the timeout names that option beside the flag and the flush interval.
     */
    @Test
    fun `a startup timeout names includePackages, the javaagent flag and the flush interval`() {
        // No agent is attached to this JVM, so the heartbeat wait times out after one second.
        val failure = assertFailsWith<IllegalStateException> { OtherlodeExtension().beforeAll(fakeContext()) }
        val message = failure.message.orEmpty()

        assertTrue("includePackages=<your package>" in message, message)
        assertTrue("refuses to start" in message, message)
        assertTrue("-javaagent:" in message, message)
        assertTrue("flushIntervalSeconds=1" in message, message)
        assertTrue("default flush interval is 60 seconds" in message, message)
    }

    @Test
    fun `a port property that is not a number fails naming the property`() {
        System.setProperty("otherlode.testkit.port", "not-a-port")

        val failure = assertFailsWith<IllegalStateException> { OtherlodeExtension().beforeAll(fakeContext()) }

        assertTrue("otherlode.testkit.port" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `a startup timeout property that is not a positive number fails naming the property`() {
        System.setProperty("otherlode.testkit.startupTimeoutSeconds", "0")

        val failure = assertFailsWith<IllegalStateException> { OtherlodeExtension().beforeAll(fakeContext()) }

        assertTrue("otherlode.testkit.startupTimeoutSeconds" in failure.message.orEmpty(), failure.message)
    }

    private fun fakeContext(): ExtensionContext {
        val loader = ExtensionContext::class.java.classLoader
        val store =
            Proxy.newProxyInstance(loader, arrayOf(ExtensionContext.Store::class.java)) { _, method, args ->
                when (method.name) {
                    "getOrComputeIfAbsent" -> {
                        @Suppress("UNCHECKED_CAST")
                        val compute = args[1] as Function<Any, Any?>
                        stored.getOrPut(args[0]) { compute.apply(args[0]) }
                    }

                    "get" -> {
                        stored[args[0]]
                    }

                    "put" -> {
                        stored[args[0]] = args[1]
                        null
                    }

                    else -> {
                        null
                    }
                }
            } as ExtensionContext.Store
        lateinit var context: ExtensionContext
        context =
            Proxy.newProxyInstance(loader, arrayOf(ExtensionContext::class.java)) { _, method, _ ->
                when (method.name) {
                    "getRoot" -> context
                    "getStore" -> store
                    else -> null
                }
            } as ExtensionContext
        return context
    }
}
