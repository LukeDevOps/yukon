package io.github.lukedevops.yukon.instrumentation.endpoints

import io.github.lukedevops.yukon.instrumentation.endpoints.api.EndpointModule
import java.lang.System.Logger.Level
import java.util.ServiceConfigurationError
import java.util.ServiceLoader

/**
 * Finds every [EndpointModule] on [classLoader] with [ServiceLoader].
 *
 * Walks [ServiceLoader.iterator] by hand instead of collecting it into a list directly. The JDK's
 * own iterator defers a provider's classloading and construction to the [Iterator.next] call that
 * reaches it, so a single misconfigured provider (a `META-INF/services` entry naming a class no
 * longer on the classpath, a provider whose constructor throws) surfaces as a
 * [ServiceConfigurationError] from that one [Iterator.next] call rather than from
 * [ServiceLoader.load] itself. Catching it there and moving on means one broken module cannot
 * hide every other module behind it.
 */
object EndpointModules {
    private val log = System.getLogger(EndpointModules::class.java.name)

    fun discover(classLoader: ClassLoader = EndpointModules::class.java.classLoader): List<EndpointModule> {
        val modules = mutableListOf<EndpointModule>()
        val iterator = ServiceLoader.load(EndpointModule::class.java, classLoader).iterator()
        while (hasNextSafely(iterator)) {
            nextSafely(iterator)?.let { modules += it }
        }
        return modules.sortedBy { it.name }
    }

    private fun hasNextSafely(iterator: Iterator<EndpointModule>): Boolean =
        try {
            iterator.hasNext()
        } catch (e: ServiceConfigurationError) {
            log.log(Level.WARNING, "yukon: endpoint module discovery stopped early", e)
            false
        }

    private fun nextSafely(iterator: Iterator<EndpointModule>): EndpointModule? =
        try {
            iterator.next()
        } catch (e: ServiceConfigurationError) {
            log.log(Level.WARNING, "yukon: an endpoint module provider failed to load and will be skipped", e)
            null
        }
}
