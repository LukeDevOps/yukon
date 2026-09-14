package com.example.jaxrs.fixture

import javax.ws.rs.GET
import javax.ws.rs.Path

/**
 * Returned by [OrdersResource.subResource]. It carries its own `@Path`, so its identity, computed
 * purely from its own declared annotations when this class transforms on its own, happens to
 * match the path segment the locator method also declares. JAX-RS itself does not consult this
 * class-level annotation when resolving a sub-resource locator's result; only this class's own
 * method-level `@Path` values matter there. `JaxRsModule` reads each class's own annotations
 * independently and never composes a path across a locator and the class it returns, so this
 * class's identity on the wire is `GET /sub/leaf`, not `GET /orders/sub/leaf`.
 */
@Path("/sub")
class SubResource {
    @GET
    @Path("/leaf")
    fun leaf(): String = "leaf"
}
