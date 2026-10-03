package dev.otherlode.instrumentation

import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Says where a class the adopter's code references lives, from the referencing class's own loader,
 * at transform time.
 *
 * It asks the loader for the class file as a resource, which reads and never loads, and sorts the
 * answer by [classify]. A null loader, the bootstrap loader, is asked through the platform loader,
 * which delegates to it: the bootstrap loader has no `ClassLoader` object to ask.
 *
 * Answers are cached per loader in a [WeakHashMap], so the cache never keeps a retired loader
 * alive, and whether the platform loader provides a name is cached once for every loader. Safe to
 * call from any transforming thread.
 */
internal class ReferencedClassLocator(
    private val platformLoader: ClassLoader = ClassLoader.getPlatformClassLoader(),
) {
    /**
     * A referenced class worth keeping. [location] is the code-source location string of the jar
     * holding it, or the resource URL itself for a scheme that is not a jar, or null when no loader
     * could find the class at all, an absent reference.
     */
    data class Found(
        val location: String?,
    )

    private val byLoader = WeakHashMap<ClassLoader, MutableMap<String, Any>>()
    private val platformClasses = PlatformProvidedClasses(platformLoader)

    /**
     * Where [className] (dotted) lives as [classLoader] sees it, or null when the reference is to be
     * dropped: a JDK class, any name in a `java` package, or a class read from a directory, which is
     * the adopter's own.
     */
    fun locate(
        className: String,
        classLoader: ClassLoader?,
    ): Found? {
        val loader = classLoader ?: platformLoader
        val cache = synchronized(byLoader) { byLoader.getOrPut(loader) { ConcurrentHashMap() } }
        val cached = cache[className]
        if (cached != null) return cached as? Found
        val found = compute(className, loader)
        cache.putIfAbsent(className, found ?: DROPPED)
        return found
    }

    private fun compute(
        className: String,
        loader: ClassLoader,
    ): Found? {
        val resourceName = className.replace('.', '/') + ".class"
        val url =
            try {
                loader.getResource(resourceName)?.toString()
            } catch (_: Exception) {
                null
            }
        // Only the JDK may define a class in a java package, so a java name nothing provides is
        // one an agent injected at runtime, such as JaCoCo's java.lang.$JaCoCo, and no dependency.
        if (url == null) return if (className.startsWith("java.")) null else Found(null)
        if (url.startsWith("jrt:", ignoreCase = true)) return null
        return classify(url, platformClasses.provides(className))
    }

    companion object {
        private val DROPPED = Any()
        private const val JAR_SEPARATOR = "!/"

        /**
         * Sorts one resource lookup. [url] is the resource URL's string form, or null when the loader
         * found nothing; [providedByPlatform] says whether the platform loader, and through it the
         * bootstrap loader, finds the same name.
         *
         * - Nothing found: kept, as absent.
         * - `jrt:`, or provided by the platform or bootstrap loader: a JDK class, dropped.
         * - `file:`: a class file in a directory on the classpath, the adopter's own, dropped.
         * - `jar:`: kept, with the URL cut after its last `!/`, which is the jar's code-source
         *   location in both `jar:file:<jar>!/` and Spring Boot's `jar:nested:<outer>/!<entry>!/`.
         * - Anything else: kept with the whole URL, which resolves to no dependency.
         *
         * Schemes are matched ignoring case: `java.net.URL` lowercases its protocol, but a custom
         * handler's `toExternalForm` may print it in any case.
         */
        fun classify(
            url: String?,
            providedByPlatform: Boolean,
        ): Found? {
            if (url == null) return Found(null)
            if (url.startsWith("jrt:", ignoreCase = true) || providedByPlatform) return null
            if (url.startsWith("file:", ignoreCase = true)) return null
            if (url.startsWith("jar:", ignoreCase = true)) {
                val separator = url.lastIndexOf(JAR_SEPARATOR)
                return Found(if (separator < 0) url else url.substring(0, separator + JAR_SEPARATOR.length))
            }
            return Found(url)
        }
    }
}
