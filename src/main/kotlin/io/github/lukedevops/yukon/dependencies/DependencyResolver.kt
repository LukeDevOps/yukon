package io.github.lukedevops.yukon.dependencies

import io.github.lukedevops.yukon.export.DependencyDiscoverySource
import io.github.lukedevops.yukon.registry.DependencyOrigin
import io.github.lukedevops.yukon.registry.DependencyRegistry
import java.io.FileNotFoundException
import java.lang.System.Logger.Level
import java.nio.file.Files
import java.nio.file.Path
import java.security.ProtectionDomain
import java.util.WeakHashMap
import java.util.jar.JarFile
import java.util.zip.ZipInputStream

/**
 * Finds the dependency a loaded class came from, by its [ProtectionDomain]. See ADR 0030.
 *
 * Two caches keep each location to one resolution per process. The first is keyed by the
 * protection domain in a [WeakHashMap]: `ProtectionDomain` does not override `equals`, so the key is
 * the object itself, and holding it weakly never pins a class loader. The second is keyed by the
 * code-source location's string form, since many domains (one per loader) can share a location.
 * Both keep negative answers too.
 *
 * A location is matched first against the origins the startup listing recorded, both the
 * dependencies it registered and the jars it judged not to be one. A jar the listing never saw (a
 * war's `WEB-INF/lib` opened by an app server after `premain`, a library Spring Boot unpacked to
 * `java.io.tmpdir`, a jar a runtime `URLClassLoader` reads) is read and judged by the listing's own
 * rules through [classifier]. A dependency whose identity is already registered, such
 * as an unpacked copy of a listed jar, maps to that record; any other is registered as discovered
 * by load. A directory, an unsupported scheme and a location that fails to resolve are not
 * dependencies; a failure is logged once at FINE, since this runs on every flush.
 *
 * Every public call is synchronized, so the resolver is safe to share between threads.
 */
internal class DependencyResolver(
    private val registry: DependencyRegistry,
    private val classifier: JarClassifier,
    private val readFlatJar: (Path) -> JarContents = ::readFlat,
    private val readNestedJar: (Path, String) -> JarContents = ::readNested,
) {
    private val log = System.getLogger(DependencyResolver::class.java.name)
    private val byDomain = WeakHashMap<ProtectionDomain, Int>()
    private val byLocation = HashMap<String, Int>()

    /** The dependency id [domain]'s classes belong to, or null when they belong to none. */
    @Synchronized
    fun resolve(domain: ProtectionDomain): Int? {
        val cached = byDomain[domain]
        if (cached != null) return cached.takeIf { it != NOT_A_DEPENDENCY }
        val location = domain.codeSource?.location
        val id = if (location == null) null else resolveLocation(location.toString())
        byDomain[domain] = id ?: NOT_A_DEPENDENCY
        return id
    }

    /** The dependency id for a code-source location's string form, or null. */
    @Synchronized
    fun resolveLocation(location: String): Int? {
        val cached = byLocation.getOrPut(location) { compute(location) ?: NOT_A_DEPENDENCY }
        return cached.takeIf { it != NOT_A_DEPENDENCY }
    }

    private fun compute(location: String): Int? =
        try {
            when (val parsed = CodeSourceLocation.parse(location)) {
                is CodeSourceLocation.OnDisk -> resolveOnDisk(parsed.path)
                is CodeSourceLocation.InJar -> resolveInJar(parsed)
                CodeSourceLocation.Unsupported -> null
            }
        } catch (e: Exception) {
            log.log(Level.DEBUG, "yukon: could not resolve $location to a dependency; its classes are not counted", e)
            null
        }

    private fun resolveOnDisk(path: Path): Int? {
        if (!Files.isRegularFile(path)) return null
        val origin = DependencyOrigin.FlatJar(path.toAbsolutePath())
        registry.idForOrigin(origin)?.let { return it }
        if (registry.isJudgedNotADependency(origin)) return null
        val contents = readFlatJar(path)
        return discovered(classifier.classifyFlat(contents, path.fileName.toString(), path.toAbsolutePath().toString(), origin))
    }

    private fun resolveInJar(location: CodeSourceLocation.InJar): Int? {
        if (location.isDirectory) return null
        val origin = DependencyOrigin.NestedJar(location.outerJar, location.entryName)
        registry.idForOrigin(origin)?.let { return it }
        if (registry.isJudgedNotADependency(origin)) return null
        val contents = readNestedJar(location.outerJar, location.entryName)
        val fileName = location.entryName.substringAfterLast('/')
        return discovered(
            classifier.classifyNested(contents, fileName, location.entryName, "${location.outerJar}!/${location.entryName}", origin),
        )
    }

    /** Maps [listed] to its existing record by identity, or registers it as discovered by load. */
    private fun discovered(listed: ListedDependency?): Int? {
        if (listed == null) return null
        registry.idForKey(DependencyRegistry.identityKey(listed.identities))?.let { return it }
        return registry.register(
            listed.identities,
            listed.identitySource,
            listed.location,
            DependencyDiscoverySource.LOAD,
            listed.classCount,
            listed.origin,
        )
    }

    private companion object {
        const val NOT_A_DEPENDENCY = -1

        fun readFlat(path: Path): JarContents = JarFile(path.toFile()).use(JarContents::of)

        /** Streams [entryName] out of [outerJar], the way the startup listing reads a nested jar. */
        fun readNested(
            outerJar: Path,
            entryName: String,
        ): JarContents =
            JarFile(outerJar.toFile()).use { jar ->
                val entry = jar.getJarEntry(entryName) ?: throw FileNotFoundException("$outerJar!/$entryName")
                ZipInputStream(jar.getInputStream(entry)).use(JarContents::of)
            }
    }
}
