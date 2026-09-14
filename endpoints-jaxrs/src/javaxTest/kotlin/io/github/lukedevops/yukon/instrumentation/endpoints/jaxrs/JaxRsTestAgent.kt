package io.github.lukedevops.yukon.instrumentation.endpoints.jaxrs

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.instrumentation.YukonInstrumentation
import io.github.lukedevops.yukon.instrumentation.endpoints.EndpointInstrumentation
import io.github.lukedevops.yukon.registry.EndpointRegistry
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.lang.instrument.Instrumentation

/**
 * A real `-javaagent` entry point for [JaxRsModuleTest], installed at JVM startup rather than
 * through [net.bytebuddy.agent.ByteBuddyAgent]'s self-attach.
 *
 * The fixture resource classes are referenced from the test body, which Gradle's JUnit Platform
 * integration loads while discovering `@Test` methods, ahead of that test method's own first
 * statement. A `-javaagent` on the test JVM's own command line installs both transformers during
 * [premain], which the JVM runs before any application class loads at all, discovery included;
 * see `Ktor3TestAgent`'s Javadoc in `:endpoints-ktor-3` for the same reasoning in full against a
 * different framework.
 *
 * Both instrumentation tiers install here, in the order this project's design requires:
 * [YukonInstrumentation] (the method tier) first, so [EndpointInstrumentation]'s `DECORATE`-mode
 * transformer sees a fixture class the method tier has already rewritten, never the reverse. Both
 * tiers are pointed at the same fixture package, so [JaxRsModuleTest] can prove they coexist
 * correctly on one resource method rather than merely running side by side.
 *
 * This is `:endpoints-jaxrs`'s `javax.ws.rs` copy of the agent used by the default `test` suite's
 * `jakarta.ws.rs` copy. It carries no `javax`/`jakarta` reference itself, so the duplication is
 * only about keeping this suite's `src/javaxTest/kotlin` source directory self-contained.
 *
 * [endpointRegistry] and [probeRegistry] are exposed for [JaxRsModuleTest] to read after driving
 * the fixture server. There is exactly one instance of each for the life of the test JVM, matching
 * the one real [premain] call this object ever receives.
 */
object JaxRsTestAgent {
    private const val FIXTURE_PACKAGE = "com.example.jaxrs.fixture"

    lateinit var endpointRegistry: EndpointRegistry
        private set

    lateinit var probeRegistry: ProbeRegistry
        private set

    private lateinit var instrumentation: Instrumentation
    private lateinit var methodInstrumentation: YukonInstrumentation
    private lateinit var methodTransformer: ResettableClassFileTransformer
    private lateinit var endpointInstrumentation: EndpointInstrumentation
    private lateinit var endpointTransformer: ResettableClassFileTransformer

    @JvmStatic
    fun premain(
        agentArgs: String?,
        inst: Instrumentation,
    ) {
        instrumentation = inst

        probeRegistry = ProbeRegistry()
        methodInstrumentation = YukonInstrumentation(AgentConfig.parse("includePackages=$FIXTURE_PACKAGE"), probeRegistry)
        methodTransformer = methodInstrumentation.install(inst)

        endpointRegistry = EndpointRegistry()
        endpointInstrumentation = EndpointInstrumentation(endpointRegistry, listOf(JaxRsModule()))
        endpointTransformer = endpointInstrumentation.install(inst)
    }

    /** Removes both transformers this object's [premain] installed, called by [JaxRsModuleTest] during teardown. */
    fun uninstall() {
        endpointInstrumentation.uninstall(instrumentation, endpointTransformer)
        methodInstrumentation.uninstall(instrumentation, methodTransformer)
    }
}
