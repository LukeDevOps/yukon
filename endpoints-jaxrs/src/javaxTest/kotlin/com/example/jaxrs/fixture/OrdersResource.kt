package com.example.jaxrs.fixture

import javax.ws.rs.DELETE
import javax.ws.rs.GET
import javax.ws.rs.HttpMethod
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.PathParam

/** A custom verb annotation, resolved through its own `@HttpMethod` meta-annotation, not by name. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@HttpMethod("PURGE")
annotation class PURGE

/**
 * A `javax.ws.rs` resource fixture proving
 * [io.github.lukedevops.yukon.instrumentation.endpoints.jaxrs.JaxRsModule] end to end: an ordinary
 * verb-and-path method, a method carrying only a verb, two methods this module's own test never
 * calls, a sub-resource locator, and a custom verb built on `@HttpMethod`.
 */
@Path("/orders")
class OrdersResource {
    @GET
    @Path("/{id}")
    fun getOrder(
        @PathParam("id") id: String,
    ): String = "order-$id"

    @POST
    fun createOrder(): String = "created"

    @GET
    @Path("/{id}/invoice")
    fun getInvoice(
        @PathParam("id") id: String,
    ): String = "invoice-$id"

    @DELETE
    @Path("/{id: \\d+}")
    fun deleteOrder(
        @PathParam("id") id: String,
    ): String = "deleted-$id"

    @Path("/sub")
    fun subResource(): SubResource = SubResource()

    @PURGE
    @Path("/{id}/purge")
    fun purgeOrder(
        @PathParam("id") id: String,
    ): String = "purged-$id"
}
