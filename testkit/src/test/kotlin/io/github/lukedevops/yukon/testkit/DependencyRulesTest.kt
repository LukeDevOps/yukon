package io.github.lukedevops.yukon.testkit

import io.github.lukedevops.yukon.export.DependencyDiscoverySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DependencyRulesTest {
    private val jackson =
        DependencyView(
            dependencyId = 1,
            identities = listOf(DependencyIdentityView("tools.jackson.core", "jackson-databind", "3.0.2")),
            discoverySource = DependencyDiscoverySource.STARTUP_CLASSPATH,
            classCount = 880,
            location = "BOOT-INF/lib/jackson-databind-3.0.2.jar",
        )
    private val jsonMapper = listOf("tools.jackson.databind.json.JsonMapper")

    private fun instance(
        references: List<HeldReferences> = emptyList(),
        referencesRecorded: Boolean = true,
        baselineComplete: Boolean = true,
        loaded: Long = 378,
        loadedClassNames: Set<String> = emptySet(),
        instanceId: String = "instance-1",
        dependency: DependencyView = jackson,
    ) = InstanceDependencyView(
        instanceId = instanceId,
        referencesRecorded = referencesRecorded,
        baselineComplete = baselineComplete,
        dependencies = listOf(dependency),
        loadedClassesTotal = mapOf(dependency.dependencyId to loaded),
        externalClasses = mapOf("tools.jackson.databind.json.JsonMapper" to ExternalClassView(dependency.dependencyId, absent = false)),
        references = references,
        loadedClassNames = loadedClassNames,
    )

    private fun method(
        className: String,
        methodName: String,
        hits: Long,
        inline: Boolean = false,
        referenced: List<String> = jsonMapper,
    ) = HeldReferences(className, methodName, "()V", ReferenceOrigin.MANIFEST_METHOD, referenced, inline = inline, hits = hits)

    private fun statusOf(vararg instances: InstanceDependencyView): DependencyUsage =
        computeDependencyReport(instances.toList()).findings.single().status

    @Test
    fun `a startup dependency no instance loaded a class from is unloaded`() {
        assertEquals(DependencyUsage.UNLOADED, statusOf(instance(loaded = 0)))
    }

    @Test
    fun `a startup dependency is not unloaded once any instance loaded a class from it`() {
        val status = statusOf(instance(loaded = 0), instance(loaded = 4, instanceId = "instance-2"))

        assertEquals(DependencyUsage.UNREFERENCED, status)
    }

    @Test
    fun `a loaded dependency nothing references is unreferenced when every recording instance has a complete baseline`() {
        assertEquals(DependencyUsage.UNREFERENCED, statusOf(instance()))
    }

    @Test
    fun `a dependency referenced only from a never-hit method is unreached, and the method is listed`() {
        val report =
            computeDependencyReport(
                listOf(
                    instance(
                        references = listOf(method("demo.OrderController", "legacy", hits = 0)),
                        loadedClassNames = setOf("demo.OrderController"),
                    ),
                ),
            )

        val finding = report.findings.single()
        assertEquals(DependencyUsage.UNREACHED, finding.status)
        assertEquals(listOf(DependencyReferenceSite("demo.OrderController", "legacy", neverLoaded = false)), finding.sites)
    }

    @Test
    fun `a dependency referenced only from a never-loaded class's baseline declaration is unreached`() {
        val baselineReference =
            HeldReferences("demo.LegacyPricing", "apply", "(D)D", ReferenceOrigin.BASELINE, jsonMapper)
        val report = computeDependencyReport(listOf(instance(references = listOf(baselineReference))))

        val finding = report.findings.single()
        assertEquals(DependencyUsage.UNREACHED, finding.status)
        assertEquals(listOf(DependencyReferenceSite("demo.LegacyPricing", "apply", neverLoaded = true)), finding.sites)
    }

    @Test
    fun `an inline method's own references never count, however often it ran`() {
        val references =
            listOf(
                method("demo.Json", "encode", hits = 50, inline = true),
                method("demo.OrderController", "legacy", hits = 0),
            )

        assertEquals(DependencyUsage.UNREACHED, statusOf(instance(references = references)))
        assertEquals(
            DependencyUsage.UNREFERENCED,
            statusOf(instance(references = listOf(method("demo.Json", "encode", hits = 50, inline = true)))),
        )
    }

    @Test
    fun `a dependency referenced from a hit method is used`() {
        assertEquals(DependencyUsage.USED, statusOf(instance(references = listOf(method("demo.OrderController", "get", hits = 3)))))
    }

    @Test
    fun `a dependency referenced at class level by a loaded class is used`() {
        val classLevel = HeldReferences("demo.Config", null, null, ReferenceOrigin.MANIFEST_CLASS, jsonMapper)

        assertEquals(DependencyUsage.USED, statusOf(instance(references = listOf(classLevel), loadedClassNames = setOf("demo.Config"))))
    }

    @Test
    fun `without a complete baseline from every recording instance, no reference and a dead reference both read as no live reference`() {
        val dead = listOf(method("demo.OrderController", "legacy", hits = 0))

        assertEquals(DependencyUsage.NO_LIVE_REFERENCE, statusOf(instance(baselineComplete = false)))
        assertEquals(DependencyUsage.NO_LIVE_REFERENCE, statusOf(instance(references = dead, baselineComplete = false)))
        assertEquals(
            DependencyUsage.NO_LIVE_REFERENCE,
            statusOf(instance(references = dead), instance(baselineComplete = false, instanceId = "instance-2")),
        )
        assertEquals(
            DependencyUsage.USED,
            statusOf(instance(references = listOf(method("demo.OrderController", "get", hits = 1)), baselineComplete = false)),
        )
    }

    @Test
    fun `with no instance recording references a loaded dependency reads as loaded and levels 2 and 3 are unavailable`() {
        val report = computeDependencyReport(listOf(instance(referencesRecorded = false)))

        assertTrue(report.referencesUnavailable)
        assertEquals(DependencyUsage.LOADED, report.findings.single().status)
        assertEquals(DependencyUsage.UNLOADED, statusOf(instance(referencesRecorded = false, loaded = 0)))
    }

    @Test
    fun `an instance that records nothing does not decide the baseline split`() {
        val status = statusOf(instance(), instance(referencesRecorded = false, baselineComplete = false, instanceId = "instance-2"))

        assertEquals(DependencyUsage.UNREFERENCED, status)
    }

    @Test
    fun `a reference sent by an instance that records nothing never counts, even from a hit method`() {
        val notRecording =
            instance(
                references = listOf(method("org.thirdparty.Codec", "encode", hits = 9)),
                referencesRecorded = false,
                instanceId = "instance-2",
            )

        assertEquals(DependencyUsage.UNREFERENCED, statusOf(instance(), notRecording))
    }

    @Test
    fun `a recording instance that does not list the dependency does not decide its baseline split`() {
        val noDependencies = InstanceDependencyView("instance-2", referencesRecorded = true, baselineComplete = false)

        assertEquals(DependencyUsage.UNREFERENCED, statusOf(instance(), noDependencies))
    }

    @Test
    fun `a dependency only non-recording instances list reads as loaded, even beside an instance that records`() {
        val recordsButListsNothing = InstanceDependencyView("instance-2", referencesRecorded = true, baselineComplete = true)
        val report = computeDependencyReport(listOf(instance(referencesRecorded = false), recordsButListsNothing))

        assertFalse(report.referencesUnavailable)
        assertEquals(DependencyUsage.LOADED, report.findings.single().status)
    }

    @Test
    fun `a dependency discovered by load is never unloaded, even before its first loaded-class total arrives`() {
        val discoveredByLoad = jackson.copy(discoverySource = DependencyDiscoverySource.LOAD)

        assertEquals(DependencyUsage.UNREFERENCED, statusOf(instance(loaded = 0, dependency = discoveredByLoad)))
    }

    @Test
    fun `a dependency one instance listed at startup and another discovered by load is unloaded while no class from it loaded`() {
        val discoveredByLoad = jackson.copy(dependencyId = 7, discoverySource = DependencyDiscoverySource.LOAD)

        assertEquals(
            DependencyUsage.UNLOADED,
            statusOf(instance(loaded = 0), instance(loaded = 0, instanceId = "instance-2", dependency = discoveredByLoad)),
        )
    }

    @Test
    fun `a shaded jar merges across instances whatever order each listed its identities in`() {
        val guava = DependencyIdentityView("com.google.guava", "guava", "33.0")
        val fat = DependencyIdentityView("com.acme", "fat", "1.0")
        val report =
            computeDependencyReport(
                listOf(
                    instance(dependency = jackson.copy(identities = listOf(guava, fat))),
                    instance(instanceId = "instance-2", dependency = jackson.copy(identities = listOf(fat, guava))),
                ),
            )

        assertEquals(listOf("com.acme:fat,com.google.guava:guava"), report.findings.map { it.identityKey })
    }

    @Test
    fun `a class-level reference held by a class that never loaded is not live`() {
        val classLevel = HeldReferences("demo.Config", null, null, ReferenceOrigin.MANIFEST_CLASS, jsonMapper)

        assertEquals(DependencyUsage.UNREACHED, statusOf(instance(references = listOf(classLevel))))
    }

    @Test
    fun `a referenced class with no mapping on its instance counts for no dependency`() {
        val view =
            instance(references = listOf(method("demo.OrderController", "get", hits = 3)))
                .copy(externalClasses = emptyMap())

        assertEquals(DependencyUsage.UNREFERENCED, statusOf(view))
    }

    @Test
    fun `two instances merge by identity, a hit on either makes the reference live, and both versions are shown`() {
        val other =
            jackson.copy(dependencyId = 9, identities = listOf(DependencyIdentityView("tools.jackson.core", "jackson-databind", "3.0.3")))
        val report =
            computeDependencyReport(
                listOf(
                    instance(references = listOf(method("demo.OrderController", "get", hits = 0)), loaded = 300),
                    instance(
                        references = listOf(method("demo.OrderController", "get", hits = 2)),
                        loaded = 378,
                        instanceId = "instance-2",
                        dependency = other,
                    ),
                ),
            )

        val finding = report.findings.single()
        assertEquals(DependencyUsage.USED, finding.status)
        assertEquals(mapOf("tools.jackson.core:jackson-databind" to setOf("3.0.2", "3.0.3")), finding.versionsByIdentity)
        assertEquals(listOf("tools.jackson.core" to "jackson-databind"), finding.identities)
        assertEquals(378L, finding.loadedClassesTotal)
    }

    @Test
    fun `a hit on another instance makes a never-hit method's reference live even when that instance sent no reference`() {
        val status =
            statusOf(
                instance(references = listOf(method("demo.OrderController", "get", hits = 0))),
                instance(
                    references = listOf(method("demo.OrderController", "get", hits = 5, referenced = emptyList())),
                    instanceId = "instance-2",
                ),
            )

        assertEquals(DependencyUsage.USED, status)
    }

    @Test
    fun `a hit on an instance that records nothing still makes a recording instance's reference live`() {
        val status =
            statusOf(
                instance(references = listOf(method("demo.OrderController", "get", hits = 0))),
                instance(
                    references = listOf(method("demo.OrderController", "get", hits = 5, referenced = emptyList())),
                    referencesRecorded = false,
                    instanceId = "instance-2",
                ),
            )

        assertEquals(DependencyUsage.USED, status)
    }

    @Test
    fun `a reference to a class mapped to another dependency does not count for this one`() {
        val view =
            instance(references = listOf(method("demo.OrderController", "get", hits = 3, referenced = listOf("org.example.Other"))))
                .let { it.copy(externalClasses = it.externalClasses + ("org.example.Other" to ExternalClassView(2, absent = false))) }

        assertEquals(DependencyUsage.UNREFERENCED, statusOf(view))
    }

    @Test
    fun `an absent referenced class is listed with the sites that reference it`() {
        val view =
            instance(references = listOf(method("demo.OrderController", "optional", hits = 0, referenced = listOf("org.example.Missing"))))
                .let { it.copy(externalClasses = it.externalClasses + ("org.example.Missing" to ExternalClassView(null, absent = true))) }
        val report = computeDependencyReport(listOf(view))

        assertEquals(
            listOf(
                AbsentReference(
                    "org.example.Missing",
                    listOf(DependencyReferenceSite("demo.OrderController", "optional", neverLoaded = false)),
                ),
            ),
            report.absentReferences,
        )
        assertEquals(DependencyUsage.UNREFERENCED, report.findings.single().status)
    }

    @Test
    fun `findings sort by status, then by identity, and an unreached finding lists its site`() {
        fun dependency(
            id: Int,
            artifact: String,
        ) = DependencyView(
            id,
            listOf(DependencyIdentityView("org.example", artifact, "1.0")),
            DependencyDiscoverySource.STARTUP_CLASSPATH,
            10,
            "$artifact.jar",
        )

        val view =
            InstanceDependencyView(
                instanceId = "instance-1",
                referencesRecorded = true,
                baselineComplete = true,
                dependencies = listOf(dependency(1, "zeta"), dependency(2, "alpha"), dependency(3, "beta"), dependency(4, "gamma")),
                loadedClassesTotal = mapOf(1 to 3L, 2 to 0L, 3 to 1L, 4 to 2L),
                externalClasses = mapOf("org.example.Z" to ExternalClassView(1, false), "org.example.G" to ExternalClassView(4, false)),
                references =
                    listOf(
                        method("demo.A", "a", hits = 1, referenced = listOf("org.example.Z")),
                        method("demo.B", "b", hits = 0, referenced = listOf("org.example.G")),
                    ),
                loadedClassNames = setOf("demo.A", "demo.B"),
            )
        val report = computeDependencyReport(listOf(view))

        assertEquals(
            listOf("org.example:alpha", "org.example:beta", "org.example:gamma", "org.example:zeta"),
            report.findings.map { it.identityKey },
        )
        assertEquals(
            listOf(DependencyUsage.UNLOADED, DependencyUsage.UNREFERENCED, DependencyUsage.UNREACHED, DependencyUsage.USED),
            report.findings.map { it.status },
        )
        assertEquals(
            listOf(DependencyReferenceSite("demo.B", "b", neverLoaded = false)),
            report.findings.single { it.identityKey == "org.example:gamma" }.sites,
        )
        assertFalse(report.referencesUnavailable)
    }
}
