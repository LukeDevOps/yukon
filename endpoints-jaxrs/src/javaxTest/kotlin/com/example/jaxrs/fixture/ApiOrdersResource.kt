package com.example.jaxrs.fixture

import javax.ws.rs.GET
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.PathParam
import javax.ws.rs.Produces

/**
 * An interface resource, carrying class-level `@Path` and every method annotation, proving
 * [io.github.lukedevops.yukon.instrumentation.endpoints.jaxrs.JaxRsModule] resolves an inherited
 * method's verb and path from the interface it declares them on.
 */
@Path("/api/orders")
interface ApiOrders {
    @GET
    @Path("/{id}")
    fun get(
        @PathParam("id") id: String,
    ): String

    @POST
    fun create(): String

    @GET
    @Path("/{id}/audit")
    fun audit(
        @PathParam("id") id: String,
    ): String
}

/**
 * A bare implementation of [ApiOrders]. [get] and [create] carry no annotations of their own, so
 * both inherit their verb and path from the interface method they implement. [audit] carries its
 * own `@Produces`, which switches inheritance off for that method entirely under the
 * specification's all-or-nothing rule: it inherits nothing, is not an endpoint, and Jersey does
 * not serve it either, since Jersey follows the same rule. `JaxRsModuleTest` drives a request
 * against it and asserts a 404 to prove this module's declared endpoints match what Jersey
 * actually serves.
 */
class ApiOrdersResource : ApiOrders {
    override fun get(id: String): String = "order-$id"

    override fun create(): String = "created"

    @Produces("text/plain")
    override fun audit(id: String): String = "audit-$id"
}
