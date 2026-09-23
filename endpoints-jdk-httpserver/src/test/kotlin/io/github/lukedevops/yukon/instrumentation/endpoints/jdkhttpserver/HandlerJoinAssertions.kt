package io.github.lukedevops.yukon.instrumentation.endpoints.jdkhttpserver

import io.github.lukedevops.yukon.export.EndpointLocation
import java.lang.invoke.MethodType
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Asserts that [endpoint] joins to a method [owner] declares, named with [methodPrefix] and having
 * [descriptor].
 *
 * A compiler picks a lambda body's name, such as `lambda$lambda$0` from javac or `kotlinLambda$lambda$0`
 * from kotlinc. The index is the compiler's choice, so only the prefix is fixed here. The method must
 * still exist on [owner] with that exact name and descriptor, so a join to a method that is not there
 * fails.
 */
internal fun assertJoinsDeclaredMethod(
    endpoint: EndpointLocation,
    owner: Class<*>,
    methodPrefix: String,
    descriptor: String,
) {
    val route = endpoint.routeTemplate
    assertEquals(owner.name, endpoint.handlerClass, "$route: handler class")
    val method = assertNotNull(endpoint.handlerMethod, "$route: handler method")
    assertTrue(method.startsWith(methodPrefix), "$route: handler method $method should start with $methodPrefix")
    assertEquals(descriptor, endpoint.handlerDescriptor, "$route: handler descriptor")
    val declared =
        owner.declaredMethods.any {
            it.name == method && MethodType.methodType(it.returnType, it.parameterTypes).toMethodDescriptorString() == descriptor
        }
    assertTrue(declared, "$route: ${owner.name} declares no method $method$descriptor")
}

/** Asserts that [endpoint] has no handler join at all. */
internal fun assertNoJoin(
    endpoint: EndpointLocation,
    why: String,
) {
    assertNull(endpoint.handlerClass, "${endpoint.routeTemplate}: $why")
    assertNull(endpoint.handlerMethod, "${endpoint.routeTemplate}: $why")
    assertNull(endpoint.handlerDescriptor, "${endpoint.routeTemplate}: $why")
}
