package io.github.lukedevops.yukon.dependencies

import io.github.lukedevops.yukon.dependencies.TestJars.classEntry
import io.github.lukedevops.yukon.dependencies.TestJars.pom
import io.github.lukedevops.yukon.export.DependencyDiscoverySource
import io.github.lukedevops.yukon.export.DependencyIdentity
import io.github.lukedevops.yukon.export.DependencyIdentitySource
import io.github.lukedevops.yukon.registry.DependencyOrigin
import io.github.lukedevops.yukon.registry.DependencyRegistry
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.CodeSource
import java.security.ProtectionDomain
import java.security.cert.Certificate
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import java.util.logging.Level as JulLevel
import java.util.logging.Logger as JulLogger

class DependencyResolverTest {
    @TempDir
    lateinit var dir: Path

    private val registry = DependencyRegistry()

    private fun resolver(
        includes: List<String> = emptyList(),
        readFlat: ((Path) -> JarContents)? = null,
        readNested: ((Path, String) -> JarContents)? = null,
    ): DependencyResolver {
        val classifier = JarClassifier(includes, emptyList())
        return when {
            readFlat != null && readNested != null -> DependencyResolver(registry, classifier, readFlat, readNested)
            readFlat != null -> DependencyResolver(registry, classifier, readFlatJar = readFlat)
            readNested != null -> DependencyResolver(registry, classifier, readNestedJar = readNested)
            else -> DependencyResolver(registry, classifier)
        }
    }

    private val neverRead: (Path) -> JarContents = { error("a known origin must not be read, got $it") }
    private val neverReadNested: (
        Path,
        String,
    ) -> JarContents = { outer, entry -> error("a known origin must not be read, got $outer!/$entry") }

    private fun domain(location: String): ProtectionDomain =
        ProtectionDomain(CodeSource(java.net.URI(location).toURL(), null as Array<Certificate>?), null)

    private fun listed(
        artifact: String,
        origin: DependencyOrigin,
    ): Int =
        registry.register(
            listOf(DependencyIdentity("g", artifact, "1")),
            DependencyIdentitySource.POM_PROPERTIES,
            "/listed/$artifact.jar",
            DependencyDiscoverySource.STARTUP_CLASSPATH,
            classCount = 1,
            origin = origin,
        )

    @Test
    fun `a flat jar class matches its FlatJar origin without reading the jar`() {
        val jar = TestJars.write(dir.resolve("one-1.0.jar"), listOf(classEntry("org.one.A")))
        val id = listed("one", DependencyOrigin.FlatJar(jar))

        assertEquals(id, resolver(readFlat = neverRead).resolve(domain(jar.toUri().toString())))
    }

    @Test
    fun `a nested jar matches its NestedJar origin in both Spring Boot URL formats`() {
        val outer = dir.resolve("app.jar")
        Files.write(outer, ByteArray(0))
        val id = listed("jackson-core", DependencyOrigin.NestedJar(outer, "BOOT-INF/lib/jackson-core-2.17.0.jar"))
        val resolver = resolver(readFlat = neverRead, readNested = neverReadNested)

        assertEquals(id, resolver.resolveLocation("jar:nested:$outer/!BOOT-INF/lib/jackson-core-2.17.0.jar!/"))
        assertEquals(id, resolver.resolveLocation("jar:file:$outer!/BOOT-INF/lib/jackson-core-2.17.0.jar!/"))
    }

    @Test
    fun `a directory location is not a dependency, on disk or inside a fat jar`() {
        val classes = Files.createDirectories(dir.resolve("classes"))
        val resolver = resolver(readFlat = neverRead, readNested = neverReadNested)

        assertNull(resolver.resolveLocation(classes.toUri().toString()))
        assertNull(resolver.resolveLocation("jar:nested:${dir.resolve("app.jar")}/!BOOT-INF/classes/!/"))
        assertNull(resolver.resolveLocation("jar:file:${dir.resolve("app.jar")}!/BOOT-INF/classes!/"))
        assertEquals(emptyList(), registry.entries())
    }

    @Test
    fun `an unknown flat jar that is a dependency is registered as discovered by load`() {
        val jar =
            TestJars.write(
                dir.resolve("late-2.0.jar"),
                listOf(pom("org.late", "late", "2.0"), classEntry("org.late.A"), classEntry("org.late.B")),
            )

        val id = resolver().resolve(domain(jar.toUri().toString()))

        val entry = registry.entries().single()
        assertEquals(entry.dependencyId, id)
        assertEquals(DependencyDiscoverySource.LOAD, entry.discoverySource)
        assertEquals(listOf(DependencyIdentity("org.late", "late", "2.0")), entry.identities)
        assertEquals(2, entry.classCount)
        assertEquals(DependencyOrigin.FlatJar(jar.toAbsolutePath()), entry.origin)
    }

    @Test
    fun `an unknown nested jar that is a dependency is streamed from its outer jar and registered as discovered by load`() {
        val nested = TestJars.bytes(listOf(pom("org.inner", "inner", "1.0"), classEntry("org.inner.A")))
        val outer = TestJars.write(dir.resolve("app.war"), listOf("WEB-INF/lib/inner-1.0.jar" to nested))

        val id = resolver().resolveLocation("jar:file:$outer!/WEB-INF/lib/inner-1.0.jar!/")

        val entry = registry.entries().single()
        assertEquals(entry.dependencyId, id)
        assertEquals(DependencyDiscoverySource.LOAD, entry.discoverySource)
        assertEquals("WEB-INF/lib/inner-1.0.jar", entry.location)
        assertEquals(DependencyOrigin.NestedJar(outer, "WEB-INF/lib/inner-1.0.jar"), entry.origin)
    }

    @Test
    fun `an unknown agent jar, Boot outer jar or jar of the adopter's own is not registered`() {
        val agent = TestJars.write(dir.resolve("agent.jar"), listOf(classEntry("x.Agent")), mapOf("Premain-Class" to "x.Agent"))
        val boot =
            TestJars.write(
                dir.resolve("boot.jar"),
                listOf(classEntry("org.springframework.boot.loader.Launcher")),
                mapOf("Spring-Boot-Lib" to "BOOT-INF/lib/"),
            )
        val own = TestJars.write(dir.resolve("own.jar"), listOf(classEntry("com.acme.App"), classEntry("org.other.Lib")))
        val resolver = resolver(includes = listOf("com.acme"))

        assertNull(resolver.resolveLocation(agent.toUri().toString()))
        assertNull(resolver.resolveLocation(boot.toUri().toString()))
        assertNull(resolver.resolveLocation(own.toUri().toString()))
        assertEquals(emptyList(), registry.entries())
    }

    @Test
    fun `an unknown jar whose identity is already registered maps to the existing id and registers nothing`() {
        val id = listed("guava", DependencyOrigin.FlatJar(dir.resolve("lib/guava-33.0.jar")))
        val unpacked =
            TestJars.write(dir.resolve("tmp/guava-33.0-unpacked.jar"), listOf(pom("g", "guava", "33.0"), classEntry("com.google.A")))

        assertEquals(id, resolver().resolveLocation(unpacked.toUri().toString()))
        assertEquals(listOf(id), registry.entries().map { it.dependencyId })
    }

    @Test
    fun `an unsupported scheme is not a dependency`() {
        val resolver = resolver(readFlat = neverRead, readNested = neverReadNested)

        assertNull(resolver.resolveLocation("jrt:/java.sql"))
        assertNull(resolver.resolve(domain("jrt:/java.sql")))
    }

    @Test
    fun `a protection domain that is not a dependency is answered from its own cache on later calls`() {
        var locationReads = 0
        val handler =
            object : java.net.URLStreamHandler() {
                override fun openConnection(u: java.net.URL): java.net.URLConnection = error("never opened")

                override fun toExternalForm(u: java.net.URL): String {
                    locationReads++
                    return super.toExternalForm(u)
                }
            }
        val location = java.net.URL(null, "counted:/lib.jar", handler)
        val domain = ProtectionDomain(CodeSource(location, null as Array<Certificate>?), null)
        val resolver = resolver(readFlat = neverRead, readNested = neverReadNested)

        assertNull(resolver.resolve(domain))
        assertNull(resolver.resolve(domain))

        assertEquals(1, locationReads, "the second call must not reach the location at all")
    }

    @Test
    fun `a location that throws while resolving is a cached negative result`() {
        val jar = TestJars.write(dir.resolve("broken.jar"), listOf(classEntry("org.b.A")))
        var reads = 0
        val resolver =
            resolver(readFlat = {
                reads++
                throw java.io.IOException("unreadable")
            })

        assertNull(resolver.resolve(domain(jar.toUri().toString())))
        assertNull(resolver.resolve(domain(jar.toUri().toString())))
        assertEquals(1, reads, "a failure is cached like any other answer")
    }

    @Test
    fun `one location is read once however many classes and protection domains share it`() {
        val jar = TestJars.write(dir.resolve("shared-1.0.jar"), listOf(pom("g", "shared", "1.0"), classEntry("org.s.A")))
        var reads = 0
        val resolver =
            resolver(readFlat = {
                reads++
                java.util.jar
                    .JarFile(it.toFile())
                    .use(JarContents::of)
            })
        val first = domain(jar.toUri().toString())
        val second = domain(jar.toUri().toString())

        val ids = listOf(resolver.resolve(first), resolver.resolve(first), resolver.resolve(second), resolver.resolve(second))

        assertEquals(1, ids.toSet().size)
        assertEquals(1, reads)
        assertEquals(1, registry.entries().size)
    }

    @Test
    fun `a jar the listing judged not a dependency is not read again, and its INFO line is logged once`() {
        val agent = TestJars.write(dir.resolve("agent.jar"), listOf(classEntry("x.Agent")), mapOf("Premain-Class" to "x.Agent"))
        val own = TestJars.write(dir.resolve("own.jar"), listOf(classEntry("com.acme.App"), classEntry("org.other.Lib")))
        val nestedOwn = TestJars.bytes(listOf(classEntry("com.acme.Nested"), classEntry("org.other.Bundled")))
        val boot =
            TestJars.write(
                dir.resolve("boot.jar"),
                listOf(classEntry("org.springframework.boot.loader.Launcher"), "BOOT-INF/lib/own-nested.jar" to nestedOwn),
                mapOf("Spring-Boot-Lib" to "BOOT-INF/lib/"),
            )
        val includes = listOf("com.acme")
        val classPath = listOf(agent, own, boot).joinToString(java.io.File.pathSeparator)
        var reads = 0
        val records =
            captureLogRecords {
                val listed = StartupClasspathLister(includes, emptyList(), classPath, registry::recordNotADependency).list()
                assertEquals(emptyList(), listed)
                registry.markListingComplete()
                val resolver =
                    resolver(
                        includes = includes,
                        readFlat = {
                            reads++
                            java.util.jar
                                .JarFile(it.toFile())
                                .use(JarContents::of)
                        },
                        readNested = { outer, entry ->
                            reads++
                            error("not read: $outer!/$entry")
                        },
                    )

                assertNull(resolver.resolveLocation(agent.toUri().toString()))
                assertNull(resolver.resolveLocation(own.toUri().toString()))
                assertNull(resolver.resolveLocation(boot.toUri().toString()))
                assertNull(resolver.resolveLocation("jar:nested:$boot/!BOOT-INF/lib/own-nested.jar!/"))
            }

        assertEquals(0, reads, "every jar the listing judged is known without reading it")
        assertEquals(emptyList(), registry.entries())
        assertEquals(2, records.count { it.level == JulLevel.INFO }, "one INFO each for own.jar and the nested jar, from the listing only")
    }

    /** Records what the adopter's-own rule logs, through the JUL backend of `System.Logger`. */
    private fun captureLogRecords(block: () -> Unit): List<LogRecord> {
        val records = mutableListOf<LogRecord>()
        val handler =
            object : Handler() {
                override fun publish(record: LogRecord) {
                    records += record
                }

                override fun flush() {}

                override fun close() {}
            }
        val julLogger = JulLogger.getLogger(StartupClasspathLister::class.java.name)
        val originalLevel = julLogger.level
        julLogger.addHandler(handler)
        julLogger.level = JulLevel.ALL
        try {
            block()
        } finally {
            julLogger.removeHandler(handler)
            julLogger.level = originalLevel
        }
        return records
    }
}
