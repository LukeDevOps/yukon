package io.github.lukedevops.yukon.instrumentation.endpoints.jdkhttpserver

import io.github.lukedevops.yukon.instrumentation.endpoints.api.AdviceBinder
import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.DynamicType
import net.bytebuddy.matcher.ElementMatcher
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.namedOneOf
import net.bytebuddy.matcher.ElementMatchers.takesArguments

private const val SERVER_IMPL = "sun.net.httpserver.ServerImpl"
private const val HTTP_CONTEXT_IMPL = "sun.net.httpserver.HttpContextImpl"
private const val CONTEXT_LIST = "sun.net.httpserver.ContextList"
private const val ADVICE_PACKAGE = "io.github.lukedevops.yukon.endpoints.jdkhttpserver"

/**
 * Endpoint module for the JDK's own `com.sun.net.httpserver.HttpServer`.
 *
 * `HttpServer.create` returns a `sun.net.httpserver.HttpServerImpl` (or `HttpsServerImpl`), both
 * of which delegate registration and dispatch to one `sun.net.httpserver.ServerImpl`. This module
 * hooks three package-private types that `ServerImpl` delegates to, never `HttpServer` itself:
 *
 * - `ServerImpl.createContext` (both overloads, matched by name) is the registration hook. It
 *   constructs and returns the `HttpContextImpl` for a path, with or without a handler.
 * - `HttpContextImpl.setHandler` is how a context created without a handler gets one later. It is
 *   declared on the public `HttpContext` API, so `HttpContextImpl` only overrides it.
 * - `ContextList.findContext(String protocol, String path)`, the two-argument overload, is the
 *   dispatch point: it is the one call the request path makes, per incoming request, to match a
 *   path to its context. It returns the matched context before that context's handler runs, and a
 *   null return is the framework's own 404 path, which counts nothing. The three-argument overload
 *   (`boolean exact`) backs `contains`/`remove`, never the request path, so it is left alone.
 *
 * A context path is a prefix match with no verb constraint, so every endpoint this module
 * declares has verb `"*"` and a verbatim template equal to `HttpContext.getPath()` exactly as
 * registered.
 *
 * `HttpContextImpl` and `ContextList` are package-private, so every advice class here is typed
 * against the public `com.sun.net.httpserver.HttpContext`/`HttpHandler` supertypes instead, which
 * is a legal [net.bytebuddy.asm.Advice.Return]/[net.bytebuddy.asm.Advice.This] target for a
 * subtype's own method body.
 */
class JdkHttpServerModule : EndpointModule {
    override val name: String = "jdk-httpserver"

    /**
     * `jdk.httpserver` is a named module and cannot read the endpoint seam's own module, which
     * lives on the bootstrap loader's unnamed module. [io.github.lukedevops.yukon.instrumentation.endpoints.EndpointInstrumentation.install]
     * adds the read edge this module declares needing.
     */
    override val bootModulesNeedingSeamRead: Set<String> = setOf("jdk.httpserver")

    override fun typeMatcher(): ElementMatcher<in TypeDescription> = namedOneOf(SERVER_IMPL, HTTP_CONTEXT_IMPL, CONTEXT_LIST)

    override fun transform(
        builder: DynamicType.Builder<*>,
        typeDescription: TypeDescription,
        advice: AdviceBinder,
        classLoader: ClassLoader?,
    ): DynamicType.Builder<*> =
        when (typeDescription.name) {
            SERVER_IMPL -> {
                builder.visit(advice.bind("$ADVICE_PACKAGE.CreateContextAdvice").on(named("createContext")))
            }

            HTTP_CONTEXT_IMPL -> {
                builder.visit(advice.bind("$ADVICE_PACKAGE.SetHandlerAdvice").on(named("setHandler")))
            }

            CONTEXT_LIST -> {
                builder.visit(
                    advice
                        .bind("$ADVICE_PACKAGE.FindContextAdvice")
                        .on(named<MethodDescription>("findContext").and(takesArguments(2))),
                )
            }

            else -> {
                builder
            }
        }
}
