package io.github.lukedevops.yukon.instrumentation

import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.BranchRole
import io.github.lukedevops.yukon.export.BranchSite
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.export.ProbeLocation
import io.github.lukedevops.yukon.export.ProbeManifest
import io.github.lukedevops.yukon.export.ResourceAttributes
import io.github.lukedevops.yukon.instrumentation.branch.BranchSiteAnalyzer
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves through the real transform that each kept branch site reaches the manifest on its
 * METHOD probe, with its outcomes listed inside it, and that each BRANCH probe names its site.
 * See ADR 0037.
 */
class BranchSiteInstrumentationTest {
    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedYukon: YukonInstrumentation? = null

    private val resource = ResourceAttributes("test", null, "instance-1", null, "run-1")

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { installedYukon?.uninstall(ByteBuddyAgent.install(), it) }
        installedTransformer = null
        installedYukon = null
    }

    private fun install(
        registry: ProbeRegistry,
        includePackages: String,
    ) {
        val yukon = YukonInstrumentation(AgentConfig.parse("includePackages=$includePackages"), registry)
        installedYukon = yukon
        installedTransformer = yukon.install(ByteBuddyAgent.install())
    }

    private fun javaFixtureLoader() = FixtureClassLoader(arrayOf(File("build/classes/java/test").toURI().toURL()), javaClass.classLoader)

    private fun kotlinFixtureLoader() =
        FixtureClassLoader(arrayOf(File("build/classes/kotlin/test").toURI().toURL()), javaClass.classLoader, "com.example.")

    private fun methodProbe(
        manifest: ProbeManifest,
        className: String,
        methodName: String,
    ): ProbeLocation = manifest.probes.single { it.className == className && it.kind == ProbeKind.METHOD && it.methodName == methodName }

    private fun branchProbes(
        manifest: ProbeManifest,
        className: String,
        methodName: String,
    ): List<ProbeLocation> =
        manifest.probes
            .filter { it.className == className && it.kind == ProbeKind.BRANCH && it.methodName == methodName }
            .sortedBy { it.probeIndex }

    /**
     * Asserts that [sites] and [branches], one method's listed sites and BRANCH probes, describe
     * the same outcomes: each outcome's branch index is a BRANCH probe's own, in the same order,
     * and each BRANCH probe names the site that lists it, on the same line.
     */
    private fun assertSitesMatchBranchProbes(
        sites: List<BranchSite>,
        branches: List<ProbeLocation>,
    ) {
        assertEquals(branches.map { it.branchIndex }, sites.flatMap { site -> site.outcomes.map { it.branchIndex } })
        for (branch in branches) {
            val site = sites.single { site -> site.outcomes.any { it.branchIndex == branch.branchIndex } }
            assertEquals(site.siteIndex, branch.siteIndex, "branch ${branch.branchIndex} names its own site")
            assertEquals(site.line, branch.line)
        }
    }

    /**
     * The first branch index of each kept site of [methodName] in [classBytes], counted
     * independently of the agent: the outcome total of every earlier site in the class, dropped
     * sites included.
     */
    private fun expectedFirstBranchIndexes(
        classBytes: ByteArray,
        methodName: String,
        includePackages: List<String>,
        methodFilter: (String, String) -> Boolean,
    ): List<Int> {
        val sites = BranchSiteAnalyzer.analyze(classBytes, includePackages = includePackages, methodFilter = methodFilter).sites
        return sites
            .filter { it.methodName == methodName && it.dropReason == null }
            .map { kept -> sites.filter { it.siteIndex < kept.siteIndex }.sumOf { it.outcomeCount } }
    }

    @Test
    fun `an if-else is one site whose TAKEN and FALL_THROUGH outcomes are its BRANCH probes' own`() {
        val registry = ProbeRegistry()
        install(registry, "com.example.target")
        val targetClass = Class.forName("com.example.target.BranchTarget", true, javaFixtureLoader())
        val target = targetClass.getDeclaredConstructor().newInstance()
        targetClass.getMethod("classify", Int::class.java).invoke(target, 5)

        val manifest = registry.manifest(resource)
        val sites = methodProbe(manifest, "com.example.target.BranchTarget", "classify").branchSites
        val branches = branchProbes(manifest, "com.example.target.BranchTarget", "classify")

        val site = sites.single()
        assertEquals(listOf(BranchRole.TAKEN, BranchRole.FALL_THROUGH), site.outcomes.map { it.role })
        assertTrue(site.outcomes.all { it.caseKey == null })
        assertSitesMatchBranchProbes(sites, branches)

        // javac compiles `if (value > 0)` as a jump past the body when value <= 0, so 5 falls through.
        val hits =
            registry
                .computeDeltaBatch(resource)
                .batch.deltas
                .filter { it.classId == branches.first().classId }
                .associate { it.probeIndex to it.hitsTotal }
        val fallThrough = site.outcomes.single { it.role == BranchRole.FALL_THROUGH }
        val hitBranch = branches.single { hits.containsKey(it.probeIndex) }
        assertEquals(fallThrough.branchIndex, hitBranch.branchIndex, "the FALL_THROUGH outcome's slot is the one that fired")
    }

    @Test
    fun `a tableswitch and a lookupswitch list CASE outcomes with their case keys in order and DEFAULT last`() {
        val registry = ProbeRegistry()
        install(registry, "com.example.target")
        val targetClass = Class.forName("com.example.target.BranchTarget", true, javaFixtureLoader())
        val target = targetClass.getDeclaredConstructor().newInstance()
        targetClass.getMethod("classifyDense", Int::class.java).invoke(target, 1)
        val gappyClass = Class.forName("com.example.target.SwitchFillerTarget", true, javaFixtureLoader())

        val manifest = registry.manifest(resource)
        val dense = methodProbe(manifest, "com.example.target.BranchTarget", "classifyDense").branchSites
        val sparse = methodProbe(manifest, "com.example.target.BranchTarget", "classifySparse").branchSites
        val gappy = methodProbe(manifest, gappyClass.name, "classifyGappy").branchSites

        val cases = { sites: List<BranchSite> -> sites.single().outcomes.map { it.role to it.caseKey } }
        assertEquals(
            listOf(BranchRole.CASE to 0, BranchRole.CASE to 1, BranchRole.CASE to 2, BranchRole.DEFAULT to null),
            cases(dense),
        )
        assertEquals(listOf(BranchRole.CASE to 1, BranchRole.CASE to 1000, BranchRole.DEFAULT to null), cases(sparse))
        assertEquals(
            listOf(BranchRole.CASE to 1, BranchRole.CASE to 2, BranchRole.CASE to 3, BranchRole.CASE to 5, BranchRole.DEFAULT to null),
            cases(gappy),
            "the tableswitch's filler entry for 4 is the default, not a case of its own",
        )
        assertSitesMatchBranchProbes(dense, branchProbes(manifest, "com.example.target.BranchTarget", "classifyDense"))
        assertSitesMatchBranchProbes(sparse, branchProbes(manifest, "com.example.target.BranchTarget", "classifySparse"))

        val denseBranches = branchProbes(manifest, "com.example.target.BranchTarget", "classifyDense")
        val hits =
            registry
                .computeDeltaBatch(resource)
                .batch.deltas
                .filter { it.classId == denseBranches.first().classId }
                .associate { it.probeIndex to it.hitsTotal }
        val caseOne = dense.single().outcomes.single { it.caseKey == 1 }
        assertEquals(caseOne.branchIndex, denseBranches.single { hits.containsKey(it.probeIndex) }.branchIndex)
    }

    @Test
    fun `a dropped out-of-scope inlined copy is not listed, and the kept site after it keeps its branch indexes`() {
        val registry = ProbeRegistry()
        install(registry, "com.example.target")
        val className = "com.example.target.InlinedCopyTargetKt"
        Class.forName(className, true, kotlinFixtureLoader())

        val manifest = registry.manifest(resource)
        val methodProbe = methodProbe(manifest, className, "useCollections")
        val sites = methodProbe.branchSites
        val branches = branchProbes(manifest, className, "useCollections")

        assertEquals(1, sites.size, "only the adopter's own predicate is listed; the copies from map and firstOrNull are not")
        assertSitesMatchBranchProbes(sites, branches)
        val methodNames = manifest.probes.filter { it.className == className && it.kind == ProbeKind.METHOD }
        val expected =
            expectedFirstBranchIndexes(
                File("build/classes/kotlin/test/com/example/target/InlinedCopyTargetKt.class").readBytes(),
                "useCollections",
                listOf("com.example.target"),
            ) { name, descriptor -> name != "<clinit>" && methodNames.any { it.methodName == name && it.methodDescriptor == descriptor } }
        assertEquals(expected, sites.map { it.outcomes.first().branchIndex })
        assertTrue(expected.single() > 0, "the dropped copies before the predicate still take their branch indexes")
    }

    @Test
    fun `coroutine machinery sites are not listed, and the adopter's site between them keeps its branch indexes`() {
        val registry = ProbeRegistry()
        install(registry, "com.example.target")
        val className = "com.example.target.CoroutineTargetKt"
        Class.forName(className, true, kotlinFixtureLoader())

        val manifest = registry.manifest(resource)
        val sites = methodProbe(manifest, className, "twoPoints").branchSites
        val branches = branchProbes(manifest, className, "twoPoints")

        assertEquals(1, sites.size, "the state machine's switch and compares are not listed")
        assertEquals(listOf(BranchRole.TAKEN, BranchRole.FALL_THROUGH), sites.single().outcomes.map { it.role })
        assertSitesMatchBranchProbes(sites, branches)
        val methodNames = manifest.probes.filter { it.className == className && it.kind == ProbeKind.METHOD }
        val expected =
            expectedFirstBranchIndexes(
                File("build/classes/kotlin/test/com/example/target/CoroutineTargetKt.class").readBytes(),
                "twoPoints",
                listOf("com.example.target"),
            ) { name, descriptor -> name != "<clinit>" && methodNames.any { it.methodName == name && it.methodDescriptor == descriptor } }
        assertEquals(expected, sites.map { it.outcomes.first().branchIndex })
        assertTrue(expected.single() > 0, "the machinery before the adopter's if still takes its branch indexes")
    }

    @Test
    fun `two colliding sites in one method have no site key and no branch keys, and a lone site has both`() {
        val registry = ProbeRegistry()
        install(registry, "com.example.target.keypairs")
        val loader = kotlinFixtureLoader()
        val lone = Class.forName("com.example.target.keypairs.IdenticalConditionAddedV1", true, loader).name
        val colliding = Class.forName("com.example.target.keypairs.IdenticalConditionAddedV2", true, loader).name

        val manifest = registry.manifest(resource)

        val collidingSites = methodProbe(manifest, colliding, "check").branchSites
        assertEquals(2, collidingSites.size)
        assertTrue(collidingSites.all { it.siteKey == null })
        assertTrue(branchProbes(manifest, colliding, "check").all { it.branchKey == null })

        val loneSite = methodProbe(manifest, lone, "check").branchSites.single()
        val loneBranches = branchProbes(manifest, lone, "check")
        val siteKey = assertNotNull(loneSite.siteKey)
        assertEquals(32, siteKey.length)
        assertTrue(siteKey.all { it in "0123456789abcdef" })
        assertTrue(loneBranches.all { it.branchKey != null })
        assertTrue(loneBranches.none { it.branchKey == siteKey }, "a site key never equals one of its own branch keys")
    }

    @Test
    fun `a type initializer's METHOD probe lists no sites, even when its own code branches`() {
        val registry = ProbeRegistry()
        install(registry, "com.example.target")
        val targetClass = Class.forName("com.example.target.StaticInitBranchTarget", true, javaFixtureLoader())

        val manifest = registry.manifest(resource)
        val typeInitializer = methodProbe(manifest, targetClass.name, "<clinit>")
        assertEquals(emptyList(), typeInitializer.branchSites)
        assertNull(typeInitializer.siteIndex)
        assertEquals(1, methodProbe(manifest, targetClass.name, "pick").branchSites.size)
    }
}
