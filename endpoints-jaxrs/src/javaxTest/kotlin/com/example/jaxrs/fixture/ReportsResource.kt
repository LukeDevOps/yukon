package com.example.jaxrs.fixture

import javax.ws.rs.GET
import javax.ws.rs.Path

/** An abstract superclass carrying a method annotation, with no class-level `@Path` of its own. */
abstract class BaseReports {
    @GET
    @Path("/summary")
    abstract fun summary(): String
}

/**
 * A concrete subclass of [BaseReports], proving
 * [io.github.lukedevops.yukon.instrumentation.endpoints.jaxrs.JaxRsModule] resolves an inherited
 * method's verb and path from a superclass, not only from an interface, while its own class-level
 * `@Path` (which the specification never treats as inherited in the first place) is simply read
 * from this class directly.
 */
@Path("/reports")
class ReportsResource : BaseReports() {
    override fun summary(): String = "summary"
}
