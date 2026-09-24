package io.github.lukedevops.yukon.instrumentation.staticscan

import io.github.lukedevops.yukon.Agent
import io.github.lukedevops.yukon.dependencies.StartupClasspathLister
import io.github.lukedevops.yukon.dependencies.TestJars
import io.github.lukedevops.yukon.export.DeclaredClass
import io.github.lukedevops.yukon.export.DeclaredMethod
import io.github.lukedevops.yukon.export.DeltaBatch
import io.github.lukedevops.yukon.export.DependencyDiscoverySource
import io.github.lukedevops.yukon.export.DependencyIdentity
import io.github.lukedevops.yukon.export.DependencyIdentitySource
import io.github.lukedevops.yukon.export.Exporter
import io.github.lukedevops.yukon.export.ExternalClass
import io.github.lukedevops.yukon.export.ProbeManifest
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.export.StaticBaseline
import io.github.lukedevops.yukon.registry.DependencyRegistry
import io.github.lukedevops.yukon.registry.ExternalClassRegistry
import io.github.lukedevops.yukon.registry.ProbeRegistry
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins how the static baseline's references are filtered and mapped without a defining loader,
 * and how the publisher waits for the dependency listing first. See ADR 0030.
 */
class BaselineReferenceFilterTest {
    @TempDir
    lateinit var dir: Path

    private val resource = ResourceAttributes("checkout", "1.0.0", "instance-1", "test", "run-1")

    private class RecordingExporter : Exporter {
        val baselines = CopyOnWriteArrayList<StaticBaseline>()

        override fun exportDeltaBatch(batch: DeltaBatch) {}

        override fun exportManifest(manifest: ProbeManifest) {}

        override fun exportStaticBaseline(baseline: StaticBaseline) {
            baselines += baseline
        }
    }

    private fun indexedRegistry(): DependencyRegistry = DependencyRegistry(indexClassNames = true)

    private fun DependencyRegistry.addListed(
        artifact: String,
        vararg classNames: String,
    ): Int =
        register(
            listOf(DependencyIdentity("g", artifact, "1")),
            DependencyIdentitySource.POM_PROPERTIES,
            "/libs/$artifact.jar",
            DependencyDiscoverySource.STARTUP_CLASSPATH,
            classNames = classNames.toList(),
        )

    private fun externalRegistry(dependencies: DependencyRegistry) = ExternalClassRegistry(dependencies::isListingComplete, { null })

    private fun sent(registry: ExternalClassRegistry): List<ExternalClass> =
        registry.computeManifestEntries(100).flatMap { it.externalClasses }

    private val referencing =
        listOf(
            "java.lang.String",
            "com.acme.OwnOutOfScope",
            "com.acme.boot.NestedOwn",
            "org.lib.Indexed",
            "org.flat.InDependency",
            "org.flat.AdoptersJar",
            "org.gone.Missing",
        )

    private fun scanReferencingEverything() =
        StaticScanResult(
            declaredClasses =
                listOf(
                    DeclaredClass(
                        "com.acme.App",
                        listOf(DeclaredMethod("run", "()V", referencedClasses = referencing)),
                        referencedClasses = referencing,
                    ),
                ),
            staticallyUnsafeClasses = emptyList(),
            unreadableClasses = emptyList(),
            ownClassNames = setOf("com.acme.App", "com.acme.OwnOutOfScope", "com.acme.boot.NestedOwn"),
            flatJarClassNames = setOf("org.flat.InDependency", "org.flat.AdoptersJar"),
        )

    @Test
    fun `drops JDK and adopter's-own names, and records indexed names as their dependency and leaves the rest unmapped, never absent`() {
        val dependencies = indexedRegistry()
        val lib = dependencies.addListed("lib", "org.lib.Indexed")
        val flat = dependencies.addListed("flat", "org.flat.InDependency")
        dependencies.markListingComplete()
        val external = externalRegistry(dependencies)

        val filtered = BaselineReferenceFilter(dependencies, external).filter(scanReferencingEverything())

        val kept = listOf("org.lib.Indexed", "org.flat.InDependency", "org.gone.Missing")
        val declared = filtered.declaredClasses.single()
        assertEquals(kept, declared.referencedClasses)
        assertEquals(kept, declared.methods.single().referencedClasses)
        assertEquals(
            listOf(
                ExternalClass("org.lib.Indexed", lib),
                ExternalClass("org.flat.InDependency", flat),
            ),
            sent(external),
        )
        assertEquals(0, dependencies.classIndexSize, "the index is released once the baseline has read it")
    }

    @Test
    fun `a name the baseline cannot map is left for the transform path, which maps it when it finds the jar`() {
        val dependencies = indexedRegistry()
        dependencies.addListed("lib", "org.lib.Indexed")
        val plugin = dependencies.addListed("plugin")
        dependencies.markListingComplete()
        val external =
            ExternalClassRegistry(dependencies::isListingComplete, { location ->
                if (location == "jar:file:/plugins/plugin.jar!/") plugin else null
            })

        BaselineReferenceFilter(dependencies, external).filter(scanReferencingEverything())
        external.record("org.gone.Missing", "jar:file:/plugins/plugin.jar!/")

        assertEquals(
            ExternalClass("org.gone.Missing", plugin),
            sent(external).single { it.className == "org.gone.Missing" },
        )
    }

    @Test
    fun `a name the scan saw in its own roots is dropped even when a dependency also holds it`() {
        val dependencies = indexedRegistry()
        dependencies.addListed("shadow", "com.acme.OwnOutOfScope", "com.acme.boot.NestedOwn")
        dependencies.markListingComplete()
        val external = externalRegistry(dependencies)

        val filtered = BaselineReferenceFilter(dependencies, external).filter(scanReferencingEverything())

        assertTrue(
            filtered.declaredClasses
                .single()
                .referencedClasses
                .none { it.startsWith("com.acme.") },
        )
        assertTrue(sent(external).none { it.className.startsWith("com.acme.") })
    }

    @Test
    fun `a name the transform path recorded first keeps that recording`() {
        val dependencies = indexedRegistry()
        dependencies.addListed("lib", "org.lib.Indexed")
        dependencies.markListingComplete()
        val external = ExternalClassRegistry(dependencies::isListingComplete, mapOf("jar:file:/other.jar!/" to 42)::get)
        external.record("org.lib.Indexed", "jar:file:/other.jar!/")

        BaselineReferenceFilter(dependencies, external).filter(scanReferencingEverything())

        assertEquals(ExternalClass("org.lib.Indexed", 42), sent(external).single { it.className == "org.lib.Indexed" })
    }

    @Test
    fun `the publisher sends references filtered before chunking, so the weights count only what is sent`() {
        val dependencies = indexedRegistry()
        dependencies.addListed("lib", "org.lib.Indexed")
        dependencies.markListingComplete()
        val exporter = RecordingExporter()
        val second = DeclaredClass("com.acme.Second", listOf(DeclaredMethod("m", "()V")))
        val scan = scanReferencingEverything().let { it.copy(declaredClasses = it.declaredClasses + second) }
        // "App" weighs 1 method + 1 class record + 3 kept references on the method + 3 on the
        // class = 8, and "Second" weighs 2. Unfiltered, "App" alone would weigh 16.
        val publisher =
            StaticBaselinePublisher(
                { scan },
                exporter,
                ProbeRegistry(),
                StaticBaselineMismatchDetector(),
                maxEntriesPerChunk = 10,
                filterReferences = BaselineReferenceFilter(dependencies, externalRegistry(dependencies))::filter,
            )

        publisher.run(resource)

        assertEquals(
            listOf(listOf("com.acme.App", "com.acme.Second")),
            exporter.baselines.map { b -> b.declaredClasses.map { it.className } },
        )
        assertTrue(exporter.baselines.all { it.externalClasses.isEmpty() }, "mappings travel on the manifest, never the baseline")
    }

    @Test
    fun `the publisher waits for the listing before it filters and sends`() {
        val dependencies = indexedRegistry()
        val external = externalRegistry(dependencies)
        val exporter = RecordingExporter()
        val publisher =
            StaticBaselinePublisher(
                ::scanReferencingEverything,
                exporter,
                ProbeRegistry(),
                StaticBaselineMismatchDetector(),
                filterReferences = BaselineReferenceFilter(dependencies, external)::filter,
            )

        val worker = Thread { publisher.run(resource) }
        worker.start()
        Thread.sleep(200)
        assertTrue(exporter.baselines.isEmpty(), "nothing is sent while the listing runs")
        val lib = dependencies.addListed("lib", "org.lib.Indexed")
        dependencies.markListingComplete()
        worker.join(10_000)

        assertTrue(
            "org.lib.Indexed" in
                exporter.baselines
                    .single()
                    .declaredClasses
                    .single()
                    .referencedClasses,
        )
        assertTrue(ExternalClass("org.lib.Indexed", lib) in sent(external))
    }

    @Test
    fun `a failed listing releases the wait at once and the baseline goes out with every reference list empty`() {
        val dependencies = indexedRegistry()
        val external = externalRegistry(dependencies)
        Agent.runDependencyListing({ throw IllegalStateException("simulated listing failure") }, dependencies)
        val exporter = RecordingExporter()
        val publisher =
            StaticBaselinePublisher(
                ::scanReferencingEverything,
                exporter,
                ProbeRegistry(),
                StaticBaselineMismatchDetector(),
                filterReferences = BaselineReferenceFilter(dependencies, external, listingWait = Duration.ofMinutes(5))::filter,
            )

        val started = System.nanoTime()
        publisher.run(resource)

        assertTrue(System.nanoTime() - started < Duration.ofSeconds(5).toNanos())
        assertEmptyReferences(exporter.baselines.single())
        dependencies.markListingComplete()
        assertTrue(sent(external).isEmpty())
    }

    @Test
    fun `a listing that outlasts the bounded wait leaves every reference list empty`() {
        val dependencies = indexedRegistry()
        val external = externalRegistry(dependencies)
        val exporter = RecordingExporter()
        val publisher =
            StaticBaselinePublisher(
                ::scanReferencingEverything,
                exporter,
                ProbeRegistry(),
                StaticBaselineMismatchDetector(),
                filterReferences = BaselineReferenceFilter(dependencies, external, listingWait = Duration.ofMillis(50))::filter,
            )

        publisher.run(resource)

        assertEmptyReferences(exporter.baselines.single())
        dependencies.addListed("lib", "org.lib.Indexed")
        dependencies.markListingComplete()
        assertTrue(sent(external).isEmpty())
        assertEquals(0, dependencies.classIndexSize, "a listing that ends after the wait indexes nothing")
    }

    @Test
    fun `a filter that throws still lets the baseline go out, with every reference list empty`() {
        val exporter = RecordingExporter()

        StaticBaselinePublisher(
            ::scanReferencingEverything,
            exporter,
            ProbeRegistry(),
            StaticBaselineMismatchDetector(),
            filterReferences = { throw IllegalStateException("simulated filter failure") },
        ).run(resource)

        assertEmptyReferences(exporter.baselines.single())
    }

    @Test
    fun `a publisher given no filter sends no references at all`() {
        val exporter = RecordingExporter()

        StaticBaselinePublisher(::scanReferencingEverything, exporter, ProbeRegistry(), StaticBaselineMismatchDetector()).run(resource)

        assertEmptyReferences(exporter.baselines.single())
    }

    private fun assertEmptyReferences(baseline: StaticBaseline) {
        val declared = baseline.declaredClasses.single()
        assertTrue(declared.referencedClasses.isEmpty())
        assertTrue(declared.methods.all { it.referencedClasses.isEmpty() })
    }

    @Test
    fun `a never-loaded class under BOOT-INF classes declares its reference into a BOOT-INF lib jar, mapped to that dependency`() {
        val referenceTargetBytes = File("build/classes/java/test/com/example/target/ReferenceTarget.class").readBytes()
        val library =
            TestJars.bytes(
                listOf(
                    TestJars.pom("com.example", "library", "1.0"),
                    TestJars.classEntry("com.example.library.Lib"),
                    TestJars.classEntry("com.example.library.Lib\$New"),
                    TestJars.classEntry("com.example.library.Lib\$Base"),
                ),
            )
        val fatJar =
            TestJars.write(
                dir.resolve("app.jar"),
                listOf(
                    "BOOT-INF/classes/com/example/target/ReferenceTarget.class" to referenceTargetBytes,
                    "BOOT-INF/lib/library-1.0.jar" to library,
                ),
                mapOf("Spring-Boot-Lib" to "BOOT-INF/lib/", "Spring-Boot-Classes" to "BOOT-INF/classes/"),
            )
        val includes = listOf("com.example.target")
        val dependencies = indexedRegistry()
        Agent.runDependencyListing(
            StartupClasspathLister(includes, emptyList(), fatJar.toString(), keepClassNames = true)::list,
            dependencies,
        )
        val external = externalRegistry(dependencies)
        val exporter = RecordingExporter()
        val publisher =
            StaticBaselinePublisher(
                { StaticBaselineScanner(includes).scan(listOf(fatJar.toFile())) },
                exporter,
                ProbeRegistry(),
                StaticBaselineMismatchDetector(),
                filterReferences = BaselineReferenceFilter(dependencies, external)::filter,
            )

        publisher.run(resource)

        val declared =
            exporter.baselines
                .single()
                .declaredClasses
                .single { it.className == "com.example.target.ReferenceTarget" }
        assertTrue("com.example.library.Lib\$New" in declared.methods.single { it.methodName == "newInstance" }.referencedClasses)
        assertTrue("com.example.library.Lib\$Base" in declared.referencedClasses)
        assertTrue(declared.referencedClasses.none { it.startsWith("java.") }, "JDK names are dropped")
        val library1 = dependencies.entries().single { entry -> entry.identities.any { it.artifactId == "library" } }
        val mappings = sent(external)
        assertTrue(ExternalClass("com.example.library.Lib\$New", library1.dependencyId) in mappings)
        assertTrue(ExternalClass("com.example.library.Lib\$Base", library1.dependencyId) in mappings)
        assertTrue(
            exporter.baselines
                .single()
                .externalClasses
                .isEmpty(),
        )
    }
}
