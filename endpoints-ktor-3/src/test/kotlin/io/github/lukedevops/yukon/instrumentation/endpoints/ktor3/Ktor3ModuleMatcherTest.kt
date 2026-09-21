package io.github.lukedevops.yukon.instrumentation.endpoints.ktor3

import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.type.TypeDescription
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins which Ktor 3 types [Ktor3Module] instruments, and that it leaves anything else alone.
 *
 * The names are literals here on purpose. They are the module's only link to the framework, and a
 * framework renaming one is exactly the drift this project has no muzzle for: the module would
 * match nothing, instrument nothing, and report no endpoints without ever failing. Describing each
 * type through [TypeDescription.Latent] keeps that check free of the framework itself, so nothing
 * here loads a Ktor 3 class into this JVM ahead of the end-to-end test that needs to weave it.
 */
class Ktor3ModuleMatcherTest {
    @Test
    fun `the type matcher names this module's own Ktor 3 types and nothing else`() {
        val matcher = Ktor3Module().typeMatcher()

        assertTrue(matcher.matches(named("io.ktor.server.routing.RoutingNode")), "io.ktor.server.routing.RoutingNode")
        assertTrue(matcher.matches(named("io.ktor.server.routing.RoutingRoot")), "io.ktor.server.routing.RoutingRoot")
        assertFalse(matcher.matches(named("io.ktor.server.routing.Route")), "Ktor 2's own name for the node type belongs to Ktor2Module")
        assertFalse(matcher.matches(named("java.lang.String")), "an unrelated type must never match")
    }

    @Test
    fun `transform hands back the builder unchanged for a type the matcher does not name`() {
        val builder = ByteBuddy().redefine(UnrelatedType::class.java)

        val returned =
            Ktor3Module().transform(
                builder,
                named("io.ktor.server.routing.Route"),
                AdviceBinder(javaClass.classLoader, javaClass.classLoader),
                javaClass.classLoader,
            )

        assertSame(builder, returned, "an unmatched type must be handed back unvisited")
    }

    /** A type no name in this module's matcher matches. */
    private class UnrelatedType

    /** A description carrying nothing but [name], which is all the matcher and [Ktor3Module.transform] read. */
    private fun named(name: String): TypeDescription = TypeDescription.Latent(name, 0, null)
}
