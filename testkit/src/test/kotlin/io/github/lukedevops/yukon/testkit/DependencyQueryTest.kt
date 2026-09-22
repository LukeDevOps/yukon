package io.github.lukedevops.yukon.testkit

import io.github.lukedevops.yukon.export.ClassReferences
import io.github.lukedevops.yukon.export.DeclaredClass
import io.github.lukedevops.yukon.export.DeclaredMethod
import io.github.lukedevops.yukon.export.DeltaBatch
import io.github.lukedevops.yukon.export.DependencyDelta
import io.github.lukedevops.yukon.export.DependencyDiscoverySource
import io.github.lukedevops.yukon.export.DependencyIdentity
import io.github.lukedevops.yukon.export.DependencyIdentitySource
import io.github.lukedevops.yukon.export.DependencyLocation
import io.github.lukedevops.yukon.export.ExternalClass
import io.github.lukedevops.yukon.export.HttpOtlpStyleExporter
import io.github.lukedevops.yukon.export.ProbeDelta
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ProbeLocation
import io.github.lukedevops.yukon.export.ProbeManifest
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.export.SkippedClass
import io.github.lukedevops.yukon.export.StaticBaseline
import io.github.lukedevops.yukon.export.UnreportedClass
import java.time.Duration
import java.util.concurrent.TimeoutException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DependencyQueryTest {
    private val collector = YukonTestCollector.start()
    private val exporter = HttpOtlpStyleExporter(collector.endpoint)

    @AfterTest
    fun tearDown() {
        collector.close()
    }

    private fun dependency(
        id: Int,
        groupId: String?,
        artifactId: String,
        version: String? = "1.0",
        discoverySource: DependencyDiscoverySource = DependencyDiscoverySource.STARTUP_CLASSPATH,
        extraIdentities: List<DependencyIdentity> = emptyList(),
    ) = DependencyLocation(
        dependencyId = id,
        identities = listOf(DependencyIdentity(groupId, artifactId, version)) + extraIdentities,
        identitySource = if (groupId == null) DependencyIdentitySource.FILENAME else DependencyIdentitySource.POM_PROPERTIES,
        location = "/libs/$artifactId.jar",
        discoverySource = discoverySource,
        classCount = 10,
    )

    private fun methodProbe(
        classId: Int,
        probeIndex: Int,
        className: String,
        methodName: String,
        referenced: List<String> = emptyList(),
    ) = ProbeLocation(
        classId,
        probeIndex,
        ProbeKind.METHOD,
        className,
        methodName,
        "()V",
        1,
        null,
        referencedClasses = referenced,
    )

    private fun manifest(
        dependencies: List<DependencyLocation> = emptyList(),
        probes: List<ProbeLocation> = emptyList(),
        externalClasses: List<ExternalClass> = emptyList(),
        classReferences: List<ClassReferences> = emptyList(),
        unreportedClasses: List<UnreportedClass> = emptyList(),
        skippedClasses: List<SkippedClass> = emptyList(),
        referencesRecorded: Boolean = true,
        instanceId: String = "i-1",
    ) {
        exporter.exportManifest(
            ProbeManifest(
                resource = ResourceAttributes("svc", null, instanceId, null, "run-1"),
                probes = probes,
                dependencies = dependencies,
                classReferences = classReferences,
                externalClasses = externalClasses,
                unreportedClasses = unreportedClasses,
                skippedClasses = skippedClasses,
                referencesRecorded = referencesRecorded,
            ),
        )
    }

    private fun deltas(
        dependencyDeltas: List<DependencyDelta> = emptyList(),
        probeDeltas: List<ProbeDelta> = emptyList(),
        instanceId: String = "i-1",
    ) {
        exporter.exportDeltaBatch(
            DeltaBatch(ResourceAttributes("svc", null, instanceId, null, "run-1"), probeDeltas, dependencyDeltas = dependencyDeltas),
        )
    }

    /** Two delta batches after the listing manifest, the most an agent needs to deliver the counts that go with it. */
    private fun settle(instanceId: String = "i-1") {
        deltas(instanceId = instanceId)
        deltas(instanceId = instanceId)
    }

    private fun baseline(
        declaredClasses: List<DeclaredClass> = emptyList(),
        externalClasses: List<ExternalClass> = emptyList(),
        chunkCount: Int = 1,
        instanceId: String = "i-1",
    ) {
        exporter.exportStaticBaseline(
            StaticBaseline(
                resource = ResourceAttributes("svc", null, instanceId, null, "run-1"),
                declaredClasses = declaredClasses,
                scannedAt = 1000L,
                chunkIndex = 0,
                chunkCount = chunkCount,
                externalClasses = externalClasses,
            ),
        )
    }

    @Test
    fun `dependency matches by group and artifact, and a null or empty group matches a filename identity`() {
        manifest(
            dependencies =
                listOf(
                    dependency(0, "com.fasterxml.jackson.core", "jackson-databind", "2.15.1"),
                    dependency(1, null, "commons-lang3", "3.14.0"),
                ),
        )
        deltas(listOf(DependencyDelta(0, 1L, 12L)))
        settle()

        val jackson = collector.dependency("com.fasterxml.jackson.core", "jackson-databind")
        assertEquals("com.fasterxml.jackson.core:jackson-databind", jackson.identityKey)
        assertEquals(listOf(DependencyIdentityRef("com.fasterxml.jackson.core", "jackson-databind", setOf("2.15.1"))), jackson.identities)
        assertEquals(12L, jackson.loadedClassesTotal)
        assertEquals(10, jackson.classCount)
        assertEquals(setOf(DependencyDiscoverySource.STARTUP_CLASSPATH), jackson.discoverySources)
        assertEquals(DependencyUsage.NO_LIVE_REFERENCE, jackson.status)

        assertEquals(":commons-lang3", collector.dependency(null, "commons-lang3").identityKey)
        assertEquals(DependencyUsage.UNLOADED, collector.dependency("", "commons-lang3").status)
        assertFailsWith<UnknownDependencyException> { collector.dependency("org.apache.commons", "commons-lang3") }
    }

    @Test
    fun `dependency matches any one identity a shaded jar carries`() {
        manifest(
            dependencies =
                listOf(dependency(0, "com.acme", "fat", extraIdentities = listOf(DependencyIdentity("com.google.guava", "guava", "33.0")))),
        )
        settle()

        assertEquals("com.acme:fat,com.google.guava:guava", collector.dependency("com.google.guava", "guava").identityKey)
        assertEquals("com.acme:fat,com.google.guava:guava", collector.dependency("com.acme", "fat").identityKey)
    }

    @Test
    fun `an unknown dependency throws, naming what the collector knows`() {
        val nothingYet = assertFailsWith<UnknownDependencyException> { collector.dependency("com.acme", "missing") }
        assertTrue(nothingYet.message!!.contains("no manifest has listed any dependency"), nothingYet.message)

        manifest(dependencies = listOf(dependency(0, "com.acme", "known"), dependency(1, null, "plain")))
        settle()

        val failure = assertFailsWith<UnknownDependencyException> { collector.dependency("com.acme", "missing") }
        assertTrue(failure.message!!.contains("com.acme:missing"), failure.message)
        assertTrue(failure.message!!.contains(":plain, com.acme:known"), failure.message)
    }

    @Test
    fun `a dependency is not judged until two delta batches from its instance follow the manifest listing it`() {
        manifest(dependencies = listOf(dependency(0, "com.acme", "lib")))

        val early = assertFailsWith<IllegalStateException> { collector.dependency("com.acme", "lib") }
        assertTrue(early.message!!.contains("awaitSettled"), early.message)
        assertFailsWith<IllegalStateException> { collector.unloadedDependencies() }

        deltas(listOf(DependencyDelta(0, 1L, 3L)))
        assertFailsWith<IllegalStateException> { collector.dependency("com.acme", "lib") }

        deltas()
        assertEquals(3L, collector.dependency("com.acme", "lib").loadedClassesTotal)
        assertEquals(emptyList(), collector.unloadedDependencies())
    }

    @Test
    fun `awaitDependency returns once the dependency is listed and settled, and times out otherwise`() {
        assertFailsWith<TimeoutException> { collector.awaitDependency("com.acme", "lib", Duration.ofMillis(200)) }

        manifest(dependencies = listOf(dependency(0, "com.acme", "lib")))
        deltas()
        assertFailsWith<TimeoutException> { collector.awaitDependency("com.acme", "lib", Duration.ofMillis(200)) }

        deltas()
        collector.awaitDependency("com.acme", "lib", Duration.ofSeconds(5))
    }

    @Test
    fun `the split queries throw without references_recorded`() {
        manifest(dependencies = listOf(dependency(0, "com.acme", "lib")), referencesRecorded = false)
        deltas(listOf(DependencyDelta(0, 1L, 3L)))
        settle()
        baseline()

        for (query in listOf(collector::unreferencedDependencies, collector::unreachedDependencies)) {
            val failure = assertFailsWith<IllegalStateException> { query() }
            assertTrue(failure.message!!.contains("includePackages"), failure.message)
            assertTrue(failure.message!!.contains("staticBaselineEnabled=true"), failure.message)
        }
        assertFailsWith<IllegalStateException> { collector.absentReferences() }
        assertEquals(DependencyUsage.LOADED, collector.dependency("com.acme", "lib").status)
    }

    @Test
    fun `the split queries throw without references_recorded even when every listed dependency is unloaded`() {
        manifest(dependencies = listOf(dependency(0, "com.acme", "lib")), referencesRecorded = false)
        settle()
        baseline()

        assertFailsWith<IllegalStateException> { collector.unreferencedDependencies() }
        assertFailsWith<IllegalStateException> { collector.unreachedDependencies() }
        assertEquals(listOf("com.acme:lib"), collector.unloadedDependencies().map { it.identityKey })
    }

    @Test
    fun `a loaded-class total that arrives lower than one already seen does not lower it`() {
        manifest(dependencies = listOf(dependency(0, "com.acme", "lib")))
        deltas(listOf(DependencyDelta(0, 1L, 12L)))
        deltas(listOf(DependencyDelta(0, 1L, 5L)))

        assertEquals(12L, collector.dependency("com.acme", "lib").loadedClassesTotal)
    }

    @Test
    fun `the split queries throw without a complete static baseline, and answer once it arrives`() {
        manifest(dependencies = listOf(dependency(0, "com.acme", "lib")))
        deltas(listOf(DependencyDelta(0, 1L, 3L)))
        settle()

        val noBaseline = assertFailsWith<IllegalStateException> { collector.unreferencedDependencies() }
        assertTrue(noBaseline.message!!.contains("staticBaselineEnabled=true"), noBaseline.message)
        assertTrue(noBaseline.message!!.contains("com.acme:lib"), noBaseline.message)

        baseline(chunkCount = 2)
        assertFailsWith<IllegalStateException> { collector.unreachedDependencies() }

        exporter.exportStaticBaseline(
            StaticBaseline(
                ResourceAttributes("svc", null, "i-1", null, "run-1"),
                emptyList(),
                scannedAt = 1000L,
                chunkIndex = 1,
                chunkCount = 2,
            ),
        )
        assertEquals(listOf("com.acme:lib"), collector.unreferencedDependencies().map { it.identityKey })
        assertEquals(emptyList(), collector.unreachedDependencies())
    }

    @Test
    fun `unloadedDependencies needs neither references nor a baseline`() {
        manifest(dependencies = listOf(dependency(0, "com.acme", "lib"), dependency(1, "com.acme", "loaded")), referencesRecorded = false)
        deltas(listOf(DependencyDelta(1, 1L, 2L)))
        settle()

        assertEquals(listOf("com.acme:lib"), collector.unloadedDependencies().map { it.identityKey })
    }

    @Test
    fun `the lists are filtered by status and sorted by identity, across method, class-level and baseline references`() {
        manifest(
            dependencies =
                listOf(
                    dependency(0, "org.zeta", "unloaded-z"),
                    dependency(1, "org.alpha", "unloaded-a"),
                    dependency(2, "org.example", "unreferenced"),
                    dependency(3, "org.example", "from-dead-method"),
                    dependency(4, "org.example", "from-baseline"),
                    dependency(5, "org.example", "from-hit-method"),
                    dependency(6, "org.example", "from-loaded-class"),
                ),
            probes =
                listOf(
                    methodProbe(1, 0, "demo.Controller", "get", listOf("org.example.Hit")),
                    methodProbe(1, 1, "demo.Controller", "legacy", listOf("org.example.Dead")),
                    methodProbe(2, 0, "demo.Config", "<init>"),
                ),
            classReferences = listOf(ClassReferences(2, listOf("org.example.Annotation"))),
            externalClasses =
                listOf(
                    ExternalClass("org.example.Hit", 5),
                    ExternalClass("org.example.Dead", 3),
                    ExternalClass("org.example.Annotation", 6),
                ),
        )
        deltas(
            dependencyDeltas = (2..6).map { DependencyDelta(it, 1L, 1L) },
            probeDeltas = listOf(ProbeDelta(1, 0, ProbeKind.METHOD, 1L, 4L)),
        )
        settle()
        baseline(
            declaredClasses =
                listOf(DeclaredClass("demo.Legacy", listOf(DeclaredMethod("apply", "()V", referencedClasses = listOf("org.example.Old"))))),
            externalClasses = listOf(ExternalClass("org.example.Old", 4)),
        )

        assertEquals(listOf("org.alpha:unloaded-a", "org.zeta:unloaded-z"), collector.unloadedDependencies().map { it.identityKey })
        assertEquals(listOf("org.example:unreferenced"), collector.unreferencedDependencies().map { it.identityKey })
        val unreached = collector.unreachedDependencies()
        assertEquals(listOf("org.example:from-baseline", "org.example:from-dead-method"), unreached.map { it.identityKey })
        assertEquals(listOf(DependencyReferenceSite("demo.Legacy", "apply", neverLoaded = true)), unreached[0].sites)
        assertEquals(listOf(DependencyReferenceSite("demo.Controller", "legacy", neverLoaded = false)), unreached[1].sites)
        assertEquals(DependencyUsage.USED, collector.dependency("org.example", "from-hit-method").status)
        assertEquals(DependencyUsage.USED, collector.dependency("org.example", "from-loaded-class").status)
    }

    @Test
    fun `an unreported class counts as loaded, so a baseline site in it is not marked never loaded`() {
        manifest(
            dependencies = listOf(dependency(0, "org.example", "lib")),
            unreportedClasses = listOf(UnreportedClass("demo.Deflected", 1L)),
        )
        deltas(listOf(DependencyDelta(0, 1L, 1L)))
        settle()
        baseline(
            declaredClasses =
                listOf(
                    DeclaredClass("demo.Deflected", listOf(DeclaredMethod("run", "()V", referencedClasses = listOf("org.example.Lib")))),
                ),
            externalClasses = listOf(ExternalClass("org.example.Lib", 0)),
        )

        assertEquals(
            listOf(DependencyReferenceSite("demo.Deflected", "run", neverLoaded = false)),
            collector.dependency("org.example", "lib").sites,
        )
    }

    @Test
    fun `a skipped class counts as loaded, so a baseline site in it is not marked never loaded`() {
        manifest(
            dependencies = listOf(dependency(0, "org.example", "lib")),
            skippedClasses = listOf(SkippedClass("demo.Skipped", "unsafe annotation", 1L)),
        )
        deltas(listOf(DependencyDelta(0, 1L, 1L)))
        settle()
        baseline(
            declaredClasses =
                listOf(DeclaredClass("demo.Skipped", listOf(DeclaredMethod("run", "()V", referencedClasses = listOf("org.example.Lib"))))),
            externalClasses = listOf(ExternalClass("org.example.Lib", 0)),
        )

        assertEquals(
            listOf(DependencyReferenceSite("demo.Skipped", "run", neverLoaded = false)),
            collector.dependency("org.example", "lib").sites,
        )
    }

    @Test
    fun `absentReferences lists each missing class with the sites that reference it`() {
        manifest(
            probes = listOf(methodProbe(1, 0, "demo.Optional", "probe", listOf("org.example.Missing"))),
            externalClasses = listOf(ExternalClass("org.example.Missing", null, absent = true)),
        )

        assertEquals(
            listOf(AbsentReference("org.example.Missing", listOf(DependencyReferenceSite("demo.Optional", "probe", neverLoaded = false)))),
            collector.absentReferences(),
        )
    }
}
