package io.github.lukedevops.demo.spring

import tools.jackson.databind.json.JsonMapper

/**
 * Guarded by a feature flag that is always off in this demo, so this class never loads at all.
 * Mirrors the plain demo server's `LegacyDiscountCalculator`, but here the static scan has to
 * read this class out of `BOOT-INF/classes` inside a real Spring Boot fat jar rather than off a
 * flat classpath directory.
 *
 * It is also the only class in this demo that names Jackson. Spring loads `jackson-databind` for
 * its own message converters, but the adopter's one reference to it sits here, in a class that
 * never loads, so the stub collector's dependency report shows `jackson-databind` as unreached
 * and lists [apply] as where the reference sits.
 */
class LegacyPricing {
    /** Applies the legacy discount, round-tripping the price through the JSON the old pricing service stored it as. */
    fun apply(price: Double): Double {
        val mapper = JsonMapper.shared()
        val stored = mapper.writeValueAsString(price * 0.95)
        return mapper.readValue(stored, Double::class.java)
    }
}
