package io.github.lukedevops.demo.spring

import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * A component-scanned controller bean, registered through Spring's bean-name path: its
 * `AbstractHandlerMethodMapping` receives this controller's bean name, not a resolved instance,
 * the branch the `endpoints-spring-webmvc` module's `MockMvc`-driven tests never exercise, since
 * those register a controller instance directly into the test context.
 *
 * Each method carries its own full path rather than a class-level `@RequestMapping`, so [ping]'s
 * route is `/ping`, not `/orders/ping`: a class-level mapping would combine with every method's
 * path, including [ping]'s, and Spring MVC has no way for one method to opt back out of it.
 *
 * The demo client calls [get], [create], and [ping]. [invoice] and [delete] are declared but
 * never called, and [InvoiceService.render], reachable only through [invoice], is never hit at
 * the method tier either. [get]'s legacy-pricing branch is always off in this demo, the same
 * feature-flag shape as the plain demo server's `/checkout`, and its [PriceFormatter] comes from
 * [PricingConfiguration], the `@Bean`-bearing class Spring proxies with CGLIB.
 *
 * Three of the demo's dependencies show the three statuses short of used in the stub
 * collector's dependency report. `commons-lang3` is on the classpath and no class from it loads,
 * so it is unloaded. `jackson-databind` is loaded by Spring and referenced only from
 * [LegacyPricing], which never loads, so it is unreached. `spring-webmvc` is loaded by Spring and
 * never named here, since the mapping annotations above come from `spring-web`, so it is
 * unreferenced, as are several other framework jars.
 */
@RestController
class OrderController(
    private val invoiceService: InvoiceService,
    private val priceFormatter: PriceFormatter,
) {
    @GetMapping("/orders/{id}")
    fun get(
        @PathVariable id: String,
    ): String {
        val price =
            if (System.getenv("YUKON_DEMO_LEGACY_PRICING") == "true") {
                LegacyPricing().apply(100.0)
            } else {
                100.0
            }
        return "order $id price=${priceFormatter.withTax(price)}"
    }

    @PostMapping("/orders")
    fun create(): String = "order created"

    @GetMapping("/orders/{id}/invoice")
    fun invoice(
        @PathVariable id: String,
    ): String = invoiceService.render(id)

    @DeleteMapping("/orders/{id}")
    fun delete(
        @PathVariable id: String,
    ): String = "order $id deleted"

    @RequestMapping("/ping")
    fun ping(): String = "pong"
}
