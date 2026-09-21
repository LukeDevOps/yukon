package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.dependencies.TestJars
import io.github.lukedevops.yukon.instrumentation.ReferencedClassLocator.Found
import java.lang.ref.WeakReference
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pins how [ReferencedClassLocator] reads where a referenced class lives, without loading it. See ADR 0030. */
class ReferencedClassLocatorTest {
    private val tempDir: Path = createTempDirectory("yukon-locator")

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    private fun jarWith(
        name: String,
        vararg classNames: String,
    ): Path = TestJars.write(tempDir.resolve(name), classNames.map { TestJars.classEntry(it) })

    @Test
    fun `a JDK class is dropped`() {
        assertNull(ReferencedClassLocator().locate("java.lang.String", javaClass.classLoader))
    }

    @Test
    fun `a null loader asks the platform loader, which finds the JDK and nothing else`() {
        val locator = ReferencedClassLocator()

        assertNull(locator.locate("java.lang.String", null))
        assertEquals(Found(null), locator.locate("org.acme.Missing", null))
    }

    @Test
    fun `a class in a directory on the classpath is the adopter's own and is dropped`() {
        val dir = tempDir.resolve("classes")
        Files.createDirectories(dir.resolve("org/acme"))
        Files.write(dir.resolve("org/acme/Dir.class"), ByteArray(4))
        val loader = URLClassLoader(arrayOf(dir.toUri().toURL()), ClassLoader.getPlatformClassLoader())

        assertNull(ReferencedClassLocator().locate("org.acme.Dir", loader))
    }

    @Test
    fun `a class in a jar is kept with the jar's code-source location`() {
        val jar = jarWith("lib-1.0.jar", "org.acme.Lib\$Nested")
        val loader = URLClassLoader(arrayOf(jar.toFile().toURI().toURL()), ClassLoader.getPlatformClassLoader())

        assertEquals(Found("jar:file:${jar.toFile().absolutePath}!/"), ReferencedClassLocator().locate("org.acme.Lib\$Nested", loader))
    }

    @Test
    fun `a class no loader can find is kept as absent`() {
        val loader = URLClassLoader(emptyArray(), ClassLoader.getPlatformClassLoader())

        assertEquals(Found(null), ReferencedClassLocator().locate("org.acme.Missing", loader))
    }

    @Test
    fun `a jar resource is cut after its last separator, in the flat form and both nested forms`() {
        assertEquals(
            Found("jar:nested:/o.jar/!BOOT-INF/lib/x.jar!/"),
            ReferencedClassLocator.classify("jar:nested:/o.jar/!BOOT-INF/lib/x.jar!/com/Foo.class", providedByPlatform = false),
        )
        assertEquals(
            Found("jar:file:/x.jar!/"),
            ReferencedClassLocator.classify("jar:file:/x.jar!/com/Foo.class", providedByPlatform = false),
        )
        assertEquals(
            Found("jar:file:/o.jar!/BOOT-INF/lib/x.jar!/"),
            ReferencedClassLocator.classify("jar:file:/o.jar!/BOOT-INF/lib/x.jar!/com/Foo.class", providedByPlatform = false),
            "Spring Boot before 3.2 nests with two separators; the cut is after the last",
        )
    }

    @Test
    fun `a jrt resource, a file resource and anything the platform loader provides are dropped`() {
        assertNull(ReferencedClassLocator.classify("jrt:/java.base/java/lang/String.class", providedByPlatform = true))
        assertNull(ReferencedClassLocator.classify("file:/app/classes/org/acme/Foo.class", providedByPlatform = false))
        assertNull(ReferencedClassLocator.classify("jar:file:/boot/extra.jar!/org/acme/Foo.class", providedByPlatform = true))
    }

    @Test
    fun `a jrt resource is dropped even when the platform loader does not provide it`() {
        // A JDK module defined to the application loader, such as jdk.compiler, answers with a
        // jrt: URL the platform loader cannot see.
        assertNull(ReferencedClassLocator.classify("jrt:/jdk.compiler/com/sun/tools/javac/Main.class", providedByPlatform = false))
    }

    @Test
    fun `a jar resource for a name the platform loader also provides is dropped`() {
        // A loader that answers for java.lang.String out of a jar of its own, as a loader that
        // does not delegate first could.
        val loader =
            object : ClassLoader(getPlatformClassLoader()) {
                override fun getResource(name: String): URL? =
                    if (name ==
                        "java/lang/String.class"
                    ) {
                        URL("jar:file:/shadow.jar!/$name")
                    } else {
                        null
                    }
            }

        assertNull(ReferencedClassLocator().locate("java.lang.String", loader))
        assertEquals(Found(null), ReferencedClassLocator().locate("org.acme.Missing", loader))
    }

    @Test
    fun `a loader whose resource lookup throws is read as absent, and the exception never escapes`() {
        val loader =
            object : ClassLoader(getPlatformClassLoader()) {
                override fun getResource(name: String): URL? = throw IllegalStateException("broken loader")
            }

        assertEquals(Found(null), ReferencedClassLocator().locate("org.acme.Anything", loader))
    }

    @Test
    fun `any other scheme is kept with the whole URL, and no URL at all is absent`() {
        assertEquals(Found("http://repo/org/acme/Foo.class"), ReferencedClassLocator.classify("http://repo/org/acme/Foo.class", false))
        assertEquals(Found(null), ReferencedClassLocator.classify(null, false))
    }

    @Test
    fun `each name is looked up once per loader`() {
        val first = CountingLoader()
        val second = CountingLoader()
        val locator = ReferencedClassLocator()

        repeat(3) { locator.locate("org.acme.Missing", first) }
        locator.locate("org.acme.Missing", second)

        assertEquals(1, first.lookups)
        assertEquals(1, second.lookups)
    }

    @Test
    fun `the cache does not keep a loader alive`() {
        val locator = ReferencedClassLocator()
        val reference = lookUpThroughThrowawayLoader(locator)

        repeat(50) {
            if (reference.get() == null) return@repeat
            System.gc()
            Thread.sleep(20)
        }

        assertNull(reference.get(), "the locator's cache must not pin a loader nothing else holds")
    }

    @Test
    fun `looking a class up never loads it`() {
        val jar = jarWith("never-loaded.jar", "org.acme.NeverLoaded")
        val loader = FindLoadedProbe(arrayOf(jar.toFile().toURI().toURL()))

        ReferencedClassLocator().locate("org.acme.NeverLoaded", loader)

        assertTrue(!loader.isLoaded("org.acme.NeverLoaded"))
    }

    private fun lookUpThroughThrowawayLoader(locator: ReferencedClassLocator): WeakReference<ClassLoader> {
        val loader = CountingLoader()
        locator.locate("org.acme.Missing", loader)
        return WeakReference(loader)
    }

    private class CountingLoader : ClassLoader(getPlatformClassLoader()) {
        var lookups = 0

        override fun getResource(name: String): URL? {
            lookups++
            return super.getResource(name)
        }
    }

    private class FindLoadedProbe(
        urls: Array<URL>,
    ) : URLClassLoader(urls, getPlatformClassLoader()) {
        fun isLoaded(name: String): Boolean = findLoadedClass(name) != null
    }
}
