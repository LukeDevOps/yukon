package dev.otherlode.instrumentation.endpoints

import dev.otherlode.bootstrap.OtherlodeEndpoints
import dev.otherlode.instrumentation.BootstrapHolder
import net.bytebuddy.agent.ByteBuddyAgent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.lang.invoke.MethodHandleInfo
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.Callable
import java.util.function.IntSupplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the seam half of hidden-handler naming (ADR 0035): what `OtherlodeEndpoints.recordLambdaClass`
 * keeps and what `OtherlodeEndpoints.lambdaImplementation` gives back. The lambda factory hook that
 * feeds it is proven end to end against the real JDK in the `endpoints-jdk-httpserver` module.
 * Here every input is built by hand. Another test in this JVM may have installed the real hook,
 * so each test spins its lambda classes before it installs a handler interface set, which keeps the
 * hook from recording them first.
 */
class LambdaImplementationSeamTest {
    private val lookup = MethodHandles.lookup()

    @BeforeEach
    fun installBootstrapHolder() {
        BootstrapHolder.install(ByteBuddyAgent.install())
    }

    @AfterEach
    fun clearHandlerInterfaces() {
        OtherlodeEndpoints.installHandlerInterfaces(emptySet())
    }

    @Test
    fun `a lambda class for a handler interface is recorded under its own class`() {
        val lambdaClass = IntSupplier { 1 }.javaClass
        OtherlodeEndpoints.installHandlerInterfaces(setOf(IntSupplier::class.java.name))

        OtherlodeEndpoints.recordLambdaClass(lambdaClass, IntSupplier::class.java, targetInfo())

        val found = assertNotNull(OtherlodeEndpoints.lambdaImplementation(lambdaClass))
        assertEquals("java.lang.Integer", found.className, "the owner is the dotted Class.getName() form")
        assertEquals("parseInt", found.methodName)
        assertEquals("(Ljava/lang/String;I)I", found.descriptor)
    }

    @Test
    fun `a lambda class for any other interface costs a set lookup and is not recorded`() {
        val lambdaClass = Runnable {}.javaClass
        OtherlodeEndpoints.installHandlerInterfaces(setOf(IntSupplier::class.java.name))

        OtherlodeEndpoints.recordLambdaClass(lambdaClass, Runnable::class.java, targetInfo())

        assertNull(OtherlodeEndpoints.lambdaImplementation(lambdaClass))
    }

    @Test
    fun `nothing is recorded while the handler interface set is empty`() {
        OtherlodeEndpoints.installHandlerInterfaces(emptySet())
        val lambdaClass = IntSupplier { 2 }.javaClass

        OtherlodeEndpoints.recordLambdaClass(lambdaClass, IntSupplier::class.java, targetInfo())

        assertNull(OtherlodeEndpoints.lambdaImplementation(lambdaClass))
    }

    @Test
    fun `an implementation whose owner is itself hidden is not recorded`() {
        val hiddenOwner = Callable { "a hidden class with a method of its own" }.javaClass
        assertTrue(hiddenOwner.isHidden)
        val hiddenInfo = lookup.revealDirect(lookup.unreflect(hiddenOwner.getDeclaredMethod("call")))
        val lambdaClass = Callable { "the lambda being recorded" }.javaClass
        OtherlodeEndpoints.installHandlerInterfaces(setOf(Callable::class.java.name))

        OtherlodeEndpoints.recordLambdaClass(lambdaClass, Callable::class.java, hiddenInfo)

        assertNull(OtherlodeEndpoints.lambdaImplementation(lambdaClass))
    }

    @Test
    fun `an abstract implementation is not recorded, since the method that runs is decided later`() {
        val lambdaClass = IntSupplier { 4 }.javaClass
        val abstractInfo =
            lookup.revealDirect(
                lookup.findVirtual(IntSupplier::class.java, "getAsInt", MethodType.methodType(Int::class.javaPrimitiveType)),
            )
        OtherlodeEndpoints.installHandlerInterfaces(setOf(IntSupplier::class.java.name))

        OtherlodeEndpoints.recordLambdaClass(lambdaClass, IntSupplier::class.java, abstractInfo)

        assertNull(OtherlodeEndpoints.lambdaImplementation(lambdaClass))
    }

    @Test
    fun `null inputs and a class never recorded are misses, never a throw`() {
        val lambdaClass = IntSupplier { 3 }.javaClass
        OtherlodeEndpoints.installHandlerInterfaces(setOf(IntSupplier::class.java.name))

        OtherlodeEndpoints.recordLambdaClass(null, IntSupplier::class.java, targetInfo())
        OtherlodeEndpoints.recordLambdaClass(lambdaClass, null, targetInfo())
        OtherlodeEndpoints.recordLambdaClass(lambdaClass, IntSupplier::class.java, null)

        assertNull(OtherlodeEndpoints.lambdaImplementation(lambdaClass))
        assertNull(OtherlodeEndpoints.lambdaImplementation(null))
        assertNull(OtherlodeEndpoints.lambdaImplementation(String::class.java))
    }

    /** `Integer.parseInt(String, int)`, a real named method standing in for a lambda's implementation. */
    private fun targetInfo(): MethodHandleInfo {
        val type = MethodType.methodType(Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
        return lookup.revealDirect(lookup.findStatic(Integer::class.java, "parseInt", type))
    }
}
