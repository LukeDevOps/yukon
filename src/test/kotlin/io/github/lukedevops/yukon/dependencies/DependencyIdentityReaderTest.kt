package io.github.lukedevops.yukon.dependencies

import io.github.lukedevops.yukon.dependencies.TestJars.classEntry
import io.github.lukedevops.yukon.dependencies.TestJars.pom
import io.github.lukedevops.yukon.export.DependencyIdentity
import io.github.lukedevops.yukon.export.DependencyIdentitySource
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals

class DependencyIdentityReaderTest {
    @TempDir
    lateinit var dir: Path

    private fun identifyFile(
        fileName: String,
        entries: List<Pair<String, ByteArray>>,
        manifest: Map<String, String>? = null,
    ): JarIdentity {
        val path = TestJars.write(dir.resolve(fileName), entries, manifest)
        return JarFile(path.toFile()).use { DependencyIdentityReader.identify(JarContents.of(it), fileName) }
    }

    private fun identifyStream(
        fileName: String,
        entries: List<Pair<String, ByteArray>>,
        manifest: Map<String, String>? = null,
    ): JarIdentity {
        val contents = ZipInputStream(ByteArrayInputStream(TestJars.bytes(entries, manifest))).use(JarContents::of)
        return DependencyIdentityReader.identify(contents, fileName)
    }

    @Test
    fun `a single pom properties gives one identity with its group, artifact and version`() {
        val identity = identifyFile("whatever.jar", listOf(pom("org.apache.commons", "commons-text", "1.10.0")))

        assertEquals(DependencyIdentitySource.POM_PROPERTIES, identity.identitySource)
        assertEquals(listOf(DependencyIdentity("org.apache.commons", "commons-text", "1.10.0")), identity.identities)
    }

    @Test
    fun `a shaded jar with two poms gives one identity per pom, sorted by group then artifact`() {
        val identity =
            identifyFile(
                "shaded.jar",
                listOf(pom("org.zeta", "zlib", "2"), pom("com.alpha", "beta", "1"), pom("com.alpha", "alpha", "1")),
            )

        assertEquals(DependencyIdentitySource.POM_PROPERTIES, identity.identitySource)
        assertEquals(listOf("alpha", "beta", "zlib"), identity.identities.map { it.artifactId })
    }

    @Test
    fun `a pom with no artifactId is ignored and identity falls through to the filename`() {
        val identity = identifyFile("lib-1.0.jar", listOf(pom("com.acme", null, "1.0")))

        assertEquals(DependencyIdentitySource.FILENAME, identity.identitySource)
        assertEquals(listOf(DependencyIdentity(null, "lib", "1.0")), identity.identities)
    }

    @Test
    fun `the manifest's Implementation-Title is never the artifact, and the filename's version wins over Implementation-Version`() {
        val identity =
            identifyFile(
                "spring-boot-webmvc-4.1.1.jar",
                emptyList(),
                manifest = mapOf("Implementation-Title" to "Spring Boot Web MVC", "Implementation-Version" to "9.9"),
            )

        assertEquals(DependencyIdentitySource.FILENAME, identity.identitySource)
        assertEquals(listOf(DependencyIdentity(null, "spring-boot-webmvc", "4.1.1")), identity.identities)
    }

    @Test
    fun `a filename with no version takes the manifest's Implementation-Version`() {
        val identity =
            identifyFile("acme.jar", emptyList(), manifest = mapOf("Implementation-Title" to "Acme", "Implementation-Version" to "2.5"))

        assertEquals(DependencyIdentitySource.FILENAME, identity.identitySource)
        assertEquals(listOf(DependencyIdentity(null, "acme", "2.5")), identity.identities)
    }

    @Test
    fun `a filename with no version and no Implementation-Version has a null version`() {
        val identity = identifyFile("acme.jar", emptyList(), manifest = mapOf("Implementation-Title" to "Acme"))

        assertEquals(listOf(DependencyIdentity(null, "acme", null)), identity.identities)
    }

    @Test
    fun `with no pom the filename is split into artifact and version`() {
        val cases =
            mapOf(
                "commons-text-1.10.0.jar" to DependencyIdentity(null, "commons-text", "1.10.0"),
                "guava-33.0.0-jre.jar" to DependencyIdentity(null, "guava", "33.0.0-jre"),
                "kotlin-stdlib-2.3.21.jar" to DependencyIdentity(null, "kotlin-stdlib", "2.3.21"),
                "foo.jar" to DependencyIdentity(null, "foo", null),
                "my-lib.jar" to DependencyIdentity(null, "my-lib", null),
                "legacy-2.1.ZIP" to DependencyIdentity(null, "legacy", "2.1"),
                "annotations-13.0.jar" to DependencyIdentity(null, "annotations", "13.0"),
                "hibernate-core-6.4.0.Final.jar" to DependencyIdentity(null, "hibernate-core", "6.4.0.Final"),
                "foo-1.0-SNAPSHOT.jar" to DependencyIdentity(null, "foo", "1.0-SNAPSHOT"),
                "foo-1-SNAPSHOT.jar" to DependencyIdentity(null, "foo", "1-SNAPSHOT"),
                "yukon-1.0-SNAPSHOT-plain.jar" to DependencyIdentity(null, "yukon", "1.0-SNAPSHOT-plain"),
                "kotlin-stdlib-jdk8-1.9.0.jar" to DependencyIdentity(null, "kotlin-stdlib-jdk8", "1.9.0"),
            )
        for ((fileName, expected) in cases) {
            val identity = identifyFile(fileName, emptyList(), manifest = mapOf("Created-By" to "test"))
            assertEquals(DependencyIdentitySource.FILENAME, identity.identitySource, fileName)
            assertEquals(listOf(expected), identity.identities, fileName)
        }
    }

    @Test
    fun `a trailing number with no dot is part of the artifact, so a module per framework major version keeps its own identity`() {
        val cases =
            mapOf(
                "endpoints-ktor-2.jar" to DependencyIdentity(null, "endpoints-ktor-2", null),
                "endpoints-ktor-3.jar" to DependencyIdentity(null, "endpoints-ktor-3", null),
                "endpoints-ktor-2-1.0.jar" to DependencyIdentity(null, "endpoints-ktor-2", "1.0"),
                "scala-library-2-bridge.jar" to DependencyIdentity(null, "scala-library-2-bridge", null),
            )
        for ((fileName, expected) in cases) {
            val identity = identifyFile(fileName, emptyList(), manifest = mapOf("Created-By" to "test"))
            assertEquals(listOf(expected), identity.identities, fileName)
        }
    }

    @Test
    fun `a jar read from a stream gives the same identity as one read from disk, manifest included`() {
        val entries = listOf(classEntry("com.acme.A"))
        val manifest = mapOf("Implementation-Version" to "3")

        val fromStream = identifyStream("s.jar", entries, manifest)
        assertEquals(identifyFile("s.jar", entries, manifest), fromStream)
        assertEquals("3", fromStream.identities.single().version, "the streamed manifest supplies the version")
        assertEquals(
            listOf(DependencyIdentity("g", "a", "1")),
            identifyStream("s.jar", listOf(pom("g", "a", "1"))).identities,
        )
    }

    @Test
    fun `class count leaves out module-info, package-info and versioned variants, and counts a class once`() {
        val entries =
            listOf(
                classEntry("com.acme.A"),
                classEntry("com.acme.B"),
                classEntry("com.acme.B\$Inner"),
                "module-info.class" to ByteArray(1),
                "com/acme/package-info.class" to ByteArray(1),
                "META-INF/versions/11/com/acme/A.class" to ByteArray(1),
                "com/acme/readme.txt" to ByteArray(1),
            )

        assertEquals(3, identifyFile("c.jar", entries).classCount)
        assertEquals(3, identifyStream("c.jar", entries).classCount)
    }
}
