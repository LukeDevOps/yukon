package io.github.lukedevops.yukon.instrumentation

import com.example.target.keypairs.kotlinFixtureBytes
import com.example.target.keypairs.renamedTo
import io.github.lukedevops.yukon.config.AgentConfig
import io.github.lukedevops.yukon.export.ProbeKind
import io.github.lukedevops.yukon.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Proves ADR 0031's key end to end: loads `EarlierMethodEditV1` and `EarlierMethodEditV2` through
 * the real transform, both under one common class name, and shows that `second`'s branch key
 * survives the edit while its `branchIndex` does not.
 *
 * The two builds cannot share a name in one classloader, since the JVM's class identity is
 * (classloader, name); each is instead defined under the common name in its own classloader.
 * `ProbeRegistry` keys its arrays by (class name, layout hash, classloader), so this yields two
 * separate manifest entries for what reads as the same class name.
 */
class BranchKeyEndToEndTest {
    /** Defines arbitrary bytes as a class, the way a JVM classloader would, without touching disk. */
    private class ByteArrayClassLoader(
        parent: ClassLoader,
    ) : ClassLoader(parent) {
        fun define(
            name: String,
            bytes: ByteArray,
        ): Class<*> = defineClass(name, bytes, 0, bytes.size)
    }

    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedYukon: YukonInstrumentation? = null

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { installedYukon?.uninstall(ByteBuddyAgent.install(), it) }
        installedTransformer = null
        installedYukon = null
    }

    @Test
    fun `second's branch key survives the edit to first, while its branch index does not`() {
        val commonName = "com.example.target.keypairs.CommonBranchKeyE2E"
        val commonInternal = commonName.replace('.', '/')

        val v1Bytes =
            renamedTo(kotlinFixtureBytes("EarlierMethodEditV1"), "com/example/target/keypairs/EarlierMethodEditV1", commonInternal)
        val v2Bytes =
            renamedTo(kotlinFixtureBytes("EarlierMethodEditV2"), "com/example/target/keypairs/EarlierMethodEditV2", commonInternal)

        val registry = ProbeRegistry()
        val config = AgentConfig.parse("includePackages=com.example.target.keypairs")
        val yukon = YukonInstrumentation(config, registry)
        installedYukon = yukon
        installedTransformer = yukon.install(ByteBuddyAgent.install())

        val v1Loader = ByteArrayClassLoader(javaClass.classLoader)
        val v2Loader = ByteArrayClassLoader(javaClass.classLoader)
        val v1Class = v1Loader.define(commonName, v1Bytes)
        val v2Class = v2Loader.define(commonName, v2Bytes)

        val v1Instance = v1Class.getDeclaredConstructor().newInstance()
        val v2Instance = v2Class.getDeclaredConstructor().newInstance()
        v1Class.getMethod("second", Int::class.java).invoke(v1Instance, 5)
        v2Class.getMethod("second", Int::class.java).invoke(v2Instance, 5)
        v2Class.getMethod("first", Int::class.java).invoke(v2Instance, 5)

        val manifest = registry.manifest("test", null, "instance-1")
        val commonProbes = manifest.probes.filter { it.className == commonName }
        val classIds = commonProbes.map { it.classId }.distinct()
        assertEquals(2, classIds.size, "v1 and v2 must register as two distinct classes despite sharing a name")

        val v1SecondBranches = commonProbes.filter { it.classId == classIds[0] && it.methodName == "second" && it.kind == ProbeKind.BRANCH }
        val v2SecondBranches = commonProbes.filter { it.classId == classIds[1] && it.methodName == "second" && it.kind == ProbeKind.BRANCH }
        // classIds are assigned by registration order, which may not match v1/v2; work it out
        // from which class has a branch probe on `first` at all.
        val firstHasFirstBranch = commonProbes.any { it.classId == classIds[0] && it.methodName == "first" && it.kind == ProbeKind.BRANCH }
        val (v1Branches, v2Branches) =
            if (firstHasFirstBranch) v2SecondBranches to v1SecondBranches else v1SecondBranches to v2SecondBranches

        assertEquals(2, v1Branches.size)
        assertEquals(2, v2Branches.size)

        val v1ByOffset = v1Branches.sortedBy { it.branchIndex }
        val v2ByOffset = v2Branches.sortedBy { it.branchIndex }
        val v1Keys = v1ByOffset.map { it.branchKey }
        val v2Keys = v2ByOffset.map { it.branchKey }
        assertEquals(v1Keys, v2Keys, "second's outcomes keep the same branch keys across the edit to first")
        v1Keys.forEach { assertNotNull(it) }

        assertNotEquals(
            v1ByOffset.map { it.branchIndex },
            v2ByOffset.map { it.branchIndex },
            "first gained a conditional, so second's branch indices shift in v2",
        )
    }
}
