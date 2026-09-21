package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.Agent
import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.dependencies.DependencyResolver
import io.github.lukedevops.yukon.dependencies.JarClassifier
import io.github.lukedevops.yukon.dependencies.StartupClasspathLister
import io.github.lukedevops.yukon.dependencies.TestJars
import io.github.lukedevops.yukon.export.ExternalClass
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.registry.DependencyRegistry
import io.github.lukedevops.yukon.registry.ExternalClassRegistry
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives references end to end through the real pipeline (ADR 0030): an in-scope class compiled
 * against library types, served at run time from a jar this test builds and lists as a dependency,
 * transformed by [YukonInstrumentation], and resolved on the export side through the same
 * [DependencyResolver] the loaded-class count uses.
 */
class ReferenceInstrumentationTest {
    @TempDir
    lateinit var dir: Path

    private val includes = listOf("com.example.target")
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedYukon: YukonInstrumentation? = null

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { installedYukon?.uninstall(ByteBuddyAgent.install(), it) }
    }

    /** Copies the compiled library types into a jar, leaving out [excluded], with a pom so the listing names it. */
    private fun libraryJar(excluded: Set<String>): Path {
        val libraryDir = File("build/classes/java/test/com/example/library")
        val entries =
            libraryDir
                .listFiles()!!
                .filter { it.name.endsWith(".class") && it.name.removeSuffix(".class") !in excluded }
                .map { "com/example/library/${it.name}" to it.readBytes() }
        return TestJars.write(dir.resolve("widget-1.0.jar"), listOf(TestJars.pom("org.acme", "widget", "1.0")) + entries)
    }

    /** A directory holding only the fixture's own class file, so nothing else resolves from a directory. */
    private fun fixtureDirectory(): Path {
        val classes = dir.resolve("classes")
        val target = classes.resolve("com/example/target/WidgetUser.class")
        Files.createDirectories(target.parent)
        Files.copy(File("build/classes/java/test/com/example/target/WidgetUser.class").toPath(), target)
        return classes
    }

    @Test
    fun `a probe names the jar's classes it references, and each maps to that jar's dependency`() {
        val jar = libraryJar(excluded = setOf("Lib\$Helper"))
        val dependencyRegistry = DependencyRegistry()
        Agent.runDependencyListing(StartupClasspathLister(includes, emptyList(), jar.toString())::list, dependencyRegistry)
        val resolver = DependencyResolver(dependencyRegistry, JarClassifier(includes, emptyList()))
        val externalClassRegistry = ExternalClassRegistry(dependencyRegistry::isListingComplete, resolver::resolveLocation)
        val registry = ProbeRegistry()
        val yukon =
            YukonInstrumentation(
                AgentConfig.parse("includePackages=com.example.target"),
                registry,
                externalClassRegistry = externalClassRegistry,
            )
        installedYukon = yukon
        installedTransformer = yukon.install(ByteBuddyAgent.install())

        val loader =
            FixtureClassLoader(
                arrayOf(fixtureDirectory().toUri().toURL(), jar.toUri().toURL()),
                ClassLoader.getPlatformClassLoader(),
            )
        val type = Class.forName("com.example.target.WidgetUser", true, loader)
        type.getMethod("make").invoke(type.getDeclaredConstructor().newInstance())

        val manifest = registry.manifest("test", null, "instance-1")
        val make = manifest.probes.single { it.methodName == "make" && it.kind == ProbeKind.METHOD }
        assertEquals(listOf("com.example.library.Lib\$Widget"), make.referencedClasses, "java.lang.Object, a JDK class, is dropped")
        val missing = manifest.probes.single { it.methodName == "missing" && it.kind == ProbeKind.METHOD }
        assertEquals(listOf("com.example.library.Lib\$Helper"), missing.referencedClasses)
        assertEquals(
            listOf("com.example.library.Lib\$FieldType"),
            manifest.classReferences.single { it.classId == make.classId }.referencedClasses,
            "java.lang.Object as the superclass is dropped too",
        )

        val dependencyId = dependencyRegistry.entries().single { it.identities.single().artifactId == "widget" }.dependencyId
        val external = externalClassRegistry.computeManifestEntries(100).flatMap { it.externalClasses }
        assertEquals(
            setOf(
                ExternalClass("com.example.library.Lib\$Widget", dependencyId),
                ExternalClass("com.example.library.Lib\$FieldType", dependencyId),
                ExternalClass("com.example.library.Lib\$Helper", null, absent = true),
            ),
            external.toSet(),
        )
        assertTrue(external.none { it.className.startsWith("java.") })
    }
}
