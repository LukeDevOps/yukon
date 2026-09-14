package io.github.lukedevops.yukon.instrumentation.endpoints

import io.github.lukedevops.yukon.bootstrap.YukonEndpoints
import io.github.lukedevops.yukon.instrumentation.BootstrapHolder
import io.github.lukedevops.yukon.registry.EndpointRegistry
import io.github.lukedevops.yukon.registry.HandlerRef
import net.bytebuddy.agent.ByteBuddyAgent
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestMethodOrder
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// YukonEndpoints is a bootstrap-resident singleton: install() has no matching uninstall, so once
// any test calls it the resolver stays set for the rest of this JVM. The pre-install behaviour
// (buffering, no-op with nothing installed) is only observable before the first ever install()
// call, so it is ordered to run first and this is the only test class that touches this class.
//
// Every Resolver used here is a java.lang.reflect.Proxy built at runtime inside a test method,
// never a compiled class declaring "implements YukonEndpoints.Resolver": a compiled implementer
// is verified against the interface the moment its own class file loads, and Gradle loads every
// class on the test classpath up front to look for tests, long before any test's BeforeEach has
// installed the bootstrap holder. A proxy defers that resolution to the moment it is built.
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class YukonEndpointsSeamTest {
    @BeforeEach
    fun installBootstrapHolder() {
        BootstrapHolder.install(ByteBuddyAgent.install())
    }

    @Test
    @Order(1)
    fun `lookup, hit, and attachHandler no-op when no resolver is installed`() {
        val module = uniqueModule("no-resolver")

        assertNull(YukonEndpoints.lookup(module, "some-key"))
        // Must not throw either with a null entry or with one that was never really resolved.
        YukonEndpoints.hit(null)
        YukonEndpoints.hit("an-entry-that-was-never-really-resolved")
        YukonEndpoints.attachHandler(module, null, "com.example.Handler", "handle", "()V")
        YukonEndpoints.attachHandler(module, "an-entry-that-was-never-really-resolved", "com.example.Handler", "handle", "()V")
        assertFalse(YukonEndpoints.isDisabled(module))
    }

    @Test
    @Order(2)
    fun `register and recordDispatch buffer before install, replay in order once installed, and drop beyond the 4096 cap`() {
        val registerModule = uniqueModule("cap-register")
        val dispatchModule = uniqueModule("cap-dispatch")
        val failureModule = uniqueModule("cap-failure")

        val firstRegisterReturn =
            YukonEndpoints.register(registerModule, "key-0", "GET", "/cap-test/register/0", null, null, null, null)
        assertNull(firstRegisterReturn, "register must return null before a resolver is installed")
        for (i in 1 until 4090) {
            assertNull(YukonEndpoints.register(registerModule, "key-$i", "GET", "/cap-test/register/$i", null, null, null, null))
        }

        val firstDispatchReturn = YukonEndpoints.recordDispatch(dispatchModule, "dispatch-key-0", "GET", "/cap-test/dispatch/0", null, null)
        assertNull(firstDispatchReturn, "recordDispatch must return null before a resolver is installed")
        for (i in 1 until 5) {
            assertNull(YukonEndpoints.recordDispatch(dispatchModule, "dispatch-key-$i", "GET", "/cap-test/dispatch/$i", null, null))
        }

        // 4090 register + 5 dispatch + 1 failure records exactly fill the 4096-record cap.
        YukonEndpoints.moduleFailed(failureModule, RuntimeException("linkage boom"))

        // Every one of these arrives after the buffer is already full, so all must be dropped.
        for (i in 4090 until 4110) {
            assertNull(YukonEndpoints.register(registerModule, "overflow-key-$i", "GET", "/cap-test/register/$i", null, null, null, null))
        }

        val resolver = RecordingResolver()
        YukonEndpoints.install(resolver.asResolver() as YukonEndpoints.Resolver)

        assertEquals(4090, resolver.registerCalls.size, "only the first 4090 register records should have survived the cap")
        assertEquals("/cap-test/register/0", resolver.registerCalls.first().verbatimTemplate)
        assertEquals("/cap-test/register/4089", resolver.registerCalls.last().verbatimTemplate)
        assertTrue(resolver.registerCalls.none { it.verbatimTemplate == "/cap-test/register/4090" }, "overflow records must not replay")

        assertEquals(5, resolver.dispatchCalls.size)
        assertEquals(5, resolver.hitCalls.size, "each replayed dispatch must be followed by exactly one hit")

        val disableCall = resolver.disableCalls.single()
        assertEquals(failureModule, disableCall.module)
        assertTrue("linkage boom" in disableCall.reason)
        assertTrue(YukonEndpoints.isDisabled(failureModule))
    }

    @Test
    @Order(3)
    fun `moduleFailed disables one module while another keeps working`() {
        val resolver = RecordingResolver()
        YukonEndpoints.install(resolver.asResolver() as YukonEndpoints.Resolver)
        val brokenModule = uniqueModule("broken")
        val healthyModule = uniqueModule("healthy")

        YukonEndpoints.moduleFailed(brokenModule, RuntimeException("unexpected framework version"))

        assertTrue(YukonEndpoints.isDisabled(brokenModule))
        assertFalse(YukonEndpoints.isDisabled(healthyModule))

        assertNull(YukonEndpoints.lookup(brokenModule, "key"))
        assertNull(YukonEndpoints.register(brokenModule, "key", "GET", "/dead", null, null, null, null))
        assertNull(YukonEndpoints.recordDispatch(brokenModule, "key2", "GET", "/dead2", null, null))
        YukonEndpoints.attachHandler(brokenModule, "some-entry", "com.example.Handler", "handle", "()V")
        assertTrue(resolver.registerCalls.none { it.framework == brokenModule })
        assertTrue(resolver.attachCalls.isEmpty())

        val entry = YukonEndpoints.register(healthyModule, "key3", "GET", "/alive", null, null, null, null)
        assertNotNull(entry)
        YukonEndpoints.hit(entry)
        assertTrue(resolver.hitCalls.contains(entry))
    }

    @Test
    @Order(4)
    fun `a resolver that throws from lookup or hit does not propagate`() {
        val module = uniqueModule("throwing")
        YukonEndpoints.install(alwaysThrowingResolver() as YukonEndpoints.Resolver)

        assertNull(YukonEndpoints.lookup(module, "key"))
        assertNull(YukonEndpoints.lookup(module, "key"))

        YukonEndpoints.hit("some-entry")
        YukonEndpoints.hit("some-entry")

        // A resolver bug never disables the module on its own; only moduleFailed does that.
        assertFalse(YukonEndpoints.isDisabled(module))
    }

    @Test
    @Order(5)
    fun `install twice with different resolvers uses the latest`() {
        val module = uniqueModule("swap")
        val first = RecordingResolver()
        val second = RecordingResolver()

        YukonEndpoints.install(first.asResolver() as YukonEndpoints.Resolver)
        YukonEndpoints.install(second.asResolver() as YukonEndpoints.Resolver)
        val entry = YukonEndpoints.register(module, "key", "GET", "/swap", null, null, null, null)

        assertTrue(first.registerCalls.isEmpty())
        assertEquals(1, second.registerCalls.size)
        assertNotNull(entry)
    }

    @Test
    @Order(6)
    fun `wired to a real EndpointRegistry, register, lookup and hit show up in computeDeltas`() {
        val module = uniqueModule("real-registry")
        val registry = EndpointRegistry()
        YukonEndpoints.install(endpointRegistryResolver(registry) as YukonEndpoints.Resolver)
        val key = Any()

        val entry = YukonEndpoints.register(module, key, "GET", "/checkout", null, "com.example.Checkout", "handle", "()V")
        assertNotNull(entry)
        assertSame(entry, YukonEndpoints.lookup(module, key))

        YukonEndpoints.hit(entry)

        val delta =
            registry
                .computeDeltas(maxPerBatch = 10)
                .single()
                .deltas
                .single()
        assertEquals(1L, delta.hitsTotal)
    }

    private companion object {
        val MODULE_SEQUENCE = AtomicLong()

        fun uniqueModule(prefix: String): String = "$prefix-${MODULE_SEQUENCE.incrementAndGet()}"
    }
}

/**
 * Builds a [YukonEndpoints.Resolver] as a [Proxy] instead of a compiled implementer class.
 *
 * The interface [Class] is fetched with the bootstrap loader (`null`) explicitly, so this only
 * works once [BootstrapHolder.install] has already appended it there. A compiled `class Foo :
 * YukonEndpoints.Resolver` would need that interface resolved the moment `Foo.class` itself
 * loads, which happens far earlier, during Gradle's up-front scan for test classes; building the
 * implementation as a proxy at runtime, inside a test method, avoids that entirely.
 */
private fun resolverProxy(dispatch: (methodName: String, args: Array<out Any?>) -> Any?): Any {
    val resolverInterface = Class.forName("io.github.lukedevops.yukon.bootstrap.YukonEndpoints\$Resolver", false, null)
    val loader = Thread.currentThread().contextClassLoader
    val proxy =
        Proxy.newProxyInstance(loader, arrayOf(resolverInterface)) { _, method, args ->
            dispatch(method.name, args ?: emptyArray())
        }
    return proxy
}

/** Records every call a [Resolver][YukonEndpoints.Resolver] proxy receives, for a test to assert on shape and order. */
private class RecordingResolver {
    data class RegisterCall(
        val key: Any,
        val framework: String,
        val verbatimTemplate: String,
    )

    data class DispatchCall(
        val key: Any,
        val framework: String,
        val verbatimTemplate: String,
    )

    data class DisableCall(
        val module: String,
        val reason: String,
    )

    val registerCalls = mutableListOf<RegisterCall>()
    val dispatchCalls = mutableListOf<DispatchCall>()
    val hitCalls = mutableListOf<Any>()
    val attachCalls = mutableListOf<Any>()
    val disableCalls = mutableListOf<DisableCall>()

    private var nextEntryId = 0

    /** A [Resolver][YukonEndpoints.Resolver] proxy that records every call it receives onto this instance. */
    fun asResolver(): Any =
        resolverProxy { name, args ->
            when (name) {
                "lookup" -> {
                    null
                }

                "register" -> {
                    registerCalls += RegisterCall(args[0]!!, args[1] as String, args[3] as String)
                    "entry-${nextEntryId++}"
                }

                "recordDispatch" -> {
                    dispatchCalls += DispatchCall(args[0]!!, args[1] as String, args[3] as String)
                    "entry-${nextEntryId++}"
                }

                "hit" -> {
                    hitCalls += args[0]!!
                    null
                }

                "attachHandler" -> {
                    attachCalls += args[0]!!
                    null
                }

                "disableModule" -> {
                    disableCalls += DisableCall(args[0] as String, args[1] as String)
                    null
                }

                "toString" -> {
                    "RecordingResolver"
                }

                "hashCode" -> {
                    System.identityHashCode(this)
                }

                "equals" -> {
                    args.getOrNull(0) === this
                }

                else -> {
                    null
                }
            }
        }
}

/** A [Resolver][YukonEndpoints.Resolver] proxy whose every method throws, to prove a broken resolver can never escape into a caller. */
private fun alwaysThrowingResolver(): Any =
    resolverProxy { name, _ ->
        when (name) {
            "toString" -> "AlwaysThrowingResolver"
            "hashCode" -> System.identityHashCode(Unit)
            "equals" -> false
            else -> throw RuntimeException("boom: $name")
        }
    }

/** A [Resolver][YukonEndpoints.Resolver] proxy wired to a real [EndpointRegistry], proving the seam's shapes line up. */
private fun endpointRegistryResolver(registry: EndpointRegistry): Any =
    resolverProxy { name, args ->
        when (name) {
            "lookup" -> {
                registry.lookup(args[0]!!)
            }

            "register" -> {
                registry.register(
                    key = args[0]!!,
                    framework = args[1] as String,
                    verb = args[2] as String?,
                    verbatimTemplate = args[3] as String,
                    contextPath = args[4] as String?,
                    handler = (args[5] as String?)?.let { HandlerRef(it, args[6] as String?, args[7] as String?) },
                )
            }

            "recordDispatch" -> {
                registry.recordDispatch(
                    key = args[0]!!,
                    framework = args[1] as String,
                    verb = args[2] as String?,
                    verbatimTemplate = args[3] as String,
                    contextPath = args[4] as String?,
                    handlerClass = args[5] as String?,
                )
            }

            "hit" -> {
                (args[0] as EndpointRegistry.EndpointEntry).hit()
                null
            }

            "attachHandler" -> {
                val handlerClass = args[1] as String?
                if (handlerClass != null) {
                    registry.attachHandler(
                        args[0] as EndpointRegistry.EndpointEntry,
                        HandlerRef(handlerClass, args[2] as String?, args[3] as String?),
                    )
                }
                null
            }

            "disableModule" -> {
                registry.recordDisabledModule(args[0] as String, args[1] as String)
                null
            }

            "toString" -> {
                "EndpointRegistryResolver"
            }

            "hashCode" -> {
                System.identityHashCode(registry)
            }

            "equals" -> {
                false
            }

            else -> {
                null
            }
        }
    }
