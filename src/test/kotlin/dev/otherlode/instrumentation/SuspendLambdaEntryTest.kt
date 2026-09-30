package dev.otherlode.instrumentation

import dev.otherlode.config.AgentConfig
import dev.otherlode.export.CallEdgeKind
import dev.otherlode.export.ProbeKind
import dev.otherlode.export.ResourceAttributes
import dev.otherlode.instrumentation.staticscan.StaticBaselineScanner
import dev.otherlode.registry.ProbeRegistry
import net.bytebuddy.agent.ByteBuddyAgent
import net.bytebuddy.agent.builder.ResettableClassFileTransformer
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.pool.TypePool
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proves ADR 0025's suspend-lambda amendment through the real pipeline and the static scanner:
 * kotlinc's `create` and `invoke` on a suspend lambda's class get no probe and are not declared,
 * while `invokeSuspend`, which holds the body, is probed, declared and reached from the creator.
 */
class SuspendLambdaEntryTest {
    private companion object {
        const val PACKAGE = "com.example.target"
        const val FACADE = "$PACKAGE.CoroutineTargetKt"
        const val LAMBDA = "$PACKAGE.CoroutineTargetKt\$runLambda\$1"
        val ROOT = File("build/classes/kotlin/test")
    }

    private var installedTransformer: ResettableClassFileTransformer? = null
    private var installedOtherlode: OtherlodeInstrumentation? = null

    @AfterTest
    fun tearDown() {
        installedTransformer?.let { installedOtherlode?.uninstall(ByteBuddyAgent.install(), it) }
    }

    @Test
    fun `a suspend lambda's create and invoke get no probe, and its creator reaches only invokeSuspend`() {
        val registry = ProbeRegistry()
        val otherlode = OtherlodeInstrumentation(AgentConfig.parse("includePackages=$PACKAGE"), registry)
        installedOtherlode = otherlode
        installedTransformer = otherlode.install(ByteBuddyAgent.install())
        val loader = FixtureClassLoader(arrayOf(ROOT.toURI().toURL()), javaClass.classLoader, "com.example.")

        Class.forName(FACADE, true, loader).getMethod("runLambda", Int::class.java).invoke(null, 5)

        val probes = registry.manifest(ResourceAttributes("test", null, "instance-1", null, "run-1")).probes
        val lambdaMethods = probes.filter { it.className == LAMBDA && it.kind == ProbeKind.METHOD }.map { it.methodName }.toSet()
        assertEquals(setOf("<init>", "invokeSuspend"), lambdaMethods)

        val creates =
            probes
                .single { it.className == FACADE && it.methodName == "runLambda" && it.kind == ProbeKind.METHOD }
                .calls
                .filter { it.className == LAMBDA && it.kind == CallEdgeKind.CREATES }
                .map { it.methodName }
        assertEquals(listOf("invokeSuspend"), creates)
    }

    @Test
    fun `the static baseline declares a suspend lambda's invokeSuspend and not its create or invoke`() {
        val result = StaticBaselineScanner(listOf(PACKAGE)).scan(listOf(ROOT))

        val methods = result.declaredClasses.single { it.className == LAMBDA }.methods
        assertEquals(setOf("<init>", "invokeSuspend"), methods.map { it.methodName }.toSet())
    }

    @Test
    fun `only an instance create or invoke on a direct SuspendLambda or RestrictedSuspendLambda subclass is an entry`() {
        val suspendLambda = "kotlin.coroutines.jvm.internal.SuspendLambda"
        val restricted = "kotlin.coroutines.jvm.internal.RestrictedSuspendLambda"
        assertTrue(TypeMatchPolicy.isSuspendLambdaEntry("create", false) { suspendLambda })
        assertTrue(TypeMatchPolicy.isSuspendLambdaEntry("invoke", false) { suspendLambda })
        assertTrue(TypeMatchPolicy.isSuspendLambdaEntry("invoke", false) { restricted })
        assertTrue(TypeMatchPolicy.isSuspendLambdaEntry("create", false) { "shaded.kotlin.coroutines.jvm.internal.SuspendLambda" })

        assertFalse(TypeMatchPolicy.isSuspendLambdaEntry("invokeSuspend", false) { suspendLambda })
        assertFalse(TypeMatchPolicy.isSuspendLambdaEntry("invoke", true) { suspendLambda })
        assertFalse(TypeMatchPolicy.isSuspendLambdaEntry("invoke", false) { "kotlin.jvm.internal.Lambda" })
        assertFalse(TypeMatchPolicy.isSuspendLambdaEntry("create", false) { "kotlin.coroutines.jvm.internal.ContinuationImpl" })
        assertFalse(TypeMatchPolicy.isSuspendLambdaEntry("invoke", false) { null })
    }

    @Test
    fun `the superclass is read only for the two entry names`() {
        assertFalse(TypeMatchPolicy.isSuspendLambdaEntry("invokeSuspend", false) { error("superclass read") })
        assertFalse(TypeMatchPolicy.isSuspendLambdaEntry("create", true) { error("superclass read") })
    }

    /** The names of [className]'s methods the method matcher takes, read through a pool that cannot see the Kotlin stdlib. */
    private fun matchedWithoutStdlib(className: String): Set<String> {
        val bytes = File(ROOT, className.replace('.', '/') + ".class").readBytes()
        val pool = TypePool.Default.WithLazyResolution.of(ClassFileLocator.Simple.of(className, bytes))
        return pool
            .describe(className)
            .resolve()
            .declaredMethods
            .filter(TypeMatchPolicy.methodMatcher(isScalaClass = false))
            .map { it.internalName }
            .toSet()
    }

    @Test
    fun `the method matcher leaves out create and invoke when nothing can resolve the Kotlin stdlib`() {
        // The fat-jar case: the stdlib sits in a nested jar the scan never opens. A lazily
        // resolving pool still answers the superclass's name, which is all the rule reads.
        assertEquals(setOf("<init>", "invokeSuspend"), matchedWithoutStdlib(LAMBDA))
    }

    @Test
    fun `a restricted suspend lambda's create and invoke are left out the same way`() {
        // sequence {}'s lambda: RestrictedSuspendLambda, a receiver, so create(Object, Continuation).
        assertEquals(setOf("<init>", "invokeSuspend"), matchedWithoutStdlib("$PACKAGE.BodyKindTarget\$restrictedSuspendLambda\$1"))
    }
}
