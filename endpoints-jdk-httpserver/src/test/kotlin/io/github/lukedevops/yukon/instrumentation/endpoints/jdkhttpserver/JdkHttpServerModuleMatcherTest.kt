package io.github.lukedevops.yukon.instrumentation.endpoints.jdkhttpserver

import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import net.bytebuddy.ByteBuddy
import net.bytebuddy.description.type.TypeDescription
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins which JDK HttpServer types [JdkHttpServerModule] instruments, and that it leaves anything else alone.
 *
 * The names are literals here on purpose. They are the module's only link to the framework, and a
 * framework renaming one is exactly the drift this project has no muzzle for: the module would
 * match nothing, instrument nothing, and report no endpoints without ever failing. Describing each
 * type through [TypeDescription.Latent] keeps that check free of the framework itself, so nothing
 * here loads a JDK HttpServer class into this JVM ahead of the end-to-end test that needs to weave it.
 */
class JdkHttpServerModuleMatcherTest {
    @Test
    fun `the type matcher names this module's own JDK HttpServer types and nothing else`() {
        val matcher = JdkHttpServerModule().typeMatcher()

        assertTrue(matcher.matches(named("sun.net.httpserver.ServerImpl")), "sun.net.httpserver.ServerImpl")
        assertTrue(matcher.matches(named("sun.net.httpserver.HttpContextImpl")), "sun.net.httpserver.HttpContextImpl")
        assertFalse(matcher.matches(named("com.sun.net.httpserver.HttpServer")), "the public facade is not what carries the implementation")
        assertFalse(matcher.matches(named("java.lang.String")), "an unrelated type must never match")
    }

    @Test
    fun `transform hands back the builder unchanged for a type the matcher does not name`() {
        val builder = ByteBuddy().redefine(UnrelatedType::class.java)

        val returned =
            JdkHttpServerModule().transform(
                builder,
                named("com.sun.net.httpserver.HttpServer"),
                AdviceBinder(javaClass.classLoader, javaClass.classLoader),
                javaClass.classLoader,
            )

        assertSame(builder, returned, "an unmatched type must be handed back unvisited")
    }

    @Test
    fun `the module asks for HttpHandler lambdas to be recorded, and for no other interface`() {
        assertEquals(setOf("com.sun.net.httpserver.HttpHandler"), JdkHttpServerModule().handlerInterfaces)
    }

    /** A type no name in this module's matcher matches. */
    private class UnrelatedType

    /** A description carrying nothing but [name], which is all the matcher and [JdkHttpServerModule.transform] read. */
    private fun named(name: String): TypeDescription = TypeDescription.Latent(name, 0, null)
}
