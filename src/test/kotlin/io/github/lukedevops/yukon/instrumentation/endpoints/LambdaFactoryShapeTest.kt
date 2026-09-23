package io.github.lukedevops.yukon.instrumentation.endpoints

import io.github.lukedevops.yukon.endpoints.lambdafactory.SpinInnerClassAdvice
import net.bytebuddy.asm.Advice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the check that decides whether the lambda factory hook (ADR 0035) installs.
 *
 * The first test is the one that fails when a JDK renames a member the hook reads, since CI runs
 * this suite on every JDK in its matrix. The rest prove that each kind of mismatch is caught and
 * named in the reason.
 */
class LambdaFactoryShapeTest {
    @Test
    fun `the running JDK's lambda factory has the shape the hook reads`() {
        assertNull(LambdaFactoryShape.JDK.problem(), "JDK ${Runtime.version()}")
    }

    @Test
    fun `a missing factory class is reported`() {
        val problem = assertNotNull(LambdaFactoryShape.JDK.copy(factoryClass = "java.lang.invoke.NoSuchMetafactory").problem())
        assertTrue("java.lang.invoke.NoSuchMetafactory" in problem, problem)
    }

    @Test
    fun `a missing or reshaped spin method is reported`() {
        val missing = assertNotNull(LambdaFactoryShape.JDK.copy(spinMethod = "spinSomethingElse").problem())
        assertTrue("spinSomethingElse" in missing, missing)

        // buildCallSite() exists with no parameters but returns a CallSite, not a Class.
        val reshaped = assertNotNull(LambdaFactoryShape.JDK.copy(spinMethod = "buildCallSite").problem())
        assertTrue("buildCallSite" in reshaped, reshaped)
    }

    @Test
    fun `a missing or retyped field is reported`() {
        val missing = assertNotNull(LambdaFactoryShape.JDK.copy(implInfoField = "implInfoGone").problem())
        assertTrue("implInfoGone" in missing, missing)

        // implKind exists on the same class but is an int, not a MethodHandleInfo.
        val retyped = assertNotNull(LambdaFactoryShape.JDK.copy(implInfoField = "implKind").problem())
        assertTrue("implKind" in retyped, retyped)

        val interfaceRetyped = assertNotNull(LambdaFactoryShape.JDK.copy(interfaceClassField = "implKind").problem())
        assertTrue("implKind" in interfaceRetyped, interfaceRetyped)
    }

    @Test
    fun `the advice reads the members the shape checks`() {
        val onExit = SpinInnerClassAdvice::class.java.declaredMethods.single { it.name == "onExit" }
        val fieldNames =
            onExit.parameterAnnotations.flatMap { annotations ->
                annotations.filterIsInstance<Advice.FieldValue>().map { it.value }
            }

        assertEquals(listOf(LambdaFactoryShape.JDK.interfaceClassField, LambdaFactoryShape.JDK.implInfoField), fieldNames)
    }
}
