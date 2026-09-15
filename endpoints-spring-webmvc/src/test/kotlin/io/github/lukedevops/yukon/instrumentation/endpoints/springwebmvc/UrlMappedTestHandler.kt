package io.github.lukedevops.yukon.instrumentation.endpoints.springwebmvc

import org.springframework.web.HttpRequestHandler
import java.io.PrintWriter
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Builds an [HttpRequestHandler] that writes a fixed [body] to the response, as a JDK dynamic
 * proxy rather than a class implementing the interface directly.
 *
 * `HttpRequestHandler.handleRequest`'s own parameter types are `javax.servlet.http
 * .HttpServletRequest`/`HttpServletResponse` on Spring Framework 5.3 and `jakarta.servlet.http
 * .HttpServletRequest`/`HttpServletResponse` on 6.x and 7.x. This test source directory is shared
 * unmodified across all three, so a class implementing the interface directly, with either
 * signature hardcoded, would fail to compile against the other. A dynamic proxy sidesteps this: it
 * reaches the response argument reflectively instead of naming its type.
 */
fun urlMappedTestHandler(body: String): Any {
    val handlerInterface = HttpRequestHandler::class.java
    val invocationHandler =
        InvocationHandler { proxy, method, args ->
            when (method.name) {
                "handleRequest" -> {
                    val response = args[1]
                    val writer = response.javaClass.getMethod("getWriter").invoke(response) as PrintWriter
                    writer.write(body)
                    null
                }

                "hashCode" -> {
                    System.identityHashCode(proxy)
                }

                "equals" -> {
                    proxy === args[0]
                }

                "toString" -> {
                    "UrlMappedTestHandler($body)"
                }

                else -> {
                    null
                }
            }
        }
    return Proxy.newProxyInstance(handlerInterface.classLoader, arrayOf(handlerInterface), invocationHandler)
}
