package com.example.agenttarget

import com.example.fixture.used.Greeter

/**
 * The adopter-side code that references the `dep-used` fixture jar (see `testkit/build.gradle.kts`).
 * `DependencyUsageAgentTest` calls [greet], so the reference is held by a hit method.
 */
class DependencyUser {
    fun greet(): String = Greeter.greet("yukon")
}
