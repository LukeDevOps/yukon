package com.example.jaxrs.fixture

import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.HttpMethod
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam

/** A custom verb annotation, resolved through its own `@HttpMethod` meta-annotation, not by name. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@HttpMethod("PURGE")
annotation class PURGE

/**
 * A `jakarta.ws.rs` resource fixture proving
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
