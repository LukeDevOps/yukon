package io.github.lukedevops.yukon.dependencies

import io.github.lukedevops.yukon.dependencies.TestJars.classEntry
import io.github.lukedevops.yukon.dependencies.TestJars.pom
import io.github.lukedevops.yukon.export.DependencyIdentity
import io.github.lukedevops.yukon.export.DependencyIdentitySource
import io.github.lukedevops.yukon.registry.DependencyOrigin
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.util.logging.Level as JulLevel
import java.util.logging.Logger as JulLogger

class StartupClasspathListerTest {
    @TempDir
    lateinit var dir: Path

    private fun classPath(vararg paths: Path): String = paths.joinToString(File.pathSeparator) { it.toString() }

    private fun lister(
        classPath: String,
        includes: List<String> = emptyList(),
        excludes: List<String> = emptyList(),
    ) = StartupClasspathLister(includes, excludes, classPath)

    private fun artifacts(listed: List<ListedDependency>): List<String> = listed.flatMap { d -> d.identities.map { it.artifactId } }

    /** Mirrors `LoadedClassSweepTest`'s helper: records what a `System.Logger` emits through its JUL backend. */
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

    @Test
    fun `with includes unset every jar is a dependency and a directory never is`() {
        val classesDir = Files.createDirectories(dir.resolve("classes/com/acme"))
        Files.write(classesDir.resolve("App.class"), ByteArray(4))
        val one = TestJars.write(dir.resolve("one-1.0.jar"), listOf(pom("g", "one", "1.0"), classEntry("org.one.A")))
        val two = TestJars.write(dir.resolve("two-2.0.jar"), listOf(classEntry("org.two.B")))

        val listed = lister(classPath(dir.resolve("classes"), one, two)).list()

        assertEquals(listOf("one", "two"), artifacts(listed))
        assertEquals(one.toAbsolutePath().toString(), listed[0].location)
        assertEquals(DependencyOrigin.FlatJar(one.toAbsolutePath()), listed[0].origin)
        assertEquals(1, listed[0].classCount)
        assertEquals(DependencyIdentitySource.FILENAME, listed[1].identitySource)
    }

    @Test
    fun `a jar whose manifest names Premain-Class or Launcher-Agent-Class is never a dependency`() {
        val agent = TestJars.write(dir.resolve("agent.jar"), listOf(classEntry("x.Agent")), mapOf("Premain-Class" to "x.Agent"))
        val launcher =
            TestJars.write(dir.resolve("launcher.jar"), listOf(classEntry("y.Agent")), mapOf("Launcher-Agent-Class" to "y.Agent"))
        val lib = TestJars.write(dir.resolve("lib-1.0.jar"), listOf(classEntry("z.Lib")))

        assertEquals(listOf("lib"), artifacts(lister(classPath(agent, launcher, lib)).list()))
    }

    @Test
    fun `a jar holding the agent's own package is never a dependency, flat or nested, whatever its manifest says`() {
        val plainAgent = TestJars.write(dir.resolve("yukon-1.0-SNAPSHOT-plain.jar"), listOf(classEntry("io.github.lukedevops.yukon.Agent")))
        val fat =
            TestJars.write(
                dir.resolve("app.jar"),
                listOf(
                    "BOOT-INF/lib/yukon-1.0.jar" to nestedJar(classEntry("io.github.lukedevops.yukon.Agent")),
                    "BOOT-INF/lib/lib-1.0.jar" to nestedJar(classEntry("org.lib.L")),
                ),
                mapOf("Spring-Boot-Lib" to "BOOT-INF/lib/"),
            )
        val judged = mutableListOf<DependencyOrigin>()

        val listed =
            StartupClasspathLister(
                emptyList(),
                emptyList(),
                classPath(plainAgent, fat),
                onNotADependency = { judged += it },
            ).list()

        assertEquals(listOf("lib"), artifacts(listed))
        assertEquals(
            2,
            judged.count { it.toString().contains("yukon") },
            "both agent jars are recorded as judged, so the sweep never reads them",
        )
    }

    @Test
    fun `an agent jar's Class-Path is still followed`() {
        TestJars.write(dir.resolve("libs/dep-1.0.jar"), listOf(classEntry("d.Dep")))
        val agent =
            TestJars.write(dir.resolve("agent.jar"), emptyList(), mapOf("Premain-Class" to "x.Agent", "Class-Path" to "libs/dep-1.0.jar"))

        assertEquals(listOf("dep"), artifacts(lister(classPath(agent)).list()))
    }

    @Test
    fun `with includes set a jar holding an in-scope class is the adopter's own and one with only out-of-scope classes is listed`() {
        val own = TestJars.write(dir.resolve("app.jar"), listOf(classEntry("com.acme.App")))
        val lib = TestJars.write(dir.resolve("lib-1.0.jar"), listOf(classEntry("org.lib.Lib")))

        assertEquals(listOf("lib"), artifacts(lister(classPath(own, lib), includes = listOf("com.acme")).list()))
    }

    @Test
    fun `a jar whose only in-scope classes are excluded is a dependency`() {
        val lib = TestJars.write(dir.resolve("lib-1.0.jar"), listOf(classEntry("com.acme.generated.X")))

        val listed = lister(classPath(lib), includes = listOf("com.acme"), excludes = listOf("com.acme.generated")).list()

        assertEquals(listOf("lib"), artifacts(listed))
    }

    @Test
    fun `a mixed jar is the adopter's own and logs one INFO naming how many classes fall outside the include rules`() {
        val shaded =
            TestJars.write(
                dir.resolve("shaded.jar"),
                listOf(classEntry("com.acme.App"), classEntry("org.lib.A"), classEntry("org.lib.B")),
            )

        lateinit var listed: List<ListedDependency>
        val records = captureLogRecords { listed = lister(classPath(shaded), includes = listOf("com.acme")).list() }

        assertTrue(listed.isEmpty())
        val info = records.filter { it.level == JulLevel.INFO }
        assertEquals(1, info.size, "exactly one INFO line for the mixed jar")
        assertTrue(info.single().message.contains(shaded.toString()), info.single().message)
        assertTrue(info.single().message.contains("its 2 classes outside the include rules"), info.single().message)
    }

    @Test
    fun `a jar wholly in scope logs nothing`() {
        val own = TestJars.write(dir.resolve("app.jar"), listOf(classEntry("com.acme.App")))

        val records = captureLogRecords { lister(classPath(own), includes = listOf("com.acme")).list() }

        assertTrue(records.none { it.level == JulLevel.INFO })
    }

    private fun nestedJar(vararg entries: Pair<String, ByteArray>): ByteArray = TestJars.bytes(entries.toList())

    @Test
    fun `a fat jar lists every nested jar under BOOT-INF lib, not the outer jar, ignoring the classpath index`() {
        val jackson = nestedJar(pom("com.fasterxml.jackson.core", "jackson-core", "3.1.5"), classEntry("tools.jackson.core.A"))
        val unindexed = nestedJar(classEntry("org.unindexed.U"))
        val fat =
            TestJars.write(
                dir.resolve("app.jar"),
                listOf(
                    classEntry("org.springframework.boot.loader.launch.JarLauncher"),
                    "BOOT-INF/classes/com/acme/App.class" to ByteArray(4),
                    "BOOT-INF/classpath.idx" to "- \"BOOT-INF/lib/jackson-core-3.1.5.jar\"\n".toByteArray(),
                    "BOOT-INF/lib/jackson-core-3.1.5.jar" to jackson,
                    "BOOT-INF/lib/unindexed-1.0.jar" to unindexed,
                ),
                mapOf(
                    "Spring-Boot-Classpath-Index" to "BOOT-INF/classpath.idx",
                    "Spring-Boot-Lib" to "BOOT-INF/lib/",
                    "Spring-Boot-Classes" to "BOOT-INF/classes/",
                ),
            )

        val listed = lister(classPath(fat)).list()

        assertEquals(listOf("jackson-core", "unindexed"), artifacts(listed))
        val dependency = listed.first()
        assertEquals(listOf(DependencyIdentity("com.fasterxml.jackson.core", "jackson-core", "3.1.5")), dependency.identities)
        assertEquals(DependencyIdentitySource.POM_PROPERTIES, dependency.identitySource)
        assertEquals("BOOT-INF/lib/jackson-core-3.1.5.jar", dependency.location)
        assertEquals(1, dependency.classCount)
        assertEquals(DependencyOrigin.NestedJar(fat.toAbsolutePath(), "BOOT-INF/lib/jackson-core-3.1.5.jar"), dependency.origin)
    }

    @Test
    fun `a fat jar lists every file under BOOT-INF lib at any depth and with any extension, and nothing outside it`() {
        val fat =
            TestJars.write(
                dir.resolve("app.jar"),
                listOf(
                    "BOOT-INF/lib/a-1.0.jar" to nestedJar(classEntry("org.a.A")),
                    "BOOT-INF/lib/b-2.0.jar" to nestedJar(classEntry("org.b.B")),
                    "BOOT-INF/lib/deeper/c-3.0.jar" to nestedJar(classEntry("org.c.C")),
                    "BOOT-INF/lib/e-5.0.zip" to nestedJar(classEntry("org.e.E")),
                    "BOOT-INF/libs-other/d-4.0.jar" to nestedJar(classEntry("org.d.D")),
                ),
                mapOf("Spring-Boot-Lib" to "BOOT-INF/lib/"),
            )

        val listed = lister(classPath(fat)).list()

        assertEquals(listOf("a", "b", "c", "e"), artifacts(listed))
        assertEquals(listOf("1.0", "2.0", "3.0", "5.0"), listed.map { it.identities.single().version })
    }

    @Test
    fun `an executable war lists every file under WEB-INF lib and WEB-INF lib-provided`() {
        val war =
            TestJars.write(
                dir.resolve("app.war"),
                listOf(
                    "WEB-INF/classes/com/acme/App.class" to ByteArray(4),
                    "WEB-INF/lib/a-1.0.jar" to nestedJar(classEntry("org.a.A")),
                    "WEB-INF/lib-provided/b-2.0.jar" to nestedJar(classEntry("org.b.B")),
                    "BOOT-INF/lib/c-3.0.jar" to nestedJar(classEntry("org.c.C")),
                ),
                mapOf("Spring-Boot-Lib" to "WEB-INF/lib/", "Main-Class" to "org.springframework.boot.loader.launch.WarLauncher"),
            )

        assertEquals(listOf("a", "b"), artifacts(lister(classPath(war)).list()))
    }

    @Test
    fun `a nested jar holding in-scope classes is the adopter's own`() {
        val fat =
            TestJars.write(
                dir.resolve("app.jar"),
                listOf(
                    "BOOT-INF/lib/acme-module-1.0.jar" to nestedJar(classEntry("com.acme.module.M")),
                    "BOOT-INF/lib/lib-1.0.jar" to nestedJar(classEntry("org.lib.L")),
                ),
                mapOf("Spring-Boot-Lib" to "BOOT-INF/lib/"),
            )

        assertEquals(listOf("lib"), artifacts(lister(classPath(fat), includes = listOf("com.acme")).list()))
    }

    @Test
    fun `a nested jar that cannot be read is skipped with a WARNING naming it and its siblings are still listed`() {
        // A complete local file header whose name length (100) runs past the end of the data.
        // ZipInputStream reads a stream cut inside the header as empty, but one cut inside the
        // name it throws on.
        val truncated = byteArrayOf(0x50, 0x4b, 0x03, 0x04) + ByteArray(22) + byteArrayOf(100, 0, 0, 0)
        val fat =
            TestJars.write(
                dir.resolve("app.jar"),
                listOf(
                    "BOOT-INF/lib/broken-1.0.jar" to truncated,
                    "BOOT-INF/lib/fine-1.0.jar" to nestedJar(classEntry("org.fine.F")),
                ),
                mapOf("Spring-Boot-Lib" to "BOOT-INF/lib/"),
            )

        lateinit var listed: List<ListedDependency>
        val records = captureLogRecords { listed = lister(classPath(fat)).list() }

        assertEquals(listOf("fine"), artifacts(listed))
        val warnings = records.filter { it.level == JulLevel.WARNING }
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().message.contains("BOOT-INF/lib/broken-1.0.jar"), warnings.single().message)
    }

    @Test
    fun `a nested jar whose bytes are not a zip is skipped with a WARNING naming it, not listed with no classes`() {
        val fat =
            TestJars.write(
                dir.resolve("app.jar"),
                listOf(
                    "BOOT-INF/lib/garbage-1.0.jar" to "this is not a zip file".toByteArray(),
                    "BOOT-INF/lib/fine-1.0.jar" to nestedJar(classEntry("org.fine.F")),
                ),
                mapOf("Spring-Boot-Lib" to "BOOT-INF/lib/"),
            )

        lateinit var listed: List<ListedDependency>
        val records = captureLogRecords { listed = lister(classPath(fat)).list() }

        assertEquals(listOf("fine"), artifacts(listed))
        val warnings = records.filter { it.level == JulLevel.WARNING }
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().message.contains("BOOT-INF/lib/garbage-1.0.jar"), warnings.single().message)
    }

    @Test
    fun `a fat jar whose classpath index is present but empty still lists its nested jars`() {
        val fat =
            TestJars.write(
                dir.resolve("app.jar"),
                listOf(
                    "BOOT-INF/classpath.idx" to ByteArray(0),
                    "BOOT-INF/lib/a-1.0.jar" to nestedJar(classEntry("org.a.A")),
                ),
                mapOf("Spring-Boot-Classpath-Index" to "BOOT-INF/classpath.idx", "Spring-Boot-Lib" to "BOOT-INF/lib/"),
            )

        assertEquals(listOf("a"), artifacts(lister(classPath(fat)).list()), "a packaged launch never reads the index")
    }

    @Test
    fun `three jars sharing one Implementation-Title stay three dependencies, named by their filenames`() {
        val title = mapOf("Implementation-Title" to "Apache Tomcat", "Implementation-Version" to "11.0.0")
        val fat =
            TestJars.write(
                dir.resolve("app.jar"),
                listOf(
                    "BOOT-INF/lib/tomcat-embed-core-11.0.0.jar" to TestJars.bytes(listOf(classEntry("org.apache.catalina.A")), title),
                    "BOOT-INF/lib/tomcat-embed-el-11.0.0.jar" to TestJars.bytes(listOf(classEntry("org.apache.el.B")), title),
                    "BOOT-INF/lib/tomcat-embed-websocket-11.0.0.jar" to TestJars.bytes(listOf(classEntry("org.apache.tomcat.C")), title),
                ),
                mapOf("Spring-Boot-Lib" to "BOOT-INF/lib/"),
            )

        val listed = lister(classPath(fat)).list()

        assertEquals(listOf("tomcat-embed-core", "tomcat-embed-el", "tomcat-embed-websocket"), artifacts(listed))
        assertTrue(listed.all { it.identitySource == DependencyIdentitySource.FILENAME })
    }

    @Test
    fun `a classpath file with no jar extension is read as a jar, as the system loader reads it`() {
        val lib = TestJars.write(dir.resolve("lib-1.0.bin"), listOf(pom("g", "lib", "1.0"), classEntry("org.lib.L")))

        assertEquals(listOf("lib"), artifacts(lister(classPath(lib)).list()))
    }

    @Test
    fun `Class-Path entries are followed relative to the jar's directory, and a cycle terminates`() {
        val libs = Files.createDirectories(dir.resolve("libs"))
        TestJars.write(libs.resolve("b-1.0.jar"), listOf(classEntry("org.b.B")), mapOf("Class-Path" to "../a-1.0.jar"))
        TestJars.write(libs.resolve("with space-1.0.jar"), listOf(classEntry("org.s.S")))
        val a =
            TestJars.write(
                dir.resolve("a-1.0.jar"),
                listOf(classEntry("org.a.A")),
                mapOf("Class-Path" to "libs/b-1.0.jar libs/with%20space-1.0.jar libs/missing.jar"),
            )

        val listed = lister(classPath(a)).list()

        assertEquals(listOf("a", "b", "with space"), artifacts(listed))
        assertEquals(libs.resolve("b-1.0.jar").toAbsolutePath().toString(), listed[1].location)
    }

    @Test
    fun `a Class-Path jar is searched straight after the jar naming it, before the next classpath entry`() {
        TestJars.write(dir.resolve("libs/shared-1.0.jar"), listOf(pom("g", "shared", "1.0"), classEntry("org.s.S")))
        val first = TestJars.write(dir.resolve("first.jar"), listOf(classEntry("org.f.F")), mapOf("Class-Path" to "libs/shared-1.0.jar"))
        val second = TestJars.write(dir.resolve("other/shared-2.0.jar"), listOf(pom("g", "shared", "2.0"), classEntry("org.s.S")))

        val listed = lister(classPath(first, second)).list()

        assertEquals(listOf("first", "shared"), artifacts(listed))
        assertEquals("1.0", listed[1].identities.single().version)
    }

    @Test
    fun `a missing classpath entry is skipped`() {
        val lib = TestJars.write(dir.resolve("lib-1.0.jar"), listOf(classEntry("org.lib.L")))

        assertEquals(listOf("lib"), artifacts(lister(classPath(dir.resolve("nope.jar"), dir.resolve("nodir"), lib)).list()))
    }

    @Test
    fun `a corrupt jar is skipped with one WARNING naming it and the rest are still listed`() {
        val corrupt = dir.resolve("corrupt-1.0.jar")
        Files.write(corrupt, "this is not a zip file".toByteArray())
        val lib = TestJars.write(dir.resolve("lib-1.0.jar"), listOf(classEntry("org.lib.L")))

        lateinit var listed: List<ListedDependency>
        val records = captureLogRecords { listed = lister(classPath(corrupt, lib)).list() }

        assertEquals(listOf("lib"), artifacts(listed))
        val warnings = records.filter { it.level == JulLevel.WARNING }
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().message.contains(corrupt.toString()), warnings.single().message)
    }

    @Test
    fun `a flat jar with no entries at all is skipped with one WARNING naming it`() {
        val empty = TestJars.write(dir.resolve("empty-1.0.jar"), emptyList())
        val lib = TestJars.write(dir.resolve("lib-1.0.jar"), listOf(classEntry("org.lib.L")))

        lateinit var listed: List<ListedDependency>
        val records = captureLogRecords { listed = lister(classPath(empty, lib)).list() }

        assertEquals(listOf("lib"), artifacts(listed))
        val warnings = records.filter { it.level == JulLevel.WARNING }
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().message.contains(empty.toString()), warnings.single().message)
    }

    @Test
    fun `two jars with one identity are one dependency, and the first found keeps its location and class count`() {
        val first = TestJars.write(dir.resolve("a/lib-1.0.jar"), listOf(pom("g", "lib", "1.0"), classEntry("org.lib.A")))
        val second =
            TestJars.write(
                dir.resolve("b/lib-2.0.jar"),
                listOf(pom("g", "lib", "2.0"), classEntry("org.lib.A"), classEntry("org.lib.B")),
            )

        val listed = lister(classPath(first, second)).list()

        assertEquals(1, listed.size)
        assertEquals(first.toAbsolutePath().toString(), listed.single().location)
        assertEquals(1, listed.single().classCount)
    }

    @Test
    fun `class names are kept only when asked for, including a fat jar's nested dependencies`() {
        val flat = TestJars.write(dir.resolve("flat-1.0.jar"), listOf(classEntry("org.flat.F")))
        val nested = nestedJar(pom("g", "nested", "1.0"), classEntry("org.nested.N"), classEntry("org.nested.M"))
        val fat =
            TestJars.write(
                dir.resolve("app.jar"),
                listOf(
                    "BOOT-INF/classes/com/acme/App.class" to ByteArray(4),
                    "BOOT-INF/lib/nested-1.0.jar" to nested,
                ),
                mapOf("Spring-Boot-Lib" to "BOOT-INF/lib/"),
            )

        val kept = StartupClasspathLister(emptyList(), emptyList(), classPath(flat, fat), keepClassNames = true).list()
        val dropped = lister(classPath(flat, fat)).list()

        assertEquals(listOf(setOf("org.flat.F"), setOf("org.nested.N", "org.nested.M")), kept.map { it.classNames })
        assertTrue(dropped.all { it.classNames.isEmpty() })
    }

    @Test
    fun `a second jar with an identity already listed adds its class names to the first`() {
        val one = TestJars.write(dir.resolve("a/lib-1.0.jar"), listOf(pom("g", "lib", "1.0"), classEntry("org.lib.Old")))
        val two = TestJars.write(dir.resolve("b/lib-2.0.jar"), listOf(pom("g", "lib", "2.0"), classEntry("org.lib.New")))

        val listed = StartupClasspathLister(emptyList(), emptyList(), classPath(one, two), keepClassNames = true).list()

        assertEquals(one.toAbsolutePath().toString(), listed.single().location)
        assertEquals(setOf("org.lib.Old", "org.lib.New"), listed.single().classNames)
    }

    @Test
    fun `a class name held by two jars stays with the first in search order, even when a later jar merges into an earlier identity`() {
        val first = TestJars.write(dir.resolve("a/lib-1.0.jar"), listOf(pom("g", "lib", "1.0"), classEntry("org.lib.Old")))
        val other = TestJars.write(dir.resolve("other-1.0.jar"), listOf(pom("g", "other", "1.0"), classEntry("org.shared.Both")))
        val second = TestJars.write(dir.resolve("b/lib-2.0.jar"), listOf(pom("g", "lib", "2.0"), classEntry("org.shared.Both")))

        val listed = StartupClasspathLister(emptyList(), emptyList(), classPath(first, other, second), keepClassNames = true).list()

        assertEquals(setOf("org.lib.Old"), listed.single { it.identities.single().artifactId == "lib" }.classNames)
        assertEquals(setOf("org.shared.Both"), listed.single { it.identities.single().artifactId == "other" }.classNames)
    }
}
