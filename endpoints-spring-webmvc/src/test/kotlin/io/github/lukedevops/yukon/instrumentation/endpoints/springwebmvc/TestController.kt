package io.github.lukedevops.yukon.instrumentation.endpoints.springwebmvc

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
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
