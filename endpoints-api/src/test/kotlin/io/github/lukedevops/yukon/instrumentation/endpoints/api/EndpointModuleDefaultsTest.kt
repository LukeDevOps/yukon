package io.github.lukedevops.yukon.instrumentation.endpoints.api

import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.none
import net.bytebuddy.pool.TypePool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins what an [EndpointModule] that implements only the two required members inherits.
 *
 * Each default exists so that the common module needs no boilerplate, and each is load-bearing:
 * `bootModulesNeedingSeamRead` returning an empty set is what stops
 * `EndpointInstrumentation` adding a read edge for a module that instruments no JDK module,
 * `handlerInterfaces` returning an empty set is what keeps the lambda factory hook out of a JVM
 * whose modules never take a handler lambda, and
 * `declare` doing nothing is what lets every framework whose routes are readable from a
 * registration hook's own arguments ignore the seam's declare path entirely. A default that
 * silently changed to something else would be a behaviour change in every module that leans on it,
 * so they are asserted here rather than left to the modules that happen to inherit them.
 */
class EndpointModuleDefaultsTest {
    /** A module that overrides nothing beyond the two members [EndpointModule] has no default for. */
    private class MinimalModule : EndpointModule {
        override val name: String = "minimal"

        override fun typeMatcher(): ElementMatcher<in TypeDescription> = none()

        override fun transform(
            builder: DynamicType.Builder<*>,
            typeDescription: TypeDescription,
            advice: AdviceBinder,
            classLoader: ClassLoader?,
        ): DynamicType.Builder<*> = builder
    }

    @Test
    fun `a module that names no boot module needs no read edge to the seam`() {
        assertEquals(emptySet(), MinimalModule().bootModulesNeedingSeamRead)
    }

    @Test
    fun `a module that names no handler interface asks for no lambda to be recorded`() {
        assertEquals(emptySet(), MinimalModule().handlerInterfaces)
    }

    @Test
    fun `an advice binder resolves against the bootstrap loader when the target loader is null`() {
        // A module instrumenting a JDK class (the `jdk.httpserver` module's own types) is handed a
        // null classloader, which means the bootstrap loader rather than "no loader at all". The
        // advice class itself still comes from the agent loader, so binding one it carries has to
        // succeed, and binding a name nothing carries has to fail rather than yield empty advice.
        val binder = AdviceBinder(javaClass.classLoader, null)

        binder.bind("io.github.lukedevops.yukon.instrumentation.endpoints.api.fixture.PingAdvice")

        assertFailsWith<TypePool.Resolution.NoSuchTypeException> {
            binder.bind("io.github.lukedevops.yukon.instrumentation.endpoints.api.fixture.NotAnAdvice")
        }
    }
}
