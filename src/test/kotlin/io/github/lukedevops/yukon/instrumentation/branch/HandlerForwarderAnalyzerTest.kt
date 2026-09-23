package io.github.lukedevops.yukon.instrumentation.branch

import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.jar.asm.ClassWriter
import net.bytebuddy.jar.asm.Handle
import net.bytebuddy.jar.asm.Opcodes
import net.bytebuddy.jar.asm.Type
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

private const val HTTP_HANDLER = "com.sun.net.httpserver.HttpHandler"
private const val HANDLE = "(Lcom/sun/net/httpserver/HttpExchange;)V"

/**
 * Proves the forwarder table's entries on real scalac and kotlinc output (ADR 0035): a pass-through
 * a handler can be reported as gets one entry, pointing at the one probed method it forwards to.
 */
class HandlerForwarderAnalyzerTest {
    /**
     * An `HttpHandler` lambda returns void and takes an object, so scalac names no `$adapted`
     * forwarder for one. The fixture's `Int => String` lambda does get one, so these tests name
     * `scala.Function1` as the handler interface instead.
     */
    private fun analyzeScala(
        module: String,
        handlerInterfaces: Set<String>,
    ): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(
            ScalaFixtures.classBytes(module, "LambdaHost\$"),
            includePackages = listOf("com.example.scalatarget"),
            handlerInterfaces = handlerInterfaces,
        ) { name, _ -> "adapted" !in name }

    @Test
    fun `a Scala 2 adapted forwarder named by a lambda for a handler interface points at the body it calls`() {
        assertEquals(
            listOf(
                HandlerForwarder(
                    "com.example.scalatarget.LambdaHost\$",
                    "\$anonfun\$classify\$1\$adapted",
                    "(Ljava/lang/Object;)Ljava/lang/String;",
                    "com.example.scalatarget.LambdaHost\$",
                    "\$anonfun\$classify\$1",
                    "(I)Ljava/lang/String;",
                ),
            ),
            analyzeScala("scala2", setOf("scala.Function1")).handlerForwarders,
        )
    }

    @Test
    fun `a Scala 3 adapted bridge named by a lambda for a handler interface points at the body it calls`() {
        assertEquals(
            listOf(
                HandlerForwarder(
                    "com.example.scalatarget.LambdaHost\$",
                    "\$anonfun\$adapted\$1",
                    "(Ljava/lang/Object;)Ljava/lang/String;",
                    "com.example.scalatarget.LambdaHost\$",
                    "\$anonfun\$1",
                    "(I)Ljava/lang/String;",
                ),
            ),
            analyzeScala("scala3", setOf("scala.Function1")).handlerForwarders,
        )
    }

    @Test
    fun `the same forwarder for an interface outside the handler set gets no entry`() {
        assertEquals(emptyList(), analyzeScala("scala2", setOf(HTTP_HANDLER)).handlerForwarders)
        assertEquals(emptyList(), analyzeScala("scala3", emptySet()).handlerForwarders)
    }

    private val classSamDir =
        File(
            System.getProperty("yukon.fixtures.classsam.dir")
                ?: error("system property yukon.fixtures.classsam.dir is not set; run tests through the root Gradle build"),
        )

    /** `HandlersKt`, compiled with `-Xsam-conversions=class -Xlambdas=class`, analysed the way the method tier would. */
    private fun analyzeClassSam(handlerInterfaces: Set<String>): BranchSiteAnalyzer.Analysis =
        BranchSiteAnalyzer.analyze(
            File(classSamDir, "com/example/classsam/HandlersKt.class").readBytes(),
            lookup = { internalName ->
                ClassFileLocator
                    .ForFolder(classSamDir)
                    .locate(internalName.replace('/', '.'))
                    .takeIf { it.isResolved }
                    ?.resolve()
            },
            includePackages = listOf("com.example.classsam"),
            handlerInterfaces = handlerInterfaces,
        ) { name, _ -> !name.startsWith("access\$") }

    private fun toHandlersKt(
        passThroughClass: String,
        target: String,
    ) = HandlerForwarder(
        "com.example.classsam.$passThroughClass",
        "handle",
        HANDLE,
        "com.example.classsam.HandlersKt",
        target,
        HANDLE,
    )

    /**
     * kotlinc marks each reference class synthetic, so the method tier never analyses one. The entry
     * is written when the class that creates it is analysed. A reference to a private function goes
     * through the `access$` accessor, and the entry names the function behind it.
     */
    @Test
    fun `a Kotlin reference class for a handler interface gets an entry, written at its creator's analysis`() {
        assertEquals(
            setOf(
                toHandlersKt("HandlersKt\$reference\$1", "handleOrder"),
                toHandlersKt("HandlersKt\$register\$1", "handleOrder"),
                toHandlersKt("HandlersKt\$privateReference\$1", "handleSecret"),
            ),
            analyzeClassSam(setOf(HTTP_HANDLER)).handlerForwarders.toSet(),
        )
    }

    /**
     * The `$sam$` wrapper's `handle` only calls `Function1.invoke` on the value it holds, which is
     * out of scope, so it reaches no probed method and keeps its own name. The lambda compiled to a
     * class is not synthetic, so its `handle` is probed and needs no entry.
     */
    @Test
    fun `a SAM wrapper around a function value, and a lambda compiled to a class, get no entry`() {
        val passThroughs = analyzeClassSam(setOf(HTTP_HANDLER)).handlerForwarders.map { it.className }

        assertEquals(emptyList(), passThroughs.filter { "\$sam\$" in it || it.endsWith("\$lambda\$1") })
    }

    @Test
    fun `a reference class for an interface outside the handler set gets no entry`() {
        assertEquals(emptyList(), analyzeClassSam(emptySet()).handlerForwarders)
        assertEquals(emptyList(), analyzeClassSam(setOf("java.lang.Runnable")).handlerForwarders)
    }

    private val creator = "com/example/target/ForwarderCreator"
    private val bodyClass = "com/example/target/ForwarderCreator\$1"
    private val handlerInterface = "com/example/target/Handler"

    /**
     * A creator with three same-class forwarders named by lambdas for `Handler`: one that calls `a`,
     * one that calls `a` and `b`, and one that calls the abstract `abs`. It also creates a synthetic
     * body class for `Handler` whose `handle` calls `a` and `b`.
     */
    private fun creatorBytes(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, creator, null, "java/lang/Object", null)
        for (name in listOf("a", "b")) staticMethod(writer, Opcodes.ACC_PUBLIC, name) {}
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "abs", "()V", null, null).visitEnd()
        val synthetic = Opcodes.ACC_PRIVATE or Opcodes.ACC_SYNTHETIC
        staticMethod(writer, synthetic, "one\$adapted") { callCreator("a") }
        staticMethod(writer, synthetic, "two\$adapted") {
            callCreator("a")
            callCreator("b")
        }
        writer.visitMethod(synthetic, "abstract\$adapted", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKEVIRTUAL, creator, "abs", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC, "make", "()V", null, null).apply {
            visitCode()
            for (forwarder in listOf("one\$adapted", "two\$adapted")) {
                visitInvokeDynamicInsn(
                    "handle",
                    "()L$handlerInterface;",
                    METAFACTORY,
                    VOID,
                    Handle(Opcodes.H_INVOKESTATIC, creator, forwarder, "()V", false),
                    VOID,
                )
                visitInsn(Opcodes.POP)
            }
            visitVarInsn(Opcodes.ALOAD, 0)
            visitInvokeDynamicInsn(
                "handle",
                "(L$creator;)L$handlerInterface;",
                METAFACTORY,
                VOID,
                Handle(Opcodes.H_INVOKESPECIAL, creator, "abstract\$adapted", "()V", false),
                VOID,
            )
            visitInsn(Opcodes.POP)
            visitTypeInsn(Opcodes.NEW, bodyClass)
            visitInsn(Opcodes.DUP)
            visitMethodInsn(Opcodes.INVOKESPECIAL, bodyClass, "<init>", "()V", false)
            visitInsn(Opcodes.POP)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun bodyClassBytes(): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(
            Opcodes.V21,
            Opcodes.ACC_FINAL or Opcodes.ACC_SYNTHETIC,
            bodyClass,
            null,
            "java/lang/Object",
            arrayOf(handlerInterface),
        )
        writer.visitOuterClass(creator, "make", "()V")
        writer.visitMethod(0, "<init>", "()V", null, null).apply {
            visitCode()
            visitVarInsn(Opcodes.ALOAD, 0)
            visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL, "handle", "()V", null, null).apply {
            visitCode()
            callCreator("a")
            callCreator("b")
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun staticMethod(
        writer: ClassWriter,
        access: Int,
        name: String,
        body: net.bytebuddy.jar.asm.MethodVisitor.() -> Unit,
    ) {
        writer.visitMethod(access or Opcodes.ACC_STATIC, name, "()V", null, null).apply {
            visitCode()
            body()
            visitInsn(Opcodes.RETURN)
            visitMaxs(0, 0)
            visitEnd()
        }
    }

    private fun net.bytebuddy.jar.asm.MethodVisitor.callCreator(name: String) {
        visitMethodInsn(Opcodes.INVOKESTATIC, creator, name, "()V", false)
    }

    @Test
    fun `a pass-through that reaches two probed methods, or an abstract one, gets no entry`() {
        val body = bodyClassBytes()
        val analysis =
            BranchSiteAnalyzer.analyze(
                creatorBytes(),
                lookup = { if (it == bodyClass) body else null },
                includePackages = listOf("com.example.target"),
                handlerInterfaces = setOf("com.example.target.Handler"),
            ) { name, _ -> !name.endsWith("\$adapted") }

        val dotted = creator.replace('/', '.')
        assertEquals(
            listOf(HandlerForwarder(dotted, "one\$adapted", "()V", dotted, "a", "()V")),
            analysis.handlerForwarders,
            "only the forwarder with one concrete target gets an entry",
        )
    }

    private companion object {
        val METAFACTORY =
            Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
                    "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
                    "Ljava/lang/invoke/CallSite;",
                false,
            )
        val VOID: Type = Type.getType("()V")
    }
}
