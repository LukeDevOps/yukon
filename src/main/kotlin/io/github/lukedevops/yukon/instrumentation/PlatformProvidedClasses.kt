package io.github.lukedevops.yukon.instrumentation

import java.util.concurrent.ConcurrentHashMap

/**
 * Whether the platform loader, and through it the bootstrap loader, provides a class: the JDK's
 * own classes, which are never a dependency. Asks for the class file as a resource, which reads
 * and never loads, and caches each answer. See ADR 0030.
 *
 * Shared by the transform path ([ReferencedClassLocator]) and the static baseline, which has no
 * defining loader to ask but can ask this one.
 */
internal class PlatformProvidedClasses(
    private val platformLoader: ClassLoader = ClassLoader.getPlatformClassLoader(),
) {
    private val answers = ConcurrentHashMap<String, Boolean>()

    /** Whether the platform or bootstrap loader finds [className] (dotted). A lookup that throws counts as not found. */
    fun provides(className: String): Boolean =
        answers.getOrPut(className) {
            try {
                platformLoader.getResource(className.replace('.', '/') + ".class") != null
            } catch (_: Exception) {
                false
            }
        }
}
