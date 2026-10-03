package dev.otherlode.instrumentation.endpoints.springwebmvc

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.bind.annotation.RestController

/**
 * Fixture controller for [SpringWebMvcModuleTest], covering four kinds of Spring MVC mapping: a
 * single-verb path variable, a single-verb no-variable path, one mapping shared by two paths and
 * two verbs (four endpoints from one `@RequestMapping`), and a mapping with no verb constraint.
 */
@RestController
class TestController {
    @GetMapping("/checkout/{id}")
    fun checkout(
        @PathVariable("id") id: String,
    ): String = "checkout $id"

    @PostMapping("/promo")
    fun promo(): String = "promo"

    @RequestMapping(path = ["/a", "/b"], method = [RequestMethod.GET, RequestMethod.POST])
    fun ab(): String = "ab"

    @RequestMapping("/any")
    fun any(): String = "any"
}

/** Declares a mapping that [InheritingController] inherits without overriding. */
open class BaseController {
    @GetMapping("/inherited")
    fun inherited(): String = "inherited"
}

/** Serves [BaseController]'s mapping, whose method and probe live on the base class. */
@RestController
class InheritingController : BaseController()

/**
 * One mapping with two patterns that both match `/items/7`. Spring's best match is `/items/{id}`,
 * and a `HashSet` of the two pattern strings iterates the wildcard one first.
 */
@RestController
class ItemsController {
    @GetMapping("/items/{id}", "/items/**")
    fun items(): String = "items"
}

/** A handler registered in code through `registerMapping`, the way Spring Boot Actuator registers each operation. */
class ProgrammaticHandler {
    @ResponseBody
    fun registered(): String = "registered"
}
