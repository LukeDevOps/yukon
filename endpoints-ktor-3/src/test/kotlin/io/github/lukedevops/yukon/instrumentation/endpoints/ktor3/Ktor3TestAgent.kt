package io.github.lukedevops.yukon.instrumentation.endpoints.ktor3

import io.github.lukedevops.yukon.instrumentation.endpoints.EndpointInstrumentation
import io.github.lukedevops.yukon.registry.EndpointRegistry
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.lang.instrument.Instrumentation

/**
 * A real `-javaagent` entry point for [Ktor3ModuleTest], installed at JVM startup rather than
 * through [net.bytebuddy.agent.ByteBuddyAgent]'s self-attach.
 *
 * Ktor's routing DSL (`routing`, `get`, `post`, `route`, `handle`) is built from Kotlin `inline`
 * functions, so any code that calls it embeds `RoutingNode`/`RoutingRoot` references directly in
 * whatever class that code compiles into. Gradle's JUnit Platform integration loads every class in
 * this module's compiled test output while discovering `@Test` methods, before any test method's
 * own first statement runs, and it does this regardless of which class in that output a test
 * method actually calls or how the fixture routing is split across files. A self-attach inside the
 * test method, the approach `JdkHttpServerModuleTest` and `SpringWebMvcModuleTest` both use, is too
 * late here for that reason: `RoutingNode`/`RoutingRoot` are already loaded, unadvised, before
 * either test's own `install` call would run. A `-javaagent` on the test JVM's own command line
 * installs the transformer during [premain], which the JVM runs before any application class loads
 * at all, including Gradle's own test-worker bootstrap and JUnit Platform's discovery.
 *
 * [registry] is exposed for [Ktor3ModuleTest] to read after starting the fixture server. There is
 * exactly one instance for the life of the test JVM, matching the one real [premain] call this
 * object ever receives.
 */
object Ktor3TestAgent {
    lateinit var registry: EndpointRegistry
        private set

    private lateinit var instrumentation: Instrumentation
    private lateinit var transformer: ResettableClassFileTransformer

    @JvmStatic
    fun premain(
        agentArgs: String?,
        inst: Instrumentation,
    ) {
        registry = EndpointRegistry()
        val endpointInstrumentation = EndpointInstrumentation(registry, listOf(Ktor3Module()))
        transformer = endpointInstrumentation.install(inst)
        instrumentation = inst
    }

    /** Removes the transformer this object's [premain] installed, called by [Ktor3ModuleTest] during teardown. */
    fun uninstall() {
        EndpointInstrumentation(registry, listOf(Ktor3Module())).uninstall(instrumentation, transformer)
    }
}
