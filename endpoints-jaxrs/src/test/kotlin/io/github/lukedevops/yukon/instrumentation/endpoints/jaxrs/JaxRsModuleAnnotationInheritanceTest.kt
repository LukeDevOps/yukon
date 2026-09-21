package io.github.lukedevops.yukon.instrumentation.endpoints.jaxrs

import com.example.jaxrs.fixture.ApiOrdersResource
import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import io.github.lukedevops.yukon.registry.EndpointRegistry
import io.github.lukedevops.yukon.registry.HandlerRef
import jakarta.ws.rs.GET
import jakarta.ws.rs.HttpMethod
import jakarta.ws.rs.Path
import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.pool.TypePool
import java.lang.reflect.Proxy
import java.net.URLClassLoader
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

    @Test
    fun `a superclass's class-level Path is inherited when Jersey is present`() {
        assertEquals(setOf("GET" to "/legacy/{id}"), declaredBy(LegacyResource::class.java, jerseyPresent = true))
    }

    @Test
    fun `a superclass's class-level Path is dropped when Jersey is absent`() {
        assertEquals(setOf("GET" to "/{id}"), declaredBy(LegacyResource::class.java, jerseyPresent = false))
    }

    @Test
    fun `a class-level Path two superclasses up is inherited`() {
        assertEquals(setOf("GET" to "/deep/leaf"), declaredBy(DeepLeafResource::class.java, jerseyPresent = true))
    }

    @Test
    fun `a superclass's class-level Path wins over an interface's, as Jersey resolves it`() {
        assertEquals(setOf("GET" to "/from-class/both"), declaredBy(BothSourcesResource::class.java, jerseyPresent = true))
    }

    @Test
    fun `a class-level Path on a superclass's own interface is inherited`() {
        assertEquals(setOf("GET" to "/from-iface/mid"), declaredBy(MidInterfaceResource::class.java, jerseyPresent = true))
    }

    @Test
    fun `a method with no annotation anywhere is not an endpoint`() {
        assertEquals(emptySet(), declaredBy(UnannotatedResource::class.java, jerseyPresent = true))
    }

    @Test
    fun `a method inherits its verb and path from the superclass method it overrides`() {
        assertEquals(setOf("GET" to "/inherited"), declaredBy(OverridingResource::class.java, jerseyPresent = false))
    }

    @Test
    fun `a method inherits its annotations through a superinterface of an implemented interface`() {
        assertEquals(setOf("GET" to "/grandparent"), declaredBy(GrandInterfaceResource::class.java, jerseyPresent = false))
    }

    @Test
    fun `a custom annotation meta-annotated with HttpMethod is read as that verb, uppercased`() {
        assertEquals(setOf("PURGE" to "/cache"), declaredBy(CustomVerbResource::class.java, jerseyPresent = false))
    }

    @Test
    fun `a supertype the type pool cannot resolve leaves the method declared without a class prefix`() {
        // The description ByteBuddy hands a real transform is TypePool-backed and resolves a
        // supertype lazily, so a resource whose superclass is not on its own loader (an optional
        // dependency, a jar trimmed at packaging) raises on first touch. Degrading to "no class
        // prefix" keeps the method; letting it escape would disable this module for the process.
        val hidden = LegacyBase::class.java.name
        val loader = LegacyResource::class.java.classLoader
        val locator =
            object : ClassFileLocator {
                private val delegate = ClassFileLocator.ForClassLoader.of(loader)

                override fun locate(name: String): ClassFileLocator.Resolution =
                    if (name == hidden) ClassFileLocator.Resolution.Illegal(name) else delegate.locate(name)

                override fun close() = delegate.close()
            }
        val described =
            TypePool.Default
                .of(locator)
                .describe(LegacyResource::class.java.name)
                .resolve()

        lateinit var declared: Set<Pair<String, String>>
        withRecordingResolver { calls ->
            JaxRsModule(jerseyPresent = { true }).transform(
                ByteBuddy().decorate(LegacyResource::class.java),
                described,
                advice,
                loader,
            )
            declared = calls.map { it.verb to it.template }.toSet()
        }

        assertEquals(setOf("GET" to "/{id}"), declared)
    }

    @Test
    fun `the default module reads Jersey's presence from the loader it is given`() {
        // Jersey is on this test's own classpath, so the real isJerseyPresent finds its marker
        // through the fixture's loader and applies the inherited class prefix; a loader with no
        // parent and no classpath sees nothing, and the prefix is dropped.
        val withJersey = declaredByDefaultModule(LegacyResource::class.java.classLoader)
        val withoutJersey = declaredByDefaultModule(URLClassLoader(arrayOf(), null))

        assertEquals(setOf("GET" to "/legacy/{id}"), withJersey)
        assertEquals(setOf("GET" to "/{id}"), withoutJersey)
    }

    /** What a [JaxRsModule] built with no injected Jersey check declares for [LegacyResource] on [classLoader]. */
    private fun declaredByDefaultModule(classLoader: ClassLoader?): Set<Pair<String, String>> {
        lateinit var declared: Set<Pair<String, String>>
        withRecordingResolver { calls ->
            JaxRsModule().transform(
                ByteBuddy().decorate(LegacyResource::class.java),
                TypeDescription.ForLoadedType.of(LegacyResource::class.java),
                advice,
                classLoader,
            )
            declared = calls.map { it.verb to it.template }.toSet()
        }
        return declared
    }

    /** Every `(verb, template)` [JaxRsModule.transform] declares for [type]. */
    private fun declaredBy(
        type: Class<*>,
        jerseyPresent: Boolean,
    ): Set<Pair<String, String>> {
        lateinit var declared: Set<Pair<String, String>>
        withRecordingResolver { calls ->
            JaxRsModule(jerseyPresent = { jerseyPresent }).transform(
                ByteBuddy().decorate(type),
                TypeDescription.ForLoadedType.of(type),
                advice,
                type.classLoader,
            )
            declared = calls.map { it.verb to it.template }.toSet()
        }
        return declared
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

@Path("/legacy")
private abstract class LegacyBase

/** Inherits its class-level `@Path` from [LegacyBase], which only Jersey's resolution order honours. */
private class LegacyResource : LegacyBase() {
    @GET
    @Path("/{id}")
    fun find(): String = "one"
}

@Path("/deep")
private abstract class DeepRoot

private abstract class DeepMiddle : DeepRoot()

/** Two classes below the one carrying `@Path`, so the superclass walk has to keep going. */
private class DeepLeafResource : DeepMiddle() {
    @GET
    @Path("/leaf")
    fun leaf(): String = "leaf"
}

@Path("/from-iface")
private interface PathBearingInterface

@Path("/from-class")
private abstract class PathBearingBase

/** Carries a class-level `@Path` on both a superclass and an interface; Jersey's order takes the superclass. */
private class BothSourcesResource :
    PathBearingBase(),
    PathBearingInterface {
    @GET
    @Path("/both")
    fun both(): String = "both"
}

private abstract class MidWithInterface : PathBearingInterface

/** Its own interfaces carry no `@Path`; the one on its superclass's interface is the only source. */
private class MidInterfaceResource : MidWithInterface() {
    @GET
    @Path("/mid")
    fun mid(): String = "mid"
}

/** No JAX-RS annotation on the class, its supertypes, or the method: not an endpoint at all. */
private class UnannotatedResource {
    fun plain(): String = "plain"
}

private abstract class AnnotatedMethodBase {
    @GET
    @Path("/inherited")
    open fun read(): String = "base"
}

/** Overrides an annotated method without annotating the override, which the specification inherits. */
private class OverridingResource : AnnotatedMethodBase() {
    override fun read(): String = "child"
}

private interface GrandparentInterface {
    @GET
    @Path("/grandparent")
    fun read(): String
}

private interface ParentInterface : GrandparentInterface

/** Reaches its annotations only through [ParentInterface]'s own superinterface. */
private class GrandInterfaceResource : ParentInterface {
    override fun read(): String = "grandchild"
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@HttpMethod("purge")
private annotation class Purge

/** Declares a verb JAX-RS has no annotation of its own for, the way `@PATCH` was defined before the spec had one. */
private class CustomVerbResource {
    @Purge
    @Path("/cache")
    fun purge(): String = "purged"
}
