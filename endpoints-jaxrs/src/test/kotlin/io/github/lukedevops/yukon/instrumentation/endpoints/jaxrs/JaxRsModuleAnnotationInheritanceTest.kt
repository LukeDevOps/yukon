package io.github.lukedevops.yukon.instrumentation.endpoints.jaxrs

import com.example.jaxrs.fixture.ApiOrdersResource
import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import io.github.lukedevops.yukon.registry.EndpointRegistry
import io.github.lukedevops.yukon.registry.HandlerRef
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.type.TypeDescription
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives [JaxRsModule.transform] directly, bypassing [JaxRsModule.typeMatcher] and the real
 * `-javaagent` weave, to pin the class-level `@Path` gate and the method-level inheritance walk
 * against known fixtures without going through a real Jersey server.
 *
 * [JaxRsTestAgent] already installed a resolver into the bootstrap-resident endpoint seam, and
 * already appended the bootstrap holder to the bootstrap loader, during this test JVM's own
 * `premain`, well before any test class loads. Each test here swaps that resolver for a recording
 * one for the duration of one [withRecordingResolver] call and restores an equivalent resolver,
 * wired to the same [EndpointRegistry], before returning, so a later test in this JVM (including
 * [JaxRsModuleTest] itself) never loses a registration to this test's own resolver.
 *
 * The resolver is built as a [Proxy] over `YukonEndpoints.Resolver`, never a compiled implementer:
 * see [YukonEndpointsSeamTest][io.github.lukedevops.yukon.instrumentation.endpoints.YukonEndpointsSeamTest]
 * in the root project for why. This module has no compile-time dependency on `:bootstrap` at all
 * (only `endpoints-jaxrs`'s main source set does), so both the resolver and the call into
 * `YukonEndpoints.install` are reflective here.
 */
class JaxRsModuleAnnotationInheritanceTest {
    private val advice = AdviceBinder(JaxRsModule::class.java.classLoader, ApiOrdersResource::class.java.classLoader)

    @Test
    fun `jerseyPresent true carries the interface's class-level Path onto both inherited methods`() {
        withRecordingResolver { calls ->
            val module = JaxRsModule(jerseyPresent = { true })

            module.transform(
                ByteBuddy().decorate(ApiOrdersResource::class.java),
                TypeDescription.ForLoadedType.of(ApiOrdersResource::class.java),
                advice,
                ApiOrdersResource::class.java.classLoader,
            )

            val templates = calls.map { it.verb to it.template }.toSet()
            assertEquals(setOf("GET" to "/api/orders/{id}", "POST" to "/api/orders"), templates)
        }
    }

    @Test
    fun `jerseyPresent false drops the interface's class-level Path from both inherited methods`() {
        withRecordingResolver { calls ->
            val module = JaxRsModule(jerseyPresent = { false })

            module.transform(
                ByteBuddy().decorate(ApiOrdersResource::class.java),
                TypeDescription.ForLoadedType.of(ApiOrdersResource::class.java),
                advice,
                ApiOrdersResource::class.java.classLoader,
            )

            val templates = calls.map { it.verb to it.template }.toSet()
            assertEquals(setOf("GET" to "/{id}", "POST" to "/"), templates)
        }
        // The INFO line resolveClassPath logs when a supertype @Path is ignored is not asserted
        // here: java.lang.System.Logger's default backend only bridges to java.util.logging when
        // something in the process has already initialised the JUL LogManager, which this test
        // does not control and cannot assume, so a java.util.logging.Handler is not a reliable way
        // to observe it. The behaviour the log line describes, the dropped class prefix above, is
        // asserted directly instead.
    }

    @Test
    fun `a path inherited from two interfaces at once resolves to the first declared`() {
        withRecordingResolver { calls ->
            val module = JaxRsModule(jerseyPresent = { false })

            module.transform(
                ByteBuddy().decorate(PingResource::class.java),
                TypeDescription.ForLoadedType.of(PingResource::class.java),
                advice,
                PingResource::class.java.classLoader,
            )

            assertEquals(listOf("GET" to "/ping-a"), calls.map { it.verb to it.template })
        }
    }

    /**
     * Installs a resolver that records every `register` call into [block], then restores a
     * resolver equivalent to the one [JaxRsTestAgent] installed during `premain`, wired to the
     * same [JaxRsTestAgent.endpointRegistry].
     */
    private fun withRecordingResolver(block: (List<RegisterCall>) -> Unit) {
        val calls = mutableListOf<RegisterCall>()
        installResolver(recordingResolver(calls))
        try {
            block(calls)
        } finally {
            installResolver(endpointRegistryResolver(JaxRsTestAgent.endpointRegistry))
        }
    }
}

private data class RegisterCall(
    val verb: String,
    val template: String,
)

/** Calls the bootstrap-resident `YukonEndpoints.install(Resolver)` reflectively, with no compile-time dependency on `:bootstrap`. */
private fun installResolver(resolver: Any) {
    val endpointsClass = Class.forName("io.github.lukedevops.yukon.bootstrap.YukonEndpoints", false, null)
    val resolverInterface = Class.forName("io.github.lukedevops.yukon.bootstrap.YukonEndpoints\$Resolver", false, null)
    endpointsClass.getMethod("install", resolverInterface).invoke(null, resolver)
}

/**
 * Builds a `YukonEndpoints.Resolver` as a [Proxy] instead of a compiled implementer class, the
 * same reasoning [YukonEndpointsSeamTest][io.github.lukedevops.yukon.instrumentation.endpoints.YukonEndpointsSeamTest]
 * gives in the root project: a compiled `implements YukonEndpoints.Resolver` class is verified
 * against that interface the moment its own class file loads, during Gradle's up-front scan for
 * test classes, which can run before the bootstrap loader carries the interface at all.
 */
private fun resolverProxy(dispatch: (methodName: String, args: Array<out Any?>) -> Any?): Any {
    val resolverInterface = Class.forName("io.github.lukedevops.yukon.bootstrap.YukonEndpoints\$Resolver", false, null)
    val loader = Thread.currentThread().contextClassLoader
    return Proxy.newProxyInstance(loader, arrayOf(resolverInterface)) { _, method, args -> dispatch(method.name, args ?: emptyArray()) }
}

/** Records every `register` call's verb and verbatim template; every other call is a no-op. */
private fun recordingResolver(calls: MutableList<RegisterCall>): Any =
    resolverProxy { name, args ->
        when (name) {
            "register" -> {
                calls += RegisterCall(args[2] as String, args[3] as String)
                null
            }

            "toString" -> {
                "RecordingResolver"
            }

            "hashCode" -> {
                System.identityHashCode(calls)
            }

            "equals" -> {
                args.getOrNull(0) === calls
            }

            else -> {
                null
            }
        }
    }

/**
 * A resolver wired to a real [EndpointRegistry], for restoring the seam between tests.
 *
 * It registers straight through, with none of `RegistryResolver`'s per-transform staging. That is
 * fine for leaving the seam in a usable state, and wrong for anything that asserts on when a
 * declaration lands, so no test should assert against this stand-in. `JaxRsModuleTest` runs in the
 * same JVM and would otherwise inherit it.
 */
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

private interface PingA {
    @GET
    @Path("/ping-a")
    fun ping(): String
}

private interface PingB {
    @GET
    @Path("/ping-b")
    fun ping(): String
}

/** Implements both [PingA] and [PingB], in that declaration order, so both interfaces supply an annotated `ping` with a different path. */
private class PingResource :
    PingA,
    PingB {
    override fun ping(): String = "pong"
}
