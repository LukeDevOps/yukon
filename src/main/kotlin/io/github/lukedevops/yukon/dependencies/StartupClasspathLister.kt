package io.github.lukedevops.yukon.dependencies

import io.github.lukedevops.yukon.export.DependencyIdentity
import io.github.lukedevops.yukon.export.DependencyIdentitySource
import io.github.lukedevops.yukon.registry.DependencyOrigin
import io.github.lukedevops.yukon.registry.DependencyRegistry
import java.io.File
import java.lang.System.Logger.Level
import java.net.URI
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.ZipInputStream

/**
 * One dependency the startup listing found, or the sweep found at load. [origin] is where its
 * bytes live, kept off the wire.
 */
data class ListedDependency(
    val identities: List<DependencyIdentity>,
    val identitySource: DependencyIdentitySource,
    val location: String,
    val classCount: Int,
    val origin: DependencyOrigin,
)

/**
 * Lists the dependencies on the startup classpath. See ADR 0030.
 *
 * Walks every entry of [classPath] (split on [File.pathSeparator]) and every jar a manifest
 * `Class-Path` attribute reaches from one, resolved against the referencing jar the way the JDK's
 * system loader resolves it, each jar once. Like the system loader, any file that is not a
 * directory is read as a jar, whatever its extension. A missing entry and a directory are never
 * dependencies. Neither is a jar whose manifest names an agent (`Premain-Class` or
 * `Launcher-Agent-Class`), though its `Class-Path` is still followed.
 *
 * A Spring Boot fat jar or executable war (a manifest with `Spring-Boot-Classpath-Index` or
 * `Spring-Boot-Lib`) is the application, never a dependency. Its dependencies are the nested jars
 * Boot's launcher puts on the classpath (see [nestedJarNames]), each streamed from the outer jar
 * with nothing extracted to disk.
 *
 * With [includes] set, a jar holding any class the include rules admit is the adopter's own and
 * not a dependency. With [includes] empty, every class is in scope, so every jar is a dependency
 * and only directories and a fat jar's own classes are the adopter's. [JarClassifier] holds these
 * rules, shared with the sweep's discovery at load.
 *
 * Two jars with one identity key ([DependencyRegistry.identityKey]) are one dependency, and the
 * first found keeps its location and class count. A jar that cannot be read is skipped with one
 * WARNING and the rest of the listing continues.
 *
 * Every jar read and judged not a dependency (an agent jar, a fat jar, a jar of the adopter's own)
 * goes to [onNotADependency], so the sweep can recognise its classes without reading it again.
 */
class StartupClasspathLister(
    includes: List<String>,
    excludes: List<String>,
    private val classPath: String = System.getProperty("java.class.path").orEmpty(),
    private val onNotADependency: (DependencyOrigin) -> Unit = {},
) {
    private val log = System.getLogger(StartupClasspathLister::class.java.name)
    private val classifier = JarClassifier(includes, excludes)

    /** Walks the classpath and returns the dependencies found, in the order the JDK would search them. */
    fun list(): List<ListedDependency> {
        val found = LinkedHashMap<List<String>, ListedDependency>()
        val visited = mutableSetOf<File>()
        val pending = ArrayDeque(classPath.split(File.pathSeparator).filter { it.isNotEmpty() }.map(::File))
        while (pending.isNotEmpty()) {
            val root = pending.removeFirst()
            if (!root.isFile) continue
            val canonical = runCatching { root.canonicalFile }.getOrDefault(root.absoluteFile)
            if (!visited.add(canonical)) continue
            val referenced = listJar(root.absoluteFile) { found.putIfAbsent(DependencyRegistry.identityKey(it.identities), it) }
            // Searched straight after the jar naming them, before the next classpath entry, as the JDK does.
            referenced.asReversed().forEach(pending::addFirst)
        }
        return found.values.toList()
    }

    /** Lists one flat jar, reporting each dependency it yields to [emit]; returns the jars its `Class-Path` names. */
    private fun listJar(
        jar: File,
        emit: (ListedDependency) -> Unit,
    ): List<File> {
        try {
            JarFile(jar).use { jarFile ->
                val manifest = jarFile.manifest
                val referenced = classPathEntries(jar, manifest)
                val origin = DependencyOrigin.FlatJar(jar.toPath())
                when (classifier.kindOf(manifest)) {
                    JarClassifier.Kind.AGENT -> {
                        onNotADependency(origin)
                    }

                    JarClassifier.Kind.BOOT_APPLICATION -> {
                        onNotADependency(origin)
                        listNestedJars(jar, jarFile, manifest?.mainAttributes ?: Attributes(), emit)
                    }

                    JarClassifier.Kind.ORDINARY -> {
                        val listed = classifier.classifyFlat(JarContents.of(jarFile), jar.name, jar.path, origin)
                        if (listed != null) emit(listed) else onNotADependency(origin)
                    }
                }
                return referenced
            }
        } catch (e: Exception) {
            log.log(Level.WARNING, "yukon: could not read $jar for the dependency listing, skipping it", e)
            return emptyList()
        }
    }

    private fun listNestedJars(
        outer: File,
        jarFile: JarFile,
        attributes: Attributes,
        emit: (ListedDependency) -> Unit,
    ) {
        for (entryName in nestedJarNames(jarFile, attributes)) {
            val entry = jarFile.getJarEntry(entryName) ?: continue
            try {
                val contents = ZipInputStream(jarFile.getInputStream(entry)).use(JarContents::of)
                val origin = DependencyOrigin.NestedJar(outer.toPath(), entryName)
                val listed = classifier.classifyNested(contents, entryName.substringAfterLast('/'), entryName, "$outer!/$entryName", origin)
                if (listed != null) emit(listed) else onNotADependency(origin)
            } catch (e: Exception) {
                log.log(Level.WARNING, "yukon: could not read $outer!/$entryName for the dependency listing, skipping it", e)
            }
        }
    }

    /**
     * The nested jars Spring Boot's launcher puts on the classpath, in the outer jar's entry order:
     * every entry that is not a directory under `BOOT-INF/lib/`, or under `WEB-INF/lib/` and
     * `WEB-INF/lib-provided/` for an executable war, at any depth and whatever its extension.
     *
     * The classpath index is not consulted. `Launcher.getClassPathIndex` returns null unless the
     * archive is exploded, so a packaged `java -jar` launch ignores it, and `JarLauncher` and
     * `WarLauncher` select these prefixes themselves in `isLibraryFileOrClassesDirectory` (read from
     * the loader bytecode Boot 4.1.1 packages). The index can leave a jar out that still runs, such as
     * `spring-boot-jarmode-tools`.
     */
    private fun nestedJarNames(
        jarFile: JarFile,
        attributes: Attributes,
    ): List<String> {
        val isWar =
            attributes.getValue(BOOT_LIB)?.trim()?.startsWith(WAR_PREFIX) == true ||
                attributes.getValue("Main-Class")?.trim()?.endsWith(WAR_LAUNCHER) == true
        val prefixes = if (isWar) WAR_LIB_PREFIXES else JAR_LIB_PREFIXES
        return jarFile
            .entries()
            .asSequence()
            .filter { !it.isDirectory && prefixes.any { prefix -> it.name.startsWith(prefix) } }
            .map { it.name }
            .toList()
    }

    /**
     * The jars [manifest]'s `Class-Path` names. Each entry is a URL, relative to [jar] unless it
     * names a scheme, the way the system loader reads it, so a space in a path arrives as `%20`.
     * An entry that is not a valid URI, or names a scheme other than `file`, is dropped.
     */
    private fun classPathEntries(
        jar: File,
        manifest: Manifest?,
    ): List<File> {
        val value = manifest?.mainAttributes?.getValue("Class-Path") ?: return emptyList()
        val base = jar.toURI()
        return value.split(' ').filter { it.isNotBlank() }.mapNotNull { entry ->
            runCatching {
                val resolved = base.resolve(URI(entry))
                if (resolved.scheme == "file") File(resolved) else null
            }.getOrNull()
        }
    }

    private companion object {
        const val BOOT_LIB = "Spring-Boot-Lib"
        const val WAR_PREFIX = "WEB-INF/"
        const val WAR_LAUNCHER = ".WarLauncher"
        val JAR_LIB_PREFIXES = listOf("BOOT-INF/lib/")
        val WAR_LIB_PREFIXES = listOf("WEB-INF/lib/", "WEB-INF/lib-provided/")
    }
}
