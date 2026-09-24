package io.github.lukedevops.yukon.dependencies

import io.github.lukedevops.yukon.registry.DependencyRegistry
import java.security.ProtectionDomain

/**
 * Counts the distinct classes loaded from each dependency, from the loaded-class array the sweep
 * takes on every flush. See ADR 0030.
 *
 * Counts nothing until [DependencyRegistry.isListingComplete], so a jar on the startup classpath is
 * never registered as discovered by load; the first count after the listing completes picks up
 * everything already loaded. A name is added to its dependency's set once and stays there when the
 * class unloads, so a total never falls.
 *
 * Arrays, primitives and hidden classes are skipped. A hidden class carries its host's protection
 * domain, so a lambda would otherwise count against its host's jar beyond the classes that jar
 * holds. So is a class with no protection domain or code source, which is a bootstrap or platform
 * class.
 */
class LoadedDependencyCounter internal constructor(
    private val registry: DependencyRegistry,
    private val resolve: (ProtectionDomain) -> Int?,
) {
    /** Resolves classes by the ADR 0030 rules, judging a jar the listing never saw under [includes] and [excludes]. */
    constructor(registry: DependencyRegistry, includes: List<String>, excludes: List<String>) :
        this(registry, DependencyResolver(registry, JarClassifier(includes, excludes))::resolve)

    /**
     * Adds every class in [loaded] to the dependency it came from, once the listing is complete.
     * Then it marks a counting generation ([DependencyRegistry.markCounted]). A jar this call
     * registered takes that generation too. A call that throws partway marks nothing.
     */
    fun count(loaded: Array<Class<*>>) {
        if (!registry.isListingComplete) return
        for (type in loaded) {
            if (type.isArray || type.isPrimitive || type.isHidden) continue
            val domain = type.protectionDomain ?: continue
            if (domain.codeSource == null) continue
            val dependencyId = resolve(domain) ?: continue
            registry.recordLoaded(dependencyId, type.name)
        }
        registry.markCounted()
    }
}
