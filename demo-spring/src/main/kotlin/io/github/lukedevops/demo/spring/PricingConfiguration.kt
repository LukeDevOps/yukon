package io.github.lukedevops.demo.spring

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The one thing in this demo that makes Spring generate a CGLIB proxy in the adopter's own
 * package: a `@Configuration` class with `@Bean` methods and `proxyBeanMethods` left at its
 * default of true, which Spring enhances into a `PricingConfiguration$$SpringCGLIB$$0` subclass so
 * that [priceFormatter]'s call to [taxRate] returns the singleton bean rather than a second
 * instance. The proxy is synthesized in memory under a name no `.class` file carries, so it is a
 * class the static baseline scan cannot know about and a collector holds nothing else by that name.
 */
@Configuration
class PricingConfiguration {
    @Bean
    fun taxRate(): TaxRate = TaxRate(0.2)

    @Bean
    fun priceFormatter(): PriceFormatter = PriceFormatter(taxRate())
}

/** The rate [PriceFormatter] charges. */
data class TaxRate(
    val fraction: Double,
)

/** Applies [rate] to a pre-tax price. Reached from [OrderController.get], a hit path. */
class PriceFormatter(
    private val rate: TaxRate,
) {
    fun withTax(price: Double): String = "%.2f".format(price * (1 + rate.fraction))
}
